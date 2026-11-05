-- Yêu cầu buổi 1-1: member xin N buổi (cùng khung giờ mỗi tuần, liên tiếp), manager duyệt, member trả tiền một lần.
-- Suất 1-1 chỉ được tạo khi manager duyệt, nên không còn suất trống bị bỏ không.
-- Chạy lại được. Áp dụng lên database đã nạp database/schema_v3.sql và các file v3_04 đến v3_11.
-- Chạy NGAY SAU khi triển khai code mới: code mới đọc các cấu hình one_on_one.* ở cuối file này.

BEGIN;

-- ===== 1. Bảng yêu cầu =====
-- PENDING: chờ manager duyệt. APPROVED: đã duyệt, các suất đang giữ chỗ chờ trả tiền. PAID: đã trả tiền.
-- REJECTED: manager từ chối. CANCELED: member hủy. EXPIRED: quá hạn duyệt hoặc quá hạn trả tiền.

CREATE TABLE IF NOT EXISTS coach_slot_requests (
    id            bigserial PRIMARY KEY,
    member_id     bigint       NOT NULL REFERENCES users(id),
    coach_id      bigint       NOT NULL REFERENCES users(id),
    category_id   bigint       NOT NULL REFERENCES sport_categories(id),
    time_slot_id  bigint       NOT NULL REFERENCES time_slots(id),
    first_date    date         NOT NULL,
    sessions      smallint     NOT NULL,
    status        varchar(10)  NOT NULL DEFAULT 'PENDING',
    decided_by    bigint       REFERENCES users(id),
    decided_at    timestamptz,
    reject_reason varchar(500),
    expires_at    timestamptz  NOT NULL,
    created_at    timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT ck_request_status CHECK (status IN ('PENDING', 'APPROVED', 'PAID', 'REJECTED', 'CANCELED', 'EXPIRED')),
    CONSTRAINT ck_request_sessions CHECK (sessions >= 1),
    CONSTRAINT ck_request_reject CHECK (status <> 'REJECTED' OR reject_reason IS NOT NULL)
);

CREATE INDEX IF NOT EXISTS idx_request_member ON coach_slot_requests (member_id);
CREATE INDEX IF NOT EXISTS idx_request_status ON coach_slot_requests (status);

-- ===== 2. Suất trỏ về yêu cầu =====
-- Bảng orders không đổi: đơn của yêu cầu trỏ vào buổi sớm nhất của yêu cầu, tổng tiền là cả chuỗi.

ALTER TABLE coach_slots ADD COLUMN IF NOT EXISTS request_id bigint REFERENCES coach_slot_requests(id);
CREATE INDEX IF NOT EXISTS idx_slot_request ON coach_slots (request_id) WHERE request_id IS NOT NULL;

-- ===== 3. Cấu hình =====

INSERT INTO system_settings (key, value, description) VALUES
    ('one_on_one.max_sessions_per_request', '12', 'Số buổi tối đa trong một yêu cầu 1-1'),
    ('one_on_one.request_min_lead_hours', '48', 'Buổi đầu của yêu cầu 1-1 phải cách lúc gửi ít nhất bấy nhiêu giờ'),
    ('one_on_one.request_expire_hours', '24', 'Yêu cầu 1-1 quá bấy nhiêu giờ chưa được duyệt thì tự hết hạn'),
    ('one_on_one.request_pay_hours', '3', 'Sau khi manager duyệt, member có bấy nhiêu giờ để trả tiền, quá hạn thì các suất được nhả')
ON CONFLICT (key) DO NOTHING;

COMMIT;
