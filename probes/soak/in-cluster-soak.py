#!/usr/bin/env python3
"""B-77's soak, run from inside the cluster it is measuring.

Deployed beside a throwaway konekt release in a namespace of its own, with the chart's
`simulateTraffic` on and its IngressRoute deleted: the loader needs `/api/v1/dev/otp`, and the
chart's own comment is right that such a route reachable from outside IS the authentication system.
Not reachable, not a problem; the stand holds nothing but fictional subscribers either way.

Twelve hours is the condition this item failed to reproduce at one hour, and a loader on a laptop
does not live that long: a port-forward dies with the machine that opened it, and what it measures
is the tunnel. So this runs as a pod beside the stand and talks to the Service by cluster DNS.

WHAT IT RECORDS, every SAMPLE seconds:
  * usage_counter's live rows, dead rows, size, and — the column that can settle the question early —
    autovacuum_count and last_autovacuum. The suspicion is dead row versions accumulating without
    bound; if autovacuum keeps pace the story is wrong, and that is readable in an hour.
  * the lag from a BRAND NEW subscriber to their data counter moving, which is what both failing
    scenarios wait on. `probes/lag.sh` measured it flat from 3 to 83 subscribers on a fresh stand;
    the open question is whether it stays flat as the stand ages.

Append-only from the first line: a soak killed at hour nine must leave nine hours behind.
"""
import json
import os
import subprocess
import time
import urllib.error
import urllib.request

HOST = os.environ.get("HOST", "http://konekt-soak:8080")
SUBSCRIBERS = int(os.environ.get("SUBSCRIBERS", "90"))
SAMPLE = int(os.environ.get("SAMPLE", "600"))
LAG_TIMEOUT = int(os.environ.get("LAG_TIMEOUT", "150"))
OUT = os.environ.get("OUT", "/data/soak.tsv")
PG = os.environ.get("PGHOST", "konekt-soak-postgres")


def call(method, path, token=None, body=None):
    request = urllib.request.Request(
        f"{HOST}{path}",
        method=method,
        data=json.dumps(body).encode() if body is not None else None,
        headers={
            "Content-Type": "application/json",
            **({"Authorization": f"Bearer {token}"} if token else {}),
        },
    )
    with urllib.request.urlopen(request, timeout=30) as answer:
        text = answer.read().decode()
    return json.loads(text) if text.strip().startswith(("{", "[")) else text


def subscriber(index):
    # The msisdn is derived from a counter and the pod's start, not from randomness: a soak that
    # collides with an existing subscriber signs in as them and quietly measures somebody else's
    # counter.
    msisdn = f"1555{(int(os.environ['SEED']) + index) % 9_000_000 + 1_000_000}"
    call("POST", "/api/v1/auth/otp/request", body={"msisdn": msisdn})
    code = call("GET", f"/api/v1/dev/otp?msisdn={msisdn}")["code"]
    token = call("POST", "/api/v1/auth/otp/verify", body={"msisdn": msisdn, "code": code})["accessToken"]
    call("POST", "/api/v1/top-ups", token=token, body={"amountMinor": 5000})
    order = call("POST", "/api/v1/purchases", token=token, body={"planId": "home-20gb-30d"})["orderId"]
    call("POST", f"/api/v1/purchases/{order}/confirm", token=token)
    return token


def counter(token):
    tree = call("GET", "/api/v1/screens/home", token=token)
    found = []

    def walk(node):
        if isinstance(node, dict):
            if node.get("id") == "counter-data":
                found.append(node.get("valueText"))
            for value in node.values():
                walk(value)
        elif isinstance(node, list):
            for value in node:
                walk(value)

    walk(tree)
    return found[0] if found else None


def lag():
    """Seconds from 'this subscriber exists' to 'their data counter has moved'."""
    token = subscriber(int(time.time()) % 1000 + 500_000)
    first = counter(token)
    started = time.time()
    for _ in range(LAG_TIMEOUT):
        if counter(token) != first:
            return int(time.time() - started)
        time.sleep(1)
    return LAG_TIMEOUT


def psql(sql):
    done = subprocess.run(
        ["psql", "-h", PG, "-U", os.environ["PGUSER"], "-d", os.environ["PGDATABASE"], "-tAc", sql],
        capture_output=True,
        text=True,
        env={**os.environ, "PGPASSWORD": os.environ["PGPASSWORD"]},
    )
    return done.stdout.strip() if done.returncode == 0 else ""


def main():
    loaded = lambda: int(psql("SELECT count(DISTINCT subscriber_id) FROM usage_counter") or 0)
    print(f"soak: loading to {SUBSCRIBERS} subscribers (now {loaded()})", flush=True)
    index = 0
    while loaded() < SUBSCRIBERS:
        try:
            subscriber(index)
        except (urllib.error.URLError, KeyError, TimeoutError) as refused:
            print(f"soak: a sign-up failed ({refused}), retrying", flush=True)
            time.sleep(2)
        index += 1
    print(f"soak: loaded, {loaded()} subscribers, writing to {OUT}", flush=True)

    with open(OUT, "a") as out:
        out.write("elapsed_s\tsubscribers\tlive\tdead\tsize\tautovac\tlast_autovac\tlag_s\n")
        out.flush()
        started = time.time()
        while True:
            row = psql(
                "SELECT n_live_tup||'|'||n_dead_tup||'|'||pg_size_pretty(pg_total_relation_size(relid))"
                "||'|'||autovacuum_count||'|'||coalesce(to_char(last_autovacuum,'HH24:MI:SS'),'never') "
                "FROM pg_stat_user_tables WHERE relname='usage_counter'"
            )
            # Split on `|`: pg_size_pretty answers "672 kB", with a space in it, and a whitespace
            # split writes a plausible-looking row of nonsense.
            live, dead, size, autovac, last = (row.split("|") + ["-"] * 5)[:5]
            try:
                waited = lag()
            except Exception as broke:  # noqa: BLE001 - a failed probe is a reading, not a crash
                waited = f"error:{type(broke).__name__}"
            line = (
                f"{int(time.time() - started)}\t{loaded()}\t{live}\t{dead}\t{size}\t"
                f"{autovac}\t{last}\t{waited}\n"
            )
            out.write(line)
            out.flush()
            print(line.rstrip(), flush=True)
            time.sleep(SAMPLE)


if __name__ == "__main__":
    main()
