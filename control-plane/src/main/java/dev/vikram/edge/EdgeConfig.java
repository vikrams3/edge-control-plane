package dev.vikram.edge;

import com.google.protobuf.Any;
import com.google.protobuf.Duration;
import io.envoyproxy.controlplane.cache.v3.Snapshot;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.core.v3.Address;
import io.envoyproxy.envoy.config.core.v3.AggregatedConfigSource;
import io.envoyproxy.envoy.config.core.v3.ApiVersion;
import io.envoyproxy.envoy.config.core.v3.ConfigSource;
import io.envoyproxy.envoy.config.core.v3.SocketAddress;
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
import io.envoyproxy.envoy.extensions.filters.http.router.v3.Router;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpFilter;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.Rds;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Turns a simple "hostname -> backend" route table into Envoy xDS resources:
 * one listener on :10000, one route configuration, and one cluster per backend.
 */
public final class EdgeConfig {

  public static final String LISTENER_NAME = "edge_listener";
  public static final String ROUTE_CONFIG_NAME = "edge_routes";
  public static final int LISTENER_PORT = 10000;

  private EdgeConfig() {}

  /** Builds a complete, versioned snapshot for one group of Envoys. */
  public static Snapshot snapshot(Map<String, String> backends, Map<String, String> routes, String version) {
    List<Cluster> clusters = new TreeMap<>(backends).entrySet().stream()
        .map(e -> cluster(e.getKey(), e.getValue()))
        .toList();
    return Snapshot.create(
        clusters,
        List.of(),                      // endpoints are inline in each cluster (STRICT_DNS)
        List.of(listener()),
        List.of(routeTable(routes)),
        List.of(),                      // secrets (TLS) come in Phase 2
        version);
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
    HttpConnectionManager hcm = HttpConnectionManager.newBuilder()
        .setStatPrefix("edge")
        .setRds(Rds.newBuilder()
            .setRouteConfigName(ROUTE_CONFIG_NAME)
            .setConfigSource(ConfigSource.newBuilder()
                .setAds(AggregatedConfigSource.getDefaultInstance())
                .setResourceApiVersion(ApiVersion.V3)))
        .addHttpFilters(HttpFilter.newBuilder()
            .setName("envoy.filters.http.router")
            .setTypedConfig(Any.pack(Router.getDefaultInstance())))
        .build();

    return Listener.newBuilder()
        .setName(LISTENER_NAME)
        .setAddress(address("0.0.0.0", LISTENER_PORT))
        .addFilterChains(FilterChain.newBuilder()
            .addFilters(Filter.newBuilder()
                .setName("envoy.filters.network.http_connection_manager")
                .setTypedConfig(Any.pack(hcm))))
        .build();
  }

  static Address address(String host, int port) {
    return Address.newBuilder()
        .setSocketAddress(SocketAddress.newBuilder().setAddress(host).setPortValue(port))
        .build();
  }
}
