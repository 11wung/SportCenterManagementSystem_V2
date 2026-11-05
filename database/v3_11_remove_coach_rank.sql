-- Bỏ hạng coach: mọi coach cùng một giá 1-1 (cấu hình one_on_one.hourly_price, manager đổi được).
-- Chạy lại được (an toàn nếu đã chạy). Áp dụng lên database đã nạp database/schema_v3.sql và các file v3_04 đến v3_10.
-- Chạy NGAY SAU khi triển khai code mới: code mới cần cấu hình giá mới khi đặt suất; cột coach_rank có DEFAULT nên
-- code mới vẫn ghi coach_profiles bình thường trước khi cột bị bỏ.
-- Không thể hoàn tác: hạng của từng coach và cấu hình coach.rank_prices sẽ mất.
-- Suất 1-1 đã giữ chỗ hoặc đã đặt giữ nguyên giá đã chốt; suất còn trống tính theo giá chung mới.

BEGIN;

-- ===== 1. Giá một giờ chung (đồng) =====

INSERT INTO system_settings (key, value, description)
VALUES ('one_on_one.hourly_price', '300000', 'Giá một giờ buổi 1-1, chung cho mọi coach; giá suất = giá giờ x số phút / 60 (BR-ONE-02)')
ON CONFLICT (key) DO NOTHING;

-- ===== 2. Bỏ hạng coach =====

DELETE FROM system_settings WHERE key = 'coach.rank_prices';

ALTER TABLE coach_profiles DROP CONSTRAINT IF EXISTS ck_coach_rank;
ALTER TABLE coach_profiles DROP COLUMN IF EXISTS coach_rank;

COMMIT;
