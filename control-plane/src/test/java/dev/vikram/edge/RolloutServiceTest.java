package dev.vikram.edge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** RolloutService with a real (unstarted) xDS server, so no Envoy is ever connected. */
class RolloutServiceTest {

  private static final Map<String, String> ROUTES = Map.of("sales.edge.local", "sales");

  private final EdgeProperties props = new EdgeProperties(0, "http://x",
      Map.of("sales", "sales:8080"), ROUTES, 1, 0.05, 300, "synthetic", "http://p", 30, null, null, "http://a");
  private final RolloutRepository repo = mock(RolloutRepository.class);
  private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
  private RolloutService service;

  @BeforeEach
  void setUp() {
    when(repo.findFirstByOutcomeOrderByVersionDesc("promoted")).thenReturn(Optional.empty());
    XdsServer xds = new XdsServer(props, new ConfigAckTracker());
    HealthProbe probe = hosts -> new HealthProbe.ProbeResult(10, 0);   // would promote
    service = new RolloutService(xds, probe, new ErrorRatePolicy(props), props, repo, metrics,
        new TrafficWeights(OptimizerProperties.of(false, 30, 10, 10, 20, 20, null)),
        BackendOptions.none());
    service.init();
  }

  @Test
  void rollsBackWithoutProbingWhenNoCanaryEnvoyIsConnected() throws Exception {
    RolloutService.Started started = service.start(ROUTES, null).orElseThrow();
    assertThat(started.record().outcome()).isEqualTo("in_progress");

    RolloutService.RolloutRecord done = started.done().get(5, TimeUnit.SECONDS);
    assertThat(done.outcome()).isEqualTo("rolled_back");
    assertThat(done.canaryAck()).isEqualTo("no_nodes");
    assertThat(done.probeRequests()).isZero();
    verify(repo).save(any(RolloutEntity.class));
    assertThat(metrics.counter("edge.rollouts", "outcome", "rolled_back", "canary_ack", "no_nodes")
        .count()).isEqualTo(1);
  }

  @Test
  void onlyOneRolloutAtATimeAndTheSlotIsFreedAfterwards() throws Exception {
    RolloutService.Started first = service.start(ROUTES, null).orElseThrow();
    assertThat(service.start(ROUTES, null)).isEmpty();             // controller answers 409
    assertThat(service.find(first.record().version())).get()
        .extracting(RolloutService.RolloutRecord::outcome).isEqualTo("in_progress");

    first.done().get(5, TimeUnit.SECONDS);
    RolloutService.Started second = service.start(ROUTES, null).orElseThrow();
    assertThat(second.record().version()).isGreaterThan(first.record().version());
    second.done().get(5, TimeUnit.SECONDS);
  }
}
