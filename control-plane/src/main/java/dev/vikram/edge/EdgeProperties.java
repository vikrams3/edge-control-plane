package dev.vikram.edge;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings from application.yml.
 *
 * @param xdsPort gRPC port Envoy connects to for xDS
 * @param canaryUrl URL of the canary Envoy listener, used by the synthetic probe
 * @param backends backend (Envoy cluster) name to "host:port"
 * @param initialRoutes hostname to backend, pushed to both groups at startup
 * @param probeRequestsPerHost requests the probe sends through the canary for each hostname
 * @param maxErrorRate highest canary error rate that still promotes, for example 0.05
 * @param ackTimeoutMillis how long to wait for every canary Envoy to ACK a new version
 * @param probe which HealthProbe to use: "synthetic" (default) or "prometheus"
 * @param prometheusUrl Prometheus base URL, used by the prometheus probe
 * @param probeWindowSeconds how long the prometheus probe watches real canary traffic
 * @param tlsCertFile PEM certificate chain for HTTPS on :10443; HTTPS is off if unset or missing
 * @param tlsKeyFile PEM private key for that certificate
 * @param analyticsUrl base URL of the Kafka Streams analytics service, used by the kafka probe
 */
@ConfigurationProperties(prefix = "edge")
public record EdgeProperties(
    int xdsPort,
    String canaryUrl,
    Map<String, String> backends,
    Map<String, String> initialRoutes,
    int probeRequestsPerHost,
    double maxErrorRate,
    long ackTimeoutMillis,
    String probe,
    String prometheusUrl,
    int probeWindowSeconds,
    String tlsCertFile,
    String tlsKeyFile,
    String analyticsUrl) {}
