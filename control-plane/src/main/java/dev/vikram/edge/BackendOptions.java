package dev.vikram.edge;

import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Per-backend route options, from application.yml "edge.backend-options".
 * Streaming backends (LLM-style token streams) need them: Envoy's default 15 s route timeout
 * covers the whole response, so it cuts any stream longer than 15 s.
 *
 * @param backendOptions backend name to its options
 */
@ConfigurationProperties(prefix = "edge")
public record BackendOptions(Map<String, Options> backendOptions) {

  /**
   * @param timeout whole-response timeout; 0 turns it off (Envoy's default is 15 s)
   * @param idleTimeout cut a stream that sends nothing for this long, so a hung upstream still ends
   * @param healthPath HTTP path the control plane checks to re-admit an ejected pool member
   */
  public record Options(Duration timeout, Duration idleTimeout, String healthPath) {}

  public String healthPath(String backend) {
    Options o = backendOptions().get(backend);
    return o == null ? null : o.healthPath();
  }

  public Map<String, Options> backendOptions() {
    return backendOptions == null ? Map.of() : backendOptions;
  }

  public static BackendOptions none() {
    return new BackendOptions(Map.of());
  }
}
