// WHAT EVERY SCENARIO NEEDS AND NONE SHOULD REPEAT (`B-117`): signing a subscriber in through the
// stand's dev OTP route, giving them money, buying a plan, reading a screen. The requests are the
// product's own, with the same bodies the client sends; nothing here is a shortcut the server would
// not accept from a phone.
import http from 'k6/http';
import { check, fail } from 'k6';

export const BASE = __ENV.BASE || 'http://server:8080';
const JSON_HEADERS = { 'Content-Type': 'application/json' };

export function params(extra) {
  const p = { headers: Object.assign({}, JSON_HEADERS, extra || {}) };
  return p;
}

export function bearer(token, tags) {
  return { headers: Object.assign({}, JSON_HEADERS, { Authorization: `Bearer ${token}` }), tags: tags || {} };
}

// A fresh number every time: the stand's subscribers are created by signing in, and a number that
// already exists is a subscriber whose balance this run did not choose.
export function freshMsisdn() {
  return '1555' + String(Math.floor(1000000 + Math.random() * 9000000));
}

export function signIn(msisdn) {
  const number = msisdn || freshMsisdn();
  const asked = http.post(`${BASE}/api/v1/auth/otp/request`, JSON.stringify({ msisdn: '+' + number }), params());
  if (asked.status !== 200 && asked.status !== 202) fail(`otp request answered ${asked.status}: ${asked.body}`);
  const revealed = http.get(`${BASE}/api/v1/dev/otp?msisdn=${encodeURIComponent('+' + number)}`, params());
  if (revealed.status !== 200) fail(`dev otp answered ${revealed.status} — is DEV_REVEAL_OTP on?`);
  const code = revealed.json('code');
  const verified = http.post(`${BASE}/api/v1/auth/otp/verify`, JSON.stringify({ msisdn: '+' + number, code }), params());
  if (verified.status !== 200) fail(`verify answered ${verified.status}: ${verified.body}`);
  const token = verified.json('accessToken');
  if (!token) fail(`verify answered no token: ${verified.body}`);
  return { msisdn: number, token };
}

export function topUp(token, amountMinor) {
  const r = http.post(`${BASE}/api/v1/top-ups`, JSON.stringify({ amountMinor }), bearer(token, { name: 'top-up' }));
  check(r, { 'top-up 201': (x) => x.status === 201 || x.status === 200 });
  return r;
}

// The purchase as the client does it: start, then confirm. Answers the final order status, so a
// scenario can count `completed` against `rejected` without parsing anything twice.
export function buy(token, planId) {
  const started = http.post(`${BASE}/api/v1/purchases`, JSON.stringify({ planId }), bearer(token, { name: 'purchase' }));
  if (started.status !== 200 && started.status !== 201) return { status: `http-${started.status}` };
  const order = started.json();
  if (order.status !== 'awaiting_confirmation') return { status: order.status, orderId: order.orderId };
  const confirmed = http.post(`${BASE}/api/v1/purchases/${order.orderId}/confirm`, null, bearer(token, { name: 'confirm' }));
  if (confirmed.status !== 200) return { status: `http-${confirmed.status}`, orderId: order.orderId };
  return { status: confirmed.json('status'), orderId: order.orderId };
}

export function screen(token, path, name) {
  const r = http.get(`${BASE}/api/v1/screens/${path}`, bearer(token, { name: name || path }));
  check(r, { [`${name || path} 200`]: (x) => x.status === 200 });
  return r;
}

// THE STAIRCASE: one constant-arrival-rate scenario per rate, back to back, each held for HOLD
// seconds. A staircase of arrival rates and not of virtual users, because a user is a number of
// requests per second only by accident.
export function staircase(execFn, rates, hold, maxVus) {
  const scenarios = {};
  let start = 0;
  rates.forEach((rate) => {
    scenarios[`rate_${rate}`] = {
      executor: 'constant-arrival-rate',
      exec: execFn,
      rate,
      timeUnit: '1s',
      duration: `${hold}s`,
      startTime: `${start}s`,
      preAllocatedVUs: Math.min(maxVus, Math.max(10, rate * 2)),
      maxVUs: maxVus,
      tags: { rate: String(rate) },
    };
    start += hold + 5;
  });
  return scenarios;
}

export function announce(name, values) {
  console.log(`${name}: ${Object.entries(values).map(([k, v]) => `${k}=${v}`).join(' ')}`);
}

export function intList(name, fallback) {
  return (__ENV[name] || fallback).split(',').map((x) => parseInt(x.trim(), 10));
}
