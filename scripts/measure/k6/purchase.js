// MEASUREMENT 1, THE BUYING PROFILE (`B-117`): the saga end to end — hold, provision, settle, the
// outbox, the broker, the consumer — at a staircase of arrival rates. Each purchase is a new
// subscriber's first, funded before the run, so the saga completes and the number is the saga's.
//
//     scripts/measure/k6.sh purchase RATES=2,5,10,20 HOLD=120
import { announce, countOutcome, buy, intList, signIn, staircase, topUp } from './lib.js';

const RATES = intList('RATES', '2,5,10');
const HOLD = parseInt(__ENV.HOLD || '60', 10);
const MAX_VUS = parseInt(__ENV.MAX_VUS || '100', 10);
const PLAN = __ENV.PLAN || 'home-20gb-30d';

export const options = { scenarios: staircase('purchase', RATES, HOLD, MAX_VUS) };

// A pool of funded subscribers large enough for the whole run: buying twice on one line is a
// different product path (a second package on a line) and a different number.
export function setup() {
  const total = RATES.reduce((sum, r) => sum + r * HOLD, 0) + 50;
  announce('purchase', { RATES, HOLD, MAX_VUS, PLAN, subscribers: total });
  const tokens = [];
  for (let i = 0; i < total; i++) {
    const s = signIn();
    topUp(s.token, 5000_00);
    tokens.push(s.token);
  }
  return { tokens };
}

export function purchase(data) {
  const token = data.tokens[(__VU * 100003 + __ITER * 7919) % data.tokens.length];
  const outcome = buy(token, PLAN);
  countOutcome(outcome);
}
