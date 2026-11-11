package dev.vikram.edge;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Current traffic split of each backend pool (weights in percent, summing to 100).
 * A hostname routed to a pool name gets Envoy weighted_clusters with these weights.
 * Stored in PostgreSQL, so a restart keeps the split; a stored split whose members no longer
 * match the configured pool is ignored and the pool starts even.
 */
@Component
public class TrafficWeights {

  private static final Logger log = LoggerFactory.getLogger(TrafficWeights.class);

  private final Map<String, Map<String, Integer>> weights = new ConcurrentHashMap<>();
  private final PoolWeightRepository repo;   // null in unit tests: in memory only

  public TrafficWeights(OptimizerProperties props) {
    this(props, null);
  }

  @Autowired
  public TrafficWeights(OptimizerProperties props, PoolWeightRepository repo) {
    this.repo = repo;
    props.pools().forEach((pool, members) -> {
      Map<String, Integer> start = even(members);
      if (repo != null) {
        var stored = repo.findById(pool).map(PoolWeightEntity::weights).orElse(null);
        if (stored != null && stored.keySet().equals(Set.copyOf(members))
            && stored.values().stream().mapToInt(Integer::intValue).sum() == 100) {
          start = Map.copyOf(stored);
          log.info("Restored traffic split for {}: {}", pool, start);
        }
      }
      weights.put(pool, start);
    });
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
    if (repo != null) {
      repo.save(new PoolWeightEntity(pool, Map.copyOf(next)));
    }
  }
}
