#!/usr/bin/env python3
"""A round trip through everything a restored process might have lost: the pool, the broker, SSE.

Signs in (Postgres write and read), tops up, buys a plan, confirms it, then opens the realtime
stream and waits for an update. The purchase travels through the broker — an outbox row, a
produced event, the usage consumer — so an update arriving proves the broker connection is alive,
which a screen request would not. Standard library only, so it runs anywhere the stand runs.

    crac-probe.py --base http://127.0.0.1:18080 [--stream-seconds 20]

Prints one JSON object: every step with its HTTP status and duration, and the updates seen.
Exit code 1 if any step failed or no update arrived.
"""
import argparse, asyncio, json, random, sys, time, urllib.error, urllib.parse, urllib.request

def call(base, path, body=None, token=None, method=None, timeout=15):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(
        base + path,
        data=data if data is not None else (b"" if method == "POST" else None),
        method=method or ("POST" if data is not None else "GET"))
    if data is not None:
        req.add_header("content-type", "application/json")
    if token:
        req.add_header("authorization", "Bearer " + token)
    t0 = time.monotonic()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, r.read().decode(), round((time.monotonic() - t0) * 1000)
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()[:400], round((time.monotonic() - t0) * 1000)
    except Exception as e:                                    # noqa: BLE001 - the probe reports, never raises
        return 0, f"{type(e).__name__}: {e}", round((time.monotonic() - t0) * 1000)

async def stream(host, port, token, seconds):
    """One SSE stream, hand-rolled: the update that proves the broker path is alive."""
    updates = []
    try:
        reader, writer = await asyncio.wait_for(asyncio.open_connection(host, port), timeout=10)
    except Exception as e:                                    # noqa: BLE001
        return {"error": f"{type(e).__name__}: {e}", "updates": 0}
    writer.write((f"GET /api/v1/realtime HTTP/1.1\r\nHost: {host}:{port}\r\n"
                  f"Authorization: Bearer {token}\r\nAccept: text/event-stream\r\n"
                  f"Connection: keep-alive\r\n\r\n").encode())
    await writer.drain()
    deadline = time.monotonic() + seconds
    try:
        while time.monotonic() < deadline:
            try:
                line = await asyncio.wait_for(reader.readline(), timeout=1.0)
            except asyncio.TimeoutError:
                continue
            if not line:
                return {"error": "stream closed", "updates": len(updates)}
            text = line.decode(errors="replace").strip()
            if text.startswith("data:"):
                updates.append(text[5:].strip()[:120])
    finally:
        writer.close()
    return {"updates": len(updates), "first": updates[0] if updates else None}

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://127.0.0.1:18080")
    ap.add_argument("--stream-seconds", type=int, default=20)
    a = ap.parse_args()
    host = urllib.parse.urlparse(a.base).hostname
    port = urllib.parse.urlparse(a.base).port or 80
    out, ok = {}, True
    msisdn = "+1555" + str(random.randint(1000000, 9999999))

    def step(name, *args, **kw):
        nonlocal ok
        status, body, ms = call(a.base, *args, **kw)
        out[name] = {"status": status, "ms": ms}
        if status not in (200, 201, 202):
            out[name]["body"] = body[:300]
            ok = False
        return body

    step("otp_request", "/api/v1/auth/otp/request", {"msisdn": msisdn})
    revealed = step("otp_reveal", "/api/v1/dev/otp?msisdn=" + urllib.parse.quote(msisdn))
    try:
        code = json.loads(revealed)["code"]
    except Exception:                                         # noqa: BLE001
        print(json.dumps({**out, "fatal": "no otp code", "raw": revealed[:200]}, indent=2)); sys.exit(1)
    verified = step("otp_verify", "/api/v1/auth/otp/verify", {"msisdn": msisdn, "code": code})
    token = json.loads(verified).get("accessToken")
    step("top_up", "/api/v1/top-ups", {"amountMinor": 500000}, token)
    started = step("purchase", "/api/v1/purchases", {"planId": "home-20gb-30d"}, token)
    try:
        order = json.loads(started)
    except Exception:                                         # noqa: BLE001
        order = {}
    if order.get("status") == "awaiting_confirmation":
        step("confirm", f"/api/v1/purchases/{order['orderId']}/confirm", None, token, method="POST")
    if order.get("orderId"):
        time.sleep(2)
        state = step("order_state", f"/api/v1/purchases/{order['orderId']}", None, token)
        try:
            out["order_state"]["value"] = json.loads(state).get("status")
        except Exception:                                     # noqa: BLE001
            pass
    step("screen_home", "/api/v1/screens/home", None, token)
    out["realtime"] = asyncio.run(stream(host, port, token, a.stream_seconds))
    if out["realtime"].get("updates", 0) == 0:
        ok = False
    print(json.dumps(out, indent=2))
    sys.exit(0 if ok else 1)

main()
