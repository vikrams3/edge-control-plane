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
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Self-optimizing traffic split. Every interval, for each pool, reads each member's p99 on the
 * stable Envoys over the last minute (from the Kafka analytics service) and moves weight toward
 * the faster member. Guardrails: bounded steps, a floor per member, a threshold before acting,
 * a minimum number of requests to trust the numbers, and no change while a rollout runs.
 */
@Component
@ConditionalOnProperty(name = "edge.optimizer.enabled", havingValue = "true")
public class TrafficOptimizer {

  private static final Logger log = LoggerFactory.getLogger(TrafficOptimizer.class);

  /** One member's numbers for the last minute. */
  record MemberStats(long requests, long p99Ms) {}

  /** What one step decided, and why. */
  record Decision(Map<String, Integer> weights, String reason) {}

  private final OptimizerProperties props;
  private final TrafficWeights weights;
  private final RolloutService rollouts;
  private final MeterRegistry metrics;
  private final String analytics;
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
  private final ObjectMapper json = new ObjectMapper();

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
    props.pools().keySet().forEach(pool -> {
      Map<String, Integer> current = weights.get(pool);
      Decision d = decide(current, stats, props);
      if (d.weights().equals(current)) {
        log.info("Optimizer: {} holds {}: {}", pool, current, d.reason());
      } else if (rollouts.applyWeights(pool, d.weights())) {
        log.info("Optimizer: {} {} -> {}: {}", pool, current, d.weights(), d.reason());
      } else {
        log.info("Optimizer: {} skipped, a rollout is in progress", pool);
      }
    });
  }

  private Map<String, MemberStats> fetchStats() throws Exception {
    long since = System.currentTimeMillis() - 60_000;
    URI uri = URI.create(analytics + "/v1/stats?group=stable&by=backend&since=" + since);
    JsonNode byBackend = json.readTree(http.send(
        HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).build(),
        BodyHandlers.ofString()).body()).path("byBackend");
    Map<String, MemberStats> out = new LinkedHashMap<>();
    byBackend.fields().forEachRemaining(e -> out.put(e.getKey(), new MemberStats(
        e.getValue().path("requests").asLong(), e.getValue().path("p99Ms").asLong())));
    return out;
  }

  /**
   * The next weights for one pool. Pure function of its inputs, so it is unit-tested directly.
   * Moves at most stepPercent from the slowest to the fastest member when the slowest p99 is
   * more than thresholdPercent worse; otherwise drifts back toward an even split.
   */
  static Decision decide(Map<String, Integer> current, Map<String, MemberStats> stats,
      OptimizerProperties p) {
    for (String m : current.keySet()) {
      MemberStats s = stats.get(m);
      if (s == null || s.requests() < p.minRequests()) {
        return new Decision(current, m + " has " + (s == null ? 0 : s.requests())
            + " requests in the last minute, fewer than " + p.minRequests() + "; not enough to judge");
      }
    }
    String fast = current.keySet().stream()
        .min(Comparator.comparingLong(m -> stats.get(m).p99Ms())).orElseThrow();
    String slow = current.keySet().stream()
        .max(Comparator.comparingLong(m -> stats.get(m).p99Ms())).orElseThrow();
    long fastP99 = stats.get(fast).p99Ms();
    long slowP99 = stats.get(slow).p99Ms();
    String numbers = "p99 " + new java.util.TreeMap<>(current).keySet().stream()
        .map(m -> m + "=" + stats.get(m).p99Ms() + "ms")
        .collect(java.util.stream.Collectors.joining(", "));

    if (slowP99 * 100 > fastP99 * (100L + p.thresholdPercent())) {
      int move = Math.min(p.stepPercent(), current.get(slow) - p.minWeightPercent());
      if (move <= 0) {
        return new Decision(current, numbers + "; " + slow + " is already at the "
            + p.minWeightPercent() + "% floor");
      }
      return new Decision(shift(current, slow, fast, move), numbers + "; shifting " + move
          + "% to " + fast);
    }
    // Close enough: drift back toward an even split, one bounded step at a time.
    Map<String, Integer> even = TrafficWeights.even(current.keySet().stream().toList());
    String over = current.keySet().stream()
        .max(Comparator.comparingInt(m -> current.get(m) - even.get(m))).orElseThrow();
    String under = current.keySet().stream()
        .min(Comparator.comparingInt(m -> current.get(m) - even.get(m))).orElseThrow();
    int move = Math.min(p.stepPercent(), current.get(over) - even.get(over));
    if (move <= 0) {
      return new Decision(current, numbers + "; within " + p.thresholdPercent() + "%, already even");
    }
    return new Decision(shift(current, over, under, move), numbers + "; within "
        + p.thresholdPercent() + "%, rebalancing toward even");
  }

  private static Map<String, Integer> shift(Map<String, Integer> w, String from, String to, int n) {
    Map<String, Integer> next = new LinkedHashMap<>(w);
    next.put(from, w.get(from) - n);
    next.put(to, w.get(to) + n);
    return next;
  }
}
