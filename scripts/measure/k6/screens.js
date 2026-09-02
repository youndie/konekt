// MEASUREMENT 1, THE READING PROFILE (`B-117`): the three screens a subscriber opens most, at a
// staircase of arrival rates. kompot on the server and one query per screen; no writes.
//
//     scripts/measure/k6.sh screens RATES=10,25,50,100 HOLD=120 SUBSCRIBERS=50
import { announce, buy, intList, screen, signIn, staircase, topUp } from './lib.js';

const RATES = intList('RATES', '10,25,50');
const HOLD = parseInt(__ENV.HOLD || '60', 10);
const SUBSCRIBERS = parseInt(__ENV.SUBSCRIBERS || '30', 10);
const MAX_VUS = parseInt(__ENV.MAX_VUS || '200', 10);

export const options = {
  scenarios: staircase('read', RATES, HOLD, MAX_VUS),
  thresholds: { checks: ['rate>0.99'] },
};

// Subscribers with a plan, so the home screen carries counters and not the empty banner: a home
// with nothing on it is a cheaper screen than the one people see.
export function setup() {
  announce('screens', { RATES, HOLD, SUBSCRIBERS, MAX_VUS });
  const subscribers = [];
  for (let i = 0; i < SUBSCRIBERS; i++) {
    const s = signIn();
    topUp(s.token, 5000_00);
    buy(s.token, 'home-20gb-30d');
    subscribers.push(s.token);
  }
  return { tokens: subscribers };
}

const SCREENS = [
  ['home', 'home'],
  ['plans', 'plans'],
  ['plans/tr-10gb-30d', 'plan-detail'],
];

export function read(data) {
  const token = data.tokens[Math.floor(Math.random() * data.tokens.length)];
  const [path, name] = SCREENS[Math.floor(Math.random() * SCREENS.length)];
  screen(token, path, name);
}
