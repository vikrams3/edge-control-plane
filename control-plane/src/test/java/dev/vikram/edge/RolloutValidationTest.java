package dev.vikram.edge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class RolloutValidationTest {

  private final RolloutService service = new RolloutService(null, null, null,
      new EdgeProperties(18000, "http://x",
          Map.of("tableau", "tableau:8080", "sales", "sales:8080"), Map.of(), 10, 0.05, 1000, "synthetic", "http://p", 30, null, null, "http://a"), null, null,
      new TrafficWeights(new OptimizerProperties(false, 30, 10, 10, 20, 20,
          Map.of("tableau-pool", java.util.List.of("tableau-a", "tableau-b")))),
      BackendOptions.none());

  @Test
  void knownBackendsAreValid() {
    assertThat(service.validate(Map.of("crm.edge.local", "sales"))).isEmpty();
  }

  @Test
  void unknownBackendIsRejectedWithItsName() {
    assertThat(service.validate(Map.of("sales.edge.local", "salez")))
        .singleElement().asString()
        .contains("sales.edge.local", "salez", "[sales, tableau, tableau-pool]");
  }

  @Test
  void aPoolNameIsAValidBackend() {
    assertThat(service.validate(Map.of("tableau.edge.local", "tableau-pool"))).isEmpty();
  }
}
