-- Thêm môn Cầu lông, Zumba cùng phòng tập tương ứng (cho khớp trang chủ). Chạy lại được.
-- Áp dụng lên database đã nạp database/schema_v3.sql và các file v3_04, v3_05.

BEGIN;

INSERT INTO sport_categories (name, description) VALUES
    ('Cầu lông', 'Cầu lông đơn, đôi và rèn phản xạ'),
    ('Zumba', 'Nhảy theo nhạc, đốt calo')
ON CONFLICT (name) DO NOTHING;

INSERT INTO facility_rooms (room_name, floor, capacity, description, maintenance_status)
SELECT 'Sân cầu lông', 'Tầng 3', 24, 'Sân cầu lông trong nhà', 'AVAILABLE'
WHERE NOT EXISTS (SELECT 1 FROM facility_rooms WHERE room_name = 'Sân cầu lông');

INSERT INTO facility_rooms (room_name, floor, capacity, description, maintenance_status)
SELECT 'Phòng Zumba', 'Tầng 2', 30, 'Phòng nhảy có gương và loa', 'AVAILABLE'
WHERE NOT EXISTS (SELECT 1 FROM facility_rooms WHERE room_name = 'Phòng Zumba');

-- Phòng nào dạy môn nào (trigger kiểm tra bảng này khi xếp lớp)
INSERT INTO room_sports (room_id, category_id)
SELECT r.id, c.id FROM facility_rooms r, sport_categories c
WHERE (r.room_name, c.name) IN (('Sân cầu lông', 'Cầu lông'), ('Phòng Zumba', 'Zumba'))
ON CONFLICT DO NOTHING;

COMMIT;
