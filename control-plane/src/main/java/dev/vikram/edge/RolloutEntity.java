package dev.vikram.edge;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** One row of the rollouts table (see db/migration/V1__rollouts.sql). */
@Entity
@Table(name = "rollouts")
public class RolloutEntity {

  @Id
  private long version;

  @Column(name = "finished_at", nullable = false)
  private Instant finishedAt;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false, columnDefinition = "jsonb")
  private Map<String, String> routes;

  @Column(nullable = false)
  private String outcome;

  @Column(name = "canary_ack", nullable = false)
  private String canaryAck;

  @Column(name = "probe_requests", nullable = false)
  private int probeRequests;

  @Column(name = "probe_failures", nullable = false)
  private int probeFailures;

  @Column(name = "error_rate", nullable = false)
  private double errorRate;

  @Column(name = "reverted_from")
  private Long revertedFrom;

  protected RolloutEntity() {}   // for JPA

  static RolloutEntity from(RolloutService.RolloutRecord r) {
    RolloutEntity e = new RolloutEntity();
    e.version = r.version();
    e.finishedAt = r.finishedAt();
    e.routes = r.routes();
    e.outcome = r.outcome();
    e.canaryAck = r.canaryAck();
    e.probeRequests = r.probeRequests();
    e.probeFailures = r.probeFailures();
    e.errorRate = r.errorRate();
    e.revertedFrom = r.revertedFrom();
    return e;
  }

  RolloutService.RolloutRecord toRecord() {
    return new RolloutService.RolloutRecord(version, finishedAt, Map.copyOf(routes), outcome,
        canaryAck, probeRequests, probeFailures, errorRate, revertedFrom);
  }
}
