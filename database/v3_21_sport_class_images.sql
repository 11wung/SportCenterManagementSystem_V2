-- v3_21: ảnh minh họa cho môn thể thao và lớp học, video minh họa cho môn (đều tùy chọn). Chạy lại được. Áp dụng sau v3_20.
--   image_data: nội dung ảnh (tối đa 500 KB, kiểm ở backend); image_type: image/jpeg, image/png hoặc image/webp.
--   image_updated_at: để giao diện đổi đường dẫn ảnh (?v=...) khi ảnh được thay.
-- Chưa có ảnh thì để trống: giao diện dùng biểu tượng của môn; lớp không có ảnh riêng thì dùng ảnh của môn.

BEGIN;

ALTER TABLE sport_categories
    ADD COLUMN IF NOT EXISTS image_data       bytea,
    ADD COLUMN IF NOT EXISTS image_type       varchar(30),
    ADD COLUMN IF NOT EXISTS image_updated_at timestamptz;

-- Video minh họa của môn (tùy chọn, tối đa 5 MB, mp4 hoặc webm, kiểm ở backend). Môn có video tải lên thì trang chủ phát video này
ALTER TABLE sport_categories
    ADD COLUMN IF NOT EXISTS video_data       bytea,
    ADD COLUMN IF NOT EXISTS video_type       varchar(30),
    ADD COLUMN IF NOT EXISTS video_updated_at timestamptz;

ALTER TABLE sport_classes
    ADD COLUMN IF NOT EXISTS image_data       bytea,
    ADD COLUMN IF NOT EXISTS image_type       varchar(30),
    ADD COLUMN IF NOT EXISTS image_updated_at timestamptz;

COMMIT;
