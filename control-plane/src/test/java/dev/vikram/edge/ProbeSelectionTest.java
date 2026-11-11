package dev.vikram.edge;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ProbeSelectionTest {

  @Test
  void onlyNewOrChangedHostnamesAreProbed() {
    Map<String, String> stable = Map.of("a.local", "sales", "b.local", "tableau");
    assertThat(RolloutService.changedHosts(stable,
        Map.of("a.local", "sales", "b.local", "billing", "c.local", "sales")))
        .containsExactly("b.local", "c.local");
  }

  @Test
  void aRemovalOnlyChangeStillProbesEveryRemainingHostname() {
    assertThat(RolloutService.changedHosts(Map.of("a.local", "sales", "b.local", "sales"),
        Map.of("a.local", "sales"))).containsExactly("a.local");
  }

  @Test
  void kafkaProbeTestsQuietHostnamesSynthetically() throws Exception {
    Set<String> syntheticallyProbed = new HashSet<>();
    HealthProbe fallback = hosts -> {
      syntheticallyProbed.addAll(hosts);
      return new HealthProbe.ProbeResult(25 * hosts.size(), 0);
    };
    EdgeProperties props = new EdgeProperties(0, "http://x", Map.of(), Map.of(), 25, 0.05, 1000,
        "kafka", "http://p", 30, null, null, "http://a");
    var byHost = new ObjectMapper().readTree("""
        {"crm.local":  {"requests": 40, "errors5xx": 4},
         "shop.local": {"requests": 2,  "errors5xx": 0}}""");

    var result = new KafkaHealthProbe(props, fallback)
        .combine(Set.of("crm.local", "shop.local", "new.local"), byHost);

    assertThat(syntheticallyProbed).containsExactlyInAnyOrder("shop.local", "new.local");
    assertThat(result.total()).isEqualTo(40 + 50);
    assertThat(result.failed()).isEqualTo(4);
  }
}
