package dev.vikram.edge;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Rollout history in PostgreSQL. Spring Data generates the queries from the method names. */
public interface RolloutRepository extends JpaRepository<RolloutEntity, Long> {

  /** The last known-good route table, restored on startup. */
  Optional<RolloutEntity> findFirstByOutcomeOrderByVersionDesc(String outcome);

  List<RolloutEntity> findAllByOrderByVersionAsc();

  /** Highest version ever pushed, so versions keep increasing across restarts. */
  @Query("select coalesce(max(r.version), 0) from RolloutEntity r")
  long maxVersion();
}
