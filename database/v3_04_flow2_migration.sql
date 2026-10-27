-- Flow 2: gộp mọi thay đổi DB mà Flow 2 thêm lên schema_v3.sql. Chạy lại được (an toàn nếu đã chạy).
-- Áp dụng lên database đã nạp database/schema_v3.sql.
-- Gồm giờ mở cửa và slot giờ cố định (các file v3_02, v3_03 cũ đã gộp vào đây).
-- Đã chạy v3_02, v3_03 hoặc bản cũ của file này rồi thì chạy file này vẫn không sao.

BEGIN;

-- 1. Giờ mở cửa chung của trung tâm (tính bằng phút từ 00:00): lớp và buổi 1-1 không được nằm ngoài khoảng này
INSERT INTO system_settings (key, value, description) VALUES
    ('center.open_minute', '360', 'Giờ mở cửa của trung tâm, tính bằng phút từ 00:00 (360 = 06:00)'),
    ('center.close_minute', '1320', 'Giờ đóng cửa của trung tâm, tính bằng phút từ 00:00 (1320 = 22:00)')
ON CONFLICT (key) DO NOTHING;

-- 2. Slot giờ cố định (kiểu tiết học): lịch tuần của class, và buổi 1-1 đều chọn theo slot
CREATE TABLE IF NOT EXISTS time_slots (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    slot_no     smallint NOT NULL UNIQUE,
    start_time  time NOT NULL,
    end_time    time NOT NULL,
    is_active   boolean NOT NULL DEFAULT true,
    CONSTRAINT ck_time_slot_time CHECK (end_time > start_time)
);

-- Dòng lịch tuần ghi lại slot đã chọn. Giờ vẫn được copy vào start_time, end_time nên các luật kiểm trùng không đổi.
-- Dòng cũ không có slot thì để NULL
ALTER TABLE class_schedules ADD COLUMN IF NOT EXISTS time_slot_id bigint REFERENCES time_slots(id);

-- 8 slot mặc định, mỗi slot 105 phút, cách nhau 15 phút (schedule.buffer_minutes), trải từ 06:00 đến 21:45,
-- nằm trong giờ mở cửa 06:00 đến 22:00. Manager thêm hoặc tắt slot bằng /api/manager/time-slots
INSERT INTO time_slots (slot_no, start_time, end_time) VALUES
    (1, '06:00', '07:45'),
    (2, '08:00', '09:45'),
    (3, '10:00', '11:45'),
    (4, '12:00', '13:45'),
    (5, '14:00', '15:45'),
    (6, '16:00', '17:45'),
    (7, '18:00', '19:45'),
    (8, '20:00', '21:45')
ON CONFLICT (slot_no) DO NOTHING;

COMMIT;
