#!/usr/bin/env bash
# Long-lived streaming load: N concurrent LLM-style streams (one token per 100 ms, ~20 s each)
# through the stable Envoys. Reports how many finished with [DONE] and how many were cut off.
#   STREAMS=100 TOKENS=200 ./scripts/stream-test.sh
set -euo pipefail
STREAMS=${STREAMS:-100}; TOKENS=${TOKENS:-200}; MS=${MS:-100}
kubectl -n edge delete pod stream-test --ignore-not-found >/dev/null
kubectl -n edge run stream-test --rm -i --quiet --restart=Never --image=python:3.13-alpine \
  --image-pull-policy=IfNotPresent -- python -u -c "
import http.client, threading, time, statistics
N, TOKENS, MS = $STREAMS, $TOKENS, $MS
results = []
def one():
    t = time.time(); tokens = 0; done = False; status = None; err = None
    try:
        c = http.client.HTTPConnection('envoy-stable.edge.svc.cluster.local', 10000, timeout=120)
        c.request('GET', f'/?tokens={TOKENS}&ms={MS}', headers={'Host': 'llm.edge.local'})
        r = c.getresponse(); status = r.status
        for line in r:
            if line.startswith(b'data:token-'): tokens += 1
            if line.startswith(b'data:[DONE]'): done = True
    except Exception as e:
        err = type(e).__name__
    results.append((done, tokens, time.time() - t, status, err))
threads = [threading.Thread(target=one) for _ in range(N)]
[t.start() for t in threads]; [t.join() for t in threads]
ok = [r for r in results if r[0]]; cut = [r for r in results if not r[0]]
print(f'{N} streams of {TOKENS} tokens ({TOKENS*MS/1000:.0f} s each), status {sorted(set(r[3] for r in results))}')
print(f'  completed with [DONE]: {len(ok)}' + (f', median {statistics.median(r[2] for r in ok):.1f} s' if ok else ''))
if cut:
    print(f'  cut off: {len(cut)}, after a median {statistics.median(r[2] for r in cut):.1f} s and '
          f'{statistics.median(r[1] for r in cut):.0f} tokens; errors {sorted(set(str(r[4]) for r in cut))}')
"
