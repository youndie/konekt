#!/usr/bin/env python3
"""REALTIME FAN-OUT (`B-117`, measurement 4): N subscribers, each with a plan and an open SSE
stream, while the simulator ticks — three usage updates per subscriber every five seconds. What is
counted, per stream: the updates that arrived, against the ticks that happened; and per tick, the
spread between the first stream to get its update and the last — the fan-out's own latency, since
the wire carries no server stamp to measure from. Also which streams got nothing, and duplicates.

    python3 scripts/measure/fanout.py --base http://10.0.0.2:8080 --streams 100 --seconds 120

Standard library only, so it runs on a rented box with nothing installed. Run from the generator.
"""
import argparse
import asyncio
import json
import random
import statistics
import sys
import time
import urllib.parse
import urllib.request


def call(base, path, body=None, token=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(base + path, data=data, method="POST" if data is not None else "GET")
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    with urllib.request.urlopen(req, timeout=30) as r:
        return r.status, r.read().decode()


def sign_in(base):
    msisdn = "+1555" + str(random.randint(1000000, 9999999))
    call(base, "/api/v1/auth/otp/request", {"msisdn": msisdn})
    _, revealed = call(base, "/api/v1/dev/otp?msisdn=" + urllib.parse.quote(msisdn))
    code = json.loads(revealed)["code"]
    _, verified = call(base, "/api/v1/auth/otp/verify", {"msisdn": msisdn, "code": code})
    token = json.loads(verified)["accessToken"]
    call(base, "/api/v1/top-ups", {"amountMinor": 5000_00}, token)
    _, started = call(base, "/api/v1/purchases", {"planId": "home-20gb-30d"}, token)
    order = json.loads(started)
    if order.get("status") == "awaiting_confirmation":
        call(base, f"/api/v1/purchases/{order['orderId']}/confirm", None, token)
    return token


async def stream(idx, host, port, token, events, stop):
    """One SSE stream, hand-rolled over a socket: HTTP/1.1, chunked or not, `data:` lines."""
    reader, writer = await asyncio.open_connection(host, port)
    writer.write(
        (f"GET /api/v1/realtime HTTP/1.1\r\nHost: {host}:{port}\r\nAuthorization: Bearer {token}\r\n"
         f"Accept: text/event-stream\r\nConnection: keep-alive\r\n\r\n").encode())
    await writer.drain()
    try:
        while not stop.is_set():
            try:
                line = await asyncio.wait_for(reader.readline(), timeout=1.0)
            except asyncio.TimeoutError:
                continue
            if not line:
                events.append((idx, time.monotonic(), "closed", None))
                return
            text = line.decode(errors="replace").strip()
            if text.startswith("data:"):
                try:
                    component_id = json.loads(text[5:].strip()).get("componentId")
                except json.JSONDecodeError:
                    component_id = None
                events.append((idx, time.monotonic(), "update", component_id))
    finally:
        writer.close()


async def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://10.0.0.2:8080")
    ap.add_argument("--streams", type=int, default=100)
    ap.add_argument("--seconds", type=int, default=120)
    ap.add_argument("--tick", type=float, default=5.0, help="the simulator's interval")
    args = ap.parse_args()
    u = urllib.parse.urlparse(args.base)
    host, port = u.hostname, u.port or 80

    t0 = time.monotonic()
    tokens = [sign_in(args.base) for _ in range(args.streams)]
    print(f"fanout: {args.streams} subscribers with a plan in {time.monotonic() - t0:.0f}s", flush=True)

    events, stop = [], asyncio.Event()
    tasks = [asyncio.create_task(stream(i, host, port, tok, events, stop)) for i, tok in enumerate(tokens)]
    await asyncio.sleep(5)
    events.clear()
    start = time.monotonic()
    await asyncio.sleep(args.seconds)
    stop.set()
    await asyncio.gather(*tasks, return_exceptions=True)
    end = time.monotonic()

    updates = [e for e in events if e[2] == "update" and start <= e[1] <= end]
    closed = [e for e in events if e[2] == "closed"]
    ticks = int((end - start) / args.tick)
    expected_per_stream = ticks * 3
    per_stream = {i: 0 for i in range(args.streams)}
    for e in updates:
        per_stream[e[0]] += 1
    counts = sorted(per_stream.values())
    # A tick's spread: cluster arrivals into ticks by time, then first-to-last within each cluster.
    times = sorted(e[1] for e in updates)
    spreads, cluster = [], []
    for t in times:
        if cluster and t - cluster[-1] > 1.0:
            spreads.append(cluster[-1] - cluster[0])
            cluster = []
        cluster.append(t)
    if cluster:
        spreads.append(cluster[-1] - cluster[0])
    print(f"fanout: window {end - start:.0f}s ≈ {ticks} ticks; expected {expected_per_stream} updates per stream")
    print(f"fanout: updates received {len(updates)} of {expected_per_stream * args.streams} expected "
          f"({100 * len(updates) / max(1, expected_per_stream * args.streams):.1f}%); streams closed early: {len(closed)}")
    print(f"fanout: per-stream updates min/median/max {counts[0]}/{counts[len(counts)//2]}/{counts[-1]}; "
          f"streams with none: {sum(1 for c in counts if c == 0)}")
    if spreads:
        ms = [s * 1000 for s in spreads]
        print(f"fanout: first-to-last arrival within a tick, ms: median {statistics.median(ms):.0f} p95 "
              f"{sorted(ms)[int(0.95 * (len(ms) - 1))]:.0f} max {max(ms):.0f} over {len(ms)} ticks")
    json.dump({"streams": args.streams, "seconds": args.seconds, "ticks": ticks, "updates": len(updates),
               "expected": expected_per_stream * args.streams, "closed": len(closed), "per_stream": counts,
               "spreads_ms": [s * 1000 for s in spreads]}, open(f"fanout-{args.streams}.json", "w"))


if __name__ == "__main__":
    asyncio.run(main())
