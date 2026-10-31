-- Suất 1-1 có môn và phòng. Cột để trống được: suất cũ chưa có phòng vẫn hiển thị và đặt được.
-- Hủy 1-1 chỉ được hoàn tiền khi còn ít nhất 48 giờ (2 ngày) trước giờ học.
-- Chạy lại được. Áp dụng lên database đã nạp database/schema_v3.sql và các file v3_04 đến v3_07.

BEGIN;

-- BR-MKP-07 bị bỏ: học viên không được rời lớp giữa chừng vì trung tâm hủy nhiều buổi; từng buổi đã được hoàn hoặc học bù
DELETE FROM system_settings WHERE key = 'makeup.leave_threshold_pct';

UPDATE system_settings SET value = '48',
    description = 'Hủy 1-1 trước giờ học tối thiểu bao nhiêu giờ thì hoàn tiền (BR-ONE-04)'
WHERE key = 'one_on_one.refund_before_hours';

ALTER TABLE coach_slots ADD COLUMN IF NOT EXISTS room_id bigint REFERENCES facility_rooms(id);
ALTER TABLE coach_slots ADD COLUMN IF NOT EXISTS category_id bigint REFERENCES sport_categories(id);

-- Có phòng thì phải có môn, và ngược lại
ALTER TABLE coach_slots DROP CONSTRAINT IF EXISTS ck_slot_room_sport;
ALTER TABLE coach_slots ADD CONSTRAINT ck_slot_room_sport CHECK ((room_id IS NULL) = (category_id IS NULL));

-- Hai suất không dùng cùng một phòng chồng giờ (cùng khoảng nghỉ 15 phút như coach)
ALTER TABLE coach_slots DROP CONSTRAINT IF EXISTS ex_slot_room_overlap;
ALTER TABLE coach_slots ADD CONSTRAINT ex_slot_room_overlap
    EXCLUDE USING gist (room_id WITH =, tsrange(timezone('UTC', start_at), timezone('UTC', end_at) + interval '15 minutes') WITH &&)
    WHERE (room_id IS NOT NULL AND status <> 'CANCELED');

-- Suất 1-1 và buổi học không dùng chung phòng cùng giờ: kiểm tra ở cả hai trigger
CREATE OR REPLACE FUNCTION public.trg_check_coach_slot() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.status IN ('HELD', 'BOOKED') THEN
            RAISE EXCEPTION 'BR-ONE-01: khung % đã có người đặt, không được xóa', OLD.id;
        END IF;
        RETURN OLD;
    END IF;

    IF TG_OP = 'UPDATE' AND OLD.status IN ('HELD', 'BOOKED')
       AND (NEW.coach_id <> OLD.coach_id OR NEW.start_at <> OLD.start_at OR NEW.end_at <> OLD.end_at
            OR NEW.room_id IS DISTINCT FROM OLD.room_id OR NEW.category_id IS DISTINCT FROM OLD.category_id) THEN
        RAISE EXCEPTION 'BR-ONE-01: khung % đã có người đặt, không được sửa giờ hoặc đổi coach', OLD.id;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM users u JOIN roles r ON r.id = u.role_id
        WHERE u.id = NEW.coach_id AND r.code = 'COACH') THEN
        RAISE EXCEPTION 'coach_id % không thuộc user có vai trò COACH', NEW.coach_id;
    END IF;

    IF NEW.status <> 'CANCELED' AND EXISTS (
        SELECT 1 FROM class_sessions cs
        WHERE cs.coach_id = NEW.coach_id AND cs.status IN ('SCHEDULED', 'DONE')
          AND tstzrange(((cs.session_date + cs.start_time) AT TIME ZONE 'Asia/Ho_Chi_Minh'),
                        ((cs.session_date + cs.end_time) AT TIME ZONE 'Asia/Ho_Chi_Minh') + interval '15 minutes')
           && tstzrange(NEW.start_at, NEW.end_at + interval '15 minutes')) THEN
        RAISE EXCEPTION 'BR-SCH-01: khung coach_slots trùng buổi học của coach %', NEW.coach_id;
    END IF;

    IF NEW.room_id IS NOT NULL AND NEW.status <> 'CANCELED' THEN
        IF NOT EXISTS (SELECT 1 FROM coach_sports cs WHERE cs.coach_id = NEW.coach_id AND cs.category_id = NEW.category_id) THEN
            RAISE EXCEPTION 'BR-SCH-04: coach % không dạy bộ môn của suất 1-1', NEW.coach_id;
        END IF;
        IF NOT EXISTS (SELECT 1 FROM room_sports rs WHERE rs.room_id = NEW.room_id AND rs.category_id = NEW.category_id) THEN
            RAISE EXCEPTION 'BR-SCH-04: phòng % không thuộc bộ môn của suất 1-1', NEW.room_id;
        END IF;
        IF (TG_OP = 'INSERT' OR NEW.room_id IS DISTINCT FROM OLD.room_id)
           AND NOT EXISTS (SELECT 1 FROM facility_rooms r WHERE r.id = NEW.room_id AND r.maintenance_status = 'AVAILABLE') THEN
            RAISE EXCEPTION 'BR-SCH-02: phòng % đang bảo trì hoặc đóng cửa', NEW.room_id;
        END IF;
        IF EXISTS (
            SELECT 1 FROM class_sessions cs
            WHERE cs.room_id = NEW.room_id AND cs.status IN ('SCHEDULED', 'DONE')
              AND tstzrange(((cs.session_date + cs.start_time) AT TIME ZONE 'Asia/Ho_Chi_Minh'),
                            ((cs.session_date + cs.end_time) AT TIME ZONE 'Asia/Ho_Chi_Minh') + interval '15 minutes')
               && tstzrange(NEW.start_at, NEW.end_at + interval '15 minutes')) THEN
            RAISE EXCEPTION 'BR-SCH-02: phòng % đã có buổi học trong khung giờ này', NEW.room_id;
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION public.trg_check_class_session() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    v_sport bigint;
    v_cap   integer;
    v_room  record;
    v_start timestamptz;
    v_end   timestamptz;
BEGIN
    SELECT co.category_id, c.max_capacity INTO v_sport, v_cap
    FROM sport_classes c JOIN courses co ON co.id = c.course_id
    WHERE c.id = NEW.class_id;

    IF NOT EXISTS (
        SELECT 1 FROM users u JOIN roles r ON r.id = u.role_id
        WHERE u.id = NEW.coach_id AND r.code = 'COACH') THEN
        RAISE EXCEPTION 'coach_id % không thuộc user có vai trò COACH', NEW.coach_id;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM coach_sports cs WHERE cs.coach_id = NEW.coach_id AND cs.category_id = v_sport) THEN
        RAISE EXCEPTION 'BR-SCH-04: coach % không dạy bộ môn của class', NEW.coach_id;
    END IF;

    SELECT capacity, maintenance_status INTO v_room FROM facility_rooms WHERE id = NEW.room_id;
    IF NOT EXISTS (SELECT 1 FROM room_sports rs WHERE rs.room_id = NEW.room_id AND rs.category_id = v_sport) THEN
        RAISE EXCEPTION 'BR-SCH-04: phòng % không thuộc bộ môn của class', NEW.room_id;
    END IF;
    IF v_cap > v_room.capacity THEN
        RAISE EXCEPTION 'BR-CLS-02: sức chứa class (%) vượt sức chứa phòng (%)', v_cap, v_room.capacity;
    END IF;

    IF NEW.status IN ('SCHEDULED', 'PROPOSED') AND v_room.maintenance_status <> 'AVAILABLE' THEN
        RAISE EXCEPTION 'BR-SCH-02: phòng % đang bảo trì hoặc đóng cửa', NEW.room_id;
    END IF;

    IF NEW.status IN ('SCHEDULED', 'DONE') THEN
        v_start := (NEW.session_date + NEW.start_time) AT TIME ZONE 'Asia/Ho_Chi_Minh';
        v_end   := (NEW.session_date + NEW.end_time) AT TIME ZONE 'Asia/Ho_Chi_Minh';
        IF EXISTS (
            SELECT 1 FROM coach_slots s
            WHERE s.coach_id = NEW.coach_id AND s.status <> 'CANCELED'
              AND tstzrange(s.start_at, s.end_at + interval '15 minutes')
               && tstzrange(v_start, v_end + interval '15 minutes')) THEN
            RAISE EXCEPTION 'BR-SCH-01: buổi học trùng khung coach_slots của coach %', NEW.coach_id;
        END IF;
        IF EXISTS (
            SELECT 1 FROM coach_slots s
            WHERE s.room_id = NEW.room_id AND s.status <> 'CANCELED'
              AND tstzrange(s.start_at, s.end_at + interval '15 minutes')
               && tstzrange(v_start, v_end + interval '15 minutes')) THEN
            RAISE EXCEPTION 'BR-SCH-02: phòng % đã có suất 1-1 trong khung giờ này', NEW.room_id;
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

COMMIT;
