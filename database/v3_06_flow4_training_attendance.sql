-- Flow 4. Apply after v3_05. Forward-only, repeatable, no sample data.
BEGIN;

ALTER TABLE class_sessions ADD COLUMN IF NOT EXISTS attendance_finalized_at timestamptz;
ALTER TABLE class_attendances ADD COLUMN IF NOT EXISTS recorded_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE class_attendances ADD COLUMN IF NOT EXISTS updated_at timestamptz NOT NULL DEFAULT now();
-- Historical participation must survive attempted session deletion.
ALTER TABLE class_attendances DROP CONSTRAINT IF EXISTS class_attendances_session_id_fkey;
ALTER TABLE class_attendances ADD CONSTRAINT class_attendances_session_id_fkey
    FOREIGN KEY (session_id) REFERENCES class_sessions(id) ON DELETE RESTRICT;

ALTER TABLE workout_plans ALTER COLUMN member_id DROP NOT NULL;
ALTER TABLE workout_plans ADD COLUMN IF NOT EXISTS class_id bigint REFERENCES sport_classes(id);
ALTER TABLE workout_plans ADD COLUMN IF NOT EXISTS objective varchar(2000);
ALTER TABLE workout_plans ADD COLUMN IF NOT EXISTS coach_notes varchar(4000);
ALTER TABLE workout_plans ADD COLUMN IF NOT EXISTS version integer NOT NULL DEFAULT 1;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_plan_target') THEN
        -- Legacy individual plans remain valid. New Flow 4 plans always have a class scope.
        ALTER TABLE workout_plans ADD CONSTRAINT ck_plan_target CHECK (member_id IS NOT NULL OR class_id IS NOT NULL);
    END IF;
END $$;
CREATE INDEX IF NOT EXISTS idx_plan_class ON workout_plans(class_id);

CREATE TABLE IF NOT EXISTS training_plan_exercises (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    plan_id bigint NOT NULL REFERENCES workout_plans(id) ON DELETE RESTRICT,
    position integer NOT NULL CHECK (position >= 0),
    name varchar(200) NOT NULL CHECK (length(trim(name)) > 0),
    instructions varchar(2000),
    sets integer CHECK (sets BETWEEN 0 AND 10000),
    repetitions integer CHECK (repetitions BETWEEN 0 AND 100000),
    duration_seconds integer CHECK (duration_seconds BETWEEN 0 AND 604800),
    retired boolean NOT NULL DEFAULT false
);
CREATE INDEX IF NOT EXISTS idx_exercise_plan ON training_plan_exercises(plan_id, retired, position);

CREATE TABLE IF NOT EXISTS training_results (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    attendance_id bigint NOT NULL REFERENCES class_attendances(id) ON DELETE RESTRICT,
    exercise_id bigint REFERENCES training_plan_exercises(id) ON DELETE RESTRICT,
    activity_key varchar(100) NOT NULL CHECK (length(trim(activity_key)) > 0),
    activity_name varchar(200) NOT NULL CHECK (length(trim(activity_name)) > 0),
    -- Snapshot of the prescribed exercise and plan revision when first recorded.
    prescription jsonb,
    actual_sets integer CHECK (actual_sets BETWEEN 0 AND 10000),
    actual_repetitions integer CHECK (actual_repetitions BETWEEN 0 AND 100000),
    actual_duration_seconds integer CHECK (actual_duration_seconds BETWEEN 0 AND 604800),
    performance varchar(2000),
    completion_status varchar(20) NOT NULL CHECK (completion_status IN ('COMPLETED','PARTIAL','NOT_COMPLETED')),
    notes varchar(4000),
    recorded_by bigint NOT NULL REFERENCES users(id),
    recorded_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_training_result_activity UNIQUE(attendance_id, activity_key),
    CONSTRAINT ck_training_result_key CHECK (exercise_id IS NULL OR activity_key = 'exercise:' || exercise_id::text)
);
CREATE INDEX IF NOT EXISTS idx_training_result_exercise ON training_results(exercise_id);

CREATE TABLE IF NOT EXISTS training_progress_evaluations (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    class_id bigint NOT NULL REFERENCES sport_classes(id),
    member_id bigint NOT NULL REFERENCES users(id),
    coach_id bigint NOT NULL REFERENCES users(id),
    evaluation_date date NOT NULL,
    period_start date NOT NULL,
    period_end date NOT NULL,
    summary varchar(4000) NOT NULL CHECK (length(trim(summary)) > 0),
    rating integer NOT NULL CHECK (rating BETWEEN 1 AND 5),
    strengths varchar(2000),
    improvements varchar(2000),
    recommendations varchar(2000),
    feedback varchar(4000),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version integer NOT NULL DEFAULT 1,
    CHECK (period_end >= period_start AND evaluation_date >= period_end)
);
CREATE INDEX IF NOT EXISTS idx_training_evaluation_member ON training_progress_evaluations(member_id, evaluation_date DESC);
CREATE INDEX IF NOT EXISTS idx_training_evaluation_class ON training_progress_evaluations(class_id, member_id);
CREATE INDEX IF NOT EXISTS idx_session_coach_date_flow4 ON class_sessions(coach_id, session_date);
COMMIT;
