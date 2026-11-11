package dev.vikram.edge;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vikram.edge.TrafficOptimizer.MemberStats;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TrafficOptimizerTest {

  private final OptimizerProperties p = new OptimizerProperties(true, 30, 10, 10, 20, 20,
      Map.of("pool", List.of("a", "b")));

  private Map<String, Integer> next(Map<String, Integer> w, long p99a, long p99b, long requests) {
    return TrafficOptimizer.decide(w,
        Map.of("a", new MemberStats(requests, p99a), "b", new MemberStats(requests, p99b)), p).weights();
  }

  @Test
  void shiftsOneBoundedStepTowardTheFasterMember() {
    assertThat(next(Map.of("a", 50, "b", 50), 50, 500, 100)).isEqualTo(Map.of("a", 60, "b", 40));
  }

  @Test
  void neverStarvesTheSlowerMemberBelowTheFloor() {
    var w = Map.of("a", 50, "b", 50);
    for (int i = 0; i < 10; i++) {
      w = next(w, 50, 500, 100);
    }
    assertThat(w).isEqualTo(Map.of("a", 90, "b", 10));
  }

  @Test
  void holdsWhenThereIsTooLittleTrafficToJudge() {
    assertThat(next(Map.of("a", 50, "b", 50), 50, 500, 5)).isEqualTo(Map.of("a", 50, "b", 50));
  }

  @Test
  void smallDifferencesDoNotMoveTraffic() {
    assertThat(next(Map.of("a", 50, "b", 50), 50, 55, 100)).isEqualTo(Map.of("a", 50, "b", 50));
  }

  @Test
  void whenTheMembersAreEqualAgainItDriftsBackToEven() {
    var w = next(Map.of("a", 90, "b", 10), 50, 50, 100);
    assertThat(w).isEqualTo(Map.of("a", 80, "b", 20));
  }

  @Test
  void weightedRouteSplitsTrafficInEnvoy() {
    var rc = EdgeConfig.routeTable(Map.of("t.local", "pool"), Map.of("pool", Map.of("a", 70, "b", 30)));
    var wc = rc.getVirtualHosts(0).getRoutes(0).getRoute().getWeightedClusters();
    assertThat(wc.getClustersList()).extracting(c -> c.getName() + "=" + c.getWeight().getValue())
        .containsExactly("a=70", "b=30");
  }
}
