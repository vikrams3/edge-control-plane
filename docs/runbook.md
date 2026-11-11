# Runbook: edge control plane

For the kind setup. Control plane API on `localhost:8080`, Grafana `localhost:3000` (dashboard "Edge control plane"), Prometheus `:9090`, Alertmanager `:9093` (from `./scripts/port-forward.sh`).

## Alerts

### EdgeHigh5xxRate (critical)

More than 5% of requests on a group of Envoys (`group` label: canary or stable) returned 5xx for 1 minute.

1. **Was it a route change?** `curl -s localhost:8080/v1/rollouts | python3 -m json.tool | tail -40`. A promoted rollout a few minutes before the alert is the first suspect.
2. **Which hostname or backend?** Grafana panel "Requests/s by backend and status class", or the analytics service: `kubectl -n edge exec deploy/control-plane -- wget -qO- 'http://analytics.edge.svc.cluster.local:8080/v1/stats?group=stable&by=host'`.
3. **If a rollout caused it, revert it:** `curl -X POST 'localhost:8080/v1/rollouts/<last good version>/revert?wait=true'`. The revert goes through the canary. If the canary itself is broken and the revert keeps rolling back, see "Emergency" below.
4. **If no rollout caused it, the backend is unhealthy:** `kubectl -n edge get pods`, then `kubectl -n edge describe` / `logs` the backend. Access logs show Envoy's view: `kubectl -n edge logs deploy/envoy-stable | grep '"status":5'`. The `flags` field explains a 503: `UF` = upstream connect failure, `UH` = no healthy upstream, `UT` = upstream timeout, `NR` = no route (that one is a 404).

### EdgeHostHigh5xxRate (critical)

One hostname returns more than 20% 5xx on the stable Envoys for 1 minute (with at least 10 requests in 2 minutes). It catches a broken hostname that is too small to move the group-wide `EdgeHigh5xxRate`. The numbers come from the access logs through Kafka, so they lag a few seconds.

1. `curl -s localhost:8080/v1/routes` to see which backend the hostname points at.
2. The same steps as `EdgeHigh5xxRate`: a recent rollout → revert it; otherwise check that backend.
3. A hostname with no traffic cannot alert. New hostnames are covered by the canary probe instead.

### EdgeControlPlaneDown (warning)

The control plane is not being scraped. **Users are not affected**: Envoy keeps serving its last config (tested: 35,846 of 35,846 requests succeeded during a 2-minute outage). What stops is route changes and automatic rollbacks.

1. `kubectl -n edge get pods -l app=control-plane` and `kubectl -n edge logs deploy/control-plane --previous`.
2. Common causes: PostgreSQL down (Flyway or JPA fails at startup), a bad image (CrashLoopBackOff), a bean wiring error.
3. A bad control-plane image: `kubectl -n edge rollout undo deployment/control-plane`.
4. On restart it restores the last promoted routes from PostgreSQL and the Envoys reconnect within about 20 s.

### EdgeStableEnvoysDisconnected (warning)

No stable Envoy is connected over ADS. They keep serving their last config. Check the Envoy logs for `gRPC config stream closed` and that the `control-plane` Service resolves (`kubectl -n edge get endpoints control-plane`).

## Common tasks

| Task | How |
| --- | --- |
| See what is live | `curl localhost:8080/v1/routes` and `curl localhost:8080/v1/pools` |
| Change routes | `curl -X POST 'localhost:8080/v1/rollouts?wait=true' -H 'Content-Type: application/json' -d '{full route table}'` |
| Roll back a change | `curl -X POST 'localhost:8080/v1/rollouts/<version>/revert?wait=true'` |
| Drain a backend pool | Route its hostnames to another backend with a rollout. For a weighted pool, the optimizer keeps each member at 10% or more; to take one out entirely, route the hostname to the other member. |
| Rotate the TLS certificate | Update the `edge-tls` Secret, wait for the kubelet to sync it (about 1 minute), then push (any rollout) |
| Switch probe | `kubectl -n edge set env deploy/control-plane EDGE_PROBE=synthetic\|prometheus\|kafka` |
| Check Envoy's live config | `kubectl -n edge port-forward deploy/envoy-stable 9901` then `localhost:9901/config_dump` |

## Emergency: change routes without the canary

There is deliberately no API for this. If the canary group is broken in a way that makes every rollout fail (for example all canary Envoys down, giving `canaryAck: no_nodes`), first fix or restart the canary Envoys: `kubectl -n edge rollout restart deploy/envoy-canary`. Envoy keeps serving stable's last good config in the meantime, so there is time to do this properly.
