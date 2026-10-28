-- Apply after v3_06. Session-wide revision protects attendance and result edits.
BEGIN;
ALTER TABLE class_sessions ADD COLUMN IF NOT EXISTS training_version bigint NOT NULL DEFAULT 0;
COMMIT;
