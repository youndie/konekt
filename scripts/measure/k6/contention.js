// MEASUREMENT 3, THE SAGA UNDER CONTENTION (`B-117`): many purchases on ONE account, at once,
// with money for exactly a few of them. What the run answers is how many completed; what decides is
// `scripts/measure/contention-check.sh` afterwards — the database, not the responses.
//
//     scripts/measure/k6.sh contention AFFORDABLE=20 ATTEMPTS=200 RATE=50
import { announce, countOutcome, buy, signIn, topUp } from './lib.js';

const AFFORDABLE = parseInt(__ENV.AFFORDABLE || '20', 10);
const ATTEMPTS = parseInt(__ENV.ATTEMPTS || '200', 10);
const RATE = parseInt(__ENV.RATE || '50', 10);
const PLAN = __ENV.PLAN || 'home-20gb-30d';
const PRICE_MINOR = parseInt(__ENV.PRICE_MINOR || '1500', 10);

export const options = {
  scenarios: {
    same_account: {
      executor: 'shared-iterations',
      exec: 'attempt',
      vus: RATE,
      iterations: ATTEMPTS,
      maxDuration: '10m',
    },
  },
};

export function setup() {
  announce('contention', { AFFORDABLE, ATTEMPTS, RATE, PLAN, PRICE_MINOR });
  const s = signIn();
  topUp(s.token, AFFORDABLE * PRICE_MINOR);
  return { token: s.token, msisdn: s.msisdn };
}

export function attempt(data) {
  const outcome = buy(data.token, PLAN);
  countOutcome(outcome);
}

export function teardown(data) {
  console.log(`contention: account +${data.msisdn} was funded for ${AFFORDABLE} purchases and asked for ${ATTEMPTS}; run contention-check.sh now`);
}
