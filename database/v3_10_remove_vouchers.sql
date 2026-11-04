-- Bỏ tính năng voucher: ưu đãi duy nhất còn lại là giảm theo hạng (tier). Chạy lại được (an toàn nếu đã chạy).
-- Áp dụng lên database đã nạp database/schema_v3.sql và các file v3_04 đến v3_09.
-- Chạy SAU khi đã triển khai code mới (code cũ còn gọi bảng vouchers sẽ lỗi).
-- Không thể hoàn tác: toàn bộ voucher, chiến dịch voucher, cấu hình voucher.* và quà sinh nhật của gói sẽ mất.
-- Các đơn cũ đã giảm bằng voucher vẫn giữ nguyên số tiền giảm; discount_kind = 'VOUCHER' còn được phép để giữ lịch sử.

BEGIN;

-- ===== 1. Bảng orders: bỏ liên kết tới voucher =====

ALTER TABLE orders DROP CONSTRAINT IF EXISTS ck_order_voucher;
ALTER TABLE orders DROP COLUMN IF EXISTS voucher_id;

-- ===== 2. Xóa bảng voucher và chiến dịch =====

DROP TABLE IF EXISTS vouchers;
DROP TABLE IF EXISTS voucher_campaigns;

-- ===== 3. Bỏ quà sinh nhật của gói (chỉ phát được bằng voucher) =====

ALTER TABLE membership_packages DROP CONSTRAINT IF EXISTS ck_package_gift;
ALTER TABLE membership_packages DROP CONSTRAINT IF EXISTS ck_package_gift_value;
ALTER TABLE membership_packages DROP COLUMN IF EXISTS birthday_gift_kind;
ALTER TABLE membership_packages DROP COLUMN IF EXISTS birthday_gift_value;

-- ===== 4. Xóa cấu hình voucher.* =====

DELETE FROM system_settings WHERE key LIKE 'voucher.%';

COMMIT;
