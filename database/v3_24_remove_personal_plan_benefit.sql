-- v3_24: bỏ quyền lợi "bài tập riêng từ HLV" của gói thành viên. Chạy lại được. Áp dụng sau v3_23.
--   Xóa cột membership_packages.has_personal_plan, bỏ khóa has_personal_plan trong quyền lợi đã chốt của các gói đã mua,
--   và bỏ cụm "bài tập riêng từ coach" khỏi mô tả gói. Không hoàn tác được (cột bị xóa).
-- Chạy SAU khi triển khai BE mới: BE cũ còn đọc cột này.

BEGIN;

UPDATE member_subscriptions SET benefits_snapshot = benefits_snapshot - 'has_personal_plan'
WHERE benefits_snapshot ? 'has_personal_plan';

UPDATE membership_packages
SET description = upper(left(t.d, 1)) || substr(t.d, 2)
FROM (SELECT id, replace(replace(description, 'Bài tập riêng từ coach, ', ''), ', bài tập riêng từ coach', '') AS d
      FROM membership_packages) t
WHERE t.id = membership_packages.id AND membership_packages.description ILIKE '%bài tập riêng từ coach%';

ALTER TABLE membership_packages DROP COLUMN IF EXISTS has_personal_plan;

COMMIT;
