package dev.vikram.edge;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** The repository against a real PostgreSQL, with the schema created by the Flyway migration. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class RolloutRepositoryIT {

  @Container
  @ServiceConnection
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Autowired RolloutRepository repo;
  @Autowired PoolWeightRepository pools;
  @Autowired JdbcTemplate jdbc;

  private static RolloutService.RolloutRecord rollout(long v, String outcome, Map<String, String> routes) {
    return new RolloutService.RolloutRecord(v, Instant.now(), routes, outcome, "acked",
        75, "promoted".equals(outcome) ? 0 : 25, "promoted".equals(outcome) ? 0 : 0.33, null);
  }

  @Test
  void flywayCreatedTheSchema() {
    assertThat(jdbc.queryForObject(
        "select count(*) from flyway_schema_history where success", Integer.class)).isEqualTo(2);
  }

  @Test
  void restoresTheNewestPromotedRolloutAndItsRoutes() {
    repo.save(RolloutEntity.from(rollout(3, "promoted", Map.of("crm.edge.local", "sales"))));
    repo.save(RolloutEntity.from(rollout(5, "rolled_back", Map.of("crm.edge.local", "billing"))));
    repo.save(RolloutEntity.from(rollout(8, "promoted", Map.of("shop.edge.local", "tableau"))));
    repo.save(RolloutEntity.from(rollout(10, "rolled_back", Map.of("x.edge.local", "billing"))));

    var last = repo.findFirstByOutcomeOrderByVersionDesc("promoted").orElseThrow().toRecord();
    assertThat(last.version()).isEqualTo(8);
    assertThat(last.routes()).containsExactlyEntriesOf(Map.of("shop.edge.local", "tableau"));
    assertThat(repo.maxVersion()).isEqualTo(10);
    assertThat(repo.findAllByOrderByVersionAsc()).extracting(e -> e.toRecord().version())
        .containsExactly(3L, 5L, 8L, 10L);
  }

  @Test
  void routesAreStoredAsQueryableJsonb() {
    repo.saveAndFlush(RolloutEntity.from(rollout(3, "promoted", Map.of("crm.edge.local", "sales"))));
    assertThat(jdbc.queryForObject(
        "select routes->>'crm.edge.local' from rollouts where version = 3", String.class))
        .isEqualTo("sales");
  }

  @Test
  void maxVersionIsZeroOnAnEmptyTable() {
    assertThat(repo.maxVersion()).isZero();
  }

  @Test
  void trafficSplitSurvivesARestartUnlessThePoolChanged() {
    var props = OptimizerProperties.of(true, 30, 10, 10, 20, 20,
        Map.of("tableau-pool", java.util.List.of("tableau-a", "tableau-b")));
    new TrafficWeights(props, pools).set("tableau-pool", Map.of("tableau-a", 90, "tableau-b", 10));

    // "restart": a new instance reads the stored split
    assertThat(new TrafficWeights(props, pools).get("tableau-pool"))
        .isEqualTo(Map.of("tableau-a", 90, "tableau-b", 10));

    // the pool's members changed in config: the stored split no longer fits, start even
    var changed = OptimizerProperties.of(true, 30, 10, 10, 20, 20,
        Map.of("tableau-pool", java.util.List.of("tableau-a", "tableau-c")));
    assertThat(new TrafficWeights(changed, pools).get("tableau-pool"))
        .isEqualTo(Map.of("tableau-a", 50, "tableau-c", 50));
  }
}
