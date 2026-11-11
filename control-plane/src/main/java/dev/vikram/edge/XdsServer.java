package dev.vikram.edge;

import io.envoyproxy.controlplane.cache.v3.SimpleCache;
import io.envoyproxy.controlplane.cache.v3.Snapshot;
import io.envoyproxy.controlplane.server.V3DiscoveryServer;
import io.grpc.Server;
import io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.NettyServerBuilder;
import io.netty.handler.ssl.ClientAuth;
import java.io.File;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * gRPC xDS server. Envoys connect here over ADS and receive the snapshot for their group.
 * The group is the Envoy's node.cluster ("canary" or "stable"), set with --service-cluster.
 */
@Component
public class XdsServer {

  private static final Logger log = LoggerFactory.getLogger(XdsServer.class);

  private final SimpleCache<String> cache = new SimpleCache<>(node -> node.getCluster());
  private final ConfigAckTracker acks;
  private final int port;
  private final XdsTlsProperties tls;
  private Server server;

  public XdsServer(EdgeProperties props, ConfigAckTracker acks) {
    this(props, acks, new XdsTlsProperties(null, null, null));
  }

  @org.springframework.beans.factory.annotation.Autowired
  public XdsServer(EdgeProperties props, ConfigAckTracker acks, XdsTlsProperties tls) {
    this.port = props.xdsPort();
    this.acks = acks;
    this.tls = tls;
  }

  @PostConstruct
  void start() throws IOException {
    V3DiscoveryServer xds = new V3DiscoveryServer(acks, cache);
    NettyServerBuilder builder = NettyServerBuilder.forPort(port)
        .addService(xds.getAggregatedDiscoveryServiceImpl());
    if (tls.enabled()) {
      // mTLS: Envoys must present a certificate signed by our CA, or the handshake fails
      builder.sslContext(GrpcSslContexts.forServer(new File(tls.certFile()), new File(tls.keyFile()))
          .trustManager(new File(tls.caFile()))
          .clientAuth(ClientAuth.REQUIRE)
          .build());
    }
    server = builder.build().start();
    log.info("xDS server listening on :{} ({})", server.getPort(),
        tls.enabled() ? "mTLS, client certificates required" : "plaintext");
  }

  /** Replaces the config for one group. Envoys in that group pick it up within milliseconds. */
  public void push(String group, Snapshot snapshot) {
    cache.setSnapshot(group, snapshot);
  }

  /** Waits until every connected Envoy in the group has ACKed this version (or NACKed it). */
  public ConfigAckTracker.AckStatus awaitAck(String group, long version, Duration timeout)
      throws InterruptedException {
    return acks.await(group, version, timeout);
  }

  /** Number of Envoys currently connected in a group. */
  public long connected(String group) {
    return acks.connected(group);
  }

  @PreDestroy
  void stop() {
    if (server != null) {
      server.shutdown();
    }
  }
}
