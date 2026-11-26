# Failure drills

Break one part of the running system on purpose and check what happens, with numbers.
Run the backend with `docker compose up -d --build` and the app on a phone or emulator.
Record each result in the table at the bottom and in the main README.

Helper: count what the server has stored (run from `backend/`).

```bash
sh drills/count.sh            # total events, by type
```

## 1. Phone offline: no events lost, none double counted

Proves the on-device queue (Room + WorkManager) and device-generated event ids.

1. `sh drills/count.sh` and note the total.
2. In the app, open the Performance screen and note "events waiting to upload" (should be 0).
3. Turn on **airplane mode**. Swipe through **30 clips** (already-cached clips still play).
4. The Performance screen now shows the events waiting. Note the number.
5. Turn airplane mode **off**. Within a minute WorkManager uploads them (or tap **Upload now**).
6. `sh drills/count.sh` again.

Expected: the server total went up by exactly the number that was waiting, and the app shows 0
waiting.

## 2. App killed mid-upload: retried, stored once

Proves retries are safe: the server skips event ids it already has.

1. Do steps 1–4 above (airplane mode, 30 clips).
2. Turn airplane mode off and **immediately** force-stop the app
   (`adb shell am force-stop com.arnavjot.glimpse`).
3. Reopen the app and wait a minute.
4. `sh drills/count.sh` and check the duplicates counter:
   `curl -s localhost:8000/actuator/prometheus | grep glimpse_events_total`

Expected: every waiting event stored exactly once. If the killed upload had already reached the
server, the retry shows up as `duplicate`, not as extra rows.

## 3. Redis down: the feed keeps working

```bash
docker compose stop redis
curl -s -o /dev/null -w "%{http_code} %{time_total}s\n" "localhost:8000/v1/feed?limit=10"
```

Pull to refresh in the app and keep swiping. Then `docker compose start redis`.

Expected: `200` every time. The first request waits out one 500 ms Redis timeout; that opens a
circuit breaker (`glimpse_redis_breaker_open` = 1), and for the next 5 s Redis is skipped instead
of waited on, so the feed comes straight from Postgres. Every 5 s one request tries Redis again;
once it's back the breaker closes. The Grafana "Redis unavailable" line rises; nothing breaks in
the app. (Before the breaker, every feed request took 1 s: two Redis calls, 500 ms each.)

## 4. Postgres down: cached clips still play, events wait

```bash
docker compose stop postgres
```

Swipe through clips you've already seen, then 10 new ones (new ones may fail to load, since their
feed page isn't cached). Then `docker compose start postgres`, wait a minute,
`sh drills/count.sh`.

Expected: cached clips keep playing from the phone's disk cache, events queue on the phone while
uploads fail (5xx means "retry later"), and once Postgres is back every queued event is stored.

## Results

| Drill | Result |
|---|---|
| 1. Offline 30 clips | 78 events queued (24 clips, all from the disk cache, 0 rebuffers) → 78 stored, 0 lost, 0 duplicates; uploaded on its own within seconds of reconnecting |
| 2. Killed mid-upload | 75 events stored once; 150 duplicates ignored (the phone's own retry while the API hung, then the reopened app re-sending its unacknowledged queue). Run with the API paused (`docker compose pause api`) so the kill lands while the upload is in flight |
| 3. Redis down | feed `200` every time: first request 517 ms (one timeout), then ~10 ms with the breaker open; closes once Redis is back |
| 4. Postgres down | cached clips played: yes ("Offline · showing saved clips", 0 rebuffers); 34 events queued → 34 stored, 0 duplicates. The API reconnected ~6 s after Postgres was ready. Two gaps: the feed returned `500` after 3 s once its cached page had expired, and the phone's upload, in exponential back-off (30 s doubling), hadn't retried 4 minutes later (sent with Upload now) |
