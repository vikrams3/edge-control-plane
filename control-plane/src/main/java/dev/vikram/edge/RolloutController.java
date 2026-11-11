package dev.vikram.edge;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API for route changes. A rollout can take tens of seconds (the Prometheus probe watches
 * the canary for 30 s), so starting one returns 202 Accepted with a Location to poll.
 */
@RestController
@RequestMapping("/v1")
@Tag(name = "Edge routes", description = "Canary rollouts of the hostname -> backend route table")
public class RolloutController {

  private final RolloutService rollouts;

  public RolloutController(RolloutService rollouts) {
    this.rollouts = rollouts;
  }

  @GetMapping("/routes")
  @Operation(summary = "Current routes on each Envoy group (stable and canary)")
  public Map<String, Map<String, String>> routes() {
    return Map.of(
        RolloutService.STABLE, rollouts.stableRoutes(),
        RolloutService.CANARY, rollouts.canaryRoutes());
  }

  @PostMapping("/rollouts")
  @Operation(summary = "Start a canary rollout of a full route table (hostname -> backend)")
  @ApiResponse(responseCode = "202", description = "Started; poll the Location header")
  @ApiResponse(responseCode = "200", description = "Finished (with wait=true)")
  @ApiResponse(responseCode = "400", description = "Empty table or unknown backend")
  @ApiResponse(responseCode = "409", description = "Another rollout is in progress")
  public ResponseEntity<?> rollout(
      @RequestBody Map<String, String> routes,
      @Parameter(description = "Block until the rollout finishes and return its result")
      @RequestParam(defaultValue = "false") boolean wait) throws InterruptedException {
    if (routes == null || routes.isEmpty()) {
      return ResponseEntity.badRequest().body(Map.of("error", "route table must not be empty"));
    }
    List<String> problems = rollouts.validate(routes);
    if (!problems.isEmpty()) {
      return ResponseEntity.badRequest().body(Map.of("error", "invalid route table", "problems", problems));
    }
    return respond(rollouts.start(routes, null), wait);
  }

  @PostMapping("/rollouts/{version}/revert")
  @Operation(summary = "Roll out an earlier version's routes again, through the canary")
  @ApiResponse(responseCode = "202", description = "Started; poll the Location header")
  @ApiResponse(responseCode = "200", description = "Finished (with wait=true)")
  @ApiResponse(responseCode = "400", description = "Its backends are no longer configured")
  @ApiResponse(responseCode = "404", description = "No rollout with that version")
  @ApiResponse(responseCode = "409", description = "Another rollout is in progress")
  public ResponseEntity<?> revert(@PathVariable long version,
      @RequestParam(defaultValue = "false") boolean wait) throws InterruptedException {
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
    return respond(rollouts.start(routes, version), wait);
  }

  @GetMapping("/rollouts/{version}")
  @Operation(summary = "One rollout: in_progress, promoted or rolled_back")
  @ApiResponse(responseCode = "404", description = "No rollout with that version")
  public ResponseEntity<?> rollout(@PathVariable long version) {
    return rollouts.find(version)
        .<ResponseEntity<?>>map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(Map.of("error", "no rollout with version " + version)));
  }

  @GetMapping("/rollouts")
  @Operation(summary = "Every rollout ever run, oldest first")
  public List<RolloutService.RolloutRecord> history() {
    return rollouts.history();
  }

  private ResponseEntity<?> respond(Optional<RolloutService.Started> started, boolean wait)
      throws InterruptedException {
    if (started.isEmpty()) {
      return ResponseEntity.status(HttpStatus.CONFLICT)
          .body(Map.of("error", "another rollout is in progress"));
    }
    RolloutService.Started s = started.get();
    if (!wait) {
      return ResponseEntity.accepted()
          .location(URI.create("/v1/rollouts/" + s.record().version()))
          .body(s.record());
    }
    try {
      return ResponseEntity.ok(s.done().get());
    } catch (ExecutionException e) {
      return ResponseEntity.internalServerError()
          .body(Map.of("error", "rollout v" + s.record().version() + " failed: " + e.getCause()));
    }
  }
}
