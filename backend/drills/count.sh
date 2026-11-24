#!/bin/sh
# Count the playback events the server has stored, by type.
docker compose exec -T postgres psql -U glimpse -d glimpse -c \
  "SELECT type, count(*) FROM playback_events GROUP BY type
   UNION ALL SELECT 'TOTAL', count(*) FROM playback_events ORDER BY 1;"
