package dev.vikram.edge;

/**
 * Decides whether a canary result is good enough to promote.
 * Strategy interface: today an error-rate threshold; later latency or SLO budgets.
 */
public interface RolloutPolicy {

  boolean shouldPromote(HealthProbe.ProbeResult result);
}
