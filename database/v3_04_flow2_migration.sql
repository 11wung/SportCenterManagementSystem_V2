-- Flow 2: gộp mọi thay đổi DB mà Flow 2 thêm lên schema_v3.sql. Chạy lại được (an toàn nếu đã chạy).
-- Áp dụng lên database đã nạp database/schema_v3.sql.
-- Gồm nội dung của v3_02_center_hours.sql và v3_03_assessment_results.sql; đã chạy hai file đó rồi thì chạy file này vẫn không sao.

BEGIN;

-- 1. Giờ mở cửa chung của trung tâm (tính bằng phút từ 00:00): lớp, buổi 1-1 và khung đánh giá không được nằm ngoài khoảng này
INSERT INTO system_settings (key, value, description) VALUES
    ('center.open_minute', '360', 'Giờ mở cửa của trung tâm, tính bằng phút từ 00:00 (360 = 06:00)'),
    ('center.close_minute', '1320', 'Giờ đóng cửa của trung tâm, tính bằng phút từ 00:00 (1320 = 22:00)')
ON CONFLICT (key) DO NOTHING;

-- 2. BR-BEN-03: kết quả đánh giá thể lực lưu vào workout_progress.
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

COMMIT;
