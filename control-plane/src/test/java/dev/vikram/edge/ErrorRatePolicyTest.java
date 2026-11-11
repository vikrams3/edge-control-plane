package dev.vikram.edge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ErrorRatePolicyTest {

  private final ErrorRatePolicy policy = new ErrorRatePolicy(
      new EdgeProperties(18000, "http://x", Map.of(), Map.of(), 10, 0.05, 0, "synthetic", "http://p", 30, null, null, "http://a"));

  @Test
  void promotesAtOrBelowThreshold() {
    assertThat(policy.shouldPromote(new HealthProbe.ProbeResult(100, 5))).isTrue();
  }

  @Test
  void rollsBackAboveThreshold() {
    assertThat(policy.shouldPromote(new HealthProbe.ProbeResult(100, 6))).isFalse();
  }

  @Test
  void noProbeResultsCountsAsFailure() {
    assertThat(policy.shouldPromote(new HealthProbe.ProbeResult(0, 0))).isFalse();
  }
}
