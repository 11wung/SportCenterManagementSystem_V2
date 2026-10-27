-- Bỏ chữ "bảo lưu" và "đánh giá thể lực" khỏi mô tả gói (hai tính năng đã gỡ). Chạy lại được.
-- Áp dụng lên database đã nạp database/schema_v3.sql và các file v3_04 đến v3_06.

BEGIN;

UPDATE membership_packages SET description =
    'Đăng ký lớp sớm 2 ngày, giảm học phí lớp, trợ lý AI giới hạn lượt, ưu tiên hàng chờ.'
WHERE code = 'BUT_TOC';

UPDATE membership_packages SET description =
    'Bài tập riêng từ coach, giảm giá lớp và buổi 1-1, trợ lý AI và gợi ý thực đơn giới hạn lượt.'
WHERE code = 'VUOT_DINH';

UPDATE membership_packages SET description =
    'Trọn quyền lợi: trợ lý AI và gợi ý thực đơn không giới hạn, giảm sâu học phí và buổi 1-1, tặng 1 buổi 1-1 ngày sinh nhật.'
WHERE code = 'QUAN_QUAN';

COMMIT;
