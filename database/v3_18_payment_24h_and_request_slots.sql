-- Backend update: 24h cash payment window and one slot per selected weekday/time pair.
-- Safe for databases that have already applied v3_12 and v3_15.
BEGIN;

-- Payment methods are CASH and QR_GATEWAY; a member gets 24 hours after approval to pay an approved 1-1 request.
UPDATE system_settings
SET value = '24', description = 'Sau khi manager duyệt, member có 24 giờ để trả tiền, quá hạn thì các suất được nhả'
WHERE key = 'one_on_one.request_pay_hours';

-- Waitlist registrations use the same 24-hour CASH window as ordinary class registrations.
UPDATE system_settings
SET value = '24', description = 'Hội viên được mời từ danh sách chờ có 24 giờ để nộp tiền mặt'
WHERE key IN ('waitlist.hold_hours', 'waitlist.hold_hours_near_start');

-- New requests allow multiple slots on the same weekday, but not the exact same weekday/time slot twice.
ALTER TABLE coach_slot_request_days DROP CONSTRAINT IF EXISTS uq_request_day;
ALTER TABLE coach_slot_request_days
    ADD CONSTRAINT uq_request_day UNIQUE (request_id, weekday, time_slot_id);

COMMIT;
