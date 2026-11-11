package dev.vikram.edge;

import java.util.Set;

/**
 * Measures how healthy the canary is after a config push.
 * Strategy interface: today a synthetic probe; later Prometheus or Kafka metrics.
 */
public interface HealthProbe {

  /** Returns the canary's error rate for these hostnames, from 0.0 to 1.0. */
  ProbeResult probe(Set<String> hosts);

  record ProbeResult(int total, int failed) {
    public double errorRate() {
      return total == 0 ? 1.0 : (double) failed / total;
    }
  }
}
