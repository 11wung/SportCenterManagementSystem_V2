-- L10: kết quả đánh giá thể lực. Chạy lại được (an toàn nếu đã chạy).
-- Áp dụng lên database đã nạp scms_database_v3.sql.

-- BR-BEN-03: kết quả đánh giá thể lực lưu vào workout_progress (lịch sử số đo cơ thể của Flow 4).
-- Thêm hai chỉ số SRS có nêu mà bảng chưa có, và liên kết tới khung đánh giá để mỗi khung chỉ ghi kết quả một lần
ALTER TABLE workout_progress ADD COLUMN IF NOT EXISTS resting_heart_rate smallint;
ALTER TABLE workout_progress ADD COLUMN IF NOT EXISTS flexibility_cm numeric(5,1);
ALTER TABLE workout_progress ADD COLUMN IF NOT EXISTS assessment_slot_id bigint REFERENCES coach_slots(id);

CREATE UNIQUE INDEX IF NOT EXISTS uq_progress_assessment_slot
    ON workout_progress (assessment_slot_id) WHERE assessment_slot_id IS NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_progress_heart_rate') THEN
        ALTER TABLE workout_progress ADD CONSTRAINT ck_progress_heart_rate
            CHECK (resting_heart_rate IS NULL OR (resting_heart_rate >= 30 AND resting_heart_rate <= 220));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_progress_flexibility') THEN
        ALTER TABLE workout_progress ADD CONSTRAINT ck_progress_flexibility
            CHECK (flexibility_cm IS NULL OR (flexibility_cm >= -50 AND flexibility_cm <= 100));
    END IF;
END
$$;
