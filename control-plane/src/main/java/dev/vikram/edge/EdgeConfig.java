package dev.vikram.edge;

import com.google.protobuf.Any;
import com.google.protobuf.Duration;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import io.envoyproxy.envoy.config.accesslog.v3.AccessLog;
import io.envoyproxy.controlplane.cache.v3.Snapshot;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.core.v3.Address;
import io.envoyproxy.envoy.config.core.v3.AggregatedConfigSource;
import io.envoyproxy.envoy.config.core.v3.ApiVersion;
import io.envoyproxy.envoy.config.core.v3.ConfigSource;
import io.envoyproxy.envoy.config.core.v3.DataSource;
import io.envoyproxy.envoy.config.core.v3.SocketAddress;
import io.envoyproxy.envoy.config.core.v3.SubstitutionFormatString;
import io.envoyproxy.envoy.config.core.v3.TransportSocket;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.endpoint.v3.Endpoint;
import io.envoyproxy.envoy.config.endpoint.v3.LbEndpoint;
import io.envoyproxy.envoy.config.endpoint.v3.LocalityLbEndpoints;
import io.envoyproxy.envoy.config.listener.v3.Filter;
import io.envoyproxy.envoy.config.listener.v3.FilterChain;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.config.route.v3.Route;
import io.envoyproxy.envoy.config.route.v3.RouteAction;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.envoyproxy.envoy.config.route.v3.RouteMatch;
import io.envoyproxy.envoy.config.route.v3.VirtualHost;
import io.envoyproxy.envoy.extensions.access_loggers.stream.v3.StdoutAccessLog;
import io.envoyproxy.envoy.extensions.filters.http.router.v3.Router;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpFilter;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.Rds;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.CommonTlsContext;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.DownstreamTlsContext;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.Secret;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.SdsSecretConfig;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.TlsCertificate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Turns a simple "hostname -> backend" route table into Envoy xDS resources:
 * an HTTP listener on :10000, an HTTPS listener on :10443 when a certificate is configured
 * (delivered over SDS), one route configuration shared by both, and one cluster per backend.
 */
public final class EdgeConfig {

  public static final String LISTENER_NAME = "edge_listener";
  public static final String ROUTE_CONFIG_NAME = "edge_routes";
  public static final int LISTENER_PORT = 10000;
  public static final String TLS_LISTENER_NAME = "edge_tls_listener";
  public static final int TLS_LISTENER_PORT = 10443;
  public static final String CERT_SECRET_NAME = "edge_cert";

  /** A PEM certificate chain and private key for TLS termination. */
  public record TlsCert(String certificateChainPem, String privateKeyPem) {
    @Override
    public String toString() {
      return "TlsCert[redacted]";   // never log the private key
    }
  }

  private EdgeConfig() {}

  /** Builds a complete, versioned snapshot for one group of Envoys, without TLS. */
  public static Snapshot snapshot(Map<String, String> backends, Map<String, String> routes, String version) {
    return snapshot(backends, routes, Optional.empty(), version);
  }

  /** Builds a complete, versioned snapshot; with a certificate it also serves HTTPS on :10443. */
  public static Snapshot snapshot(Map<String, String> backends, Map<String, String> routes,
      Optional<TlsCert> tls, String version) {
    List<Cluster> clusters = new TreeMap<>(backends).entrySet().stream()
        .map(e -> cluster(e.getKey(), e.getValue()))
        .toList();
    List<Listener> listeners = new ArrayList<>(List.of(listener()));
    tls.ifPresent(c -> listeners.add(tlsListener()));
    return Snapshot.create(
        clusters,
        List.of(),                      // endpoints are inline in each cluster (STRICT_DNS)
        listeners,
        List.of(routeTable(routes)),
        tls.map(c -> List.of(certSecret(c))).orElse(List.of()),   // SDS
        version);
  }

  /**
   * HTTPS listener. Its certificate is not inline: Envoy fetches the secret "edge_cert" over SDS
   * on the same ADS stream, so a rotated certificate only needs a new snapshot.
   */
  static Listener tlsListener() {
    DownstreamTlsContext tls = DownstreamTlsContext.newBuilder()
        .setCommonTlsContext(CommonTlsContext.newBuilder()
            .addTlsCertificateSdsSecretConfigs(SdsSecretConfig.newBuilder()
                .setName(CERT_SECRET_NAME)
                .setSdsConfig(ConfigSource.newBuilder()
                    .setAds(AggregatedConfigSource.getDefaultInstance())
                    .setResourceApiVersion(ApiVersion.V3))))
        .build();
    return Listener.newBuilder()
        .setName(TLS_LISTENER_NAME)
        .setAddress(address("0.0.0.0", TLS_LISTENER_PORT))
        .addFilterChains(FilterChain.newBuilder()
            .addFilters(Filter.newBuilder()
                .setName("envoy.filters.network.http_connection_manager")
                .setTypedConfig(Any.pack(httpConnectionManager())))
            .setTransportSocket(TransportSocket.newBuilder()
                .setName("envoy.transport_sockets.tls")
                .setTypedConfig(Any.pack(tls))))
        .build();
  }

  static Secret certSecret(TlsCert cert) {
    return Secret.newBuilder()
        .setName(CERT_SECRET_NAME)
        .setTlsCertificate(TlsCertificate.newBuilder()
            .setCertificateChain(DataSource.newBuilder().setInlineString(cert.certificateChainPem()))
            .setPrivateKey(DataSource.newBuilder().setInlineString(cert.privateKeyPem())))
        .build();
  }

  /** One virtual host per hostname, each sending all paths to its backend cluster. */
  static RouteConfiguration routeTable(Map<String, String> routes) {
    RouteConfiguration.Builder rc = RouteConfiguration.newBuilder().setName(ROUTE_CONFIG_NAME);
    new TreeMap<>(routes).forEach((host, backend) -> rc.addVirtualHosts(
        VirtualHost.newBuilder()
            .setName(host)
            .addDomains(host)
            .addRoutes(Route.newBuilder()
                .setMatch(RouteMatch.newBuilder().setPrefix("/"))
                .setRoute(RouteAction.newBuilder().setCluster(backend)))));
    return rc.build();
  }

  /** A cluster that resolves a Kubernetes Service name with DNS. */
  static Cluster cluster(String name, String hostPort) {
    String[] hp = hostPort.split(":");
    return Cluster.newBuilder()
        .setName(name)
        .setType(Cluster.DiscoveryType.STRICT_DNS)
        .setConnectTimeout(Duration.newBuilder().setSeconds(1))
        .setLoadAssignment(ClusterLoadAssignment.newBuilder()
            .setClusterName(name)
            .addEndpoints(LocalityLbEndpoints.newBuilder()
                .addLbEndpoints(LbEndpoint.newBuilder()
                    .setEndpoint(Endpoint.newBuilder()
                        .setAddress(address(hp[0], Integer.parseInt(hp[1])))))))
        .build();
  }

  /** HTTP listener whose routes are fetched separately over RDS on the same ADS stream. */
  static Listener listener() {
    return Listener.newBuilder()
        .setName(LISTENER_NAME)
        .setAddress(address("0.0.0.0", LISTENER_PORT))
        .addFilterChains(FilterChain.newBuilder()
            .addFilters(Filter.newBuilder()
                .setName("envoy.filters.network.http_connection_manager")
                .setTypedConfig(Any.pack(httpConnectionManager()))))
        .build();
  }

  /**
   * HTTP handling shared by both listeners: same route table over RDS, same access log, and the
   * same stat prefix "edge", so dashboards and the Prometheus probe count HTTP and HTTPS together.
   */
  static HttpConnectionManager httpConnectionManager() {
    return HttpConnectionManager.newBuilder()
        .setStatPrefix("edge")
        // match "sales.edge.local:10443" to the virtual host "sales.edge.local"
        .setStripAnyHostPort(true)
        .setRds(Rds.newBuilder()
            .setRouteConfigName(ROUTE_CONFIG_NAME)
            .setConfigSource(ConfigSource.newBuilder()
                .setAds(AggregatedConfigSource.getDefaultInstance())
                .setResourceApiVersion(ApiVersion.V3)))
        .addHttpFilters(HttpFilter.newBuilder()
            .setName("envoy.filters.http.router")
            .setTypedConfig(Any.pack(Router.getDefaultInstance())))
        .addAccessLog(accessLog())
        .build();
  }

  /** Fields of the JSON access log line, as Envoy format operators. */
  static final Map<String, String> ACCESS_LOG_FIELDS = accessLogFields();

  private static Map<String, String> accessLogFields() {
    Map<String, String> f = new LinkedHashMap<>();
    f.put("start_time", "%START_TIME%");
    f.put("node", "%ENVIRONMENT(HOSTNAME)%");
    f.put("method", "%REQ(:METHOD)%");
    f.put("authority", "%REQ(:AUTHORITY)%");
    f.put("path", "%REQ(X-ENVOY-ORIGINAL-PATH?:PATH)%");
    f.put("status", "%RESPONSE_CODE%");
    f.put("flags", "%RESPONSE_FLAGS%");        // e.g. UF = upstream connect failure, NR = no route
    f.put("backend", "%UPSTREAM_CLUSTER%");
    f.put("duration_ms", "%DURATION%");
    f.put("upstream_ms", "%RESP(X-ENVOY-UPSTREAM-SERVICE-TIME)%");
    f.put("bytes_out", "%BYTES_SENT%");
    f.put("request_id", "%REQ(X-REQUEST-ID)%");
    return Map.copyOf(f);
  }

  /** One JSON line per request on Envoy's stdout; Phase 3 ships these to Kafka. */
  static AccessLog accessLog() {
    Struct.Builder json = Struct.newBuilder();
    new TreeMap<>(ACCESS_LOG_FIELDS).forEach(
        (k, v) -> json.putFields(k, Value.newBuilder().setStringValue(v).build()));
    StdoutAccessLog stdout = StdoutAccessLog.newBuilder()
        .setLogFormat(SubstitutionFormatString.newBuilder().setJsonFormat(json))
        .build();
    return AccessLog.newBuilder()
        .setName("envoy.access_loggers.stdout")
        .setTypedConfig(Any.pack(stdout))
        .build();
  }

  static Address address(String host, int port) {
    return Address.newBuilder()
        .setSocketAddress(SocketAddress.newBuilder().setAddress(host).setPortValue(port))
        .build();
  }
}
