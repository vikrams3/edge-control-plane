package dev.vikram.edge;

import org.springframework.stereotype.Component;

/** Promotes when the canary error rate is at or below the configured limit. */
@Component
public class ErrorRatePolicy implements RolloutPolicy {

  private final double maxErrorRate;

  public ErrorRatePolicy(EdgeProperties props) {
    this.maxErrorRate = props.maxErrorRate();
  }

  @Override
  public boolean shouldPromote(HealthProbe.ProbeResult result) {
    return result.errorRate() <= maxErrorRate;
  }
}
