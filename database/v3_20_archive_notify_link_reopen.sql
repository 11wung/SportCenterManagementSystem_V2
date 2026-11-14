-- v3_20: góp ý sau demo đợt 2. Chạy lại được. Áp dụng sau v3_19.
--   1. Phòng và môn xóa mềm: đã có người dùng thì chỉ "ngừng sử dụng" (archived_at), chưa từng dùng thì xóa hẳn.
--   2. Thông báo có đường dẫn tới nơi phát ra (link), bấm vào là mở đúng trang.
--   3. Manager mở lại điểm danh cho buổi quá hạn 48 giờ mà coach chưa chốt (attendance_reopened_until).
--   4. Bỏ quyền lợi "số buổi được hoàn khi rời lớp sớm" của gói hội viên.

BEGIN;

ALTER TABLE facility_rooms   ADD COLUMN IF NOT EXISTS archived_at timestamptz;
ALTER TABLE sport_categories ADD COLUMN IF NOT EXISTS archived_at timestamptz;

ALTER TABLE notifications ADD COLUMN IF NOT EXISTS link varchar(200);
-- Thông báo cũ chưa có link: điền theo vai trò người nhận và loại thông báo (cùng luật NotificationService.defaultLink)
UPDATE notifications n
SET link = CASE ro.code
        WHEN 'MEMBER' THEN CASE n.type WHEN 'PAYMENT' THEN '/member?tab=payments' WHEN 'MEMBERSHIP' THEN '/member?tab=subscription'
                                        WHEN 'BOOKING' THEN '/member?tab=slots' ELSE '/member?tab=classes' END
        WHEN 'COACH' THEN '/coach?view=schedule'
        WHEN 'MANAGER' THEN CASE n.type WHEN 'BOOKING' THEN '/manager?tab=coachslots' WHEN 'PAYMENT' THEN '/manager?tab=refunds'
                                         ELSE '/manager?tab=classes' END
        WHEN 'RECEPTIONIST' THEN '/receptionist?view=desk'
    END
FROM users u JOIN roles ro ON ro.id = u.role_id
WHERE u.id = n.recipient_id AND n.link IS NULL;

ALTER TABLE class_sessions ADD COLUMN IF NOT EXISTS attendance_reopened_until timestamptz;

ALTER TABLE membership_packages DROP CONSTRAINT IF EXISTS ck_package_leave;
ALTER TABLE membership_packages DROP COLUMN IF EXISTS early_leave_refund_sessions;

COMMIT;
