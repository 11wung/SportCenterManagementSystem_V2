-- v3.20: Đồng bộ availability trigger cho lịch Coach, lớp và slot 1-1.
-- Không thay đổi nghiệp vụ coachLeave hiện tại và không lưu ngày nghỉ.
-- Chạy một lần trên database scms_database sau v3.19.

BEGIN;

-- Dọn artifact của bản patch coach-leave trước đó (nếu đã chạy).
DROP TABLE IF EXISTS public.coach_leaves;
DROP FUNCTION IF EXISTS public.trg_check_coach_leave();

CREATE EXTENSION IF NOT EXISTS btree_gist;

-- Giữ nguyên các kiểm tra coach/phòng/môn và xung đột lịch của trigger cũ.
CREATE OR REPLACE FUNCTION trg_check_class_session() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    v_sport bigint;
    v_cap integer;
    v_room record;
    v_start timestamptz;
    v_end timestamptz;
BEGIN
    PERFORM 1 FROM users WHERE id = NEW.coach_id FOR UPDATE;

    SELECT co.category_id, c.max_capacity INTO v_sport, v_cap
    FROM sport_classes c JOIN courses co ON co.id = c.course_id
    WHERE c.id = NEW.class_id;

    IF NOT EXISTS (
        SELECT 1 FROM users u JOIN roles r ON r.id = u.role_id
        WHERE u.id = NEW.coach_id AND r.code = 'COACH'
    ) THEN
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
        v_end := (NEW.session_date + NEW.end_time) AT TIME ZONE 'Asia/Ho_Chi_Minh';
        IF EXISTS (
            SELECT 1 FROM coach_slots s
            WHERE s.coach_id = NEW.coach_id AND s.status <> 'CANCELED'
              AND tstzrange(s.start_at, s.end_at + interval '15 minutes')
               && tstzrange(v_start, v_end + interval '15 minutes')
        ) THEN
            RAISE EXCEPTION 'BR-SCH-01: buổi học trùng khung coach_slots của coach %', NEW.coach_id;
        END IF;
        IF EXISTS (
            SELECT 1 FROM coach_slots s
            WHERE s.room_id = NEW.room_id AND s.status <> 'CANCELED'
              AND tstzrange(s.start_at, s.end_at + interval '15 minutes')
               && tstzrange(v_start, v_end + interval '15 minutes')
        ) THEN
            RAISE EXCEPTION 'BR-SCH-02: phòng % đã có suất 1-1 trong khung giờ này', NEW.room_id;
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

-- Giữ nguyên các kiểm tra môn/phòng và bất biến của slot đã đặt.
CREATE OR REPLACE FUNCTION trg_check_coach_slot() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.status IN ('HELD', 'BOOKED') THEN
            RAISE EXCEPTION 'BR-ONE-01: khung % đã có người đặt, không được xóa', OLD.id;
        END IF;
        RETURN OLD;
    END IF;

    PERFORM 1 FROM users WHERE id = NEW.coach_id FOR UPDATE;

    IF TG_OP = 'UPDATE' AND OLD.status IN ('HELD', 'BOOKED')
       AND (NEW.coach_id <> OLD.coach_id OR NEW.start_at <> OLD.start_at OR NEW.end_at <> OLD.end_at
            OR NEW.room_id IS DISTINCT FROM OLD.room_id OR NEW.category_id IS DISTINCT FROM OLD.category_id) THEN
        RAISE EXCEPTION 'BR-ONE-01: khung % đã có người đặt, không được sửa giờ hoặc đổi coach', OLD.id;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM users u JOIN roles r ON r.id = u.role_id
        WHERE u.id = NEW.coach_id AND r.code = 'COACH'
    ) THEN
        RAISE EXCEPTION 'coach_id % không thuộc user có vai trò COACH', NEW.coach_id;
    END IF;

    IF NEW.status <> 'CANCELED' THEN

        IF EXISTS (
            SELECT 1 FROM class_sessions cs
            WHERE cs.coach_id = NEW.coach_id AND cs.status IN ('SCHEDULED', 'DONE')
              AND tstzrange(((cs.session_date + cs.start_time) AT TIME ZONE 'Asia/Ho_Chi_Minh'),
                            ((cs.session_date + cs.end_time) AT TIME ZONE 'Asia/Ho_Chi_Minh') + interval '15 minutes')
               && tstzrange(NEW.start_at, NEW.end_at + interval '15 minutes')
        ) THEN
            RAISE EXCEPTION 'BR-SCH-01: khung coach_slots trùng buổi học của coach %', NEW.coach_id;
        END IF;

        IF NEW.room_id IS NOT NULL THEN
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
                   && tstzrange(NEW.start_at, NEW.end_at + interval '15 minutes')
            ) THEN
                RAISE EXCEPTION 'BR-SCH-02: phòng % đã có buổi học trong khung giờ này', NEW.room_id;
            END IF;
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

-- Tái tạo trigger để đảm bảo DB cũ dùng đúng phiên bản hàm.
DROP TRIGGER IF EXISTS trg_class_sessions_check ON class_sessions;
CREATE TRIGGER trg_class_sessions_check BEFORE INSERT OR UPDATE ON class_sessions
    FOR EACH ROW EXECUTE FUNCTION trg_check_class_session();

DROP TRIGGER IF EXISTS trg_coach_slots_check ON coach_slots;
CREATE TRIGGER trg_coach_slots_check BEFORE INSERT OR DELETE OR UPDATE ON coach_slots
    FOR EACH ROW EXECUTE FUNCTION trg_check_coach_slot();

-- Xác nhận migration.
DO $$
BEGIN
    IF to_regprocedure('public.trg_check_class_session()') IS NULL
       OR to_regprocedure('public.trg_check_coach_slot()') IS NULL THEN
        RAISE EXCEPTION 'Migration v3.20 failed: availability trigger functions are missing';
    END IF;
END;
$$;

COMMIT;
