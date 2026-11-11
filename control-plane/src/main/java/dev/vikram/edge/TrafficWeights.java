package dev.vikram.edge;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Current traffic split of each backend pool (weights in percent, summing to 100).
 * A hostname routed to a pool name gets Envoy weighted_clusters with these weights.
 * Starts as an even split; not persisted, so a restart starts even again.
 */
@Component
public class TrafficWeights {

  private final Map<String, Map<String, Integer>> weights = new ConcurrentHashMap<>();

  public TrafficWeights(OptimizerProperties props) {
    props.pools().forEach((pool, members) -> weights.put(pool, even(members)));
  }

  static Map<String, Integer> even(List<String> members) {
    Map<String, Integer> w = new LinkedHashMap<>();
    int share = 100 / members.size();
    for (int i = 0; i < members.size(); i++) {
      w.put(members.get(i), i == 0 ? 100 - share * (members.size() - 1) : share);
    }
    return Map.copyOf(w);
  }

  public boolean isPool(String name) {
    return weights.containsKey(name);
  }

  public Map<String, Map<String, Integer>> all() {
    return Map.copyOf(weights);
  }

  public Map<String, Integer> get(String pool) {
    return weights.get(pool);
  }

  void set(String pool, Map<String, Integer> next) {
    weights.put(pool, Map.copyOf(next));
  }
}
