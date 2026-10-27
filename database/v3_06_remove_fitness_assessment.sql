-- Bỏ tính năng đánh giá thể lực. Chạy lại được (an toàn nếu đã chạy).
-- Áp dụng lên database đã chạy schema_v3.sql, v3_04, v3_05.
-- CẢNH BÁO: xóa kết quả đánh giá đã nhập (3 cột của workout_progress) và các khung đánh giá trong coach_slots.

BEGIN;

-- 1. workout_progress: bỏ 3 cột chỉ dùng cho đánh giá thể lực (index và check đi theo cột)
DROP INDEX IF EXISTS uq_progress_assessment_slot;
ALTER TABLE workout_progress DROP CONSTRAINT IF EXISTS ck_progress_heart_rate;
ALTER TABLE workout_progress DROP CONSTRAINT IF EXISTS ck_progress_flexibility;
ALTER TABLE workout_progress DROP COLUMN IF EXISTS resting_heart_rate;
ALTER TABLE workout_progress DROP COLUMN IF EXISTS flexibility_cm;
ALTER TABLE workout_progress DROP COLUMN IF EXISTS assessment_slot_id;

-- 2. coach_slots: xóa khung đánh giá, chỉ còn buổi 1-1
DELETE FROM coach_slots WHERE kind = 'ASSESSMENT';
ALTER TABLE coach_slots DROP CONSTRAINT IF EXISTS ck_slot_kind;
ALTER TABLE coach_slots ADD CONSTRAINT ck_slot_kind CHECK (kind::text = 'ONE_ON_ONE');

-- 3. membership_packages: bỏ quyền lợi đánh giá thể lực (check ck_package_assess đi theo cột)
ALTER TABLE membership_packages DROP COLUMN IF EXISTS assessment_every_months;

COMMIT;
