-- Dời lịch thay cho hủy-hoàn, và bỏ hẳn suất 1-1 lẻ (manager mở sẵn suất trống): từ nay buổi 1-1 chỉ sinh ra từ yêu cầu đã duyệt.
-- Chạy lại được. Áp dụng lên database đã nạp database/schema_v3.sql và các file v3_04 đến v3_12.
-- Chạy NGAY SAU khi triển khai code mới: code mới đọc cấu hình one_on_one.reschedule_before_hours và cột needs_reschedule.
-- Không thể hoàn tác: các suất trống chưa ai đặt bị xóa; các suất lẻ đang giữ chỗ chưa trả tiền bị hủy.
-- Các suất lẻ đã trả tiền (BOOKED) giữ nguyên làm lịch sử và vẫn diễn ra bình thường.

BEGIN;

-- ===== 1. Cờ "chờ xếp lại" =====
-- Trung tâm không dạy được buổi này (coach nghỉ, không đổi được phòng hay coach): buổi được giữ,
-- hội viên chọn giờ mới (và coach mới nếu coach cũ đã nghỉ) bất cứ lúc nào trước giờ học.

ALTER TABLE coach_slots ADD COLUMN IF NOT EXISTS needs_reschedule boolean NOT NULL DEFAULT false;

-- ===== 2. Cấu hình =====

INSERT INTO system_settings (key, value, description)
VALUES ('one_on_one.reschedule_before_hours', '24', 'Hội viên báo dời buổi 1-1 trước giờ học ít nhất bấy nhiêu giờ thì được dời, muộn hơn thì mất buổi (BR-ONE-04)')
ON CONFLICT (key) DO NOTHING;

-- ===== 3. Bỏ suất lẻ =====

-- Suất trống chưa ai đặt (không thuộc yêu cầu nào): không còn nơi nào tạo ra hay hiển thị chúng
DELETE FROM coach_slots WHERE status = 'OPEN' AND request_id IS NULL;

-- Suất lẻ đang giữ chỗ, chưa trả tiền: hủy, vì hội viên không còn cách trả tiền cho từng suất lẻ
UPDATE coach_slots
SET status = 'CANCELED', canceled_at = now()
WHERE status = 'HELD' AND request_id IS NULL;

COMMIT;
