package dev.vikram.edge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.vikram.edge.TrafficOptimizer.Kind;
import dev.vikram.edge.TrafficOptimizer.MemberStats;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TrafficOptimizerTest {

  private final OptimizerProperties p = OptimizerProperties.of(true, 30, 10, 10, 20, 20,
      Map.of("pool", List.of("a", "b")));
  private static final long NOW = 1_000_000;

  private TrafficOptimizer.Decision decide(Map<String, Integer> w, MemberStats a, MemberStats b,
      Map<String, Long> ejected) {
    return TrafficOptimizer.decide(w, Map.of("a", a, "b", b), p, ejected, NOW);
  }

  private Map<String, Integer> next(Map<String, Integer> w, long p99a, long p99b, long requests) {
    return decide(w, new MemberStats(requests, p99a, requests, 0), new MemberStats(requests, p99b, requests, 0), Map.of())
        .weights();
  }

  @Test
  void shiftsOneBoundedStepTowardTheFasterMember() {
    assertThat(next(Map.of("a", 50, "b", 50), 50, 500, 100)).isEqualTo(Map.of("a", 60, "b", 40));
  }

  @Test
  void neverStarvesTheSlowerMemberBelowTheFloor() {
    var w = Map.of("a", 50, "b", 50);
    for (int i = 0; i < 10; i++) {
      w = next(w, 50, 500, 100);
    }
    assertThat(w).isEqualTo(Map.of("a", 90, "b", 10));
  }

  @Test
  void holdsWhenThereIsTooLittleTrafficToJudge() {
    assertThat(next(Map.of("a", 50, "b", 50), 50, 500, 5)).isEqualTo(Map.of("a", 50, "b", 50));
  }

  @Test
  void smallDifferencesDoNotMoveTraffic() {
    assertThat(next(Map.of("a", 50, "b", 50), 50, 55, 100)).isEqualTo(Map.of("a", 50, "b", 50));
  }

  @Test
  void whenTheMembersAreEqualAgainItDriftsBackToEven() {
    assertThat(next(Map.of("a", 90, "b", 10), 50, 50, 100)).isEqualTo(Map.of("a", 80, "b", 20));
  }

  @Test
  void aFailingMemberIsEjectedAtOnceEvenBelowTheFloor() {
    var d = decide(Map.of("a", 50, "b", 50), new MemberStats(100, 50, 100, 0), new MemberStats(100, 5, 100, 60),
        Map.of());
    assertThat(d.kind()).isEqualTo(Kind.EJECT);
    assertThat(d.weights()).isEqualTo(Map.of("a", 100, "b", 0));
  }

  @Test
  void theLastHealthyMemberIsNeverEjected() {
    var d = decide(Map.of("a", 50, "b", 50), new MemberStats(100, 50, 100, 90), new MemberStats(100, 50, 100, 80),
        Map.of());
    assertThat(d.kind()).isEqualTo(Kind.HOLD);
  }

  @Test
  void anEjectedMemberGetsTheFloorBackAfterTheEjectionTime() {
    var w = Map.of("a", 100, "b", 0);
    var stillOut = decide(w, new MemberStats(100, 50, 100, 0), new MemberStats(0, 0, 0, 0), Map.of("b", NOW - 30_000));
    assertThat(stillOut.kind()).isEqualTo(Kind.HOLD);
    var back = decide(w, new MemberStats(100, 50, 100, 0), new MemberStats(0, 0, 0, 0), Map.of("b", NOW - 60_000));
    assertThat(back.kind()).isEqualTo(Kind.READMIT);
    assertThat(back.weights()).isEqualTo(Map.of("a", 90, "b", 10));
  }

  @Test
  void aLatencyShiftNeedsTwoAgreeingReadingsButAnEjectionDoesNot() {
    var weights = new TrafficWeights(p);
    var rollouts = mock(RolloutService.class);
    when(rollouts.applyWeights(any(), any())).thenReturn(true);
    var edge = new EdgeProperties(0, "http://x", Map.of(), Map.of(), 1, 0.05, 1, "synthetic",
        "http://p", 30, null, null, "http://a");
    var optimizer = new TrafficOptimizer(p, weights, rollouts, new SimpleMeterRegistry(), edge,
        BackendOptions.none());
    Map<String, MemberStats> slowB = new HashMap<>(Map.of(
        "a", new MemberStats(100, 50, 100, 0), "b", new MemberStats(100, 500, 100, 0)));

    optimizer.step("pool", slowB, NOW);
    verify(rollouts, never()).applyWeights(any(), any());          // first reading: wait
    optimizer.step("pool", slowB, NOW + 30_000);
    verify(rollouts).applyWeights("pool", Map.of("a", 60, "b", 40)); // second agrees: shift

    Map<String, MemberStats> failingA = Map.of(
        "a", new MemberStats(100, 50, 100, 70), "b", new MemberStats(100, 50, 100, 0));
    optimizer.step("pool", failingA, NOW + 60_000);
    verify(rollouts).applyWeights(eq("pool"), eq(Map.of("a", 0, "b", 100)));   // at once
  }

  @Test
  void ejectionTimeDoublesForAMemberThatStaysBrokenUpToTenMinutes() {
    assertThat(TrafficOptimizer.ejectionMs(p, 1)).isEqualTo(60_000);
    assertThat(TrafficOptimizer.ejectionMs(p, 2)).isEqualTo(120_000);
    assertThat(TrafficOptimizer.ejectionMs(p, 3)).isEqualTo(240_000);
    assertThat(TrafficOptimizer.ejectionMs(p, 10)).isEqualTo(600_000);
    var w = Map.of("a", 100, "b", 0);
    var healthyA = new MemberStats(100, 50, 30, 0);
    var idleB = new MemberStats(0, 0, 0, 0);
    // second ejection in a row: 60 s is no longer enough, 120 s is
    assertThat(TrafficOptimizer.decide(w, Map.of("a", healthyA, "b", idleB), p,
        Map.of("b", NOW - 60_000), Map.of("b", 2), Map.of(), NOW).kind()).isEqualTo(Kind.HOLD);
    assertThat(TrafficOptimizer.decide(w, Map.of("a", healthyA, "b", idleB), p,
        Map.of("b", NOW - 120_000), Map.of("b", 2), Map.of(), NOW).kind()).isEqualTo(Kind.READMIT);
  }

  @Test
  void aFreshFailureIsJudgedOnTheLast20SecondsNotTheWholeMinute() {
    // a minute of mostly healthy traffic, but the last 20 s are all 5xx
    var d = decide(Map.of("a", 50, "b", 50), new MemberStats(300, 50, 100, 0),
        new MemberStats(300, 50, 100, 100), Map.of());
    assertThat(d.kind()).isEqualTo(Kind.EJECT);
  }

  @Test
  void aMemberWithAHealthPathComesBackOnlyWhenItsHealthChecksPass() {
    var w = Map.of("a", 100, "b", 0);
    var a = new MemberStats(100, 50, 30, 0);
    var b = new MemberStats(0, 0, 0, 0);
    // long past any backoff, but the health checks still fail: no user traffic is risked
    assertThat(TrafficOptimizer.decide(w, Map.of("a", a, "b", b), p, Map.of("b", NOW - 3_600_000),
        Map.of("b", 5), Map.of("b", false), NOW).kind()).isEqualTo(Kind.HOLD);
    // 10 s after ejection, but healthy: straight back in
    var d = TrafficOptimizer.decide(w, Map.of("a", a, "b", b), p, Map.of("b", NOW - 10_000),
        Map.of("b", 1), Map.of("b", true), NOW);
    assertThat(d.kind()).isEqualTo(Kind.READMIT);
    assertThat(d.weights()).isEqualTo(Map.of("a", 90, "b", 10));
  }
}
