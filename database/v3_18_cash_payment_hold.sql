-- v3_18: hạn thanh toán để hội viên kịp ra quầy trả tiền mặt.
--   Gói hội viên: 24 giờ (không giữ chỗ của ai). Ghi danh lớp: giữ chỗ 2 giờ (lớp có số chỗ giới hạn).
--   Buổi 1-1: 24 giờ sau khi manager duyệt (member cần thời gian đọc email).
-- Mã QR online vẫn hiệu lực 15 phút mỗi mã, không bao giờ vượt hạn của đơn.
-- Chạy lại nhiều lần không lỗi.

INSERT INTO system_settings (key, value, description) VALUES
    ('payment.cash_hold_hours', '24',
     'Số giờ đơn gói hội viên chờ thanh toán (online hoặc tiền mặt tại quầy). Quá hạn đơn tự hủy'),
    ('payment.class_hold_hours', '2',
     'Số giờ giữ chỗ khi ghi danh lớp chờ thanh toán. Không quá giờ buổi học đầu tiên. Quá hạn tự nhả chỗ')
ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, description = EXCLUDED.description, updated_at = now();

UPDATE system_settings
SET value = '24',
    description = 'Sau khi manager duyệt, member có bấy nhiêu giờ để trả tiền (không quá giờ buổi đầu tiên), quá hạn thì các suất được nhả',
    updated_at = now()
WHERE key = 'one_on_one.request_pay_hours';
