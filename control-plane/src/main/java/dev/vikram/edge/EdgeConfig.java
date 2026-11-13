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
import io.envoyproxy.envoy.config.core.v3.HealthCheck;
import io.envoyproxy.envoy.config.core.v3.Locality;
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
import io.envoyproxy.envoy.config.route.v3.RetryPolicy;
import io.envoyproxy.envoy.config.route.v3.RouteMatch;
import io.envoyproxy.envoy.config.route.v3.VirtualHost;
import io.envoyproxy.envoy.config.route.v3.WeightedCluster;
import com.google.protobuf.UInt32Value;
import io.envoyproxy.envoy.extensions.access_loggers.stream.v3.StdoutAccessLog;
import io.envoyproxy.envoy.extensions.filters.http.router.v3.Router;
import io.envoyproxy.envoy.extensions.retry.priority.previous_priorities.v3.PreviousPrioritiesConfig;
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
    return snapshot(backends, routes, Map.of(), tls, version);
  }

  /**
   * Builds a complete, versioned snapshot. {@code pools} maps a pool name to its members'
   * weights; a hostname routed to a pool name splits its traffic by those weights.
   */
  public static Snapshot snapshot(Map<String, String> backends, Map<String, String> routes,
      Map<String, Map<String, Integer>> pools, Optional<TlsCert> tls, String version) {
    return snapshot(backends, routes, pools, BackendOptions.none(), tls, version);
  }

  /** As above, with per-backend route options such as timeouts for streaming backends. */
  public static Snapshot snapshot(Map<String, String> backends, Map<String, String> routes,
      Map<String, Map<String, Integer>> pools, BackendOptions options, Optional<TlsCert> tls,
      String version) {
    List<Cluster> clusters = new TreeMap<>(backends).entrySet().stream()
        .map(e -> cluster(e.getKey(), e.getValue(), options.backendOptions().get(e.getKey())))
        .toList();
    List<Listener> listeners = new ArrayList<>(List.of(listener()));
    tls.ifPresent(c -> listeners.add(tlsListener()));
    return Snapshot.create(
        clusters,
        List.of(),                      // endpoints are inline in each cluster (STRICT_DNS)
        listeners,
        List.of(routeTable(routes, pools, options)),
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
    return routeTable(routes, Map.of());
  }

  /** As above; a hostname routed to a pool gets weighted_clusters across the pool's members. */
  static RouteConfiguration routeTable(Map<String, String> routes,
      Map<String, Map<String, Integer>> pools) {
    return routeTable(routes, pools, BackendOptions.none());
  }

  static RouteConfiguration routeTable(Map<String, String> routes,
      Map<String, Map<String, Integer>> pools, BackendOptions options) {
    RouteConfiguration.Builder rc = RouteConfiguration.newBuilder().setName(ROUTE_CONFIG_NAME);
    new TreeMap<>(routes).forEach((host, backend) -> {
      RouteAction.Builder action = RouteAction.newBuilder();
      Map<String, Integer> pool = pools.get(backend);
      if (pool == null) {
        action.setCluster(backend);
        BackendOptions.Options o = options.backendOptions().get(backend);
        if (o != null && o.timeout() != null) {
          action.setTimeout(proto(o.timeout()));          // 0 = no whole-response timeout
        }
        if (o != null && o.idleTimeout() != null) {
          action.setIdleTimeout(proto(o.idleTimeout()));  // but a silent stream is still cut
        }
        if (o != null && o.failover() != null && !o.failover().isBlank()) {
          action.setRetryPolicy(failoverRetry());
        }
      } else {
        WeightedCluster.Builder wc = WeightedCluster.newBuilder();
        new TreeMap<>(pool).forEach((member, weight) -> wc.addClusters(
            WeightedCluster.ClusterWeight.newBuilder()
                .setName(member).setWeight(UInt32Value.of(weight))));
        action.setWeightedClusters(wc);
      }
      rc.addVirtualHosts(VirtualHost.newBuilder()
          .setName(host)
          .addDomains(host)
          .addRoutes(Route.newBuilder()
              .setMatch(RouteMatch.newBuilder().setPrefix("/"))
              .setRoute(action)));
    });
    return rc.build();
  }

  /**
   * Covers the seconds before health checks notice the local site is down: a request that cannot
   * connect (or whose connection is reset) is retried, and the retry skips the priority that just
   * failed, so it goes to the failover site. "reset" can repeat a request the upstream had started,
   * which is acceptable for these read-only backends; a backend taking writes would drop it.
   */
  static RetryPolicy failoverRetry() {
    return RetryPolicy.newBuilder()
        .setRetryOn("connect-failure,refused-stream,reset")
        .setNumRetries(UInt32Value.of(2))
        .setRetryPriority(RetryPolicy.RetryPriority.newBuilder()
            .setName("envoy.retry_priorities.previous_priorities")
            .setTypedConfig(Any.pack(PreviousPrioritiesConfig.newBuilder()
                .setUpdateFrequency(1).build())))
        .build();
  }

  /** A cluster that resolves a Kubernetes Service name with DNS. */
  static Cluster cluster(String name, String hostPort) {
    return cluster(name, hostPort, null);
  }

  /**
   * One cluster per backend. With a failover site in its options, the cluster has two
   * priorities: 0 is the local endpoint, 1 the same backend at the other site. Envoy health-checks
   * both and sends traffic to priority 1 only while priority 0 is unhealthy, then moves it back on
   * its own when the local endpoint passes again, with no control plane involvement.
   */
  static Cluster cluster(String name, String hostPort, BackendOptions.Options options) {
    String failover = options == null ? null : options.failover();
    ClusterLoadAssignment.Builder endpoints = ClusterLoadAssignment.newBuilder()
        .setClusterName(name)
        .addEndpoints(localityEndpoints(hostPort, "local", 0));
    Cluster.Builder c = Cluster.newBuilder()
        .setName(name)
        .setType(Cluster.DiscoveryType.STRICT_DNS)
        .setConnectTimeout(Duration.newBuilder().setSeconds(1));
    if (failover != null && !failover.isBlank()) {
      endpoints.addEndpoints(localityEndpoints(failover, "failover", 1));
      String path = options.healthPath() == null ? "/" : options.healthPath();
      c.addHealthChecks(HealthCheck.newBuilder()
          .setTimeout(Duration.newBuilder().setSeconds(1))
          .setInterval(Duration.newBuilder().setSeconds(2))
          // checks keep running when the cluster is idle, so a failover is ready before traffic
          .setNoTrafficInterval(Duration.newBuilder().setSeconds(2))
          .setUnhealthyThreshold(UInt32Value.of(2))     // about 4 s to fail over
          .setHealthyThreshold(UInt32Value.of(3))       // about 6 s of passes before failing back
          .setHttpHealthCheck(HealthCheck.HttpHealthCheck.newBuilder().setPath(path)));
    }
    return c.setLoadAssignment(endpoints).build();
  }

  private static LocalityLbEndpoints localityEndpoints(String hostPort, String site, int priority) {
    String[] hp = hostPort.split(":");
    return LocalityLbEndpoints.newBuilder()
        .setLocality(Locality.newBuilder().setZone(site))
        .setPriority(priority)
        .addLbEndpoints(LbEndpoint.newBuilder()
            .setEndpoint(Endpoint.newBuilder()
                .setAddress(address(hp[0], Integer.parseInt(hp[1])))))
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
    f.put("upstream_host", "%UPSTREAM_HOST%"); // which site served it, when a backend fails over
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

  static Duration proto(java.time.Duration d) {
    return Duration.newBuilder().setSeconds(d.getSeconds()).setNanos(d.getNano()).build();
  }

  static Address address(String host, int port) {
    return Address.newBuilder()
        .setSocketAddress(SocketAddress.newBuilder().setAddress(host).setPortValue(port))
        .build();
  }
}
