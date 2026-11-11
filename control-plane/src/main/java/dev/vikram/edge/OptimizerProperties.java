package dev.vikram.edge;

import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Self-optimizing traffic split, from application.yml "edge.optimizer".
 *
 * @param enabled run the optimizer loop (needs the analytics service)
 * @param intervalSeconds how often to re-balance
 * @param stepPercent most weight moved per step, so it never swings wildly
 * @param minWeightPercent floor per member, so the slower one stays measured and can win back
 * @param minRequests fewer requests than this per member in the last minute: hold
 * @param thresholdPercent act only if the slower p99 is this much worse than the faster one
 * @param pools pool name to its member backends, e.g. tableau-pool: [tableau-a, tableau-b]
 * @param ejectErrorPercent a member at or above this 5xx rate is ejected (weight 0) at once
 * @param ejectionSeconds how long an ejected member first stays out before it gets the floor
 *     again; doubles each time it is ejected again (up to 10 minutes), like Envoy's outlier
 *     detection, so re-testing a member that stays broken costs fewer and fewer requests
 * @param confirmations consecutive readings that must agree before a latency-based shift
 */
@ConfigurationProperties(prefix = "edge.optimizer")
public record OptimizerProperties(
    @DefaultValue("false") boolean enabled,
    @DefaultValue("30") int intervalSeconds,
    @DefaultValue("10") int stepPercent,
    @DefaultValue("10") int minWeightPercent,
    @DefaultValue("20") int minRequests,
    @DefaultValue("20") int thresholdPercent,
    Map<String, List<String>> pools,
    @DefaultValue("50") int ejectErrorPercent,
    @DefaultValue("60") int ejectionSeconds,
    @DefaultValue("2") int confirmations) {

  /**
   * Default ejection and confirmation settings (used by tests). A factory, not a second
   * constructor: with two constructors Spring cannot tell which one to bind properties to.
   */
  public static OptimizerProperties of(boolean enabled, int intervalSeconds, int stepPercent,
      int minWeightPercent, int minRequests, int thresholdPercent, Map<String, List<String>> pools) {
    return new OptimizerProperties(enabled, intervalSeconds, stepPercent, minWeightPercent,
        minRequests, thresholdPercent, pools, 50, 60, 2);
  }

  public Map<String, List<String>> pools() {
    return pools == null ? Map.of() : pools;
  }
}
