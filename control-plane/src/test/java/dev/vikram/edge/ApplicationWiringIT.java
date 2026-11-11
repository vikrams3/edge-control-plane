package dev.vikram.edge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Starts the whole application, with a real PostgreSQL, once per probe setting. Bean wiring
 * errors (a missing @Autowired, a missing property) only appear when Spring builds the context,
 * not in unit tests that construct classes directly. See the 2026-11-11 postmortem.
 */
class ApplicationWiringIT {

  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @BeforeAll
  static void start() {
    postgres.start();
  }

  @AfterAll
  static void stop() {
    postgres.stop();
  }

  @ParameterizedTest(name = "edge.probe={0}, optimizer={2}")
  @CsvSource({
      "synthetic,  SyntheticHealthProbe,  false",
      "prometheus, PrometheusHealthProbe, false",
      "kafka,      KafkaHealthProbe,      false",
      "synthetic,  SyntheticHealthProbe,  true"})
  void startsWithEachProbeAndTheOptimizer(String probe, String expectedClass, boolean optimizer) {
    Map<String, Object> props = Map.of(
        "spring.datasource.url", postgres.getJdbcUrl(),
        "spring.datasource.username", postgres.getUsername(),
        "spring.datasource.password", postgres.getPassword(),
        "edge.xds-port", 0,                       // any free port
        "edge.probe", probe,
        "edge.optimizer.enabled", optimizer,
        "edge.optimizer.pools.tableau-pool[0]", "tableau",
        "edge.optimizer.pools.tableau-pool[1]", "sales",
        "spring.main.banner-mode", "off");
    // as command-line arguments: builder.properties() would only set defaults, which
    // application.yml overrides
    String[] args = props.entrySet().stream().map(e -> "--" + e.getKey() + "=" + e.getValue())
        .toArray(String[]::new);
    try (ConfigurableApplicationContext ctx = new SpringApplicationBuilder(EdgeControlPlaneApplication.class)
        .web(WebApplicationType.NONE).run(args)) {
      assertThat(ctx.getBean(HealthProbe.class).getClass().getSimpleName()).isEqualTo(expectedClass);
      assertThat(ctx.getBeansOfType(TrafficOptimizer.class)).hasSize(optimizer ? 1 : 0);
      assertThat(ctx.getBean(RolloutService.class).stableRoutes()).isNotEmpty();
    }
  }
}
