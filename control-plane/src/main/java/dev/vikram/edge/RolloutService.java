package dev.vikram.edge;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
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
  private final ReentrantLock rolloutLock = new ReentrantLock();

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

  /** Runs one rollout. Returns empty if another rollout is already in progress. */
  public Optional<RolloutRecord> rollout(Map<String, String> candidate) throws InterruptedException {
    return rollout(candidate, null);
  }

  /** The stored rollout with this version, if any. */
  public Optional<RolloutRecord> find(long version) {
    return repo.findById(version).map(RolloutEntity::toRecord);
  }

  /**
   * Runs one rollout. {@code revertedFrom} is the earlier version whose routes are being rolled
   * out again, or null. A revert goes through the canary like any other change.
   */
  public Optional<RolloutRecord> rollout(Map<String, String> candidate, Long revertedFrom)
      throws InterruptedException {
    if (!rolloutLock.tryLock()) {
      return Optional.empty();
    }
    Timer.Sample timer = Timer.start(metrics);
    try {
      Map<String, String> routes = Map.copyOf(candidate);
      long v = push(CANARY, routes);
      log.info("v{} pushed to canary: {}", v, routes);

      // Wait for every canary Envoy to confirm it applied v, instead of sleeping and hoping.
      ConfigAckTracker.AckStatus ack =
          xds.awaitAck(CANARY, v, Duration.ofMillis(props.ackTimeoutMillis()));
      HealthProbe.ProbeResult result = ack == ConfigAckTracker.AckStatus.ACKED
          ? probe.probe(routes.keySet())
          : new HealthProbe.ProbeResult(0, 0);   // nothing to probe; counts as a failure

      String outcome;
      if (ack == ConfigAckTracker.AckStatus.ACKED && policy.shouldPromote(result)) {
        stableRoutes.set(routes);
        push(STABLE, routes);
        outcome = "promoted";
      } else {
        push(CANARY, stableRoutes.get());   // automatic rollback
        outcome = "rolled_back";
      }
      log.info("v{} {}: canary {}, {} of {} probe requests failed ({}%)", v, outcome, ack,
          result.failed(), result.total(), Math.round(result.errorRate() * 100));

      RolloutRecord record = new RolloutRecord(v, Instant.now(), routes, outcome,
          ack.name().toLowerCase(), result.total(), result.failed(), result.errorRate(),
          revertedFrom);
      repo.save(RolloutEntity.from(record));
      metrics.counter("edge.rollouts", "outcome", outcome, "canary_ack", record.canaryAck())
          .increment();
      timer.stop(metrics.timer("edge.rollout.duration", "outcome", outcome));
      return Optional.of(record);
    } finally {
      rolloutLock.unlock();
    }
  }

  public Map<String, String> stableRoutes() {
    return stableRoutes.get();
  }

  public Map<String, String> canaryRoutes() {
    return canaryRoutes.get();
  }

  /** Every rollout ever run, oldest first, from the database. */
  public List<RolloutRecord> history() {
    return repo.findAllByOrderByVersionAsc().stream().map(RolloutEntity::toRecord).toList();
  }

  private long push(String group, Map<String, String> routes) {
    long v = version.incrementAndGet();
    xds.push(group, EdgeConfig.snapshot(props.backends(), routes, Long.toString(v)));
    if (CANARY.equals(group)) {
      canaryRoutes.set(routes);
    }
    return v;
  }

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
