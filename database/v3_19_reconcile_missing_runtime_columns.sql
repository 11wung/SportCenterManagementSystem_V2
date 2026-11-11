-- Reconcile DB columns used by the current CoachSlot and Flow 4 training code.
-- Safe to run more than once; preserves existing records.
BEGIN;

-- CoachSlot entity and member/coach 1-1 schedule APIs read/write this field.
ALTER TABLE coach_slots
    ADD COLUMN IF NOT EXISTS coach_note varchar(1000);

-- Flow 4 timetable/training queries depend on these session columns.
ALTER TABLE class_sessions
    ADD COLUMN IF NOT EXISTS attendance_finalized_at timestamptz;

ALTER TABLE class_sessions
    ADD COLUMN IF NOT EXISTS training_version bigint;

UPDATE class_sessions
SET training_version = 0
WHERE training_version IS NULL;

ALTER TABLE class_sessions
    ALTER COLUMN training_version SET DEFAULT 0;

ALTER TABLE class_sessions
    ALTER COLUMN training_version SET NOT NULL;

COMMIT;
