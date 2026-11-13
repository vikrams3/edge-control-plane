package dev.vikram.edge;

import static org.assertj.core.api.Assertions.assertThat;

import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EdgeConfigTest {

  @Test
  void routeTableHasOneVirtualHostPerHostname() {
    RouteConfiguration rc = EdgeConfig.routeTable(Map.of(
        "tableau.edge.local", "tableau",
        "sales.edge.local", "sales"));

    assertThat(rc.getName()).isEqualTo(EdgeConfig.ROUTE_CONFIG_NAME);
    assertThat(rc.getVirtualHostsList()).hasSize(2);
    assertThat(rc.getVirtualHosts(1).getDomains(0)).isEqualTo("tableau.edge.local");
    assertThat(rc.getVirtualHosts(1).getRoutes(0).getRoute().getCluster()).isEqualTo("tableau");
  }

  @Test
  void clusterParsesHostAndPort() {
    var c = EdgeConfig.cluster("sales", "sales:8080");
    var addr = c.getLoadAssignment().getEndpoints(0).getLbEndpoints(0)
        .getEndpoint().getAddress().getSocketAddress();
    assertThat(addr.getAddress()).isEqualTo("sales");
    assertThat(addr.getPortValue()).isEqualTo(8080);
  }

  @Test
  void backendWithoutFailoverHasOnePriorityAndNoHealthCheck() {
    var c = EdgeConfig.cluster("sales", "sales:8080", null);
    assertThat(c.getLoadAssignment().getEndpointsList()).hasSize(1);
    assertThat(c.getHealthChecksList()).isEmpty();
  }

  @Test
  void failoverSiteIsPriorityOneBehindAnActiveHealthCheck() {
    var o = new BackendOptions.Options(null, null, "/healthz", "10.1.2.3:8080");
    var c = EdgeConfig.cluster("sales", "sales:8080", o);

    var local = c.getLoadAssignment().getEndpoints(0);
    var remote = c.getLoadAssignment().getEndpoints(1);
    assertThat(local.getPriority()).isZero();
    assertThat(remote.getPriority()).isEqualTo(1);
    assertThat(remote.getLocality().getZone()).isEqualTo("failover");
    var addr = remote.getLbEndpoints(0).getEndpoint().getAddress().getSocketAddress();
    assertThat(addr.getAddress()).isEqualTo("10.1.2.3");
    assertThat(addr.getPortValue()).isEqualTo(8080);

    assertThat(c.getHealthChecksList()).hasSize(1);
    var hc = c.getHealthChecks(0);
    assertThat(hc.getHttpHealthCheck().getPath()).isEqualTo("/healthz");
    assertThat(hc.getUnhealthyThreshold().getValue()).isEqualTo(2);
  }

  @Test
  void failoverHealthCheckDefaultsToRootPath() {
    var o = new BackendOptions.Options(null, null, null, "other:8080");
    var c = EdgeConfig.cluster("sales", "sales:8080", o);
    assertThat(c.getHealthChecks(0).getHttpHealthCheck().getPath()).isEqualTo("/");
  }

  @Test
  void snapshotAppliesFailoverFromBackendOptions() {
    var options = new BackendOptions(Map.of("sales",
        new BackendOptions.Options(null, null, null, "other:8080")));
    var snap = EdgeConfig.snapshot(Map.of("sales", "sales:8080", "tableau", "tableau:8080"),
        Map.of("a.local", "sales"), Map.of(), options, java.util.Optional.empty(), "1");
    assertThat(snap.clusters().resources().get("sales").getLoadAssignment().getEndpointsCount())
        .isEqualTo(2);
    assertThat(snap.clusters().resources().get("tableau").getLoadAssignment().getEndpointsCount())
        .isEqualTo(1);
  }

  @Test
  void routeToABackendWithFailoverRetriesOnTheOtherPriority() {
    var options = new BackendOptions(Map.of("sales",
        new BackendOptions.Options(null, null, null, "other:8080")));
    var rc = EdgeConfig.routeTable(Map.of("a.local", "sales", "b.local", "tableau"), Map.of(), options);
    var sales = rc.getVirtualHosts(0).getRoutes(0).getRoute();
    assertThat(sales.getRetryPolicy().getRetryOn()).contains("connect-failure");
    assertThat(sales.getRetryPolicy().getRetryPriority().getName())
        .isEqualTo("envoy.retry_priorities.previous_priorities");
    assertThat(rc.getVirtualHosts(1).getRoutes(0).getRoute().hasRetryPolicy()).isFalse();
  }

  @Test
  void listenerWritesJsonAccessLog() throws Exception {
    var hcm = EdgeConfig.listener().getFilterChains(0).getFilters(0).getTypedConfig()
        .unpack(io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3
            .HttpConnectionManager.class);
    var log = hcm.getAccessLog(0).getTypedConfig()
        .unpack(io.envoyproxy.envoy.extensions.access_loggers.stream.v3.StdoutAccessLog.class);
    assertThat(log.getLogFormat().getJsonFormat().getFieldsMap())
        .containsKeys("status", "flags", "backend", "duration_ms", "authority");
  }

  @Test
  void certificateAddsAnHttpsListenerThatFetchesItOverSds() throws Exception {
    var cert = new EdgeConfig.TlsCert("CERT PEM", "KEY PEM");
    var snap = EdgeConfig.snapshot(Map.of("sales", "sales:8080"), Map.of("a.local", "sales"),
        java.util.Optional.of(cert), "1");
    assertThat(snap.listeners().resources()).containsKeys(
        EdgeConfig.LISTENER_NAME, EdgeConfig.TLS_LISTENER_NAME);
    assertThat(snap.secrets().resources()).containsOnlyKeys(EdgeConfig.CERT_SECRET_NAME);

    var tls = snap.listeners().resources().get(EdgeConfig.TLS_LISTENER_NAME)
        .getFilterChains(0).getTransportSocket().getTypedConfig()
        .unpack(io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.DownstreamTlsContext.class);
    var sds = tls.getCommonTlsContext().getTlsCertificateSdsSecretConfigs(0);
    assertThat(sds.getName()).isEqualTo(EdgeConfig.CERT_SECRET_NAME);
    assertThat(sds.getSdsConfig().hasAds()).isTrue();
    assertThat(cert.toString()).doesNotContain("KEY PEM");
  }

  @Test
  void withoutCertificateThereIsOnlyHttp() {
    var snap = EdgeConfig.snapshot(Map.of("sales", "sales:8080"), Map.of("a.local", "sales"), "1");
    assertThat(snap.listeners().resources()).containsOnlyKeys(EdgeConfig.LISTENER_NAME);
    assertThat(snap.secrets().resources()).isEmpty();
  }

  @Test
  void streamingBackendGetsNoRouteTimeoutButAnIdleTimeout() {
    var options = new BackendOptions(Map.of("llm", new BackendOptions.Options(
        java.time.Duration.ZERO, java.time.Duration.ofSeconds(30), null, null)));
    var rc = EdgeConfig.routeTable(Map.of("llm.local", "llm", "a.local", "sales"), Map.of(), options);
    var llm = rc.getVirtualHostsList().stream().filter(v -> v.getName().equals("llm.local"))
        .findFirst().orElseThrow().getRoutes(0).getRoute();
    assertThat(llm.hasTimeout()).isTrue();
    assertThat(llm.getTimeout().getSeconds()).isZero();
    assertThat(llm.getIdleTimeout().getSeconds()).isEqualTo(30);
    var sales = rc.getVirtualHostsList().stream().filter(v -> v.getName().equals("a.local"))
        .findFirst().orElseThrow().getRoutes(0).getRoute();
    assertThat(sales.hasTimeout()).isFalse();   // Envoy's 15 s default
  }
}
