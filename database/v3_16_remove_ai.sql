-- Bỏ toàn bộ tính năng AI (trợ lý AI, gợi ý thực đơn, nhật ký chat AI). Chạy lại được (an toàn nếu đã chạy).
-- Áp dụng lên database đã nạp database/schema_v3.sql và các file v3_04 đến v3_15.
-- Chạy NGAY SAU khi triển khai code mới: code mới không còn đọc cột ai_level, menu_ai_level.
-- Không thể hoàn tác: nhật ký chat AI và cấp độ AI của từng gói sẽ mất.
-- Gói đã mua vẫn còn khóa ai_level, menu_ai_level trong benefits_snapshot nhưng code không đọc nữa nên để nguyên.

BEGIN;

-- ===== 1. Cấp độ AI của gói =====

ALTER TABLE membership_packages DROP CONSTRAINT IF EXISTS ck_package_ai;
ALTER TABLE membership_packages DROP CONSTRAINT IF EXISTS ck_package_menu_ai;
ALTER TABLE membership_packages DROP COLUMN IF EXISTS ai_level;
ALTER TABLE membership_packages DROP COLUMN IF EXISTS menu_ai_level;

-- ===== 2. Nhật ký chat AI và cờ "do AI tạo" của kế hoạch tập =====

DROP TABLE IF EXISTS ai_chat_logs;
ALTER TABLE workout_plans DROP COLUMN IF EXISTS is_ai_generated;

-- ===== 3. Quyền AI_USE =====

DELETE FROM role_permissions WHERE permission_id IN (SELECT id FROM permissions WHERE code = 'AI_USE');
DELETE FROM permissions WHERE code = 'AI_USE';

-- ===== 4. Mô tả gói: bỏ chữ AI và đăng ký lớp sớm (đã gỡ) =====

UPDATE membership_packages SET description = 'Giảm học phí lớp, ưu tiên hàng chờ.' WHERE code = 'BUT_TOC';
UPDATE membership_packages SET description = 'Bài tập riêng từ coach, giảm giá lớp và buổi 1-1, ưu tiên hàng chờ cao hơn.' WHERE code = 'VUOT_DINH';
UPDATE membership_packages SET description = 'Trọn quyền lợi: giảm sâu học phí và buổi 1-1, bài tập riêng từ coach, ưu tiên hàng chờ cao nhất.' WHERE code = 'QUAN_QUAN';

COMMIT;
