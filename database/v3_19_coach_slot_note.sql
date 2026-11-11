-- Thêm ghi chú riêng của coach cho buổi kèm cá nhân (coach_slots).
-- Chạy sau v3_18_payment_24h_and_request_slots.sql.
-- Chạy lại an toàn; không thay đổi dữ liệu hoặc trạng thái các suất đã đặt.

BEGIN;

ALTER TABLE public.coach_slots
    ADD COLUMN IF NOT EXISTS coach_note varchar(1000);

COMMENT ON COLUMN public.coach_slots.coach_note
    IS 'Ghi chú riêng của coach cho buổi kèm cá nhân';

COMMIT;
