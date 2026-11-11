package dev.vikram.edge;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Canary rollout: push candidate routes to the canary group only, wait until the canary Envoys
 * ACK them, probe the canary, then either promote to the stable group or roll the canary back to
 * the last known-good routes.
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

  private final AtomicLong version = new AtomicLong();
  private final AtomicReference<Map<String, String>> stableRoutes = new AtomicReference<>();
  private final AtomicReference<Map<String, String>> canaryRoutes = new AtomicReference<>();
  private final ReentrantLock rolloutLock = new ReentrantLock();
  private final List<RolloutRecord> history = Collections.synchronizedList(new ArrayList<>());

  public RolloutService(XdsServer xds, HealthProbe probe, RolloutPolicy policy, EdgeProperties props) {
    this.xds = xds;
    this.probe = probe;
    this.policy = policy;
    this.props = props;
  }

  @PostConstruct
  void init() {
    Map<String, String> initial = Map.copyOf(props.initialRoutes());
    stableRoutes.set(initial);
    push(STABLE, initial);
    push(CANARY, initial);
    log.info("Initial routes pushed to both groups: {}", initial);
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
            + new java.util.TreeSet<>(props.backends().keySet()));
      }
    });
    return problems;
  }

  /** Runs one rollout. Returns empty if another rollout is already in progress. */
  public Optional<RolloutRecord> rollout(Map<String, String> candidate) throws InterruptedException {
    if (!rolloutLock.tryLock()) {
      return Optional.empty();
    }
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
          ack.name().toLowerCase(), result.total(), result.failed(), result.errorRate());
      history.add(record);
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

  public List<RolloutRecord> history() {
    synchronized (history) {
      return List.copyOf(history);
    }
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
      double errorRate) {}
}
