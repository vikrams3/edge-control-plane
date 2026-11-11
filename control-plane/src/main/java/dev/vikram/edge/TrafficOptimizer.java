package dev.vikram.edge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Self-optimizing traffic split. Every interval, for each pool, reads each member's requests,
 * 5xx and p99 on the stable Envoys over the last minute (from the Kafka analytics service) and:
 *
 * <ol>
 *   <li>ejects a member returning 5xx at or above ejectErrorPercent (weight 0, at once), and
 *       gives it the floor again after ejectionSeconds to see whether it recovered;</li>
 *   <li>otherwise moves at most stepPercent from the slowest to the fastest member, but only
 *       after {@code confirmations} consecutive readings propose the same move;</li>
 *   <li>drifts back toward an even split when the members are close.</li>
 * </ol>
 *
 * Errors act at once; latency waits for confirmation, because a coarse histogram bucket can make
 * one reading look worse than it is. Never changes weights while a rollout runs.
 */
@Component
@ConditionalOnProperty(name = "edge.optimizer.enabled", havingValue = "true")
public class TrafficOptimizer {

  private static final Logger log = LoggerFactory.getLogger(TrafficOptimizer.class);

  /**
   * One member's numbers: requests and p99 over the last minute, and requests and 5xx over the
   * last 20 s (errors use a short window so a fresh failure is not diluted by older traffic).
   */
  record MemberStats(long requests, long p99Ms, long recentRequests, long recentErrors) {
    int errorPercent() {
      return recentRequests == 0 ? 0 : (int) (recentErrors * 100 / recentRequests);
    }
  }

  enum Kind { HOLD, SHIFT, REBALANCE, EJECT, READMIT }

  /** What one step decided, and why. EJECT and READMIT apply at once; SHIFT and REBALANCE wait. */
  record Decision(Map<String, Integer> weights, Kind kind, String member, String reason) {}

  private final OptimizerProperties props;
  private final TrafficWeights weights;
  private final RolloutService rollouts;
  private final MeterRegistry metrics;
  private final String analytics;
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
  private final ObjectMapper json = new ObjectMapper();
  /** pool -> member -> when it was ejected (epoch ms) */
  private final Map<String, Map<String, Long>> ejected = new ConcurrentHashMap<>();
  /** pool -> member -> how many times in a row it was ejected (for the backoff) */
  private final Map<String, Map<String, Integer>> ejections = new ConcurrentHashMap<>();
  static final long MAX_EJECTION_MS = 10 * 60_000;
  /** pool -> the weights the last reading proposed, waiting for confirmation */
  private final Map<String, Map<String, Integer>> pending = new ConcurrentHashMap<>();
  private final Map<String, Integer> agreeing = new ConcurrentHashMap<>();

  public TrafficOptimizer(OptimizerProperties props, TrafficWeights weights,
      RolloutService rollouts, MeterRegistry metrics, EdgeProperties edge) {
    this.props = props;
    this.weights = weights;
    this.rollouts = rollouts;
    this.metrics = metrics;
    this.analytics = edge.analyticsUrl();
  }

  @PostConstruct
  void registerGauges() {
    props.pools().forEach((pool, members) -> members.forEach(m ->
        Gauge.builder("edge.pool.weight", weights, w -> w.get(pool).getOrDefault(m, 0))
            .description("Percent of the pool's traffic sent to this backend")
            .tags("pool", pool, "backend", m).register(metrics)));
  }

  @Scheduled(initialDelayString = "${edge.optimizer.interval-seconds:30}000",
      fixedDelayString = "${edge.optimizer.interval-seconds:30}000")
  void step() {
    Map<String, MemberStats> stats;
    try {
      stats = fetchStats();
    } catch (Exception e) {
      log.warn("Optimizer: analytics unavailable, holding all weights ({})", e.toString());
      return;
    }
    long now = System.currentTimeMillis();
    props.pools().keySet().forEach(pool -> step(pool, stats, now));
  }

  void step(String pool, Map<String, MemberStats> stats, long now) {
    Map<String, Integer> current = weights.get(pool);
    Map<String, Long> out = ejected.computeIfAbsent(pool, p -> new ConcurrentHashMap<>());
    Map<String, Integer> count = ejections.computeIfAbsent(pool, p -> new ConcurrentHashMap<>());
    // A member back in service that is healthy again starts its backoff from scratch.
    current.forEach((m, w) -> {
      MemberStats s = stats.get(m);
      if (w > 0 && !out.containsKey(m) && s != null && s.requests() >= props.minRequests()
          && s.errorPercent() < props.ejectErrorPercent()) {
        count.remove(m);
      }
    });
    Decision d = decide(current, stats, props, out, count, now);
    switch (d.kind()) {
      case HOLD -> {
        pending.remove(pool);
        log.info("Optimizer: {} holds {}: {}", pool, sorted(current), d.reason());
      }
      case EJECT, READMIT -> {
        if (apply(pool, current, d)) {
          pending.remove(pool);
          if (d.kind() == Kind.EJECT) {
            out.put(d.member(), now);
            count.merge(d.member(), 1, Integer::sum);
          } else {
            out.remove(d.member());
          }
        }
      }
      case SHIFT, REBALANCE -> {
        int seen = d.weights().equals(pending.get(pool)) ? agreeing.getOrDefault(pool, 1) + 1 : 1;
        if (seen >= props.confirmations()) {
          if (apply(pool, current, d)) {
            pending.remove(pool);
          }
        } else {
          pending.put(pool, d.weights());
          agreeing.put(pool, seen);
          log.info("Optimizer: {} reading {} of {} proposes {}: {}", pool, seen,
              props.confirmations(), sorted(d.weights()), d.reason());
        }
      }
    }
  }

  private boolean apply(String pool, Map<String, Integer> current, Decision d) {
    if (!rollouts.applyWeights(pool, d.weights())) {
      log.info("Optimizer: {} skipped, a rollout is in progress", pool);
      return false;
    }
    log.info("Optimizer: {} {} -> {}: {}", pool, sorted(current), sorted(d.weights()), d.reason());
    return true;
  }

  /**
   * Latency over the last minute (p99 needs many samples), errors over the last 20 s: a fresh
   * failure is diluted by a minute of healthy history, and errors should act fast.
   */
  private Map<String, MemberStats> fetchStats() throws Exception {
    long now = System.currentTimeMillis();
    JsonNode minute = byBackend(now - 60_000);
    JsonNode recent = byBackend(now - ERROR_WINDOW_MS);
    Map<String, MemberStats> out = new LinkedHashMap<>();
    minute.fields().forEachRemaining(e -> {
      JsonNode r = recent.path(e.getKey());
      out.put(e.getKey(), new MemberStats(
          e.getValue().path("requests").asLong(), e.getValue().path("p99Ms").asLong(),
          r.path("requests").asLong(), r.path("errors5xx").asLong()));
    });
    return out;
  }

  static final long ERROR_WINDOW_MS = 20_000;

  private JsonNode byBackend(long since) throws Exception {
    URI uri = URI.create(analytics + "/v1/stats?group=stable&by=backend&since=" + since);
    return json.readTree(http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).build(),
        BodyHandlers.ofString()).body()).path("byBackend");
  }

  /**
   * The next weights for one pool. A pure function of its inputs, so it is unit-tested directly.
   *
   * @param ejectedSince members currently ejected, and since when (epoch ms)
   */
  static Decision decide(Map<String, Integer> current, Map<String, MemberStats> stats,
      OptimizerProperties p, Map<String, Long> ejectedSince, long now) {
    return decide(current, stats, p, ejectedSince, Map.of(), now);
  }

  /** How long the member stays out after its n-th ejection in a row: base, 2x, 4x... capped. */
  static long ejectionMs(OptimizerProperties p, int ejectionsInARow) {
    long ms = p.ejectionSeconds() * 1000L << Math.min(Math.max(ejectionsInARow - 1, 0), 10);
    return Math.min(ms, Math.max(MAX_EJECTION_MS, p.ejectionSeconds() * 1000L));
  }

  /** @param ejectionsInARow per member, how many times in a row it was ejected */
  static Decision decide(Map<String, Integer> current, Map<String, MemberStats> stats,
      OptimizerProperties p, Map<String, Long> ejectedSince, Map<String, Integer> ejectionsInARow,
      long now) {
    MemberStats none = new MemberStats(0, 0, 0, 0);

    // 1. An ejected member whose time is up gets the floor back, to show whether it recovered.
    for (String m : current.keySet()) {
      long out = ejectionMs(p, ejectionsInARow.getOrDefault(m, 1));
      if (current.get(m) == 0 && now - ejectedSince.getOrDefault(m, 0L) >= out) {
        String donor = largest(current);
        return new Decision(shift(current, donor, m, p.minWeightPercent()), Kind.READMIT, m,
            m + " was ejected " + out / 1000 + " s ago; giving it "
                + p.minWeightPercent() + "% to see whether it recovered");
      }
    }

    List<String> active = current.keySet().stream().filter(m -> current.get(m) > 0).toList();

    // 2. Errors act at once: eject a failing member, unless it is the last one serving.
    for (String m : active) {
      MemberStats s = stats.getOrDefault(m, none);
      if (s.recentRequests() >= p.minRequests() / 2 && s.errorPercent() >= p.ejectErrorPercent()) {
        String best = active.stream().filter(o -> !o.equals(m))
            .filter(o -> stats.getOrDefault(o, none).errorPercent() < p.ejectErrorPercent())
            .min(Comparator.comparingInt((String o) -> stats.getOrDefault(o, none).errorPercent())
                .thenComparingLong(o -> stats.getOrDefault(o, none).p99Ms()))
            .orElse(null);
        if (best == null) {
          return new Decision(current, Kind.HOLD, m, m + " returns " + s.errorPercent()
              + "% 5xx, but no healthy member is left to take its traffic");
        }
        return new Decision(shift(current, m, best, current.get(m)), Kind.EJECT, m,
            m + " returns " + s.errorPercent() + "% 5xx in the last 20 s (" + s.recentErrors()
                + " of " + s.recentRequests() + "); ejecting it, its traffic goes to " + best);
      }
    }

    // 3. Latency, among the members that serve traffic, with enough requests to judge.
    if (active.size() < 2) {
      return new Decision(current, Kind.HOLD, null, "only one member is serving traffic");
    }
    for (String m : active) {
      long r = stats.getOrDefault(m, none).requests();
      if (r < p.minRequests()) {
        return new Decision(current, Kind.HOLD, m, m + " has " + r
            + " requests in the last minute, fewer than " + p.minRequests() + "; not enough to judge");
      }
    }
    String fast = active.stream().min(Comparator.comparingLong(m -> stats.get(m).p99Ms())).orElseThrow();
    String slow = active.stream().max(Comparator.comparingLong(m -> stats.get(m).p99Ms())).orElseThrow();
    String numbers = "p99 " + new TreeMap<>(current).keySet().stream()
        .map(m -> m + "=" + stats.getOrDefault(m, none).p99Ms() + "ms")
        .collect(Collectors.joining(", "));

    if (stats.get(slow).p99Ms() * 100 > stats.get(fast).p99Ms() * (100L + p.thresholdPercent())) {
      int move = Math.min(p.stepPercent(), current.get(slow) - p.minWeightPercent());
      if (move <= 0) {
        return new Decision(current, Kind.HOLD, slow, numbers + "; " + slow + " is already at the "
            + p.minWeightPercent() + "% floor");
      }
      return new Decision(shift(current, slow, fast, move), Kind.SHIFT, slow,
          numbers + "; shifting " + move + "% to " + fast);
    }
    if (active.size() < current.size()) {
      return new Decision(current, Kind.HOLD, null, numbers + "; a member is ejected, not rebalancing");
    }
    // Close enough: drift back toward an even split, one bounded step at a time.
    Map<String, Integer> even = TrafficWeights.even(current.keySet().stream().toList());
    String over = current.keySet().stream()
        .max(Comparator.comparingInt(m -> current.get(m) - even.get(m))).orElseThrow();
    String under = current.keySet().stream()
        .min(Comparator.comparingInt(m -> current.get(m) - even.get(m))).orElseThrow();
    int move = Math.min(p.stepPercent(), current.get(over) - even.get(over));
    if (move <= 0) {
      return new Decision(current, Kind.HOLD, null,
          numbers + "; within " + p.thresholdPercent() + "%, already even");
    }
    return new Decision(shift(current, over, under, move), Kind.REBALANCE, over, numbers
        + "; within " + p.thresholdPercent() + "%, rebalancing toward even");
  }

  private static String largest(Map<String, Integer> w) {
    return w.keySet().stream().max(Comparator.comparingInt(w::get)).orElseThrow();
  }

  private static Map<String, Integer> shift(Map<String, Integer> w, String from, String to, int n) {
    Map<String, Integer> next = new LinkedHashMap<>(w);
    next.put(from, w.get(from) - n);
    next.put(to, w.get(to) + n);
    return next;
  }

  private static Map<String, Integer> sorted(Map<String, Integer> w) {
    return new TreeMap<>(w);
  }
}
