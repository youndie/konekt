// MEASUREMENT 2, THE SOAK (`B-117`): a moderate, constant rate for hours — screens read, a purchase
// now and then, money added so the purchases keep completing — with the simulator producing usage
// underneath. The sampler beside it (`sample.sh`) is what the soak is for; this only keeps the
// server doing what it does on the test contour, all night.
//
//     scripts/measure/k6.sh soak DURATION=6h READ_RATE=3 BUY_PER_MINUTE=2 SUBSCRIBERS=20
import { announce, countOutcome, buy, screen, signIn, tokenFor, topUp } from './lib.js';

const DURATION = __ENV.DURATION || '6h';
const READ_RATE = parseInt(__ENV.READ_RATE || '3', 10);
const BUY_PER_MINUTE = parseInt(__ENV.BUY_PER_MINUTE || '2', 10);
const SUBSCRIBERS = parseInt(__ENV.SUBSCRIBERS || '20', 10);

export const options = {
  // THE GUARD THAT WAS MISSING, and its absence is why the first run's twelve hours were thrown
  // away: `konekt-soak` finished `success` while 98% of its checks failed, because nothing here
  // said what success was. `screens.js` has had this line all along; the scenario that runs for
  // half a day did not. A soak that stops answering now ends non-zero, and systemd says so.
  thresholds: { checks: ['rate>0.95'] },
  // The setup signs subscribers in one by one — thousands of them for a big point — and k6's
  // default of a minute for it ended the 40-rps purchase point before a single purchase.
  setupTimeout: '30m',
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

// The subscribers, by NUMBER rather than by token: setup data is frozen for the whole run, and a
// token is only good for fifteen minutes of it. Each VU asks `tokenFor` and gets one that is
// current — see the note on it in `lib.js`.
export function setup() {
  announce('soak', { DURATION, READ_RATE, BUY_PER_MINUTE, SUBSCRIBERS });
  const msisdns = [];
  for (let i = 0; i < SUBSCRIBERS; i++) {
    const s = signIn();
    topUp(s.token, 5000_00);
    buy(s.token, 'home-20gb-30d');
    msisdns.push(s.msisdn);
  }
  return { msisdns };
}

function someone(data) {
  return tokenFor(data.msisdns[Math.floor(Math.random() * data.msisdns.length)]);
}

const SCREENS = [
  ['home', 'home'],
  ['plans', 'plans'],
  ['history', 'orders'],
  ['profile', 'profile'],
];

export function read(data) {
  const token = someone(data);
  const [path, name] = SCREENS[Math.floor(Math.random() * SCREENS.length)];
  screen(token, path, name);
}

// Every purchase is preceded by a top-up of its own price, so the balance never runs dry and the
// ledger grows the way it would on a line that pays as it goes — which is the growth a soak watches.
export function purchase(data) {
  const token = someone(data);
  topUp(token, 15_00);
  const outcome = buy(token, 'home-20gb-30d');
  countOutcome(outcome);
}
