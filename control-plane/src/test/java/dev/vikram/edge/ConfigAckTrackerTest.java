package dev.vikram.edge;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.rpc.Status;
import dev.vikram.edge.ConfigAckTracker.AckStatus;
import io.envoyproxy.controlplane.cache.Resources;
import io.envoyproxy.envoy.config.core.v3.Node;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryRequest;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class ConfigAckTrackerTest {

  private static final String ROUTES = Resources.V3.ROUTE_TYPE_URL;
  private static final Duration SHORT = Duration.ofMillis(100);

  private final ConfigAckTracker tracker = new ConfigAckTracker();

  /** Simulates one Envoy: it connects, we send it routes version v, and it replies. */
  private void connect(long stream, String group) {
    tracker.onStreamOpen(stream, "");
    tracker.onV3StreamRequest(stream, DiscoveryRequest.newBuilder()
        .setNode(Node.newBuilder().setId("envoy-" + stream).setCluster(group))
        .setTypeUrl(ROUTES).build());
  }

  private void send(long stream, long version) {
    tracker.onV3StreamResponse(stream, DiscoveryRequest.getDefaultInstance(),
        DiscoveryResponse.newBuilder().setTypeUrl(ROUTES)
            .setVersionInfo(Long.toString(version)).setNonce("n" + version).build());
  }

  private void ack(long stream, long version) {
    tracker.onV3StreamRequest(stream, DiscoveryRequest.newBuilder().setTypeUrl(ROUTES)
        .setVersionInfo(Long.toString(version)).setResponseNonce("n" + version).build());
  }

  private void nack(long stream, long version, long stillOn) {
    tracker.onV3StreamRequest(stream, DiscoveryRequest.newBuilder().setTypeUrl(ROUTES)
        .setVersionInfo(Long.toString(stillOn)).setResponseNonce("n" + version)
        .setErrorDetail(Status.newBuilder().setMessage("bad config")).build());
  }

  @Test
  void ackedWhenEveryEnvoyInTheGroupAppliedTheVersion() throws Exception {
    connect(1, "canary");
    connect(2, "canary");
    send(1, 3);
    send(2, 3);
    ack(1, 3);
    assertThat(tracker.await("canary", 3, SHORT)).isEqualTo(AckStatus.TIMEOUT);   // one still pending
    ack(2, 3);
    assertThat(tracker.await("canary", 3, SHORT)).isEqualTo(AckStatus.ACKED);
  }

  @Test
  void nackedWhenAnyEnvoyRejectsTheVersion() throws Exception {
    connect(1, "canary");
    send(1, 3);
    nack(1, 3, 2);
    assertThat(tracker.await("canary", 3, SHORT)).isEqualTo(AckStatus.NACKED);
  }

  @Test
  void otherGroupsDoNotCount() throws Exception {
    connect(1, "stable");
    send(1, 3);
    ack(1, 3);
    assertThat(tracker.await("canary", 3, SHORT)).isEqualTo(AckStatus.NO_NODES);
  }

  @Test
  void disconnectedEnvoysAreForgotten() throws Exception {
    connect(1, "canary");
    tracker.onStreamClose(1, "");
    assertThat(tracker.connected("canary")).isZero();
    assertThat(tracker.await("canary", 3, SHORT)).isEqualTo(AckStatus.NO_NODES);
  }

  @Test
  void awaitWakesUpAsSoonAsTheAckArrives() throws Exception {
    connect(1, "canary");
    send(1, 3);
    Thread.ofVirtual().start(() -> {
      try {
        Thread.sleep(50);
      } catch (InterruptedException ignored) {
      }
      ack(1, 3);
    });
    long start = System.nanoTime();
    assertThat(tracker.await("canary", 3, Duration.ofSeconds(5))).isEqualTo(AckStatus.ACKED);
    assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(1));
  }
}
