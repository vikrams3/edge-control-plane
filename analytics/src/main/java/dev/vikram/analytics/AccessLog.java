package dev.vikram.analytics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;

/**
 * One Envoy access log line, as Fluent Bit ships it: the JSON fields from EdgeConfig.accessLog().
 * Lines that are not access logs (Envoy's own warnings, for example) are skipped.
 */
public record AccessLog(String group, String host, String backend, int status, long durationMs) {

  private static final ObjectMapper JSON = new ObjectMapper();

  public static Optional<AccessLog> parse(String value) {
    try {
      JsonNode n = JSON.readTree(value);
      if (n == null || !n.hasNonNull("status") || !n.hasNonNull("node")) {
        return Optional.empty();
      }
      String node = n.get("node").asText();               // pod name, e.g. envoy-canary-6f9c...
      String group = node.startsWith("envoy-canary") ? "canary"
          : node.startsWith("envoy-stable") ? "stable" : "other";
      String host = n.path("authority").asText("-").replaceFirst(":\\d+$", "");
      String backend = n.path("backend").asText("-");      // "-" when no route matched
      return Optional.of(new AccessLog(group, host, backend,
          n.get("status").asInt(), n.path("duration_ms").asLong(0)));
    } catch (Exception e) {
      return Optional.empty();
    }
  }

  /** Grouping key: group|host|backend. */
  public String key() {
    return group + "|" + host + "|" + backend;
  }
}
