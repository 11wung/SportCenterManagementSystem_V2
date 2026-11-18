-- v3_22: buổi học bù ưu tiên xếp trong thời gian khóa. Chạy lại được. Áp dụng sau v3_21.
--   makeup.min_notice_days: ngày bù sớm nhất cách hôm nay bao nhiêu ngày (để học viên kịp thu xếp).
--   makeup.max_busy_percent: một ngày giờ bù chỉ được đề xuất khi số học viên bị trùng lịch không quá tỉ lệ này của lớp.

BEGIN;

INSERT INTO system_settings (key, value, description)
VALUES ('makeup.min_notice_days', '2', 'Buổi học bù sớm nhất cách hôm nay số ngày này, để học viên kịp thu xếp (BR-MKP-01)')
ON CONFLICT (key) DO NOTHING;

INSERT INTO system_settings (key, value, description)
VALUES ('makeup.max_busy_percent', '50', 'Ngày giờ bù làm quá tỉ lệ phần trăm này của lớp bị trùng lịch thì không đề xuất (BR-MKP-02)')
ON CONFLICT (key) DO NOTHING;

COMMIT;
