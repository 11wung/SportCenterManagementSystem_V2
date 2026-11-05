-- Bỏ quyền đăng ký lớp sớm theo gói; ngày mở đăng ký của lớp thành "ngày tự mở" tùy chọn (để trống = manager mở tay).
-- Chạy lại được (an toàn nếu đã chạy). Áp dụng lên database đã nạp database/schema_v3.sql và các file v3_04 đến v3_13.
-- Chạy NGAY SAU khi triển khai code mới: code mới không còn đọc cột early_register_days và ghi lớp mà không cần ngày tự mở.
-- Không thể hoàn tác: số ngày đăng ký sớm của từng gói bị xóa. Gói đã mua vẫn còn khóa early_register_days
-- trong benefits_snapshot nhưng code không đọc nữa nên để nguyên.

BEGIN;

-- ===== 1. Ngày tự mở của lớp: cho phép để trống =====
-- ck_class_reg_open (ngày tự mở <= ngày khai giảng) giữ nguyên, giá trị trống vẫn hợp lệ.

ALTER TABLE sport_classes ALTER COLUMN registration_opens_on DROP NOT NULL;

-- ===== 2. Bỏ đăng ký sớm theo gói =====

ALTER TABLE membership_packages DROP CONSTRAINT IF EXISTS ck_package_early;
ALTER TABLE membership_packages DROP COLUMN IF EXISTS early_register_days;

COMMIT;
