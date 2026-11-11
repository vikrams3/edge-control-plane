package dev.vikram.edge;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** REST API for route changes. */
@RestController
@RequestMapping("/v1")
public class RolloutController {

  private final RolloutService rollouts;

  public RolloutController(RolloutService rollouts) {
    this.rollouts = rollouts;
  }

  /** Current routes on each Envoy group. */
  @GetMapping("/routes")
  public Map<String, Map<String, String>> routes() {
    return Map.of(
        RolloutService.STABLE, rollouts.stableRoutes(),
        RolloutService.CANARY, rollouts.canaryRoutes());
  }

  /**
   * Starts a canary rollout of a full route table (hostname to backend).
   * 200 with the result, 400 for an empty table or an unknown backend, 409 if a rollout is
   * already running.
   */
  @PostMapping("/rollouts")
  public ResponseEntity<?> rollout(@RequestBody Map<String, String> routes) throws InterruptedException {
    if (routes == null || routes.isEmpty()) {
      return ResponseEntity.badRequest().body(Map.of("error", "route table must not be empty"));
    }
    List<String> problems = rollouts.validate(routes);
    if (!problems.isEmpty()) {
      return ResponseEntity.badRequest().body(Map.of("error", "invalid route table", "problems", problems));
    }
    return rollouts.rollout(routes)
        .<ResponseEntity<?>>map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.status(HttpStatus.CONFLICT)
            .body(Map.of("error", "another rollout is in progress")));
  }

  /**
   * Rolls out the routes of an earlier rollout again, through the canary like any other change.
   * 404 if that version does not exist, 400 if its backends are no longer configured.
   */
  @PostMapping("/rollouts/{version}/revert")
  public ResponseEntity<?> revert(@PathVariable long version) throws InterruptedException {
    Optional<RolloutService.RolloutRecord> earlier = rollouts.find(version);
    if (earlier.isEmpty()) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND)
          .body(Map.of("error", "no rollout with version " + version));
    }
    Map<String, String> routes = earlier.get().routes();
    List<String> problems = rollouts.validate(routes);
    if (!problems.isEmpty()) {
      return ResponseEntity.badRequest()
          .body(Map.of("error", "cannot revert to v" + version, "problems", problems));
    }
    return rollouts.rollout(routes, version)
        .<ResponseEntity<?>>map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.status(HttpStatus.CONFLICT)
            .body(Map.of("error", "another rollout is in progress")));
  }

  /** Every rollout ever run, oldest first, from PostgreSQL. */
  @GetMapping("/rollouts")
  public List<RolloutService.RolloutRecord> history() {
    return rollouts.history();
  }
}
