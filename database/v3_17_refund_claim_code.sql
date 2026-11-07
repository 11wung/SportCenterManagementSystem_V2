-- Mã nhận tiền hoàn: người nhận tiền phải đọc đúng mã 6 số (hội viên xem trong ứng dụng, nhận qua thông báo và email)
-- thì quầy mới bấm "đã hoàn" được, để người mạo danh biết thông tin hoàn tiền cũng không lấy được tiền.
-- Mã không lưu trong DB: BE tính lại từ mã khoản hoàn và khóa bí mật. DB chỉ lưu số lần nhập sai, thời điểm hết khóa
-- và lần gửi mã gần nhất. Mã chỉ được gửi cho hội viên (thông báo và email) khi quầy bấm nút gửi.
-- Chạy lại được. Áp dụng lên database đã nạp database/schema_v3.sql và các file v3_04 đến v3_16.
-- Chạy NGAY SAU khi triển khai code mới: code mới ghi ba cột này.

BEGIN;

ALTER TABLE refunds ADD COLUMN IF NOT EXISTS claim_failed_attempts smallint NOT NULL DEFAULT 0;
ALTER TABLE refunds ADD COLUMN IF NOT EXISTS claim_locked_until timestamptz;
-- Lần gần nhất quầy bấm "Gửi mã cho hội viên", để không gửi dồn dập
ALTER TABLE refunds ADD COLUMN IF NOT EXISTS claim_code_sent_at timestamptz;

COMMIT;
