-- Creators can delete their uploads. 'deleted' means: gone from the API and the feed at
-- once; the cleanup job then removes the files (original in storage, HLS in the media
-- folder) and finally the row.
ALTER TABLE uploads DROP CONSTRAINT uploads_status_check;
ALTER TABLE uploads ADD CONSTRAINT uploads_status_check
    CHECK (status IN ('pending_upload', 'uploaded', 'processing', 'ready', 'failed', 'deleted'));

-- The cleanup job looks for old rows in these states.
CREATE INDEX ix_uploads_cleanup ON uploads (status, updated_at)
    WHERE status IN ('pending_upload', 'failed', 'deleted');
