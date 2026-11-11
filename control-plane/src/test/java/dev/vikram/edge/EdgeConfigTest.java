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
}
