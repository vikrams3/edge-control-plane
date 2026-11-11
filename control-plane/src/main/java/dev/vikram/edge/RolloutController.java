package dev.vikram.edge;

import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
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
   * 200 with the result, 400 for an empty table, 409 if a rollout is already running.
   */
  @PostMapping("/rollouts")
  public ResponseEntity<?> rollout(@RequestBody Map<String, String> routes) throws InterruptedException {
    if (routes == null || routes.isEmpty()) {
      return ResponseEntity.badRequest().body(Map.of("error", "route table must not be empty"));
    }
    return rollouts.rollout(routes)
        .<ResponseEntity<?>>map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.status(HttpStatus.CONFLICT)
            .body(Map.of("error", "another rollout is in progress")));
  }

  /** Every rollout since the control plane started, oldest first. */
  @GetMapping("/rollouts")
  public List<RolloutService.RolloutRecord> history() {
    return rollouts.history();
  }
}
