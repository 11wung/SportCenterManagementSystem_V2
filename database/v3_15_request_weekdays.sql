-- Yêu cầu buổi 1-1 chọn được nhiều ngày trong tuần, mỗi ngày một khung giờ riêng (ví dụ thứ 2 lúc 10:00, thứ 4 lúc 17:00).
-- coach_slot_request_days: mỗi dòng là một cặp (thứ trong tuần, khung giờ) của một yêu cầu. weekday: 1 = thứ 2 đến 7 = Chủ nhật.
-- Có thể chọn cùng một thứ nhiều lần nếu khung giờ khác nhau. Yêu cầu cũ không có dòng nào ở bảng này vẫn được đọc theo lịch cũ.
-- first_date là ngày của buổi đầu tiên thật sự; time_slot_id của yêu cầu là giờ của buổi đầu tiên.
-- Chạy lại được (an toàn nếu đã chạy). Áp dụng lên database đã nạp schema_v3.sql và các file v3_04 đến v3_14.
-- Chạy NGAY SAU khi triển khai code mới: code mới đọc và ghi bảng này.

BEGIN;

-- Cột weekdays của bản thử đầu (nếu máy bạn đã chạy bản đó) không còn dùng
ALTER TABLE coach_slot_requests DROP CONSTRAINT IF EXISTS ck_request_weekdays;
ALTER TABLE coach_slot_requests DROP COLUMN IF EXISTS weekdays;

CREATE TABLE IF NOT EXISTS coach_slot_request_days (
    id           bigserial PRIMARY KEY,
    request_id   bigint   NOT NULL REFERENCES coach_slot_requests(id) ON DELETE CASCADE,
    weekday      smallint NOT NULL,
    time_slot_id bigint   NOT NULL REFERENCES time_slots(id),
    CONSTRAINT ck_request_day_weekday CHECK (weekday BETWEEN 1 AND 7),
    CONSTRAINT uq_request_day UNIQUE (request_id, weekday, time_slot_id)
);

CREATE INDEX IF NOT EXISTS idx_request_day_request ON coach_slot_request_days (request_id);

COMMIT;
