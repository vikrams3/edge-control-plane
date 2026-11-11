package dev.vikram.edge;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Canary rollout: push candidate routes to the canary group only, wait until the canary Envoys
 * ACK them, probe the canary, then either promote to the stable group or roll the canary back to
 * the last known-good routes. Every rollout is stored in PostgreSQL, and the last promoted routes
 * are restored on startup.
 */
@Service
public class RolloutService {

  public static final String CANARY = "canary";
  public static final String STABLE = "stable";

  private static final Logger log = LoggerFactory.getLogger(RolloutService.class);

  private final XdsServer xds;
  private final HealthProbe probe;
  private final RolloutPolicy policy;
  private final EdgeProperties props;
  private final RolloutRepository repo;
  private final MeterRegistry metrics;

  private final AtomicLong version = new AtomicLong();
  private final AtomicReference<Map<String, String>> stableRoutes = new AtomicReference<>();
  private final AtomicReference<Map<String, String>> canaryRoutes = new AtomicReference<>();
  /**
   * One rollout at a time. A Semaphore rather than a lock, because the request thread acquires
   * it and the background thread that finishes the rollout releases it.
   */
  private final Semaphore oneRollout = new Semaphore(1);
  /** Rollouts still running, by version; finished ones live in the database. */
  private final Map<Long, RolloutRecord> running = new ConcurrentHashMap<>();
  private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

  public RolloutService(XdsServer xds, HealthProbe probe, RolloutPolicy policy,
      EdgeProperties props, RolloutRepository repo, MeterRegistry metrics) {
    this.xds = xds;
    this.probe = probe;
    this.policy = policy;
    this.props = props;
    this.repo = repo;
    this.metrics = metrics;
  }

  /**
   * Restores the last promoted routes from the database (or uses the configured initial routes
   * on a fresh database) and pushes them to both groups. Versions continue from the database, so
   * a restart never reuses a version number.
   */
  @PostConstruct
  void init() {
    for (String group : List.of(CANARY, STABLE)) {
      Gauge.builder("edge.envoys.connected", xds, x -> x.connected(group))
          .description("Envoys connected over ADS").tag("group", group).register(metrics);
    }
    Gauge.builder("edge.config.version", version, AtomicLong::get)
        .description("Latest xDS snapshot version pushed").register(metrics);
    version.set(repo.maxVersion());
    Map<String, String> routes = Map.copyOf(props.initialRoutes());
    String source = "initial routes from config";
    Optional<RolloutEntity> last = repo.findFirstByOutcomeOrderByVersionDesc("promoted");
    if (last.isPresent()) {
      RolloutRecord r = last.get().toRecord();
      List<String> problems = validate(r.routes());
      if (problems.isEmpty()) {
        routes = r.routes();
        source = "last promoted rollout v" + r.version();
      } else {
        log.warn("Not restoring v{}, its backends are no longer configured: {}", r.version(), problems);
      }
    }
    stableRoutes.set(routes);
    push(STABLE, routes);
    push(CANARY, routes);
    log.info("Pushed {} to both groups: {}", source, routes);
  }

  /**
   * Checks a candidate route table before anything is pushed. Returns one message per problem,
   * empty if it is valid. Catches typos like "salez" without touching any Envoy.
   */
  public List<String> validate(Map<String, String> candidate) {
    List<String> problems = new ArrayList<>();
    candidate.forEach((host, backend) -> {
      if (host == null || host.isBlank()) {
        problems.add("hostname must not be blank");
      } else if (backend == null || !props.backends().containsKey(backend)) {
        problems.add(host + ": unknown backend '" + backend + "', expected one of "
            + new TreeSet<>(props.backends().keySet()));
      }
    });
    return problems;
  }

  /** A started rollout: its in-progress record now, and the final record when it finishes. */
  public record Started(RolloutRecord record, CompletableFuture<RolloutRecord> done) {}

  /**
   * Starts a rollout: pushes the candidate to the canary now and returns at once; the ACK wait,
   * probe and promote-or-rollback run on a virtual thread. Returns empty if another rollout is
   * already in progress. {@code revertedFrom} is the earlier version being rolled out again, or
   * null; a revert goes through the canary like any other change.
   */
  public Optional<Started> start(Map<String, String> candidate, Long revertedFrom) {
    if (!oneRollout.tryAcquire()) {
      return Optional.empty();
    }
    try {
      Map<String, String> routes = Map.copyOf(candidate);
      Timer.Sample timer = Timer.start(metrics);
      long v = push(CANARY, routes);
      log.info("v{} pushed to canary: {}", v, routes);
      RolloutRecord pending = new RolloutRecord(v, null, routes, "in_progress", "pending",
          0, 0, 0.0, revertedFrom);
      running.put(v, pending);
      CompletableFuture<RolloutRecord> done = CompletableFuture.supplyAsync(
          () -> finish(pending, timer), workers);
      return Optional.of(new Started(pending, done));
    } catch (RuntimeException e) {
      oneRollout.release();
      throw e;
    }
  }

  /** Runs on a virtual thread: wait for ACKs, probe, promote or roll back, store the result. */
  private RolloutRecord finish(RolloutRecord pending, Timer.Sample timer) {
    long v = pending.version();
    Map<String, String> routes = pending.routes();
    String ackName = "error";
    HealthProbe.ProbeResult result = new HealthProbe.ProbeResult(0, 0);
    String outcome = "rolled_back";
    try {
      // Wait for every canary Envoy to confirm it applied v, instead of sleeping and hoping.
      ConfigAckTracker.AckStatus ack =
          xds.awaitAck(CANARY, v, Duration.ofMillis(props.ackTimeoutMillis()));
      ackName = ack.name().toLowerCase();
      if (ack == ConfigAckTracker.AckStatus.ACKED) {
        result = probe.probe(routes.keySet());
      }   // otherwise nothing to probe; an empty result counts as a failure
      if (ack == ConfigAckTracker.AckStatus.ACKED && policy.shouldPromote(result)) {
        stableRoutes.set(routes);
        push(STABLE, routes);
        outcome = "promoted";
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (RuntimeException e) {
      log.error("v{} failed, rolling back", v, e);
    } finally {
      if (!"promoted".equals(outcome)) {
        push(CANARY, stableRoutes.get());   // automatic rollback
      }
    }
    log.info("v{} {}: canary {}, {} of {} probe requests failed ({}%)", v, outcome, ackName,
        result.failed(), result.total(), Math.round(result.errorRate() * 100));

    RolloutRecord record = new RolloutRecord(v, Instant.now(), routes, outcome, ackName,
        result.total(), result.failed(), result.errorRate(), pending.revertedFrom());
    try {
      repo.save(RolloutEntity.from(record));
      metrics.counter("edge.rollouts", "outcome", outcome, "canary_ack", ackName).increment();
      timer.stop(metrics.timer("edge.rollout.duration", "outcome", outcome));
    } finally {
      running.remove(v);
      oneRollout.release();
    }
    return record;
  }

  /** A rollout by version: still running, or from the database. */
  public Optional<RolloutRecord> find(long version) {
    RolloutRecord live = running.get(version);
    return live != null ? Optional.of(live)
        : repo.findById(version).map(RolloutEntity::toRecord);
  }

  @PreDestroy
  void stop() {
    workers.shutdown();
  }

  public Map<String, String> stableRoutes() {
    return stableRoutes.get();
  }

  public Map<String, String> canaryRoutes() {
    return canaryRoutes.get();
  }

  /** Every rollout ever run, oldest first, including one still in progress. */
  public List<RolloutRecord> history() {
    // keyed by version, database last, so a rollout that just finished is listed once
    Map<Long, RolloutRecord> byVersion = new java.util.TreeMap<>();
    Stream.concat(running.values().stream(),
            repo.findAllByOrderByVersionAsc().stream().map(RolloutEntity::toRecord))
        .forEach(r -> byVersion.put(r.version(), r));
    return List.copyOf(byVersion.values());
  }

  private long push(String group, Map<String, String> routes) {
    long v = version.incrementAndGet();
    xds.push(group, EdgeConfig.snapshot(props.backends(), routes, Long.toString(v)));
    if (CANARY.equals(group)) {
      canaryRoutes.set(routes);
    }
    return v;
  }

  /** One rollout. {@code outcome} is in_progress, promoted or rolled_back. */
  public record RolloutRecord(
      long version,
      Instant finishedAt,
      Map<String, String> routes,
      String outcome,
      String canaryAck,
      int probeRequests,
      int probeFailures,
      double errorRate,
      Long revertedFrom) {}
}
