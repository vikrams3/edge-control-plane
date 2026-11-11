package dev.vikram.edge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Measures the canary with real user traffic instead of test requests: watches the canary for a
 * window, then asks Prometheus how many of the requests it served in that window were 5xx.
 *
 * <p>Needs the canary to carry live traffic (on kind, the "edge" Service spreads users across
 * canary and stable Envoys). No traffic means no evidence, which counts as a failure.
 */
@Component
@ConditionalOnProperty(name = "edge.probe", havingValue = "prometheus")
public class PrometheusHealthProbe implements HealthProbe {

  private static final Logger log = LoggerFactory.getLogger(PrometheusHealthProbe.class);
  /** Envoy is scraped every 5 s; wait two more scrapes so the window's last samples exist. */
  private static final Duration SCRAPE_LAG = Duration.ofSeconds(10);
  private static final String CANARY = "group=\"canary\",envoy_http_conn_manager_prefix=\"edge\"";

  private final String prometheus;
  private final int windowSeconds;
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
  private final ObjectMapper json = new ObjectMapper();

  public PrometheusHealthProbe(EdgeProperties props) {
    this.prometheus = props.prometheusUrl();
    this.windowSeconds = props.probeWindowSeconds();
  }

  @Override
  public ProbeResult probe(Set<String> hosts) {
    try {
      Thread.sleep(Duration.ofSeconds(windowSeconds).plus(SCRAPE_LAG));
      String w = "[" + windowSeconds + "s]";
      long total = Math.round(query(
          "sum(increase(envoy_http_downstream_rq_completed{" + CANARY + "}" + w + "))"));
      long failed = Math.round(query(
          "sum(increase(envoy_http_downstream_rq_xx{" + CANARY + ",envoy_response_code_class=\"5\"}" + w + "))"));
      log.info("Prometheus: canary served {} requests in the last {}s, {} were 5xx", total, windowSeconds, failed);
      return new ProbeResult((int) total, (int) Math.min(failed, total));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return new ProbeResult(0, 0);
    } catch (Exception e) {
      log.warn("Prometheus query failed, treating the canary as unhealthy", e);
      return new ProbeResult(0, 0);   // fail closed
    }
  }

  /** Runs an instant query and returns its single value, or 0 if the result is empty. */
  double query(String promql) throws Exception {
    URI uri = URI.create(prometheus + "/api/v1/query?query="
        + URLEncoder.encode(promql, StandardCharsets.UTF_8));
    String body = http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).build(),
        BodyHandlers.ofString()).body();
    JsonNode result = json.readTree(body).path("data").path("result");
    return result.isEmpty() ? 0 : result.get(0).path("value").get(1).asDouble();
  }
}
