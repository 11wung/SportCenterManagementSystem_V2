-- Data demo nhỏ: ~21 tài khoản, 9 lớp, phủ mọi trạng thái chính để demo cả 4 vai trò. Chạy lại được.
-- Áp dụng lên database đã nạp database/schema_v3.sql và các file v3_04 đến v3_16.
-- CẢNH BÁO: XÓA SẠCH toàn bộ người dùng và dữ liệu giao dịch (lớp, đăng ký, thanh toán, 1-1, điểm danh, nhật ký...).
-- Giữ nguyên danh mục: roles, permissions, system_settings, môn, khung giờ, phòng, gói thành viên, course.
-- Mọi tài khoản dùng mật khẩu 12345678. Ngày tháng tính theo hôm nay (giờ VN) nên chạy lúc nào cũng "sống".
-- Email dạng @demo.local để hệ thống không gửi mail ra ngoài thật.

BEGIN;

-- ===== 1. Dọn sạch =====
-- audit_logs bị trigger chặn xóa: tắt tạm, dọn xong bật lại.

ALTER TABLE audit_logs DISABLE TRIGGER trg_audit_logs_immutable;
ALTER TABLE audit_logs DISABLE TRIGGER trg_audit_logs_no_truncate;
UPDATE system_settings SET updated_by = NULL;

TRUNCATE audit_logs, class_attendances, class_evaluations, class_registrations, class_schedules, class_sessions,
         coach_profiles, coach_slot_request_days, coach_slot_requests, coach_slots, coach_sports,
         facility_attendances, member_profiles, member_subscriptions, notifications, orders, payments, refunds,
         staff_profiles, support_requests, training_plan_exercises, training_progress_evaluations,
         training_results, workout_plans, workout_progress, sport_classes
RESTART IDENTITY;
-- users còn được system_settings.updated_by trỏ tới nên không TRUNCATE được: xóa rồi đặt lại bộ đếm id.
DELETE FROM users;
ALTER TABLE users ALTER COLUMN id RESTART WITH 1;

ALTER TABLE audit_logs ENABLE TRIGGER trg_audit_logs_immutable;
ALTER TABLE audit_logs ENABLE TRIGGER trg_audit_logs_no_truncate;

-- Lớp đang học / đã xong phải có đăng ký, mà trigger chỉ cho ghi danh khi lớp còn OPEN và chưa khai giảng: tắt tạm.
ALTER TABLE class_registrations DISABLE TRIGGER trg_class_registrations_check;

-- ===== 1b. Danh mục course =====
-- DB nạp từ schema_v3 và các file v3_xx chỉ có vài course đầu: thêm các course còn thiếu (không đụng course đã có).

INSERT INTO courses (category_id, name, description, level, total_sessions, default_tuition, prerequisite_course_id)
SELECT v.category_id, v.name, v.description, v.level, v.total_sessions, v.default_tuition, NULL
FROM (VALUES
    (6, 'Cầu lông cơ bản', 'Cầm vợt, di chuyển, giao cầu và các cú đánh cơ bản', 'BEGINNER', 8, 700000),
    (7, 'Zumba nhập môn', 'Nhảy theo nhạc Latin sôi động', 'BEGINNER', 10, 600000),
    (5, 'Boxing cơ bản', 'Đứng thế, đấm thẳng, phòng thủ và cardio', 'BEGINNER', 12, 1400000),
    (4, 'Thể lực nền tảng', 'Xây nền sức mạnh, sức bền và độ linh hoạt', 'BEGINNER', 12, 1000000),
    (2, 'Yoga nâng cao', 'Các tư thế nâng cao, thăng bằng và kiểm soát hơi thở', 'ADVANCED', 10, 1100000)
) AS v(category_id, name, description, level, total_sessions, default_tuition)
WHERE NOT EXISTS (SELECT 1 FROM courses c WHERE c.category_id = v.category_id AND c.name = v.name);

-- Yoga nâng cao yêu cầu đã học Yoga cơ bản
UPDATE courses SET prerequisite_course_id = (SELECT id FROM courses WHERE name = 'Yoga cơ bản' ORDER BY id LIMIT 1)
WHERE name = 'Yoga nâng cao' AND prerequisite_course_id IS NULL;

-- ===== 2. Hàm hỗ trợ (chỉ tồn tại trong phiên này) =====

CREATE FUNCTION pg_temp.course(p text) RETURNS bigint LANGUAGE sql AS $$ SELECT id FROM courses WHERE name = p ORDER BY id LIMIT 1 $$;
CREATE FUNCTION pg_temp.uid(p text) RETURNS bigint LANGUAGE sql AS $$ SELECT id FROM users WHERE email = p $$;
CREATE FUNCTION pg_temp.hex() RETURNS text LANGUAGE sql AS $$ SELECT md5(random()::text || clock_timestamp()::text) $$;
CREATE FUNCTION pg_temp.vn_ts(p_date date, p_time time) RETURNS timestamptz LANGUAGE sql AS
$$ SELECT (p_date + p_time) AT TIME ZONE 'Asia/Ho_Chi_Minh' $$;
CREATE SEQUENCE pg_temp.qr_seq START 1760000001;

-- Một giao dịch trả tiền (tiền mặt do lễ tân thu, hoặc QR online)
CREATE FUNCTION pg_temp.pay(p_order bigint, p_payer bigint, p_amount numeric, p_method text, p_status text, p_at timestamptz)
RETURNS bigint LANGUAGE sql AS $$
    INSERT INTO payments (order_id, payer_id, received_by, invoice_number, transaction_code, amount, payment_method,
                          status, paid_at, gateway_order_code, created_at)
    VALUES (p_order, p_payer,
            CASE WHEN p_method = 'CASH' THEN pg_temp.uid('letan@demo.local') END,
            'INV-' || substr(pg_temp.hex(), 1, 20),
            CASE WHEN p_method = 'CASH' THEN 'CASH-' ELSE 'PAYOS-' END || pg_temp.hex(),
            p_amount, p_method, p_status, p_at,
            CASE WHEN p_method = 'QR_GATEWAY' THEN nextval('pg_temp.qr_seq') END,
            p_at - interval '3 minutes')
    RETURNING id
$$;

-- Gói thành viên + đơn + (nếu đã trả) giao dịch
CREATE FUNCTION pg_temp.mk_sub(p_member bigint, p_pkg text, p_months int, p_status text, p_start date, p_end date,
                               p_method text, p_created timestamptz,
                               p_type text DEFAULT 'NEW', p_from bigint DEFAULT NULL, p_credit numeric DEFAULT 0)
RETURNS bigint LANGUAGE plpgsql AS $$
DECLARE
    pk      membership_packages;
    total   numeric;
    sid     bigint;
    ord_id     bigint;
    counter boolean := (p_method = 'CASH');
BEGIN
    SELECT * INTO pk FROM membership_packages WHERE code = p_pkg;
    total := round(pk.monthly_price * p_months
                   * (1 - (CASE WHEN p_months >= 12 THEN 25 WHEN p_months >= 6 THEN 15 WHEN p_months >= 3 THEN 8 ELSE 0 END) / 100.0))
             - p_credit;
    INSERT INTO member_subscriptions (user_id, package_id, package_name_snapshot, tier_level_snapshot, months, purchase_type,
                                      upgrade_from_subscription_id, upgrade_credit_amount, payment_expires_at, total_amount,
                                      start_date, end_date, status, benefits_snapshot, created_by, created_at)
    VALUES (p_member, pk.id, pk.name, pk.tier_level, p_months, p_type, p_from, p_credit,
            CASE WHEN p_status = 'PENDING' THEN now() + interval '1 day' END,
            total, p_start, p_end, p_status,
            jsonb_build_object('tuition_discount_pct', pk.tuition_discount_pct,
                               'one_on_one_discount_pct', pk.one_on_one_discount_pct,
                               'has_personal_plan', pk.has_personal_plan,
                               'early_leave_refund_sessions', pk.early_leave_refund_sessions,
                               'waitlist_priority', pk.waitlist_priority),
            CASE WHEN counter THEN pg_temp.uid('letan@demo.local') END, p_created)
    RETURNING id INTO sid;
    INSERT INTO orders (member_id, created_by, channel, subscription_id, list_price, total_amount, status, created_at)
    VALUES (p_member, CASE WHEN counter THEN pg_temp.uid('letan@demo.local') END,
            CASE WHEN counter THEN 'COUNTER' ELSE 'ONLINE' END, sid, total, total,
            CASE WHEN p_status = 'PENDING' THEN 'PENDING' WHEN p_status = 'CANCELED' THEN 'CANCELED' ELSE 'PAID' END,
            p_created)
    RETURNING id INTO ord_id;
    IF p_status IN ('ACTIVE', 'EXPIRED', 'UPGRADED', 'SCHEDULED') THEN
        PERFORM pg_temp.pay(ord_id, p_member, total, p_method, 'SUCCESS', p_created + interval '2 minutes');
    END IF;
    RETURN sid;
END $$;

-- Lớp + lịch tuần + các buổi (buổi trước hôm nay là DONE, từ hôm nay là SCHEDULED)
CREATE FUNCTION pg_temp.mk_class(p_name text, p_course bigint, p_coach text, p_room bigint, p_cap int, p_start date,
                                 p_dows int[], p_slot bigint, p_status text, p_gen boolean, p_desc text)
RETURNS bigint LANGUAGE plpgsql AS $$
DECLARE
    cid     bigint;
    co      courses;
    ts      time_slots;
    coach   bigint := pg_temp.uid(p_coach);
    d       date := p_start;
    n       int := 0;
    today   date := (now() AT TIME ZONE 'Asia/Ho_Chi_Minh')::date;
    dw      int;
BEGIN
    SELECT * INTO co FROM courses WHERE id = p_course;
    SELECT * INTO ts FROM time_slots WHERE id = p_slot;
    INSERT INTO sport_classes (course_id, coach_id, name, description, max_capacity, tuition, start_date,
                               registration_opens_on, status, created_at)
    VALUES (p_course, coach, p_name, p_desc, p_cap, co.default_tuition, p_start, NULL, p_status,
            pg_temp.vn_ts(LEAST(p_start, today) - 30, '09:00'))
    RETURNING id INTO cid;
    FOREACH dw IN ARRAY p_dows LOOP
        INSERT INTO class_schedules (class_id, room_id, day_of_week, start_time, end_time, time_slot_id)
        VALUES (cid, p_room, dw, ts.start_time, ts.end_time, p_slot);
    END LOOP;
    IF p_gen THEN
        WHILE n < co.total_sessions LOOP
            IF extract(isodow FROM d)::int = ANY (p_dows) THEN
                n := n + 1;
                INSERT INTO class_sessions (class_id, session_no, session_date, start_time, end_time, room_id, coach_id, status,
                                            cancel_reason, attendance_finalized_at)
                VALUES (cid, n, d, ts.start_time, ts.end_time, p_room, coach,
                        CASE WHEN p_status = 'CANCELED' THEN 'CANCELED_BY_CENTER' WHEN d < today THEN 'DONE' ELSE 'SCHEDULED' END,
                        CASE WHEN p_status = 'CANCELED' THEN 'Lớp bị hủy do không đủ học viên tối thiểu' END,
                        CASE WHEN p_status <> 'CANCELED' AND d < today THEN pg_temp.vn_ts(d, ts.end_time) + interval '30 minutes' END);
            END IF;
            d := d + 1;
        END LOOP;
    END IF;
    RETURN cid;
END $$;

-- Đăng ký lớp + đơn + giao dịch. Giảm học phí theo gói đang ACTIVE của hội viên.
CREATE FUNCTION pg_temp.mk_reg(p_class bigint, p_member text, p_status text, p_method text, p_at timestamptz)
RETURNS bigint LANGUAGE plpgsql AS $$
DECLARE
    m       bigint := pg_temp.uid(p_member);
    tui     numeric;
    pct     numeric;
    prio    int;
    disc    numeric;
    rid     bigint;
    ord_id     bigint;
    counter boolean := (p_method = 'CASH');
BEGIN
    SELECT tuition INTO tui FROM sport_classes WHERE id = p_class;
    SELECT COALESCE((benefits_snapshot ->> 'tuition_discount_pct')::numeric, 0),
           COALESCE((benefits_snapshot ->> 'waitlist_priority')::int, 0)
      INTO pct, prio FROM member_subscriptions WHERE user_id = m AND status = 'ACTIVE';
    pct := COALESCE(pct, 0);
    prio := COALESCE(prio, 0);
    disc := round(tui * pct / 100);
    INSERT INTO class_registrations (class_id, user_id, registered_at, status, registered_by, hold_expires_at, waitlist_rank)
    VALUES (p_class, m, p_at, p_status, CASE WHEN counter THEN pg_temp.uid('letan@demo.local') END,
            CASE WHEN p_status = 'PENDING_PAYMENT' THEN now() + interval '6 hours' END,
            CASE WHEN p_status = 'WAITLISTED' THEN prio ELSE 0 END)
    RETURNING id INTO rid;
    IF p_status <> 'WAITLISTED' THEN
        INSERT INTO orders (member_id, created_by, channel, registration_id, list_price, discount_kind, discount_amount,
                            total_amount, status, created_at)
        VALUES (m, CASE WHEN counter THEN pg_temp.uid('letan@demo.local') END,
                CASE WHEN counter THEN 'COUNTER' ELSE 'ONLINE' END, rid, tui,
                CASE WHEN disc > 0 THEN 'TIER' ELSE 'NONE' END, disc, tui - disc,
                CASE WHEN p_status = 'REGISTERED' THEN 'PAID' ELSE 'PENDING' END, p_at)
        RETURNING id INTO ord_id;
        IF p_status = 'REGISTERED' THEN
            PERFORM pg_temp.pay(ord_id, m, tui - disc, p_method, 'SUCCESS', p_at + interval '2 minutes');
        END IF;
    END IF;
    RETURN rid;
END $$;

-- Yêu cầu 1-1 nhiều buổi. APPROVED: giữ chỗ chờ trả tiền; PAID: đã trả, các buổi BOOKED.
-- Giá giờ chung 300.000, giảm 1-1 theo gói tính một lần trên tổng; đơn nằm ở buổi sớm nhất (BR-ONE-08).
CREATE FUNCTION pg_temp.mk_req(p_member text, p_coach text, p_cat bigint, p_room bigint, p_slot bigint, p_first date,
                               p_dows int[], p_sessions int, p_status text, p_created timestamptz, p_reject text DEFAULT NULL)
RETURNS bigint LANGUAGE plpgsql AS $$
DECLARE
    m       bigint := pg_temp.uid(p_member);
    coach   bigint := pg_temp.uid(p_coach);
    ts      time_slots;
    rid     bigint;
    d       date := p_first;
    n       int := 0;
    dw      int;
    sid     bigint;
    first_s bigint;
    pct     numeric;
    listp   numeric := 300000 * p_sessions;
    disc    numeric;
    ord_id     bigint;
    decided timestamptz := p_created + interval '2 hours';
BEGIN
    SELECT * INTO ts FROM time_slots WHERE id = p_slot;
    INSERT INTO coach_slot_requests (member_id, coach_id, category_id, time_slot_id, first_date, sessions, status, decided_by,
                                     decided_at, reject_reason, expires_at, created_at)
    VALUES (m, coach, p_cat, p_slot, p_first, p_sessions, p_status,
            CASE WHEN p_status <> 'PENDING' THEN pg_temp.uid('manager@demo.local') END,
            CASE WHEN p_status <> 'PENDING' THEN decided END,
            p_reject, p_created + interval '24 hours', p_created)
    RETURNING id INTO rid;
    FOREACH dw IN ARRAY p_dows LOOP
        INSERT INTO coach_slot_request_days (request_id, weekday, time_slot_id) VALUES (rid, dw, p_slot);
    END LOOP;
    IF p_status IN ('APPROVED', 'PAID') THEN
        WHILE n < p_sessions LOOP
            IF extract(isodow FROM d)::int = ANY (p_dows) THEN
                n := n + 1;
                INSERT INTO coach_slots (coach_id, start_at, end_at, kind, status, member_id, held_until, price, created_at,
                                         room_id, category_id, request_id)
                VALUES (coach, pg_temp.vn_ts(d, ts.start_time), pg_temp.vn_ts(d, ts.start_time) + interval '60 minutes',
                        'ONE_ON_ONE', CASE WHEN p_status = 'PAID' THEN 'BOOKED' ELSE 'HELD' END, m,
                        CASE WHEN p_status = 'APPROVED' THEN now() + interval '2 hours' END,
                        300000, decided, p_room, p_cat, rid)
                RETURNING id INTO sid;
                first_s := COALESCE(first_s, sid);
            END IF;
            d := d + 1;
        END LOOP;
        SELECT COALESCE((benefits_snapshot ->> 'one_on_one_discount_pct')::numeric, 0) INTO pct
          FROM member_subscriptions WHERE user_id = m AND status = 'ACTIVE';
        disc := round(listp * COALESCE(pct, 0) / 100);
        INSERT INTO orders (member_id, channel, coach_slot_id, list_price, discount_kind, discount_amount, total_amount,
                            status, created_at)
        VALUES (m, 'ONLINE', first_s, listp, CASE WHEN disc > 0 THEN 'TIER' ELSE 'NONE' END, disc, listp - disc,
                CASE WHEN p_status = 'PAID' THEN 'PAID' ELSE 'PENDING' END, decided)
        RETURNING id INTO ord_id;
        IF p_status = 'PAID' THEN
            PERFORM pg_temp.pay(ord_id, m, listp - disc, 'QR_GATEWAY', 'SUCCESS', decided + interval '40 minutes');
        END IF;
    END IF;
    RETURN rid;
END $$;

-- ===== 3. Nhân sự =====

DO $seed$
DECLARE
    h     text := '$2a$10$SlppGAjPyHSjLjfi09QQh.j7rdXP8NDPHOCxfPZDRYefYjUBC82ee';  -- BCrypt của 12345678
    d     date := (now() AT TIME ZONE 'Asia/Ho_Chi_Minh')::date;
    rec   record;
    c1 bigint; c2 bigint; c3 bigint; c4 bigint; c5 bigint; c6 bigint; c7 bigint; c8 bigint; c9 bigint;
    reg   bigint;
    pay   bigint;
    s1    bigint;
    s2    bigint;
    sp    bigint;
BEGIN
    -- role_id: 1 Manager, 2 Receptionist, 3 Coach, 4 Member
    INSERT INTO users (role_id, full_name, email, phone, password_hash, gender, date_of_birth, is_active, email_verified_at, created_at)
    SELECT role_id, full_name, email, phone, h, gender, dob::date, active, now(), now() - interval '90 days'
    FROM (VALUES
        (1, 'Nguyễn Hoàng Long',      'manager@demo.local',    '0901000001', 'MALE',   '1985-03-12', true),
        (2, 'Trần Thị Mai Anh',       'letan@demo.local',      '0901000002', 'FEMALE', '1996-07-21', true),
        (3, 'Lê Văn Hùng',            'hung.le@demo.local',    '0902000001', 'MALE',   '1988-01-15', true),
        (3, 'Phạm Thu Trang',         'trang.pham@demo.local', '0902000002', 'FEMALE', '1991-09-03', true),
        (3, 'Đỗ Quang Vinh',          'vinh.do@demo.local',    '0902000003', 'MALE',   '1989-05-27', true),
        (3, 'Nguyễn Hải Yến',         'yen.nguyen@demo.local', '0902000004', 'FEMALE', '1993-12-08', true),
        (3, 'Võ Minh Khôi',           'khoi.vo@demo.local',    '0902000005', 'MALE',   '1992-04-19', true),
        (4, 'Bùi Gia Hân',            'han.bui@demo.local',    '0903000001', 'FEMALE', '2003-05-14', true),
        (4, 'Đặng Minh Quân',         'quan.dang@demo.local',  '0903000002', 'MALE',   '1999-09-02', true),
        (4, 'Ngô Thanh Thảo',         'thao.ngo@demo.local',   '0903000003', 'FEMALE', '1997-02-18', true),
        (4, 'Trịnh Đức Anh',          'anh.trinh@demo.local',  '0903000004', 'MALE',   '1994-11-30', true),
        (4, 'Lý Bảo Châu',            'chau.ly@demo.local',    '0903000005', 'FEMALE', to_char(d + 3, '"1990-"MM-DD'), true),
        (4, 'Phan Quốc Việt',         'viet.phan@demo.local',  '0903000006', 'MALE',   '1992-06-25', true),
        (4, 'Hoàng Thị Kim Ngân',     'ngan.hoang@demo.local', '0903000007', 'FEMALE', '2000-08-08', true),
        (4, 'Vũ Đình Khang',          'khang.vu@demo.local',   '0903000008', 'MALE',   '1996-01-19', true),
        (4, 'Đinh Ngọc Bích',         'bich.dinh@demo.local',  '0903000009', 'FEMALE', '2001-12-03', true),
        (4, 'Cao Thái Sơn',           'son.cao@demo.local',    '0903000010', 'MALE',   '1995-04-07', true),
        (4, 'Lương Mỹ Linh',          'linh.luong@demo.local', '0903000011', 'FEMALE', '1998-10-22', true),
        (4, 'Tạ Hữu Phước',           'phuoc.ta@demo.local',   '0903000012', 'MALE',   '1993-03-15', true),
        (4, 'Mai Khánh Vy',           'vy.mai@demo.local',     '0903000013', 'FEMALE', '2002-07-29', true),
        (4, 'Dương Văn Tín',          'tin.duong@demo.local',  '0903000014', 'MALE',   '1991-09-09', false)
    ) AS t(role_id, full_name, email, phone, gender, dob, active)
    ORDER BY phone;

    INSERT INTO staff_profiles (user_id, job_title, shift_schedule) VALUES
        (pg_temp.uid('manager@demo.local'), 'Center Manager', '08:00-17:00'),
        (pg_temp.uid('letan@demo.local'),   'Receptionist',   '06:00-14:00');

    INSERT INTO coach_profiles (user_id, specialty, years_experience, certifications)
    SELECT pg_temp.uid(email), spec, yrs, cert FROM (VALUES
        ('hung.le@demo.local',    'Bơi lội',            9, 'Chứng chỉ HLV bơi lội quốc gia'),
        ('trang.pham@demo.local', 'Yoga, Zumba',        6, 'RYT-200 Yoga Alliance, Zumba Instructor'),
        ('vinh.do@demo.local',    'Boxing, Thể lực',    7, 'ACE Personal Trainer, HLV Boxing hạng II'),
        ('yen.nguyen@demo.local', 'Cầu lông, Bóng chuyền', 5, 'HLV cầu lông cấp tỉnh'),
        ('khoi.vo@demo.local',    'Bơi lội, Thể lực',   4, 'Cứu hộ bơi lội, NASM-CPT')
    ) AS t(email, spec, yrs, cert);

    -- Môn: 1 Bơi, 2 Yoga, 3 Bóng chuyền, 4 Thể lực, 5 Boxing, 6 Cầu lông, 7 Zumba
    INSERT INTO coach_sports (coach_id, category_id)
    SELECT pg_temp.uid(email), cat FROM (VALUES
        ('hung.le@demo.local', 1),
        ('trang.pham@demo.local', 2), ('trang.pham@demo.local', 7),
        ('vinh.do@demo.local', 5), ('vinh.do@demo.local', 4),
        ('yen.nguyen@demo.local', 6), ('yen.nguyen@demo.local', 3),
        ('khoi.vo@demo.local', 1), ('khoi.vo@demo.local', 4)
    ) AS t(email, cat);

    INSERT INTO member_profiles (user_id, join_date, height_cm, weight_kg, fitness_goal)
    SELECT pg_temp.uid(email), d - joined, height, weight, goal FROM (VALUES
        ('han.bui@demo.local',    1, NULL::numeric, NULL::numeric, NULL),
        ('quan.dang@demo.local',  60, 172, 68, 'Tăng cơ, giảm mỡ bụng'),
        ('thao.ngo@demo.local',   45, 160, 52, 'Bơi tốt hơn, giữ dáng'),
        ('anh.trinh@demo.local',  50, 178, 80, 'Giảm 8kg trước Tết'),
        ('chau.ly@demo.local',    80, 165, 57, 'Duy trì sức khỏe'),
        ('viet.phan@demo.local',  35, 175, 74, 'Chơi cầu lông giỏi hơn'),
        ('ngan.hoang@demo.local', 70, 158, 50, 'Linh hoạt, giảm căng thẳng'),
        ('khang.vu@demo.local',   2, 170, 72, 'Tập thể lực cho sức bền'),
        ('bich.dinh@demo.local',  8, 162, 55, 'Học cầu lông'),
        ('son.cao@demo.local',    30, 180, 85, 'Giảm cân, tập boxing'),
        ('linh.luong@demo.local', 25, 159, 53, 'Học bơi và boxing'),
        ('phuoc.ta@demo.local',   40, 174, 77, 'Cải thiện thể lực tổng hợp'),
        ('vy.mai@demo.local',     55, 163, 54, 'Yoga và nhảy Zumba'),
        ('tin.duong@demo.local',  90, 171, 70, NULL)
    ) AS t(email, joined, height, weight, goal);

    -- ===== 4. Gói thành viên =====
    -- mk_sub(hội viên, gói, số tháng, trạng thái, bắt đầu, kết thúc, cách trả, ngày tạo)

    PERFORM pg_temp.mk_sub(pg_temp.uid('quan.dang@demo.local'),  'KHOI_DONG', 1, 'ACTIVE',  d - 10, d + 20,  'CASH',       now() - interval '10 days');
    PERFORM pg_temp.mk_sub(pg_temp.uid('thao.ngo@demo.local'),   'BUT_TOC',   3, 'ACTIVE',  d - 30, d + 60,  'QR_GATEWAY', now() - interval '30 days');
    PERFORM pg_temp.mk_sub(pg_temp.uid('anh.trinh@demo.local'),  'VUOT_DINH', 1, 'ACTIVE',  d - 12, d + 18,  'QR_GATEWAY', now() - interval '12 days');
    PERFORM pg_temp.mk_sub(pg_temp.uid('chau.ly@demo.local'),    'QUAN_QUAN', 6, 'ACTIVE',  d - 40, d + 140, 'QR_GATEWAY', now() - interval '40 days');
    PERFORM pg_temp.mk_sub(pg_temp.uid('viet.phan@demo.local'),  'BUT_TOC',   1, 'ACTIVE',  d - 28, d + 2,   'CASH',       now() - interval '28 days');
    PERFORM pg_temp.mk_sub(pg_temp.uid('ngan.hoang@demo.local'), 'KHOI_DONG', 1, 'EXPIRED', d - 37, d - 7,   'CASH',       now() - interval '37 days');
    PERFORM pg_temp.mk_sub(pg_temp.uid('khang.vu@demo.local'),   'BUT_TOC',   1, 'PENDING', NULL,   NULL,    'CASH',       now() - interval '1 hour');
    PERFORM pg_temp.mk_sub(pg_temp.uid('son.cao@demo.local'),    'VUOT_DINH', 1, 'ACTIVE',  d - 9,  d + 21,  'QR_GATEWAY', now() - interval '9 days');
    PERFORM pg_temp.mk_sub(pg_temp.uid('linh.luong@demo.local'), 'BUT_TOC',   1, 'ACTIVE',  d - 6,  d + 24,  'QR_GATEWAY', now() - interval '6 days');
    PERFORM pg_temp.mk_sub(pg_temp.uid('vy.mai@demo.local'),     'KHOI_DONG', 1, 'ACTIVE',  d - 3,  d + 27,  'QR_GATEWAY', now() - interval '3 days');
    -- Phước: mua Bứt Tốc, 15 ngày sau nâng cấp lên Vượt Đỉnh. Credit = 750.000 x 15 / 30 = 375.000.
    s1 := pg_temp.mk_sub(pg_temp.uid('phuoc.ta@demo.local'), 'BUT_TOC', 1, 'UPGRADED', d - 20, d - 5, 'QR_GATEWAY', now() - interval '20 days');
    PERFORM pg_temp.mk_sub(pg_temp.uid('phuoc.ta@demo.local'), 'VUOT_DINH', 1, 'ACTIVE', d - 5, d + 25, 'QR_GATEWAY',
                           now() - interval '5 days', 'UPGRADE', s1, 375000);

    -- ===== 5. Lớp =====
    -- mk_class(tên, course, coach, phòng, sức chứa, khai giảng, thứ (1=T2..7=CN), khung giờ, trạng thái, sinh buổi?, mô tả)

    c1 := pg_temp.mk_class('Yoga cơ bản - Sáng T2/T4', pg_temp.course('Yoga cơ bản'), 'trang.pham@demo.local', 2, 12, d - 47, ARRAY[1,3], 2, 'COMPLETED', true,
                           'Làm quen các tư thế yoga cơ bản, hơi thở và thư giãn.');
    c2 := pg_temp.mk_class('Bơi cơ bản - Tối T3/T5', pg_temp.course('Bơi cơ bản'), 'hung.le@demo.local', 1, 15, d - 18, ARRAY[2,4], 7, 'IN_PROGRESS', true,
                           'Làm quen với nước, bơi sải và bơi ếch cơ bản.');
    c3 := pg_temp.mk_class('Boxing cơ bản - Chiều T2/T4/T6', pg_temp.course('Boxing cơ bản'), 'vinh.do@demo.local', 8, 12, d - 12, ARRAY[1,3,5], 6, 'IN_PROGRESS', true,
                           'Đứng thế, đấm thẳng, phòng thủ và cardio với bao cát.');
    c4 := pg_temp.mk_class('Thể lực nền tảng - Chiều T7/CN', pg_temp.course('Thể lực nền tảng'), 'khoi.vo@demo.local', 6, 15, d - 14, ARRAY[6,7], 6, 'IN_PROGRESS', true,
                           'Xây nền sức mạnh, sức bền và độ linh hoạt cho người mới.');
    c5 := pg_temp.mk_class('Cầu lông cơ bản - Sáng T3/T5', pg_temp.course('Cầu lông cơ bản'), 'yen.nguyen@demo.local', 9, 16, d + 10, ARRAY[2,4], 2, 'OPEN', true,
                           'Cầm vợt, di chuyển, giao cầu và các cú đánh cơ bản.');
    c6 := pg_temp.mk_class('Zumba nhập môn - Tối T2/T4', pg_temp.course('Zumba nhập môn'), 'trang.pham@demo.local', 10, 4, d + 9, ARRAY[1,3], 8, 'OPEN', true,
                           'Nhảy theo nhạc Latin sôi động, đốt calo nhẹ nhàng.');
    c7 := pg_temp.mk_class('Yoga nâng cao - Tối T3/T5', pg_temp.course('Yoga nâng cao'), 'trang.pham@demo.local', 3, 12, d + 17, ARRAY[2,4], 7, 'OPEN', true,
                           'Các tư thế nâng cao, thăng bằng và kiểm soát hơi thở sâu.');
    c8 := pg_temp.mk_class('Bóng chuyền cơ bản - Chiều T7', pg_temp.course('Bóng chuyền cơ bản'), 'yen.nguyen@demo.local', 4, 20, d + 21, ARRAY[6], 5, 'DRAFT', false,
                           'Chuyền bóng, đệm bóng, phát bóng và phối hợp đồng đội.');
    c9 := pg_temp.mk_class('Bơi nâng cao - Sáng T2/T4', pg_temp.course('Bơi nâng cao'), 'khoi.vo@demo.local', 1, 10, d + 2, ARRAY[1,3], 3, 'CANCELED', true,
                           'Hoàn thiện bốn kiểu bơi, kỹ thuật xuất phát và quay đầu.');

    -- Buổi 8 của Bơi cơ bản (sau hôm nay) bị trung tâm hủy vì hồ bảo trì, đã xếp buổi bù thứ 13
    UPDATE class_sessions SET status = 'CANCELED_BY_CENTER', cancel_reason = 'Hồ bơi bảo trì đột xuất'
     WHERE class_id = c2 AND session_no = 8;
    INSERT INTO class_sessions (class_id, session_no, session_date, start_time, end_time, room_id, coach_id, status, is_makeup)
    SELECT c2, 13, d + 20, start_time, end_time, room_id, coach_id, 'SCHEDULED', true
      FROM class_sessions WHERE class_id = c2 AND session_no = 8;

    -- ===== 6. Đăng ký lớp =====
    -- mk_reg(lớp, hội viên, trạng thái, cách trả, thời điểm)

    -- Yoga cơ bản (đã xong)
    FOR rec IN SELECT * FROM (VALUES ('vy.mai@demo.local','QR_GATEWAY'),('thao.ngo@demo.local','QR_GATEWAY'),('quan.dang@demo.local','CASH'),
                                   ('ngan.hoang@demo.local','CASH'),('viet.phan@demo.local','QR_GATEWAY')) AS t(email, method) LOOP
        PERFORM pg_temp.mk_reg(c1, rec.email, 'REGISTERED', rec.method, pg_temp.vn_ts(d - 57, '10:00'));
    END LOOP;
    -- Bơi cơ bản (đang học)
    FOR rec IN SELECT * FROM (VALUES ('thao.ngo@demo.local','QR_GATEWAY'),('anh.trinh@demo.local','QR_GATEWAY'),('chau.ly@demo.local','QR_GATEWAY'),
                                   ('viet.phan@demo.local','QR_GATEWAY'),('son.cao@demo.local','QR_GATEWAY'),('phuoc.ta@demo.local','QR_GATEWAY')) AS t(email, method) LOOP
        PERFORM pg_temp.mk_reg(c2, rec.email, 'REGISTERED', rec.method, pg_temp.vn_ts(d - 26, '10:00'));
    END LOOP;
    -- Boxing cơ bản (đang học)
    FOR rec IN SELECT * FROM (VALUES ('quan.dang@demo.local','CASH'),('anh.trinh@demo.local','QR_GATEWAY'),('phuoc.ta@demo.local','QR_GATEWAY'),
                                   ('linh.luong@demo.local','QR_GATEWAY'),('vy.mai@demo.local','CASH')) AS t(email, method) LOOP
        PERFORM pg_temp.mk_reg(c3, rec.email, 'REGISTERED', rec.method, pg_temp.vn_ts(d - 20, '10:00'));
    END LOOP;
    -- Thể lực nền tảng (đang học)
    FOR rec IN SELECT * FROM (VALUES ('quan.dang@demo.local','CASH'),('thao.ngo@demo.local','QR_GATEWAY'),('chau.ly@demo.local','QR_GATEWAY'),
                                   ('son.cao@demo.local','QR_GATEWAY')) AS t(email, method) LOOP
        PERFORM pg_temp.mk_reg(c4, rec.email, 'REGISTERED', rec.method, pg_temp.vn_ts(d - 22, '10:00'));
    END LOOP;
    -- Cầu lông cơ bản (đang mở, còn chỗ): 2 đã đăng ký, Bích đang giữ chỗ chờ thanh toán
    PERFORM pg_temp.mk_reg(c5, 'viet.phan@demo.local',  'REGISTERED',      'QR_GATEWAY', now() - interval '3 days');
    PERFORM pg_temp.mk_reg(c5, 'linh.luong@demo.local', 'REGISTERED',      'QR_GATEWAY', now() - interval '2 days');
    PERFORM pg_temp.mk_reg(c5, 'bich.dinh@demo.local',  'PENDING_PAYMENT', 'QR_GATEWAY', now() - interval '5 minutes');
    -- Zumba nhập môn (đầy 4/4): Sơn và Việt trong danh sách chờ, hạng chờ lấy từ quyền lợi gói
    FOR rec IN SELECT * FROM (VALUES ('quan.dang@demo.local','CASH'),('thao.ngo@demo.local','QR_GATEWAY'),('chau.ly@demo.local','QR_GATEWAY'),
                                   ('vy.mai@demo.local','QR_GATEWAY')) AS t(email, method) LOOP
        PERFORM pg_temp.mk_reg(c6, rec.email, 'REGISTERED', rec.method, now() - interval '4 days');
    END LOOP;
    PERFORM pg_temp.mk_reg(c6, 'viet.phan@demo.local', 'WAITLISTED', 'QR_GATEWAY', now() - interval '2 days');
    PERFORM pg_temp.mk_reg(c6, 'son.cao@demo.local',   'WAITLISTED', 'QR_GATEWAY', now() - interval '1 day');
    -- Yoga nâng cao: Vy đã học xong Yoga cơ bản nên đủ điều kiện
    PERFORM pg_temp.mk_reg(c7, 'vy.mai@demo.local', 'REGISTERED', 'CASH', now() - interval '1 day');

    -- Bơi nâng cao (đã hủy do thiếu học viên): Ngân đã được hoàn tiền, Việt đang chờ hoàn (quá 3 ngày nên tô đỏ)
    reg := pg_temp.mk_reg(c9, 'ngan.hoang@demo.local', 'REGISTERED', 'CASH', now() - interval '9 days');
    UPDATE class_registrations SET status = 'CANCELED', canceled_at = now() - interval '6 days' WHERE id = reg;
    UPDATE orders SET status = 'CANCELED' WHERE registration_id = reg;
    UPDATE payments SET status = 'REFUNDED' WHERE order_id = (SELECT id FROM orders WHERE registration_id = reg)
        RETURNING id INTO pay;
    INSERT INTO refunds (payment_id, amount, reason, method, status, requested_at, processed_by, processed_at)
    SELECT p.id, p.amount, 'Lớp bị hủy do không đủ học viên', 'CASH', 'DONE', now() - interval '6 days',
           pg_temp.uid('letan@demo.local'), now() - interval '5 days' FROM payments p WHERE p.id = pay;

    reg := pg_temp.mk_reg(c9, 'viet.phan@demo.local', 'REGISTERED', 'QR_GATEWAY', now() - interval '8 days');
    UPDATE class_registrations SET status = 'CANCELED', canceled_at = now() - interval '4 days' WHERE id = reg;
    UPDATE orders SET status = 'CANCELED' WHERE registration_id = reg;
    UPDATE payments SET status = 'PENDING_REFUND' WHERE order_id = (SELECT id FROM orders WHERE registration_id = reg)
        RETURNING id INTO pay;
    INSERT INTO refunds (payment_id, amount, reason, method, status, requested_at)
    SELECT p.id, p.amount, 'Lớp bị hủy do không đủ học viên', 'TRANSFER', 'PENDING', now() - interval '4 days'
      FROM payments p WHERE p.id = pay;

    -- ===== 7. Điểm danh, đánh giá, kế hoạch tập =====

    -- Điểm danh các buổi đã diễn ra (đa số có mặt, rải vài người đi trễ, vắng, có phép)
    INSERT INTO class_attendances (session_id, user_id, status, check_in_time, recorded_by, notes, recorded_at, updated_at)
    SELECT s.id, r.user_id, x.st,
           CASE WHEN x.st IN ('PRESENT', 'LATE')
                THEN pg_temp.vn_ts(s.session_date, s.start_time) + CASE x.st WHEN 'LATE' THEN interval '12 minutes' ELSE interval '-5 minutes' END END,
           s.coach_id,
           CASE x.st WHEN 'LATE' THEN 'Đến trễ 12 phút' WHEN 'EXCUSED' THEN 'Báo nghỉ có phép' WHEN 'ABSENT' THEN 'Vắng không báo' END,
           s.attendance_finalized_at, s.attendance_finalized_at
      FROM class_sessions s
      JOIN class_registrations r ON r.class_id = s.class_id AND r.status = 'REGISTERED'
      CROSS JOIN LATERAL (SELECT CASE (s.session_no * 7 + r.user_id::int) % 11
                                     WHEN 0 THEN 'ABSENT' WHEN 1 THEN 'LATE' WHEN 2 THEN 'EXCUSED' ELSE 'PRESENT' END AS st) x
     WHERE s.status = 'DONE' AND NOT s.is_makeup;

    -- Đánh giá cuối lớp Yoga cơ bản (mỗi đăng ký một đánh giá)
    INSERT INTO class_evaluations (registration_id, coach_id, grade, comment, created_at)
    SELECT r.id, pg_temp.uid('trang.pham@demo.local'),
           (ARRAY['A','B','A','C','B'])[1 + (row_number() OVER (ORDER BY r.id))::int % 5],
           (ARRAY['Tư thế chuẩn, hơi thở đều, tiến bộ rõ rệt.', 'Cần luyện thêm độ dẻo phần lưng, rất chăm chỉ.',
                  'Tập trung tốt, nên giữ nhịp tập đều đặn.', 'Đi học chưa đều, cần cố gắng hơn ở lớp sau.',
                  'Có tiến bộ ở các tư thế thăng bằng.'])[1 + (row_number() OVER (ORDER BY r.id))::int % 5],
           pg_temp.vn_ts(d - 16, '10:00')
      FROM class_registrations r WHERE r.class_id = c1;

    -- Kế hoạch tập của lớp Boxing (cả lớp) và kế hoạch riêng cho Anh (gói Vượt Đỉnh)
    INSERT INTO workout_plans (member_id, coach_id, title, description, status, start_date, end_date, class_id, objective, coach_notes, created_at)
    VALUES (NULL, pg_temp.uid('vinh.do@demo.local'), 'Kế hoạch 4 tuần: nền tảng boxing', 'Giáo án cho người mới', 'PUBLISHED',
            d - 12, d + 16, c3, 'Đứng thế, đấm thẳng và phòng thủ cơ bản', 'Nhớ quấn băng tay trước khi vào lớp', now() - interval '12 days'),
           (pg_temp.uid('anh.trinh@demo.local'), pg_temp.uid('vinh.do@demo.local'), 'Giáo án giảm mỡ riêng cho Anh', 'Kết hợp cardio và sức mạnh', 'PUBLISHED',
            d - 10, d + 18, c3, 'Giảm 8kg trong 2 tháng, giữ cơ', 'Ăn đủ đạm, ngủ trước 23h', now() - interval '10 days');
    SELECT id INTO sp FROM workout_plans WHERE member_id IS NULL AND class_id = c3;
    INSERT INTO training_plan_exercises (plan_id, position, name, instructions, sets, repetitions, duration_seconds) VALUES
        (sp, 0, 'Khởi động nhảy dây', 'Nhảy nhẹ nhàng, giữ nhịp thở', 3, NULL, 180),
        (sp, 1, 'Đấm thẳng (jab-cross)', 'Xoay hông, thu tay nhanh về thế thủ', 4, 20, NULL),
        (sp, 2, 'Di chuyển chân bước ngang', 'Giữ trọng tâm thấp, không bắt chéo chân', 3, NULL, 120),
        (sp, 3, 'Giãn cơ cuối buổi', 'Giữ mỗi tư thế 30 giây', 1, NULL, 300);
    SELECT id INTO sp FROM workout_plans WHERE member_id = pg_temp.uid('anh.trinh@demo.local');
    INSERT INTO training_plan_exercises (plan_id, position, name, instructions, sets, repetitions, duration_seconds) VALUES
        (sp, 0, 'Chạy bộ nhẹ trên máy', 'Giữ nhịp tim ở mức nói chuyện được', 1, NULL, 900),
        (sp, 1, 'Squat với tạ đòn', 'Lưng thẳng, gối không vượt mũi chân', 4, 12, NULL),
        (sp, 2, 'Plank', 'Giữ thân thẳng, siết bụng', 3, NULL, 60);

    -- Kết quả tập của buổi Boxing đầu tiên cho những người có mặt
    INSERT INTO training_results (attendance_id, exercise_id, activity_key, activity_name, prescription, actual_sets,
                                  actual_repetitions, actual_duration_seconds, performance, completion_status, notes, recorded_by, recorded_at, updated_at)
    SELECT a.id, e.id, 'exercise:' || e.id, e.name,
           jsonb_build_object('planId', e.plan_id, 'version', 1, 'exercise',
                              jsonb_build_object('id', e.id, 'name', e.name, 'instructions', e.instructions, 'sets', e.sets,
                                                 'repetitions', e.repetitions, 'durationSeconds', e.duration_seconds)),
           e.sets, e.repetitions, e.duration_seconds,
           CASE WHEN e.position = 1 THEN 'Đấm đúng kỹ thuật, cần xoay hông nhiều hơn' END,
           CASE WHEN (a.user_id + e.position) % 5 = 0 THEN 'PARTIAL' ELSE 'COMPLETED' END,
           NULL, a.recorded_by, a.recorded_at, a.recorded_at
      FROM class_attendances a
      JOIN class_sessions s ON s.id = a.session_id AND s.class_id = c3 AND s.session_no = 1
      JOIN training_plan_exercises e ON e.plan_id = (SELECT id FROM workout_plans WHERE member_id IS NULL AND class_id = c3)
     WHERE a.status IN ('PRESENT', 'LATE');

    -- Đánh giá tiến độ định kỳ cho 2 học viên Boxing
    INSERT INTO training_progress_evaluations (class_id, member_id, coach_id, evaluation_date, period_start, period_end, summary,
                                               rating, strengths, improvements, recommendations, feedback)
    VALUES (c3, pg_temp.uid('anh.trinh@demo.local'), pg_temp.uid('vinh.do@demo.local'), d - 5, d - 12, d - 6,
            'Tuần đầu làm quen tốt, đi học đầy đủ.', 4, 'Thể lực nền tốt, học nhanh', 'Cần xoay hông khi đấm', 'Tập thêm bài chân bước ngang ở nhà', NULL),
           (c3, pg_temp.uid('quan.dang@demo.local'), pg_temp.uid('vinh.do@demo.local'), d - 5, d - 12, d - 6,
            'Có tinh thần nhưng còn lúng túng thế đứng.', 3, 'Chăm chỉ, ham học', 'Giữ thế thủ tay chưa ổn', 'Soi gương luyện thế đứng 10 phút mỗi ngày', NULL);

    -- ===== 8. Yêu cầu 1-1 =====
    -- mk_req(hội viên, coach, môn, phòng, khung giờ, buổi đầu, thứ, số buổi, trạng thái, tạo lúc)
    -- Khung giờ: 2 = 08:00, 3 = 10:00, 4 = 12:00, 5 = 14:00. Phòng: 2 Yoga 1, 8 Boxing, 1 Hồ bơi.

    -- Chờ manager duyệt (còn trong hạn 24 giờ)
    PERFORM pg_temp.mk_req('linh.luong@demo.local', 'hung.le@demo.local', 1, 1, 3, d + 4, ARRAY[3], 3, 'PENDING', now() - interval '2 hours');
    -- Đã duyệt, các buổi đang giữ chỗ chờ member trả tiền
    PERFORM pg_temp.mk_req('phuoc.ta@demo.local', 'vinh.do@demo.local', 5, 8, 3, d + 5, ARRAY[4], 4, 'APPROVED', now() - interval '3 hours');
    -- Đã trả: Anh học Yoga 1-1 (1 buổi đã qua, buổi tiếp theo thứ Sáu tuần sau)
    PERFORM pg_temp.mk_req('anh.trinh@demo.local', 'trang.pham@demo.local', 2, 2, 4, d - 8, ARRAY[5], 3, 'PAID', now() - interval '11 days');
    -- Đã trả: Châu học Boxing 1-1 mỗi tuần 2 buổi, 6 buổi
    PERFORM pg_temp.mk_req('chau.ly@demo.local', 'vinh.do@demo.local', 5, 8, 5, d - 4, ARRAY[2,5], 6, 'PAID', now() - interval '8 days');
    -- Bị từ chối
    PERFORM pg_temp.mk_req('khang.vu@demo.local', 'hung.le@demo.local', 1, 1, 7, d + 9, ARRAY[1], 2, 'REJECTED', now() - interval '1 day',
                           'Coach kín lịch khung giờ này, bạn vui lòng chọn khung giờ khác.');
    -- Buổi cuối của Châu: coach nghỉ nên chuyển sang "chờ xếp lại", member tự chọn giờ mới
    UPDATE coach_slots SET needs_reschedule = true
     WHERE id = (SELECT max(id) FROM coach_slots WHERE member_id = pg_temp.uid('chau.ly@demo.local'));

    -- ===== 9. Ra vào trung tâm, hỗ trợ, thông báo, nhật ký =====

    INSERT INTO facility_attendances (user_id, check_in_time, check_out_time, recorded_by, notes)
    SELECT pg_temp.uid(email), now() - ago_in, CASE WHEN ago_out IS NOT NULL THEN now() - ago_out END,
           pg_temp.uid('letan@demo.local'), note
      FROM (VALUES
        ('quan.dang@demo.local',  interval '3 hours',  NULL::interval,    NULL),
        ('anh.trinh@demo.local',  interval '2 hours',  NULL::interval,    'Tập khu tạ tự do'),
        ('thao.ngo@demo.local',   interval '4 hours',  interval '2 hours', NULL),
        ('chau.ly@demo.local',    interval '1 day 3 hours', interval '1 day 1 hour', NULL),
        ('son.cao@demo.local',    interval '1 day 5 hours', interval '1 day 3 hours', NULL),
        ('linh.luong@demo.local', interval '2 days 2 hours', interval '2 days', NULL),
        ('viet.phan@demo.local',  interval '2 days 4 hours', interval '2 days 2 hours', NULL)
      ) AS t(email, ago_in, ago_out, note);

    INSERT INTO support_requests (member_id, handled_by, subject, content, status, created_at, resolved_at)
    VALUES (pg_temp.uid('han.bui@demo.local'), NULL, 'Hỏi cách đăng ký gói', 'Mình mới tham gia, nên chọn gói nào để vừa tập tự do vừa học bơi?', 'OPEN', now() - interval '1 hour', NULL),
           (pg_temp.uid('quan.dang@demo.local'), pg_temp.uid('letan@demo.local'), 'Tủ đồ số 12 bị kẹt khóa', 'Tủ số 12 khu nam không mở được, nhờ trung tâm kiểm tra.', 'IN_PROGRESS', now() - interval '5 hours', NULL),
           (pg_temp.uid('thao.ngo@demo.local'), pg_temp.uid('letan@demo.local'), 'Đổi giờ gặp coach', 'Mình muốn hỏi cách dời lịch 1-1 sang tuần sau.', 'RESOLVED', now() - interval '2 days', now() - interval '2 days' + interval '30 minutes'),
           (pg_temp.uid('ngan.hoang@demo.local'), pg_temp.uid('letan@demo.local'), 'Gia hạn gói Khởi Động', 'Gói của mình hết hạn tuần trước, muốn gia hạn trả tiền mặt.', 'CLOSED', now() - interval '6 days', now() - interval '5 days');

    INSERT INTO notifications (recipient_id, sender_id, type, title, content, is_read, created_at)
    VALUES (pg_temp.uid('viet.phan@demo.local'),  NULL, 'MEMBERSHIP', 'Gói Bứt Tốc sắp hết hạn', 'Gói của bạn còn 2 ngày. Gia hạn sớm để không gián đoạn việc tập luyện.', false, now() - interval '3 hours'),
           (pg_temp.uid('viet.phan@demo.local'),  NULL, 'PAYMENT', 'Lớp Bơi nâng cao bị hủy, chờ hoàn tiền', 'Lớp bị hủy do không đủ học viên. Lễ tân sẽ hoàn lại học phí cho bạn.', false, now() - interval '4 days'),
           (pg_temp.uid('ngan.hoang@demo.local'), NULL, 'PAYMENT', 'Đã hoàn tiền lớp Bơi nâng cao', 'Trung tâm đã hoàn học phí cho bạn bằng tiền mặt.', true, now() - interval '5 days'),
           (pg_temp.uid('son.cao@demo.local'),    NULL, 'WAITLIST', 'Bạn đang trong danh sách chờ lớp Zumba', 'Khi có chỗ trống, hệ thống sẽ mời bạn theo thứ tự ưu tiên.', false, now() - interval '1 day'),
           (pg_temp.uid('bich.dinh@demo.local'),  NULL, 'BOOKING', 'Đang giữ chỗ lớp Cầu lông cơ bản', 'Vui lòng thanh toán trước khi hết hạn giữ chỗ.', false, now() - interval '5 minutes'),
           (pg_temp.uid('thao.ngo@demo.local'),   NULL, 'MAKEUP', 'Buổi Bơi bị hủy, đã xếp buổi bù', 'Buổi ngày ' || to_char(d + 5, 'DD/MM') || ' bị hủy do hồ bảo trì. Buổi bù vào ngày ' || to_char(d + 20, 'DD/MM') || '.', false, now() - interval '2 days'),
           (pg_temp.uid('anh.trinh@demo.local'),  pg_temp.uid('vinh.do@demo.local'), 'WORKOUT_PLAN', 'Giáo án mới: giảm mỡ riêng cho Anh', 'Coach đã đăng giáo án riêng cho bạn, xem trong mục Tập luyện.', false, now() - interval '10 days'),
           (pg_temp.uid('phuoc.ta@demo.local'),   NULL, 'BOOKING', 'Yêu cầu 1-1 đã được duyệt', 'Vui lòng thanh toán trong 3 giờ để giữ lịch.', false, now() - interval '1 hour'),
           (pg_temp.uid('quan.dang@demo.local'),  pg_temp.uid('vinh.do@demo.local'), 'ANNOUNCEMENT', 'Mang theo băng tay', 'Cả lớp Boxing nhớ mang băng tay từ buổi tới nhé.', true, now() - interval '3 days'),
           (pg_temp.uid('linh.luong@demo.local'), NULL, 'BOOKING', 'Đã gửi yêu cầu 1-1', 'Yêu cầu của bạn đang chờ quản lý duyệt, thường trong vòng 24 giờ.', false, now() - interval '2 hours');

    INSERT INTO audit_logs (user_id, action, resource, resource_id, payload_diff, created_at)
    VALUES (pg_temp.uid('manager@demo.local'), 'CLASS_CANCELED', 'sport_class', c9::text, '{"reason": "Không đủ học viên tối thiểu"}', now() - interval '4 days'),
           (pg_temp.uid('letan@demo.local'),   'REFUND_COMPLETED', 'refund', '1', '{"method": "CASH"}', now() - interval '5 days'),
           (pg_temp.uid('manager@demo.local'), 'COACH_REQUEST_APPROVED', 'coach_slot_request', '2', NULL, now() - interval '3 hours'),
           (pg_temp.uid('vinh.do@demo.local'), 'TRAINING_PLAN_CREATE', 'workout_plans', '1', NULL, now() - interval '12 days'),
           (pg_temp.uid('vinh.do@demo.local'), 'TRAINING_ATTENDANCE_SAVE', 'class_sessions', '1', NULL, now() - interval '11 days'),
           (pg_temp.uid('vinh.do@demo.local'), 'TRAINING_EVALUATION_SAVE', 'training_progress_evaluations', '1', NULL, now() - interval '5 days');
END
$seed$;

ALTER TABLE class_registrations ENABLE TRIGGER trg_class_registrations_check;

COMMIT;
