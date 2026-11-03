-- Suất 1-1 dài 60 phút thay vì cả khung giờ 105 phút của lớp, để giá một buổi (giá giờ x phút / 60) hợp lý.
-- Độ dài là cấu hình one_on_one.slot_minutes; manager đổi được, không sửa bảng time_slots nên lớp học không bị ảnh hưởng.
-- Chạy lại được. Áp dụng lên database đã nạp database/schema_v3.sql và các file v3_04 đến v3_08.

BEGIN;

INSERT INTO system_settings (key, value, description)
VALUES ('one_on_one.slot_minutes', '60', 'Độ dài một suất 1-1 (phút), lấy từ đầu khung giờ khi tạo suất (BR-ONE-02)')
ON CONFLICT (key) DO NOTHING;

-- Rút các suất còn trống chưa diễn ra về 60 phút. Suất đã có người giữ chỗ hoặc đặt giữ nguyên giờ và giá đã chốt.
UPDATE coach_slots
SET end_at = start_at + interval '60 minutes'
WHERE status = 'OPEN'
  AND start_at > now()
  AND end_at > start_at + interval '60 minutes';

COMMIT;
