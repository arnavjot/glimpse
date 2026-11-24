// k6 load test: scroll the feed page by page and send playback-event batches,
// the two calls every phone makes constantly.
//
//   docker run --rm -i --network glimpse_default -v "$PWD/load:/load" \
//     -e BASE_URL=http://api:8000 grafana/k6 run /load/feed.js
import http from 'k6/http';
import { check } from 'k6';

// Random v4-style id; collisions are negligible for a load test and it avoids a remote import.
function uuidv4() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16);
  });
}

const BASE = __ENV.BASE_URL || 'http://localhost:8000';
const RPS = Number(__ENV.TARGET_RPS || 300);

export const options = {
  scenarios: {
    feed: {
      executor: 'ramping-arrival-rate',
      startRate: 10, timeUnit: '1s', preAllocatedVUs: 50, maxVUs: 300,
      stages: [
        { target: RPS, duration: '1m' },
        { target: RPS, duration: '3m' },
        { target: 0, duration: '30s' },
      ],
      exec: 'scroll',
    },
    events: {
      executor: 'constant-arrival-rate',
      rate: Math.max(1, Math.round(RPS / 10)), timeUnit: '1s', duration: '4m30s',
      preAllocatedVUs: 20, maxVUs: 100, exec: 'sendEvents',
    },
  },
  thresholds: {
    'http_req_failed': ['rate<0.001'],
    'http_req_duration{name:feed}': ['p(99)<250'],
    'http_req_duration{name:events}': ['p(99)<500'],
  },
};

export function scroll() {
  // Most sessions read the first page or two; a few go deeper.
  let cursor = null;
  const pages = Math.random() < 0.7 ? 1 : 1 + Math.floor(Math.random() * 4);
  for (let i = 0; i < pages; i++) {
    const url = `${BASE}/v1/feed?limit=10` + (cursor ? `&cursor=${cursor}` : '');
    const r = http.get(url, { tags: { name: 'feed' } });
    check(r, { 'feed 200': (x) => x.status === 200 });
    cursor = r.status === 200 ? r.json('next_cursor') : null;
    if (!cursor) break;
  }
}

export function sendEvents() {
  const now = new Date().toISOString();
  const events = [];
  for (let i = 0; i < 20; i++) {
    events.push({
      event_id: uuidv4(), video_id: 'v001', type: i % 2 ? 'first_frame' : 'play_start',
      value_ms: i % 2 ? 50 + Math.floor(Math.random() * 400) : null,
      preload: Math.random() < 0.8, session_id: 'k6', occurred_at: now,
    });
  }
  const r = http.post(`${BASE}/v1/events`, JSON.stringify({ device_id: `k6-${__VU}-device`, events }),
    { headers: { 'Content-Type': 'application/json' }, tags: { name: 'events' } });
  check(r, { 'events 200': (x) => x.status === 200 });
}
