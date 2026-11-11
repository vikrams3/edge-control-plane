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
 */
@ConfigurationProperties(prefix = "edge.optimizer")
public record OptimizerProperties(
    @DefaultValue("false") boolean enabled,
    @DefaultValue("30") int intervalSeconds,
    @DefaultValue("10") int stepPercent,
    @DefaultValue("10") int minWeightPercent,
    @DefaultValue("20") int minRequests,
    @DefaultValue("20") int thresholdPercent,
    Map<String, List<String>> pools) {

  public Map<String, List<String>> pools() {
    return pools == null ? Map.of() : pools;
  }
}
