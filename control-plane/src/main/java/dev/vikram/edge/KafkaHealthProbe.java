package dev.vikram.edge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Judges the canary on real traffic, from Envoy access logs streamed through Kafka.
 *
 * <p>Unlike {@link PrometheusHealthProbe}, which only sees the canary's total error rate, the
 * analytics service keys its stats by hostname, so this probe counts only the hostnames in the
 * rollout, and only requests served after the canary ACKed (the moment this probe starts).
 *
 * <p>A hostname with too little real traffic (a brand-new one has none) gets synthetic requests
 * instead, so a change is never promoted on another hostname's evidence.
 */
@Component
@ConditionalOnProperty(name = "edge.probe", havingValue = "kafka")
public class KafkaHealthProbe implements HealthProbe {

  private static final Logger log = LoggerFactory.getLogger(KafkaHealthProbe.class);
  /** Fluent Bit flushes every 1 s and the stream commits every 1 s; allow some slack. */
  private static final Duration PIPELINE_LAG = Duration.ofSeconds(5);
  /** Fewer real requests than this for a hostname, and it is tested synthetically instead. */
  static final int MIN_REQUESTS = 10;

  private final String analytics;
  private final int windowSeconds;
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
  private final ObjectMapper json = new ObjectMapper();
  private final HealthProbe fallback;

  @Autowired   // two constructors: tell Spring which one to use
  public KafkaHealthProbe(EdgeProperties props) {
    this(props, new SyntheticHealthProbe(props));
  }

  KafkaHealthProbe(EdgeProperties props, HealthProbe fallback) {
    this.analytics = props.analyticsUrl();
    this.windowSeconds = props.probeWindowSeconds();
    this.fallback = fallback;
  }

  @Override
  public ProbeResult probe(Set<String> hosts) {
    long since = System.currentTimeMillis();
    try {
      Thread.sleep(Duration.ofSeconds(windowSeconds).plus(PIPELINE_LAG));
      URI uri = URI.create(analytics + "/v1/stats?group=canary&by=host&since=" + since);
      JsonNode byHost = json.readTree(http.send(
          HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).build(),
          BodyHandlers.ofString()).body()).path("byHost");
      return combine(hosts, byHost);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return new ProbeResult(0, 0);
    } catch (Exception e) {
      log.warn("Analytics query failed, treating the canary as unhealthy", e);
      return new ProbeResult(0, 0);   // fail closed
    }
  }

  /** Real-traffic numbers for hostnames that have enough of it, synthetic for the rest. */
  ProbeResult combine(Set<String> hosts, JsonNode byHost) {
    long total = 0;
    long failed = 0;
    Set<String> quiet = new java.util.TreeSet<>();
    for (String host : hosts) {
      JsonNode s = byHost.path(host);
      long requests = s.path("requests").asLong(0);
      if (requests < MIN_REQUESTS) {
        quiet.add(host);
        continue;
      }
      total += requests;
      failed += s.path("errors5xx").asLong(0);
      log.info("Kafka: canary {} served {} requests in {}s, {} were 5xx, p99 {} ms", host,
          requests, windowSeconds, s.path("errors5xx").asLong(0), s.path("p99Ms").asLong(-1));
    }
    if (!quiet.isEmpty()) {
      ProbeResult synthetic = fallback.probe(quiet);
      log.info("Kafka: too little real traffic for {}; synthetic probe: {} of {} failed",
          quiet, synthetic.failed(), synthetic.total());
      total += synthetic.total();
      failed += synthetic.failed();
    }
    return new ProbeResult((int) total, (int) failed);
  }
}
