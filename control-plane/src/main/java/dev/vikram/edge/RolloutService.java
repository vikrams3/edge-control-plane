package dev.vikram.edge;

import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Canary rollout: push candidate routes to the canary group only, probe it, then either
 * promote to the stable group or roll the canary back to the last known-good routes.
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

  /** Runs one rollout. Returns empty if another rollout is already in progress. */
  public java.util.Optional<RolloutRecord> rollout(Map<String, String> candidate) throws InterruptedException {
    if (!rolloutLock.tryLock()) {
      return java.util.Optional.empty();
    }
    try {
      Map<String, String> routes = Map.copyOf(candidate);
      long v = push(CANARY, routes);
      log.info("v{} pushed to canary: {}", v, routes);

      Thread.sleep(props.settleMillis());   // let the canary Envoys apply the update
      HealthProbe.ProbeResult result = probe.probe(routes.keySet());

      String outcome;
      if (policy.shouldPromote(result)) {
        stableRoutes.set(routes);
        push(STABLE, routes);
        outcome = "promoted";
      } else {
        push(CANARY, stableRoutes.get());   // automatic rollback
        outcome = "rolled_back";
      }
      log.info("v{} {}: {} of {} probe requests failed ({}%)", v, outcome,
          result.failed(), result.total(), Math.round(result.errorRate() * 100));

      RolloutRecord record = new RolloutRecord(v, Instant.now(), routes, outcome,
          result.total(), result.failed(), result.errorRate());
      history.add(record);
      return java.util.Optional.of(record);
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
      int probeRequests,
      int probeFailures,
      double errorRate) {}
}
