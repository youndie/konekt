// MEASUREMENT 2, THE SOAK (`B-117`): a moderate, constant rate for hours — screens read, a purchase
// now and then, money added so the purchases keep completing — with the simulator producing usage
// underneath. The sampler beside it (`sample.sh`) is what the soak is for; this only keeps the
// server doing what it does on the test contour, all night.
//
//     scripts/measure/k6.sh soak DURATION=6h READ_RATE=3 BUY_PER_MINUTE=2 SUBSCRIBERS=20
import { Counter } from 'k6/metrics';
import { announce, buy, screen, signIn, topUp } from './lib.js';

const DURATION = __ENV.DURATION || '6h';
const READ_RATE = parseInt(__ENV.READ_RATE || '3', 10);
const BUY_PER_MINUTE = parseInt(__ENV.BUY_PER_MINUTE || '2', 10);
const SUBSCRIBERS = parseInt(__ENV.SUBSCRIBERS || '20', 10);
const outcomes = new Counter('soak_purchase_outcomes');

export const options = {
  scenarios: {
    reading: {
      executor: 'constant-arrival-rate',
      exec: 'read',
      rate: READ_RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: 10,
      maxVUs: 50,
    },
    buying: {
      executor: 'constant-arrival-rate',
      exec: 'purchase',
      rate: BUY_PER_MINUTE,
      timeUnit: '1m',
      duration: DURATION,
      preAllocatedVUs: 2,
      maxVUs: 10,
    },
  },
};

export function setup() {
  announce('soak', { DURATION, READ_RATE, BUY_PER_MINUTE, SUBSCRIBERS });
  const tokens = [];
  for (let i = 0; i < SUBSCRIBERS; i++) {
    const s = signIn();
    topUp(s.token, 5000_00);
    buy(s.token, 'home-20gb-30d');
    tokens.push(s.token);
  }
  return { tokens };
}

const SCREENS = [
  ['home', 'home'],
  ['plans', 'plans'],
  ['history', 'orders'],
  ['profile', 'profile'],
];

export function read(data) {
  const token = data.tokens[Math.floor(Math.random() * data.tokens.length)];
  const [path, name] = SCREENS[Math.floor(Math.random() * SCREENS.length)];
  screen(token, path, name);
}

// Every purchase is preceded by a top-up of its own price, so the balance never runs dry and the
// ledger grows the way it would on a line that pays as it goes — which is the growth a soak watches.
export function purchase(data) {
  const token = data.tokens[Math.floor(Math.random() * data.tokens.length)];
  topUp(token, 15_00);
  const outcome = buy(token, 'home-20gb-30d');
  outcomes.add(1, { status: outcome.status });
}
