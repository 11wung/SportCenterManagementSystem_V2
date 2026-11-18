-- v3_23: màn hình học bù hiện mọi ngày giờ trống; hệ thống chỉ "gợi ý" một số ngày tốt nhất. Chạy lại được. Áp dụng sau v3_22.
--   makeup.proposal_count: số ngày được gợi ý (trước đây là số ngày đề xuất tối đa, 3).

BEGIN;

UPDATE system_settings
SET value = '5',
    description = 'Số ngày bù hệ thống gợi ý cho manager, mỗi ngày một giờ (BR-MKP-02); manager vẫn xem và chọn được mọi ngày giờ trống',
    updated_at = now()
WHERE key = 'makeup.proposal_count';

COMMIT;
