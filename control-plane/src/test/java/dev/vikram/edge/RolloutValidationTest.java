package dev.vikram.edge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class RolloutValidationTest {

  private final RolloutService service = new RolloutService(null, null, null,
      new EdgeProperties(18000, "http://x",
          Map.of("tableau", "tableau:8080", "sales", "sales:8080"), Map.of(), 10, 0.05, 1000, "synthetic", "http://p", 30, null, null), null, null);

  @Test
  void knownBackendsAreValid() {
    assertThat(service.validate(Map.of("crm.edge.local", "sales"))).isEmpty();
  }

  @Test
  void unknownBackendIsRejectedWithItsName() {
    assertThat(service.validate(Map.of("sales.edge.local", "salez")))
        .singleElement().asString()
        .contains("sales.edge.local", "salez", "[sales, tableau]");
  }
}
