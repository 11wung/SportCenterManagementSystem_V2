-- Voucher gia hạn sớm và quay lại, đồng thời bỏ tính năng bảo lưu gói. Chạy lại được (an toàn nếu đã chạy).
-- Áp dụng lên database đã nạp database/schema_v3.sql và v3_04_flow2_migration.sql.
-- Chạy SAU khi đã triển khai code mới (code cũ còn gọi bảng bảo lưu sẽ lỗi).
-- Phần 2 không thể hoàn tác: các đợt bảo lưu đã ghi trong subscription_freezes sẽ mất.

BEGIN;

-- ===== 1. Voucher tự động gia hạn sớm và quay lại (BR-VCH-02) =====

ALTER TABLE vouchers DROP CONSTRAINT IF EXISTS ck_voucher_source;
ALTER TABLE vouchers ADD CONSTRAINT ck_voucher_source CHECK (source IN
    ('BIRTHDAY', 'WELCOME', 'COMPLETION', 'COMPENSATION', 'CAMPAIGN', 'EARLY_RENEWAL', 'WINBACK'));

-- Mức giảm và hạn dùng (manager chỉnh được), cùng số ngày kích hoạt
INSERT INTO system_settings (key, value, description) VALUES
    ('voucher.early_renewal', '{"kind": "PERCENT", "value": 5, "applies_to": "PACKAGE", "valid_days": 7}',
     'Voucher gia hạn sớm: tặng khi gói còn ít ngày mà chưa gia hạn (BR-VCH-02)'),
    ('voucher.winback', '{"kind": "PERCENT", "value": 10, "applies_to": "PACKAGE", "valid_days": 30}',
     'Voucher quay lại: tặng khi gói đã hết hạn một thời gian mà chưa mua lại (BR-VCH-02)'),
    ('voucher.early_renewal_days', '7', 'Gói còn tối đa bao nhiêu ngày thì được tặng voucher gia hạn sớm'),
    ('voucher.winback_after_days', '30', 'Gói đã hết hạn bao nhiêu ngày thì được tặng voucher quay lại')
ON CONFLICT (key) DO NOTHING;

-- ===== 2. Bỏ tính năng bảo lưu gói (BR-FRZ) =====

-- Xóa bảng kéo theo trigger trg_freezes_check, ràng buộc ex_freeze_overlap, chỉ mục và khóa ngoại của bảng
DROP TABLE IF EXISTS subscription_freezes;
DROP FUNCTION IF EXISTS trg_check_freeze();

DELETE FROM system_settings WHERE key IN ('freeze.min_days', 'freeze.max_periods');

-- Quỹ ngày bảo lưu của từng hạng gói
ALTER TABLE membership_packages DROP CONSTRAINT IF EXISTS ck_package_freeze;
ALTER TABLE membership_packages DROP COLUMN IF EXISTS freeze_days;

-- Quyền lợi đã chụp vào các gói đã mua
UPDATE member_subscriptions SET benefits_snapshot = benefits_snapshot - 'freeze_days'
WHERE jsonb_exists(benefits_snapshot, 'freeze_days');

COMMIT;
