package dev.vikram.edge;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vikram.edge.ConfigAckTracker.AckStatus;
import io.envoyproxy.controlplane.cache.v3.Snapshot;
import io.envoyproxy.envoy.config.route.v3.Route;
import io.envoyproxy.envoy.config.route.v3.RouteAction;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.envoyproxy.envoy.config.route.v3.RouteMatch;
import io.envoyproxy.envoy.config.route.v3.VirtualHost;
import io.envoyproxy.envoy.type.matcher.v3.RegexMatcher;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;

/**
 * The real thing: our xDS server in this JVM, a real Envoy in a container, a real backend.
 * Proves that Envoy accepts our config, ACKs it, routes by hostname, and NACKs bad config.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EnvoyIT {

  private static final String ENVOY_IMAGE = "envoyproxy/envoy:v1.31-latest";

  private static ConfigAckTracker acks;
  private static XdsServer xds;
  private static GenericContainer<?> backend;
  private static GenericContainer<?> envoy;
  private static Map<String, String> backends;

  @BeforeAll
  static void start() throws Exception {
    int xdsPort;
    try (ServerSocket s = new ServerSocket(0)) {
      xdsPort = s.getLocalPort();
    }
    acks = new ConfigAckTracker();
    xds = new XdsServer(new EdgeProperties(xdsPort, "http://unused", Map.of(), Map.of(),
        1, 0.05, 5000, "synthetic", "http://unused", 30, null, null), acks);
    xds.start();
    Testcontainers.exposeHostPorts(xdsPort);   // reachable from containers as host.testcontainers.internal

    backend = new GenericContainer<>("hashicorp/http-echo")
        .withCommand("-text=hello from sales", "-listen=:8080")
        .withExposedPorts(8080);
    backend.start();
    String backendIp = backend.getContainerInfo().getNetworkSettings().getNetworks().get("bridge").getIpAddress();
    backends = Map.of("sales", backendIp + ":8080");

    // Config is in the cache before Envoy connects, so its first fetch returns version 1.
    xds.push("canary", EdgeConfig.snapshot(backends, Map.of("sales.it.local", "sales"), "1"));

    envoy = new GenericContainer<>(ENVOY_IMAGE)
        .withCopyToContainer(Transferable.of(bootstrap(xdsPort)), "/etc/envoy/envoy.yaml")
        .withCommand("-c", "/etc/envoy/envoy.yaml", "--service-cluster", "canary",
            "--service-node", "envoy-it")
        .withExposedPorts(10000, 9901)
        .waitingFor(Wait.forHttp("/ready").forPort(9901).withStartupTimeout(Duration.ofSeconds(60)));
    envoy.start();
  }

  @AfterAll
  static void stop() {
    if (envoy != null) envoy.stop();
    if (backend != null) backend.stop();
    if (xds != null) xds.stop();
  }

  @Test
  @Order(1)
  void envoyAcksTheConfigAndRoutesByHostname() throws Exception {
    assertThat(acks.await("canary", 1, Duration.ofSeconds(20))).isEqualTo(AckStatus.ACKED);
    assertThat(get("sales.it.local")).startsWith("HTTP/1.1 200").contains("hello from sales");
    assertThat(get("unknown.it.local")).startsWith("HTTP/1.1 404");
  }

  @Test
  @Order(2)
  void aNewRouteIsLiveOnceItIsAcked() throws Exception {
    xds.push("canary", EdgeConfig.snapshot(backends,
        Map.of("sales.it.local", "sales", "crm.it.local", "sales"), "2"));
    assertThat(acks.await("canary", 2, Duration.ofSeconds(10))).isEqualTo(AckStatus.ACKED);
    assertThat(get("crm.it.local")).startsWith("HTTP/1.1 200").contains("hello from sales");
  }

  @Test
  @Order(3)
  void envoyNacksAnInvalidRouteAndKeepsServingTheLastGoodOne() throws Exception {
    RouteConfiguration bad = RouteConfiguration.newBuilder()
        .setName(EdgeConfig.ROUTE_CONFIG_NAME)
        .addVirtualHosts(VirtualHost.newBuilder().setName("bad").addDomains("*")
            .addRoutes(Route.newBuilder()
                .setMatch(RouteMatch.newBuilder()
                    .setSafeRegex(RegexMatcher.newBuilder().setRegex("(unclosed")))
                .setRoute(RouteAction.newBuilder().setCluster("sales"))))
        .build();
    xds.push("canary", Snapshot.create(
        List.of(EdgeConfig.cluster("sales", backends.get("sales"))), List.of(),
        List.of(EdgeConfig.listener()), List.of(bad), List.of(), "3"));

    assertThat(acks.await("canary", 3, Duration.ofSeconds(10))).isEqualTo(AckStatus.NACKED);
    assertThat(get("crm.it.local")).startsWith("HTTP/1.1 200");   // still on version 2
  }

  /** Plain HTTP/1.1 over a socket, so the Host header can be anything. */
  private static String get(String host) throws Exception {
    try (Socket s = new Socket(envoy.getHost(), envoy.getMappedPort(10000))) {
      s.setSoTimeout(5000);
      OutputStream out = s.getOutputStream();
      out.write(("GET / HTTP/1.1\r\nHost: " + host + "\r\nConnection: close\r\n\r\n")
          .getBytes(StandardCharsets.US_ASCII));
      out.flush();
      StringBuilder response = new StringBuilder();
      try (BufferedReader in = new BufferedReader(
          new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))) {
        for (String line; (line = in.readLine()) != null; ) {
          response.append(line).append('\n');
        }
      }
      return response.toString();
    }
  }

  private static String bootstrap(int xdsPort) {
    return """
        dynamic_resources:
          ads_config:
            api_type: GRPC
            transport_api_version: V3
            grpc_services: [{envoy_grpc: {cluster_name: xds_cluster}}]
          cds_config: {ads: {}, resource_api_version: V3}
          lds_config: {ads: {}, resource_api_version: V3}
        static_resources:
          clusters:
          - name: xds_cluster
            type: STRICT_DNS
            connect_timeout: 1s
            typed_extension_protocol_options:
              envoy.extensions.upstreams.http.v3.HttpProtocolOptions:
                "@type": type.googleapis.com/envoy.extensions.upstreams.http.v3.HttpProtocolOptions
                explicit_http_config: {http2_protocol_options: {}}
            load_assignment:
              cluster_name: xds_cluster
              endpoints:
              - lb_endpoints:
                - endpoint:
                    address:
                      socket_address: {address: host.testcontainers.internal, port_value: %d}
        admin:
          address: {socket_address: {address: 0.0.0.0, port_value: 9901}}
        """.formatted(xdsPort);
  }
}
