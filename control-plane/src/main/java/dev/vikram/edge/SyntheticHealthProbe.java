package dev.vikram.edge;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Sends test requests through the canary Envoy, in parallel on virtual threads,
 * and counts anything that is not a 2xx as a failure.
 *
 * <p>It judges the status line and headers only, then closes the response: a streaming backend
 * (LLM-style token stream) answers 200 at once but keeps the body open for tens of seconds.
 */
@Component
@ConditionalOnProperty(name = "edge.probe", havingValue = "synthetic", matchIfMissing = true)
public class SyntheticHealthProbe implements HealthProbe {

  private final URI canary;
  private final int requestsPerHost;
  private final HttpClient http = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(2))
      .build();

  public SyntheticHealthProbe(EdgeProperties props) {
    this.canary = URI.create(props.canaryUrl());
    this.requestsPerHost = props.probeRequestsPerHost();
  }

  @Override
  public ProbeResult probe(Set<String> hosts) {
    try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<Boolean>> results = new ArrayList<>();
      for (String host : hosts) {
        for (int i = 0; i < requestsPerHost; i++) {
          results.add(pool.submit(() -> ok(host)));
        }
      }
      int failed = 0;
      for (Future<Boolean> r : results) {
        try {
          if (!r.get()) {
            failed++;
          }
        } catch (Exception e) {
          failed++;
        }
      }
      return new ProbeResult(results.size(), failed);
    }
  }

  private boolean ok(String host) {
    HttpRequest req = HttpRequest.newBuilder(canary)
        .header("Host", host)
        .timeout(Duration.ofSeconds(2))
        .GET()
        .build();
    try {
      // ofInputStream returns as soon as the headers arrive; closing it ends the stream
      var response = http.send(req, BodyHandlers.ofInputStream());
      try (var body = response.body()) {
        return response.statusCode() >= 200 && response.statusCode() < 300;
      }
    } catch (Exception e) {
      return false;
    }
  }
}
