package dev.vikram.edge;

import io.envoyproxy.controlplane.cache.Resources;
import io.envoyproxy.controlplane.server.DiscoveryServerCallbacks;
import io.envoyproxy.envoy.service.discovery.v3.DeltaDiscoveryRequest;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryRequest;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Watches the xDS conversation to learn which route version each Envoy has actually applied.
 *
 * <p>In xDS, Envoy answers every response with a new request. If {@code error_detail} is empty it
 * is an ACK and {@code version_info} is the version it applied. If {@code error_detail} is set it
 * is a NACK: Envoy rejected the config whose response carried that {@code response_nonce}.
 */
@Component
public class ConfigAckTracker implements DiscoveryServerCallbacks {

  private static final Logger log = LoggerFactory.getLogger(ConfigAckTracker.class);
  private static final String ROUTES = Resources.V3.ROUTE_TYPE_URL;

  public enum AckStatus { ACKED, NACKED, TIMEOUT, NO_NODES }

  /** What one Envoy connection has told us. Fields are written on gRPC threads, read by rollouts. */
  private static final class Stream {
    volatile String node = "?";
    volatile String group;
    volatile long ackedRoutes = -1;
    volatile long nackedRoutes = -1;
    volatile String nackReason;
    /** response nonce to the route version sent with it, so a NACK can name the rejected version */
    final Map<String, Long> sentRoutes = new ConcurrentHashMap<>();
  }

  private final Map<Long, Stream> streams = new ConcurrentHashMap<>();
  private final ReentrantLock lock = new ReentrantLock();
  private final Condition changed = lock.newCondition();

  @Override
  public void onStreamOpen(long streamId, String typeUrl) {
    streams.put(streamId, new Stream());
  }

  @Override
  public void onStreamClose(long streamId, String typeUrl) {
    remove(streamId);
  }

  @Override
  public void onStreamCloseWithError(long streamId, String typeUrl, Throwable error) {
    remove(streamId);
  }

  @Override
  public void onV3StreamResponse(long streamId, DiscoveryRequest request, DiscoveryResponse response) {
    Stream s = streams.get(streamId);
    if (s != null && ROUTES.equals(response.getTypeUrl())) {
      s.sentRoutes.put(response.getNonce(), parse(response.getVersionInfo()));
    }
  }

  @Override
  public void onV3StreamRequest(long streamId, DiscoveryRequest request) {
    Stream s = streams.computeIfAbsent(streamId, id -> new Stream());
    if (request.hasNode()) {
      s.node = request.getNode().getId();
      s.group = request.getNode().getCluster();
    }
    if (!ROUTES.equals(request.getTypeUrl()) || request.getResponseNonce().isEmpty()) {
      return;   // first subscription for this type, not a reply to anything we sent
    }
    Long sent = s.sentRoutes.remove(request.getResponseNonce());
    if (request.hasErrorDetail()) {
      s.nackedRoutes = sent == null ? -1 : sent;
      s.nackReason = request.getErrorDetail().getMessage();
      log.warn("{} ({}) NACKed routes v{}: {}", s.node, s.group, sent, s.nackReason);
    } else {
      s.ackedRoutes = parse(request.getVersionInfo());
    }
    signal();
  }

  @Override
  public void onV3StreamDeltaRequest(long streamId, DeltaDiscoveryRequest request) {
    // This control plane serves state-of-the-world ADS only.
  }

  /**
   * Blocks until every Envoy connected in {@code group} has ACKed route version {@code version}
   * or later, any of them NACKs it, or {@code timeout} passes.
   */
  public AckStatus await(String group, long version, Duration timeout) throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    lock.lock();
    try {
      while (true) {
        AckStatus now = status(group, version);
        if (now == AckStatus.ACKED || now == AckStatus.NACKED) {
          return now;
        }
        long left = deadline - System.nanoTime();
        if (left <= 0) {
          return now;   // TIMEOUT, or NO_NODES if no Envoy in the group is connected
        }
        changed.await(left, TimeUnit.NANOSECONDS);
      }
    } finally {
      lock.unlock();
    }
  }

  /** Number of Envoys currently connected in a group. */
  public long connected(String group) {
    return streams.values().stream().filter(s -> group.equals(s.group)).count();
  }

  private AckStatus status(String group, long version) {
    boolean any = false;
    boolean all = true;
    for (Stream s : streams.values()) {
      if (!group.equals(s.group)) {
        continue;
      }
      any = true;
      if (s.nackedRoutes == version) {
        return AckStatus.NACKED;
      }
      all &= s.ackedRoutes >= version;
    }
    if (!any) {
      return AckStatus.NO_NODES;
    }
    return all ? AckStatus.ACKED : AckStatus.TIMEOUT;
  }

  private void remove(long streamId) {
    streams.remove(streamId);
    signal();
  }

  private void signal() {
    lock.lock();
    try {
      changed.signalAll();
    } finally {
      lock.unlock();
    }
  }

  private static long parse(String version) {
    try {
      return Long.parseLong(version);
    } catch (NumberFormatException e) {
      return -1;
    }
  }
}
