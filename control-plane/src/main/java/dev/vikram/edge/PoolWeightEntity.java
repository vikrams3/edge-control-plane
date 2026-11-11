package dev.vikram.edge;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** One row of pool_weights (db/migration/V2__pool_weights.sql). */
@Entity
@Table(name = "pool_weights")
public class PoolWeightEntity {

  @Id
  private String pool;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false, columnDefinition = "jsonb")
  private Map<String, Integer> weights;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected PoolWeightEntity() {}   // for JPA

  PoolWeightEntity(String pool, Map<String, Integer> weights) {
    this.pool = pool;
    this.weights = weights;
    this.updatedAt = Instant.now();
  }

  String pool() {
    return pool;
  }

  Map<String, Integer> weights() {
    return weights;
  }
}
