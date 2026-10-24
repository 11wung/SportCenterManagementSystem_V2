-- L10: giờ mở cửa chung của trung tâm. Chạy lại được (an toàn nếu đã chạy).
-- Áp dụng lên database đã nạp scms_database_v3.sql.

-- Giờ mở cửa chung của trung tâm (tính bằng phút từ 00:00): lớp, buổi 1-1 và khung đánh giá không được nằm ngoài khoảng này
INSERT INTO system_settings (key, value, description) VALUES
    ('center.open_minute', '360', 'Giờ mở cửa của trung tâm, tính bằng phút từ 00:00 (360 = 06:00)'),
    ('center.close_minute', '1320', 'Giờ đóng cửa của trung tâm, tính bằng phút từ 00:00 (1320 = 22:00)')
ON CONFLICT (key) DO NOTHING;
