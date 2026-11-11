# Postmortem: a route to a down backend was promoted to stable

**Date:** 2026-11-11 · **Duration on stable:** 93 s (19:12:55 to 19:14:28 UTC) · **User impact:** none (0 of about 780 stable requests failed) · **Status:** resolved, fixes merged

## Summary

Rollout v54 added `pay.edge.local -> billing`. `billing` has no running instances, so every request to that hostname would fail. The canary probe promoted it anyway. No user sent traffic to `pay.edge.local` in those 93 seconds, so nobody saw an error, but stable carried a broken route that the canary is meant to stop.

Two things went wrong together:

1. **The probe judged a change on evidence from other hostnames.** The Kafka probe summed real traffic across every hostname in the route table. `pay.edge.local` had zero requests, while `crm`, `sales` and `tableau` had 115 healthy ones, so the error rate was 0%.
2. **The fix for (1) was already written but not running.** Its deploy had failed (the new pod crash-looped), Kubernetes correctly kept the old pod serving, and the test continued against the old pod because nothing stopped it after `kubectl rollout status` timed out.

## Timeline (UTC)

| Time | Event |
| --- | --- |
| 19:07:00 | v48 promoted on the Kafka probe: `shop.edge.local` added. The probe log shows `shop.edge.local served 0 requests`, which is the first sign of the gap. Not acted on, because the change was healthy. |
| ~19:09 | Fix written: probe only changed hostnames, and fall back to synthetic requests for hostnames with under 10 real requests. Image built, rollout started. |
| ~19:09 | New control-plane pod crash-loops: `KafkaHealthProbe` now had two constructors and no `@Autowired`, so Spring could not create it. The old pod keeps serving (rolling update). |
| ~19:12 | `kubectl rollout status` times out after 180 s. The test commands that followed it were not chained to it, so they ran against the old pod. |
| 19:12:20 | v52 promoted (`new.edge.local -> sales`, a good change). |
| **19:12:55** | **v54 promoted: `pay.edge.local -> billing`.** The old probe counted 115 requests on other hostnames, 0 errors. |
| ~19:13 | Investigation: the pod events and logs show `No default constructor found`. `@Autowired` added, image rebuilt, rollout succeeds. |
| ~19:14 | The new pod starts and restores the last promoted version from PostgreSQL, which is v54: the bad route survives the restart, as designed. |
| **19:14:28** | **Mitigated: `POST /v1/rollouts/52/revert`** goes through the canary, every hostname probed, promoted as v57. `pay.edge.local` is gone from stable. |
| 19:20:04 | Verified: the same change again (v59) probes only `pay.edge.local`, which has no real traffic, so it gets 25 synthetic requests: 25 of 25 failed, rolled back. |

## Impact

- Stable carried a route to a down backend for 93 s.
- Prometheus: 0 5xx out of about 780 requests on the stable Envoys in that window. No user requested `pay.edge.local`.
- Had the hostname been live, about 100% of its requests would have failed for 93 s, and alerting (`EdgeHigh5xxRate`, 5% for 1 minute plus a 2-minute rate) would likely not have fired in time to matter.

## What went well

- Kubernetes kept the old control plane serving while the new one crash-looped. A bad control-plane deploy was not an outage.
- The revert API made mitigation one call, and it went through the canary like any change.
- The per-hostname access-log stats made the gap visible (`served 0 requests`) before it caused harm, and made the bad hostname obvious in the fix's verification.

## What went wrong

- The probe aggregated across hostnames, so one broken hostname could hide behind healthy ones. The synthetic probe had the same dilution (25 of 75, not 25 of 25).
- Real-traffic probes have no evidence for a hostname nobody calls, and the absence of evidence was counted as success.
- A failed deploy did not stop the test that depended on it.
- Two constructors without `@Autowired` compile and pass unit tests (which construct the class directly), and only fail when Spring creates the bean.

## Action items

| Action | Status |
| --- | --- |
| Probe only hostnames that a change adds or re-points; all hostnames for a removal-only change | Done (`RolloutService.changedHosts`) |
| Real-traffic probe falls back to synthetic requests for hostnames with under 10 requests in the window | Done (`KafkaHealthProbe.combine`) |
| Unit tests for both, including "no traffic" and "a removal" | Done (`ProbeSelectionTest`) |
| `@Autowired` on the Spring constructor | Done |
| Scripts that deploy then test must stop if the deploy fails (`set -e`, and chain `rollout status` with `&&`) | Done in `smoke-test.sh` and the Jenkinsfile; ad-hoc test commands are a habit to keep |
| A Spring context test that starts the app with each `edge.probe` value, so bean wiring errors fail CI rather than a deploy | Done (`ApplicationWiringIT`; it fails with `No default constructor found` when the `@Autowired` is removed) |
| Per-hostname 5xx alert, so a broken low-traffic hostname is caught even if a probe misses it | Open |
