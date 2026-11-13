-- v3_19: Gói 1-1. Buổi 1-1 không còn tính tiền theo giờ: manager tạo gói (môn, số buổi, giá trọn gói, mục tiêu),
-- member chọn gói rồi chọn coach và lịch tuần. Mỗi khung giờ đã chọn là một buổi của gói.
-- Một thứ trong tuần chọn được nhiều khung giờ (ví dụ T3 7:00 và T3 18:00, hoặc hai khung liền nhau để học dài hơn).
-- Chạy lại được. Áp dụng sau v3_18.

BEGIN;

CREATE TABLE IF NOT EXISTS one_on_one_packages (
    id          bigserial PRIMARY KEY,
    category_id bigint        NOT NULL REFERENCES sport_categories(id),
    name        varchar(150)  NOT NULL,
    description varchar(1000),
    sessions    smallint      NOT NULL,
    price       numeric(12,0) NOT NULL,
    is_active   boolean       NOT NULL DEFAULT true,
    created_at  timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT ck_one_on_one_package_sessions CHECK (sessions BETWEEN 1 AND 60),
    CONSTRAINT ck_one_on_one_package_price CHECK (price > 0),
    CONSTRAINT uq_one_on_one_package_name UNIQUE (category_id, name)
);

-- Yêu cầu 1-1 mua theo gói nào. Yêu cầu cũ (tính theo giờ) để trống
ALTER TABLE coach_slot_requests ADD COLUMN IF NOT EXISTS package_id bigint REFERENCES one_on_one_packages(id);

-- Hội viên được nhờ trung tâm xếp coach: coach trống cho tới khi manager duyệt và chọn coach
ALTER TABLE coach_slot_requests ALTER COLUMN coach_id DROP NOT NULL;
ALTER TABLE coach_slot_requests DROP CONSTRAINT IF EXISTS ck_request_coach_decided;
ALTER TABLE coach_slot_requests ADD CONSTRAINT ck_request_coach_decided
    CHECK (coach_id IS NOT NULL OR status IN ('PENDING', 'REJECTED', 'CANCELED', 'EXPIRED'));

-- Một thứ được nhiều khung giờ: bỏ "mỗi thứ một dòng", chỉ cấm trùng đúng cặp (thứ, khung giờ)
ALTER TABLE coach_slot_request_days DROP CONSTRAINT IF EXISTS uq_request_day;
ALTER TABLE coach_slot_request_days DROP CONSTRAINT IF EXISTS uq_request_day_slot;
ALTER TABLE coach_slot_request_days ADD CONSTRAINT uq_request_day_slot UNIQUE (request_id, weekday, time_slot_id);

-- Gói mẫu cho mỗi môn để demo: 8 buổi (giảm 5% so với 300.000đ/buổi) và 16 buổi (giảm 10%). Manager sửa hoặc ẩn được
INSERT INTO one_on_one_packages (category_id, name, description, sessions, price)
SELECT c.id, c.name || ' 1-1 cơ bản', 'Làm quen và nắm kỹ thuật nền tảng cùng coach riêng', 8, 2280000
FROM sport_categories c
ON CONFLICT (category_id, name) DO NOTHING;

INSERT INTO one_on_one_packages (category_id, name, description, sessions, price)
SELECT c.id, c.name || ' 1-1 chuyên sâu', 'Lộ trình dài để tiến bộ rõ rệt, coach theo sát từng buổi', 16, 4320000
FROM sport_categories c
ON CONFLICT (category_id, name) DO NOTHING;

COMMIT;
