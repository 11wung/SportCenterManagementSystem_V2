--
-- PostgreSQL database dump
--

\restrict IfHyrJOdaLHq2SFtlI4pdiFd5C1vOlJHiocQp03JawqtyCfcH1laT3njEmDwUpX

-- Dumped from database version 18.6
-- Dumped by pg_dump version 18.6

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

--
-- Name: btree_gist; Type: EXTENSION; Schema: -; Owner: -
--

CREATE EXTENSION IF NOT EXISTS btree_gist WITH SCHEMA public;


--
-- Name: set_updated_at(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.set_updated_at() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    NEW.updated_at := now();
    RETURN NEW;
END;
$$;


--
-- Name: trg_audit_immutable(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.trg_audit_immutable() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    RAISE EXCEPTION 'BR-AUD-01: audit_logs chỉ được thêm, không sửa, không xóa';
END;
$$;


--
-- Name: trg_check_class_schedule(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.trg_check_class_schedule() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    v_class_id bigint;
    v_sport    bigint;
    v_cap      integer;
    v_room     record;
BEGIN
    v_class_id := CASE WHEN TG_OP = 'DELETE' THEN OLD.class_id ELSE NEW.class_id END;

    IF EXISTS (
        SELECT 1 FROM class_registrations r
        WHERE r.class_id = v_class_id AND r.status IN ('PENDING_PAYMENT', 'REGISTERED')) THEN
        RAISE EXCEPTION 'BR-CLS-04: class đã có ghi danh, không sửa lịch hằng tuần';
    END IF;

    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;

    SELECT co.category_id, c.max_capacity INTO v_sport, v_cap
    FROM sport_classes c JOIN courses co ON co.id = c.course_id
    WHERE c.id = NEW.class_id;

    SELECT capacity, maintenance_status INTO v_room FROM facility_rooms WHERE id = NEW.room_id;
    IF v_room.maintenance_status <> 'AVAILABLE' THEN
        RAISE EXCEPTION 'BR-SCH-02: phòng % đang bảo trì hoặc đóng cửa', NEW.room_id;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM room_sports rs WHERE rs.room_id = NEW.room_id AND rs.category_id = v_sport) THEN
        RAISE EXCEPTION 'BR-SCH-04: phòng % không thuộc bộ môn của course', NEW.room_id;
    END IF;
    IF v_cap > v_room.capacity THEN
        RAISE EXCEPTION 'BR-CLS-02: max_capacity (%) vượt sức chứa phòng (%)', v_cap, v_room.capacity;
    END IF;
    RETURN NEW;
END;
$$;


--
-- Name: trg_check_class_session(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.trg_check_class_session() RETURNS trigger
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


--
-- Name: trg_check_coach_slot(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.trg_check_coach_slot() RETURNS trigger
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


--
-- Name: trg_check_registration(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.trg_check_registration() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    v_cap    integer;
    v_start  date;
    v_status varchar(15);
    v_count  integer;
BEGIN
    IF NEW.status NOT IN ('PENDING_PAYMENT', 'REGISTERED', 'WAITLISTED') THEN
        RETURN NEW;
    END IF;
    IF TG_OP = 'UPDATE' AND OLD.status = NEW.status THEN
        RETURN NEW;
    END IF;

    SELECT max_capacity, start_date, status INTO v_cap, v_start, v_status
    FROM sport_classes WHERE id = NEW.class_id FOR UPDATE;

    IF v_status <> 'OPEN' THEN
        RAISE EXCEPTION 'BR-ENR: class % không ở trạng thái mở ghi danh', NEW.class_id;
    END IF;
    IF vn_today() >= v_start THEN
        RAISE EXCEPTION 'BR-ENR-02: đã đến ngày khai giảng, không ghi danh thêm';
    END IF;

    IF NEW.status IN ('PENDING_PAYMENT', 'REGISTERED') THEN
        SELECT count(*) INTO v_count
        FROM class_registrations r
        WHERE r.class_id = NEW.class_id
          AND r.id <> COALESCE(NEW.id, -1)
          AND (r.status = 'REGISTERED'
               OR (r.status = 'PENDING_PAYMENT' AND r.hold_expires_at > now()));
        IF v_count >= v_cap THEN
            RAISE EXCEPTION 'BR-WL-01: class đã hết chỗ, hãy vào danh sách chờ';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;


--
-- Name: trg_check_sport_class(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.trg_check_sport_class() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    v_sport bigint;
BEGIN
    SELECT category_id INTO v_sport FROM courses WHERE id = NEW.course_id;

    IF NOT EXISTS (
        SELECT 1 FROM users u JOIN roles r ON r.id = u.role_id
        WHERE u.id = NEW.coach_id AND r.code = 'COACH') THEN
        RAISE EXCEPTION 'coach_id % không thuộc user có vai trò COACH', NEW.coach_id;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM coach_sports cs
        WHERE cs.coach_id = NEW.coach_id AND cs.category_id = v_sport) THEN
        RAISE EXCEPTION 'BR-SCH-04: coach % không dạy bộ môn của course này', NEW.coach_id;
    END IF;

    IF TG_OP = 'UPDATE' THEN
        IF EXISTS (
            SELECT 1 FROM class_schedules s JOIN facility_rooms fr ON fr.id = s.room_id
            WHERE s.class_id = NEW.id AND NEW.max_capacity > fr.capacity) THEN
            RAISE EXCEPTION 'BR-CLS-02: max_capacity vượt sức chứa của phòng đã xếp';
        END IF;

        IF EXISTS (
            SELECT 1 FROM class_registrations r
            WHERE r.class_id = NEW.id AND r.status IN ('PENDING_PAYMENT', 'REGISTERED'))
           AND (NEW.tuition IS DISTINCT FROM OLD.tuition
                OR NEW.start_date IS DISTINCT FROM OLD.start_date
                OR NEW.course_id IS DISTINCT FROM OLD.course_id) THEN
            RAISE EXCEPTION 'BR-CLS-04: class đã có ghi danh, không sửa học phí, ngày khai giảng, course';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;


--
-- Name: vn_today(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.vn_today() RETURNS date
    LANGUAGE sql STABLE
    AS $$
    SELECT (now() AT TIME ZONE 'Asia/Ho_Chi_Minh')::date;
$$;


SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: ai_chat_logs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ai_chat_logs (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    question text NOT NULL,
    answer text,
    status character varying(20) DEFAULT 'SUCCESS'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT ck_ai_status CHECK (((status)::text = ANY (ARRAY[('SUCCESS'::character varying)::text, ('REJECTED'::character varying)::text, ('ERROR'::character varying)::text])))
);


--
-- Name: ai_chat_logs_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.ai_chat_logs ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.ai_chat_logs_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: audit_logs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.audit_logs (
    id bigint NOT NULL,
    user_id bigint,
    action character varying(50) NOT NULL,
    resource character varying(100) NOT NULL,
    resource_id character varying(50),
    payload_diff jsonb,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: audit_logs_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.audit_logs ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.audit_logs_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: class_attendances; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.class_attendances (
    id bigint NOT NULL,
    session_id bigint NOT NULL,
    user_id bigint NOT NULL,
    status character varying(20) NOT NULL,
    check_in_time timestamp with time zone,
    recorded_by bigint,
    notes character varying(500),
    recorded_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT ck_attendance_status CHECK (((status)::text = ANY (ARRAY[('PRESENT'::character varying)::text, ('ABSENT'::character varying)::text, ('LATE'::character varying)::text, ('EXCUSED'::character varying)::text, ('DECLINED_MAKEUP'::character varying)::text])))
);


--
-- Name: class_attendances_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.class_attendances ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.class_attendances_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: class_evaluations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.class_evaluations (
    id bigint NOT NULL,
    registration_id bigint NOT NULL,
    coach_id bigint NOT NULL,
    grade character(1) NOT NULL,
    comment text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT ck_eval_grade CHECK ((grade = ANY (ARRAY['A'::bpchar, 'B'::bpchar, 'C'::bpchar, 'D'::bpchar])))
);


--
-- Name: class_evaluations_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.class_evaluations ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.class_evaluations_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: class_registrations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.class_registrations (
    id bigint NOT NULL,
    class_id bigint NOT NULL,
    user_id bigint NOT NULL,
    registered_at timestamp with time zone DEFAULT now() NOT NULL,
    status character varying(20) DEFAULT 'PENDING_PAYMENT'::character varying NOT NULL,
    canceled_at timestamp with time zone,
    registered_by bigint,
    hold_expires_at timestamp with time zone,
    waitlist_rank smallint DEFAULT 0 NOT NULL,
    offer_expires_at timestamp with time zone,
    CONSTRAINT ck_reg_canceled CHECK ((((status)::text <> ALL (ARRAY[('CANCELED'::character varying)::text, ('LEFT'::character varying)::text])) OR (canceled_at IS NOT NULL))),
    CONSTRAINT ck_reg_hold CHECK ((((status)::text <> 'PENDING_PAYMENT'::text) OR (hold_expires_at IS NOT NULL))),
    CONSTRAINT ck_reg_status CHECK (((status)::text = ANY (ARRAY[('PENDING_PAYMENT'::character varying)::text, ('REGISTERED'::character varying)::text, ('WAITLISTED'::character varying)::text, ('CANCELED'::character varying)::text, ('LEFT'::character varying)::text])))
);


--
-- Name: class_registrations_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.class_registrations ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.class_registrations_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: class_schedules; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.class_schedules (
    id bigint NOT NULL,
    class_id bigint NOT NULL,
    room_id bigint NOT NULL,
    day_of_week smallint NOT NULL,
    start_time time without time zone NOT NULL,
    end_time time without time zone NOT NULL,
    time_slot_id bigint,
    CONSTRAINT ck_schedule_day CHECK (((day_of_week >= 1) AND (day_of_week <= 7))),
    CONSTRAINT ck_schedule_time CHECK ((end_time > start_time))
);


--
-- Name: class_schedules_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.class_schedules ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.class_schedules_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: class_sessions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.class_sessions (
    id bigint NOT NULL,
    class_id bigint NOT NULL,
    session_no integer NOT NULL,
    session_date date NOT NULL,
    start_time time without time zone NOT NULL,
    end_time time without time zone NOT NULL,
    room_id bigint NOT NULL,
    coach_id bigint NOT NULL,
    status character varying(20) DEFAULT 'SCHEDULED'::character varying NOT NULL,
    is_makeup boolean DEFAULT false NOT NULL,
    cancel_reason character varying(255),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    attendance_finalized_at timestamp with time zone,
    training_version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT ck_session_proposed CHECK ((((status)::text <> 'PROPOSED'::text) OR is_makeup)),
    CONSTRAINT ck_session_status CHECK (((status)::text = ANY (ARRAY[('PROPOSED'::character varying)::text, ('SCHEDULED'::character varying)::text, ('DONE'::character varying)::text, ('CANCELED_BY_CENTER'::character varying)::text]))),
    CONSTRAINT ck_session_time CHECK ((end_time > start_time))
);


--
-- Name: class_sessions_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.class_sessions ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.class_sessions_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: coach_profiles; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.coach_profiles (
    user_id bigint NOT NULL,
    specialty character varying(255),
    years_experience smallint,
    certifications text,
    rating numeric(2,1),
    coach_rank character varying(10) DEFAULT 'COACH'::character varying NOT NULL,
    CONSTRAINT ck_coach_rank CHECK (((coach_rank)::text = ANY (ARRAY[('COACH'::character varying)::text, ('SENIOR'::character varying)::text, ('MASTER'::character varying)::text]))),
    CONSTRAINT ck_coach_rating CHECK (((rating IS NULL) OR ((rating >= (0)::numeric) AND (rating <= (5)::numeric)))),
    CONSTRAINT ck_coach_years CHECK (((years_experience IS NULL) OR (years_experience >= 0)))
);


--
-- Name: coach_slots; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.coach_slots (
    id bigint NOT NULL,
    coach_id bigint NOT NULL,
    start_at timestamp with time zone NOT NULL,
    end_at timestamp with time zone NOT NULL,
    kind character varying(12) DEFAULT 'ONE_ON_ONE'::character varying NOT NULL,
    status character varying(10) DEFAULT 'OPEN'::character varying NOT NULL,
    member_id bigint,
    held_until timestamp with time zone,
    price numeric(12,0),
    canceled_by bigint,
    canceled_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    room_id bigint,
    category_id bigint,
    CONSTRAINT ck_slot_held CHECK ((((status)::text <> 'HELD'::text) OR (held_until IS NOT NULL))),
    CONSTRAINT ck_slot_kind CHECK (((kind)::text = 'ONE_ON_ONE'::text)),
    CONSTRAINT ck_slot_member CHECK (((((status)::text = 'OPEN'::text) AND (member_id IS NULL)) OR (((status)::text = ANY (ARRAY[('HELD'::character varying)::text, ('BOOKED'::character varying)::text])) AND (member_id IS NOT NULL)) OR ((status)::text = 'CANCELED'::text))),
    CONSTRAINT ck_slot_price CHECK (((price IS NULL) OR (price >= (0)::numeric))),
    CONSTRAINT ck_slot_room_sport CHECK (((room_id IS NULL) = (category_id IS NULL))),
    CONSTRAINT ck_slot_status CHECK (((status)::text = ANY (ARRAY[('OPEN'::character varying)::text, ('HELD'::character varying)::text, ('BOOKED'::character varying)::text, ('CANCELED'::character varying)::text]))),
    CONSTRAINT ck_slot_time CHECK ((end_at > start_at))
);


--
-- Name: coach_slots_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.coach_slots ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.coach_slots_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: coach_sports; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.coach_sports (
    coach_id bigint NOT NULL,
    category_id bigint NOT NULL
);


--
-- Name: courses; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.courses (
    id bigint NOT NULL,
    category_id bigint NOT NULL,
    name character varying(150) NOT NULL,
    description text,
    level character varying(20) DEFAULT 'BEGINNER'::character varying NOT NULL,
    total_sessions integer NOT NULL,
    default_tuition numeric(12,0) NOT NULL,
    prerequisite_course_id bigint,
    is_active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT ck_course_level CHECK (((level)::text = ANY (ARRAY[('BEGINNER'::character varying)::text, ('INTERMEDIATE'::character varying)::text, ('ADVANCED'::character varying)::text]))),
    CONSTRAINT ck_course_prereq CHECK (((prerequisite_course_id IS NULL) OR (prerequisite_course_id <> id))),
    CONSTRAINT ck_course_sessions CHECK ((total_sessions > 0)),
    CONSTRAINT ck_course_tuition CHECK ((default_tuition >= (0)::numeric))
);


--
-- Name: courses_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.courses ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.courses_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: facility_attendances; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.facility_attendances (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    check_in_time timestamp with time zone DEFAULT now() NOT NULL,
    check_out_time timestamp with time zone,
    recorded_by bigint,
    notes character varying(500),
    CONSTRAINT ck_facility_att_time CHECK (((check_out_time IS NULL) OR (check_out_time >= check_in_time)))
);


--
-- Name: facility_attendances_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.facility_attendances ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.facility_attendances_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: facility_rooms; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.facility_rooms (
    id bigint NOT NULL,
    room_name character varying(100) NOT NULL,
    floor character varying(20),
    capacity integer NOT NULL,
    description character varying(500),
    maintenance_status character varying(20) DEFAULT 'AVAILABLE'::character varying NOT NULL,
    CONSTRAINT ck_room_status CHECK (((maintenance_status)::text = ANY (ARRAY[('AVAILABLE'::character varying)::text, ('MAINTENANCE'::character varying)::text, ('CLOSED'::character varying)::text]))),
    CONSTRAINT facility_rooms_capacity_check CHECK ((capacity > 0))
);


--
-- Name: facility_rooms_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.facility_rooms ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.facility_rooms_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: member_profiles; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.member_profiles (
    user_id bigint NOT NULL,
    join_date date DEFAULT CURRENT_DATE NOT NULL,
    height_cm numeric(5,2),
    weight_kg numeric(5,2),
    fitness_goal character varying(500),
    CONSTRAINT ck_member_height CHECK (((height_cm IS NULL) OR (height_cm > (0)::numeric))),
    CONSTRAINT ck_member_weight CHECK (((weight_kg IS NULL) OR (weight_kg > (0)::numeric)))
);


--
-- Name: member_subscriptions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.member_subscriptions (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    package_id bigint NOT NULL,
    package_name_snapshot character varying(150) NOT NULL,
    tier_level_snapshot smallint NOT NULL,
    months smallint NOT NULL,
    purchase_type character varying(10) DEFAULT 'NEW'::character varying NOT NULL,
    upgrade_from_subscription_id bigint,
    upgrade_credit_amount numeric(12,0) DEFAULT 0 NOT NULL,
    payment_expires_at timestamp with time zone,
    total_amount numeric(12,0) NOT NULL,
    start_date date,
    end_date date,
    status character varying(20) DEFAULT 'PENDING'::character varying NOT NULL,
    benefits_snapshot jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_by bigint,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT ck_sub_active_dates CHECK ((((status)::text <> 'ACTIVE'::text) OR ((start_date IS NOT NULL) AND (end_date IS NOT NULL)))),
    CONSTRAINT ck_sub_amount CHECK ((total_amount >= (0)::numeric)),
    CONSTRAINT ck_sub_dates CHECK (((end_date IS NULL) OR (start_date IS NULL) OR (end_date > start_date))),
    CONSTRAINT ck_sub_months CHECK (((months >= 1) AND (months <= 12))),
    CONSTRAINT ck_sub_purchase_type CHECK (((purchase_type)::text = ANY (ARRAY[('NEW'::character varying)::text, ('RENEWAL'::character varying)::text, ('UPGRADE'::character varying)::text]))),
    CONSTRAINT ck_sub_status CHECK (((status)::text = ANY (ARRAY[('PENDING'::character varying)::text, ('SCHEDULED'::character varying)::text, ('ACTIVE'::character varying)::text, ('EXPIRED'::character varying)::text, ('UPGRADED'::character varying)::text, ('CANCELED'::character varying)::text]))),
    CONSTRAINT ck_sub_tier_snapshot CHECK ((tier_level_snapshot >= 1)),
    CONSTRAINT ck_sub_upgrade_credit CHECK ((upgrade_credit_amount >= (0)::numeric)),
    CONSTRAINT ck_sub_upgrade_ref CHECK ((((purchase_type)::text <> 'UPGRADE'::text) OR (upgrade_from_subscription_id IS NOT NULL)))
);


--
-- Name: member_subscriptions_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.member_subscriptions ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.member_subscriptions_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: membership_packages; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.membership_packages (
    id bigint NOT NULL,
    code character varying(30) NOT NULL,
    name character varying(150) NOT NULL,
    description text,
    tier_level smallint NOT NULL,
    monthly_price numeric(12,0) NOT NULL,
    early_register_days smallint DEFAULT 0 NOT NULL,
    tuition_discount_pct smallint DEFAULT 0 NOT NULL,
    one_on_one_discount_pct smallint DEFAULT 0 NOT NULL,
    has_personal_plan boolean DEFAULT false NOT NULL,
    ai_level character varying(10) DEFAULT 'NONE'::character varying NOT NULL,
    menu_ai_level character varying(10) DEFAULT 'NONE'::character varying NOT NULL,
    early_leave_refund_sessions smallint DEFAULT 0 NOT NULL,
    waitlist_priority smallint DEFAULT 0 NOT NULL,
    birthday_gift_kind character varying(20) DEFAULT 'NONE'::character varying NOT NULL,
    birthday_gift_value numeric(12,0) DEFAULT 0 NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT ck_package_121 CHECK (((one_on_one_discount_pct >= 0) AND (one_on_one_discount_pct <= 100))),
    CONSTRAINT ck_package_ai CHECK (((ai_level)::text = ANY (ARRAY[('NONE'::character varying)::text, ('LIMITED'::character varying)::text, ('UNLIMITED'::character varying)::text]))),
    CONSTRAINT ck_package_early CHECK ((early_register_days >= 0)),
    CONSTRAINT ck_package_gift CHECK (((birthday_gift_kind)::text = ANY (ARRAY[('NONE'::character varying)::text, ('PERCENT'::character varying)::text, ('FREE_ONE_ON_ONE'::character varying)::text]))),
    CONSTRAINT ck_package_gift_value CHECK ((birthday_gift_value >= (0)::numeric)),
    CONSTRAINT ck_package_leave CHECK ((early_leave_refund_sessions >= 0)),
    CONSTRAINT ck_package_menu_ai CHECK (((menu_ai_level)::text = ANY (ARRAY[('NONE'::character varying)::text, ('LIMITED'::character varying)::text, ('UNLIMITED'::character varying)::text]))),
    CONSTRAINT ck_package_price CHECK ((monthly_price >= (0)::numeric)),
    CONSTRAINT ck_package_tier CHECK ((tier_level >= 1)),
    CONSTRAINT ck_package_tuition CHECK (((tuition_discount_pct >= 0) AND (tuition_discount_pct <= 100)))
);


--
-- Name: membership_packages_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.membership_packages ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.membership_packages_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: notifications; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.notifications (
    id bigint NOT NULL,
    recipient_id bigint NOT NULL,
    sender_id bigint,
    type character varying(30) NOT NULL,
    channel character varying(10) DEFAULT 'APP'::character varying NOT NULL,
    title character varying(200) NOT NULL,
    content text,
    is_read boolean DEFAULT false NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT ck_notification_channel CHECK (((channel)::text = ANY (ARRAY[('APP'::character varying)::text, ('EMAIL'::character varying)::text]))),
    CONSTRAINT ck_notification_type CHECK (((type)::text = ANY (ARRAY[('ANNOUNCEMENT'::character varying)::text, ('HOMEWORK'::character varying)::text, ('WORKOUT_PLAN'::character varying)::text, ('MEMBERSHIP'::character varying)::text, ('BOOKING'::character varying)::text, ('PAYMENT'::character varying)::text, ('WAITLIST'::character varying)::text, ('MAKEUP'::character varying)::text, ('SYSTEM'::character varying)::text])))
);


--
-- Name: notifications_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.notifications ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.notifications_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: orders; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.orders (
    id bigint NOT NULL,
    member_id bigint NOT NULL,
    created_by bigint,
    channel character varying(10) NOT NULL,
    subscription_id bigint,
    registration_id bigint,
    coach_slot_id bigint,
    list_price numeric(12,0) NOT NULL,
    discount_kind character varying(10) DEFAULT 'NONE'::character varying NOT NULL,
    discount_amount numeric(12,0) DEFAULT 0 NOT NULL,
    voucher_id bigint,
    total_amount numeric(12,0) NOT NULL,
    status character varying(10) DEFAULT 'PENDING'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT ck_order_amounts CHECK (((list_price >= (0)::numeric) AND (discount_amount >= (0)::numeric) AND (total_amount >= (0)::numeric) AND (total_amount = (list_price - discount_amount)))),
    CONSTRAINT ck_order_channel CHECK (((channel)::text = ANY (ARRAY[('ONLINE'::character varying)::text, ('COUNTER'::character varying)::text]))),
    CONSTRAINT ck_order_discount_kind CHECK (((discount_kind)::text = ANY (ARRAY[('NONE'::character varying)::text, ('TIER'::character varying)::text, ('VOUCHER'::character varying)::text]))),
    CONSTRAINT ck_order_item CHECK ((((((subscription_id IS NOT NULL))::integer + ((registration_id IS NOT NULL))::integer) + ((coach_slot_id IS NOT NULL))::integer) = 1)),
    CONSTRAINT ck_order_status CHECK (((status)::text = ANY (ARRAY[('PENDING'::character varying)::text, ('PAID'::character varying)::text, ('CANCELED'::character varying)::text, ('EXPIRED'::character varying)::text]))),
    CONSTRAINT ck_order_voucher CHECK ((((discount_kind)::text = 'VOUCHER'::text) = (voucher_id IS NOT NULL)))
);


--
-- Name: orders_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.orders ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.orders_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: payments; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.payments (
    id bigint NOT NULL,
    order_id bigint NOT NULL,
    payer_id bigint NOT NULL,
    received_by bigint,
    invoice_number character varying(30) NOT NULL,
    transaction_code character varying(100) NOT NULL,
    amount numeric(12,0) NOT NULL,
    payment_method character varying(20) NOT NULL,
    status character varying(20) DEFAULT 'PENDING'::character varying NOT NULL,
    paid_at timestamp with time zone,
    gateway_response text,
    gateway_order_code bigint,
    payment_url text,
    expires_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT ck_payment_amount CHECK ((amount > (0)::numeric)),
    CONSTRAINT ck_payment_method CHECK (((payment_method)::text = ANY (ARRAY[('CASH'::character varying)::text, ('BANK_TRANSFER'::character varying)::text, ('QR_GATEWAY'::character varying)::text]))),
    CONSTRAINT ck_payment_paid_at CHECK ((((status)::text <> ALL (ARRAY[('SUCCESS'::character varying)::text, ('PENDING_REFUND'::character varying)::text, ('REFUNDED'::character varying)::text])) OR (paid_at IS NOT NULL))),
    CONSTRAINT ck_payment_status CHECK (((status)::text = ANY (ARRAY[('CREATING'::character varying)::text, ('PENDING'::character varying)::text, ('SUCCESS'::character varying)::text, ('FAILED'::character varying)::text, ('CANCELED'::character varying)::text, ('EXPIRED'::character varying)::text, ('PENDING_REFUND'::character varying)::text, ('REFUNDED'::character varying)::text])))
);


--
-- Name: payments_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.payments ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.payments_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: permissions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.permissions (
    id bigint NOT NULL,
    code character varying(60) NOT NULL,
    name character varying(100) NOT NULL,
    description character varying(255)
);


--
-- Name: permissions_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.permissions ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.permissions_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: refunds; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.refunds (
    id bigint NOT NULL,
    payment_id bigint NOT NULL,
    amount numeric(12,0) NOT NULL,
    reason character varying(255) NOT NULL,
    method character varying(10) NOT NULL,
    status character varying(10) DEFAULT 'PENDING'::character varying NOT NULL,
    requested_at timestamp with time zone DEFAULT now() NOT NULL,
    processed_by bigint,
    processed_at timestamp with time zone,
    CONSTRAINT ck_refund_amount CHECK ((amount > (0)::numeric)),
    CONSTRAINT ck_refund_done CHECK ((((status)::text <> 'DONE'::text) OR ((processed_by IS NOT NULL) AND (processed_at IS NOT NULL)))),
    CONSTRAINT ck_refund_method CHECK (((method)::text = ANY (ARRAY[('CASH'::character varying)::text, ('TRANSFER'::character varying)::text]))),
    CONSTRAINT ck_refund_status CHECK (((status)::text = ANY (ARRAY[('PENDING'::character varying)::text, ('DONE'::character varying)::text])))
);


--
-- Name: refunds_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.refunds ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.refunds_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: role_permissions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.role_permissions (
    role_id bigint NOT NULL,
    permission_id bigint NOT NULL
);


--
-- Name: roles; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.roles (
    id bigint NOT NULL,
    code character varying(30) NOT NULL,
    name character varying(100) NOT NULL,
    description character varying(255)
);


--
-- Name: roles_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.roles ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.roles_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: room_sports; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.room_sports (
    room_id bigint NOT NULL,
    category_id bigint NOT NULL
);


--
-- Name: sport_categories; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sport_categories (
    id bigint NOT NULL,
    name character varying(100) NOT NULL,
    description character varying(500)
);


--
-- Name: sport_categories_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.sport_categories ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.sport_categories_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: sport_classes; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sport_classes (
    id bigint NOT NULL,
    course_id bigint NOT NULL,
    coach_id bigint NOT NULL,
    name character varying(150) NOT NULL,
    description text,
    max_capacity integer NOT NULL,
    tuition numeric(12,0) NOT NULL,
    start_date date NOT NULL,
    registration_opens_on date NOT NULL,
    status character varying(15) DEFAULT 'DRAFT'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT ck_class_capacity CHECK ((max_capacity > 0)),
    CONSTRAINT ck_class_reg_open CHECK ((registration_opens_on <= start_date)),
    CONSTRAINT ck_class_status CHECK (((status)::text = ANY (ARRAY[('DRAFT'::character varying)::text, ('OPEN'::character varying)::text, ('IN_PROGRESS'::character varying)::text, ('COMPLETED'::character varying)::text, ('CANCELED'::character varying)::text]))),
    CONSTRAINT ck_class_tuition CHECK ((tuition >= (0)::numeric))
);


--
-- Name: sport_classes_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.sport_classes ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.sport_classes_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: staff_profiles; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.staff_profiles (
    user_id bigint NOT NULL,
    job_title character varying(100),
    shift_schedule character varying(255)
);


--
-- Name: support_requests; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.support_requests (
    id bigint NOT NULL,
    member_id bigint NOT NULL,
    handled_by bigint,
    subject character varying(200) NOT NULL,
    content text NOT NULL,
    status character varying(20) DEFAULT 'OPEN'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    resolved_at timestamp with time zone,
    CONSTRAINT ck_support_status CHECK (((status)::text = ANY (ARRAY[('OPEN'::character varying)::text, ('IN_PROGRESS'::character varying)::text, ('RESOLVED'::character varying)::text, ('CLOSED'::character varying)::text])))
);


--
-- Name: support_requests_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.support_requests ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.support_requests_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: system_settings; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.system_settings (
    key character varying(100) NOT NULL,
    value jsonb NOT NULL,
    description character varying(255),
    updated_by bigint,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: time_slots; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.time_slots (
    id bigint NOT NULL,
    slot_no smallint NOT NULL,
    start_time time without time zone NOT NULL,
    end_time time without time zone NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    CONSTRAINT ck_time_slot_time CHECK ((end_time > start_time))
);


--
-- Name: time_slots_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.time_slots ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.time_slots_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: training_plan_exercises; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.training_plan_exercises (
    id bigint NOT NULL,
    plan_id bigint NOT NULL,
    "position" integer NOT NULL,
    name character varying(200) NOT NULL,
    instructions character varying(2000),
    sets integer,
    repetitions integer,
    duration_seconds integer,
    retired boolean DEFAULT false NOT NULL,
    CONSTRAINT training_plan_exercises_duration_seconds_check CHECK (((duration_seconds >= 0) AND (duration_seconds <= 604800))),
    CONSTRAINT training_plan_exercises_name_check CHECK ((length(TRIM(BOTH FROM name)) > 0)),
    CONSTRAINT training_plan_exercises_position_check CHECK (("position" >= 0)),
    CONSTRAINT training_plan_exercises_repetitions_check CHECK (((repetitions >= 0) AND (repetitions <= 100000))),
    CONSTRAINT training_plan_exercises_sets_check CHECK (((sets >= 0) AND (sets <= 10000)))
);


--
-- Name: training_plan_exercises_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.training_plan_exercises ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.training_plan_exercises_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: training_progress_evaluations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.training_progress_evaluations (
    id bigint NOT NULL,
    class_id bigint NOT NULL,
    member_id bigint NOT NULL,
    coach_id bigint NOT NULL,
    evaluation_date date NOT NULL,
    period_start date NOT NULL,
    period_end date NOT NULL,
    summary character varying(4000) NOT NULL,
    rating integer NOT NULL,
    strengths character varying(2000),
    improvements character varying(2000),
    recommendations character varying(2000),
    feedback character varying(4000),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    version integer DEFAULT 1 NOT NULL,
    CONSTRAINT training_progress_evaluations_check CHECK (((period_end >= period_start) AND (evaluation_date >= period_end))),
    CONSTRAINT training_progress_evaluations_rating_check CHECK (((rating >= 1) AND (rating <= 5))),
    CONSTRAINT training_progress_evaluations_summary_check CHECK ((length(TRIM(BOTH FROM summary)) > 0))
);


--
-- Name: training_progress_evaluations_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.training_progress_evaluations ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.training_progress_evaluations_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: training_results; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.training_results (
    id bigint NOT NULL,
    attendance_id bigint NOT NULL,
    exercise_id bigint,
    activity_key character varying(100) NOT NULL,
    activity_name character varying(200) NOT NULL,
    prescription jsonb,
    actual_sets integer,
    actual_repetitions integer,
    actual_duration_seconds integer,
    performance character varying(2000),
    completion_status character varying(20) NOT NULL,
    notes character varying(4000),
    recorded_by bigint NOT NULL,
    recorded_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT ck_training_result_key CHECK (((exercise_id IS NULL) OR ((activity_key)::text = ('exercise:'::text || (exercise_id)::text)))),
    CONSTRAINT training_results_activity_key_check CHECK ((length(TRIM(BOTH FROM activity_key)) > 0)),
    CONSTRAINT training_results_activity_name_check CHECK ((length(TRIM(BOTH FROM activity_name)) > 0)),
    CONSTRAINT training_results_actual_duration_seconds_check CHECK (((actual_duration_seconds >= 0) AND (actual_duration_seconds <= 604800))),
    CONSTRAINT training_results_actual_repetitions_check CHECK (((actual_repetitions >= 0) AND (actual_repetitions <= 100000))),
    CONSTRAINT training_results_actual_sets_check CHECK (((actual_sets >= 0) AND (actual_sets <= 10000))),
    CONSTRAINT training_results_completion_status_check CHECK (((completion_status)::text = ANY ((ARRAY['COMPLETED'::character varying, 'PARTIAL'::character varying, 'NOT_COMPLETED'::character varying])::text[])))
);


--
-- Name: training_results_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.training_results ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.training_results_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: users; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.users (
    id bigint NOT NULL,
    role_id bigint NOT NULL,
    full_name character varying(150) NOT NULL,
    email character varying(255) NOT NULL,
    phone character varying(10) NOT NULL,
    password_hash character varying(255) NOT NULL,
    avatar_url character varying(500),
    gender character varying(10),
    date_of_birth date,
    is_active boolean DEFAULT true NOT NULL,
    email_verified_at timestamp with time zone,
    verify_token_hash character varying(128),
    verify_token_expires_at timestamp with time zone,
    reset_token_hash character varying(128),
    reset_token_expires_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT ck_users_dob CHECK (((date_of_birth IS NULL) OR (date_of_birth < CURRENT_DATE))),
    CONSTRAINT ck_users_gender CHECK (((gender IS NULL) OR ((gender)::text = ANY (ARRAY[('MALE'::character varying)::text, ('FEMALE'::character varying)::text, ('OTHER'::character varying)::text])))),
    CONSTRAINT ck_users_phone CHECK (((phone)::text ~ '^(03|05|07|08|09)[0-9]{8}$'::text))
);


--
-- Name: users_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.users ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.users_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: voucher_campaigns; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.voucher_campaigns (
    id bigint NOT NULL,
    code character varying(40) NOT NULL,
    kind character varying(20) NOT NULL,
    value numeric(12,0) DEFAULT 0 NOT NULL,
    applies_to character varying(12) NOT NULL,
    max_uses integer,
    starts_at timestamp with time zone NOT NULL,
    ends_at timestamp with time zone NOT NULL,
    created_by bigint,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT ck_campaign_applies CHECK (((applies_to)::text = ANY (ARRAY[('CLASS'::character varying)::text, ('ONE_ON_ONE'::character varying)::text, ('PACKAGE'::character varying)::text]))),
    CONSTRAINT ck_campaign_dates CHECK ((ends_at > starts_at)),
    CONSTRAINT ck_campaign_kind CHECK (((kind)::text = ANY (ARRAY[('PERCENT'::character varying)::text, ('AMOUNT'::character varying)::text, ('FREE_ONE_ON_ONE'::character varying)::text]))),
    CONSTRAINT ck_campaign_uses CHECK (((max_uses IS NULL) OR (max_uses > 0))),
    CONSTRAINT ck_campaign_value CHECK (((value >= (0)::numeric) AND (((kind)::text <> 'PERCENT'::text) OR (value <= (100)::numeric))))
);


--
-- Name: voucher_campaigns_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.voucher_campaigns ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.voucher_campaigns_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: vouchers; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.vouchers (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    source character varying(15) NOT NULL,
    kind character varying(20) NOT NULL,
    value numeric(12,0) DEFAULT 0 NOT NULL,
    applies_to character varying(12) NOT NULL,
    campaign_id bigint,
    issued_at timestamp with time zone DEFAULT now() NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    used_at timestamp with time zone,
    CONSTRAINT ck_voucher_applies CHECK (((applies_to)::text = ANY (ARRAY[('CLASS'::character varying)::text, ('ONE_ON_ONE'::character varying)::text, ('PACKAGE'::character varying)::text]))),
    CONSTRAINT ck_voucher_campaign CHECK ((((source)::text = 'CAMPAIGN'::text) = (campaign_id IS NOT NULL))),
    CONSTRAINT ck_voucher_expiry CHECK ((expires_at > issued_at)),
    CONSTRAINT ck_voucher_kind CHECK (((kind)::text = ANY (ARRAY[('PERCENT'::character varying)::text, ('AMOUNT'::character varying)::text, ('FREE_ONE_ON_ONE'::character varying)::text]))),
    CONSTRAINT ck_voucher_source CHECK (((source)::text = ANY ((ARRAY['BIRTHDAY'::character varying, 'WELCOME'::character varying, 'COMPLETION'::character varying, 'COMPENSATION'::character varying, 'CAMPAIGN'::character varying, 'EARLY_RENEWAL'::character varying, 'WINBACK'::character varying])::text[]))),
    CONSTRAINT ck_voucher_value CHECK (((value >= (0)::numeric) AND (((kind)::text <> 'PERCENT'::text) OR (value <= (100)::numeric))))
);


--
-- Name: vouchers_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.vouchers ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.vouchers_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: workout_plans; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.workout_plans (
    id bigint NOT NULL,
    member_id bigint,
    coach_id bigint NOT NULL,
    title character varying(200) NOT NULL,
    description text,
    content jsonb,
    is_ai_generated boolean DEFAULT false NOT NULL,
    start_date date,
    end_date date,
    status character varying(20) DEFAULT 'DRAFT'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    class_id bigint,
    objective character varying(2000),
    coach_notes character varying(4000),
    version integer DEFAULT 1 NOT NULL,
    CONSTRAINT ck_plan_dates CHECK (((end_date IS NULL) OR (start_date IS NULL) OR (end_date >= start_date))),
    CONSTRAINT ck_plan_status CHECK (((status)::text = ANY (ARRAY[('DRAFT'::character varying)::text, ('PUBLISHED'::character varying)::text, ('COMPLETED'::character varying)::text, ('ARCHIVED'::character varying)::text]))),
    CONSTRAINT ck_plan_target CHECK (((member_id IS NOT NULL) OR (class_id IS NOT NULL)))
);


--
-- Name: workout_plans_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.workout_plans ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.workout_plans_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: workout_progress; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.workout_progress (
    id bigint NOT NULL,
    plan_id bigint,
    member_id bigint NOT NULL,
    recorded_by bigint,
    recorded_date date DEFAULT CURRENT_DATE NOT NULL,
    weight_kg numeric(5,2),
    height_cm numeric(5,2),
    body_fat_pct numeric(4,1),
    muscle_mass_kg numeric(5,2),
    notes text,
    CONSTRAINT ck_progress_fat CHECK (((body_fat_pct IS NULL) OR ((body_fat_pct >= (0)::numeric) AND (body_fat_pct <= (100)::numeric)))),
    CONSTRAINT ck_progress_height CHECK (((height_cm IS NULL) OR (height_cm > (0)::numeric))),
    CONSTRAINT ck_progress_muscle CHECK (((muscle_mass_kg IS NULL) OR (muscle_mass_kg > (0)::numeric))),
    CONSTRAINT ck_progress_weight CHECK (((weight_kg IS NULL) OR (weight_kg > (0)::numeric)))
);


--
-- Name: workout_progress_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.workout_progress ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.workout_progress_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Data for Name: ai_chat_logs; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.ai_chat_logs (id, user_id, question, answer, status, created_at) FROM stdin;
\.


--
-- Data for Name: audit_logs; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.audit_logs (id, user_id, action, resource, resource_id, payload_diff, created_at) FROM stdin;
1	1	SEED	database	v3	{"note": "nạp dữ liệu mẫu scms_database_v3"}	2026-10-23 18:14:43.228636+07
2	1	COACH_RANK_CHANGED	coach_profile	21	{"to": "SENIOR", "from": "COACH"}	2026-10-29 11:17:07.608977+07
3	1	COACH_RANK_CHANGED	coach_profile	22	{"to": "COACH", "from": "COACH"}	2026-10-29 11:17:08.018064+07
4	1	COACH_RANK_CHANGED	coach_profile	23	{"to": "MASTER", "from": "COACH"}	2026-10-29 11:17:08.343545+07
5	1	COACH_RANK_CHANGED	coach_profile	24	{"to": "SENIOR", "from": "COACH"}	2026-10-29 11:17:08.653269+07
6	1	COACH_RANK_CHANGED	coach_profile	25	{"to": "COACH", "from": "COACH"}	2026-10-29 11:17:09.060397+07
7	1	COACH_RANK_CHANGED	coach_profile	26	{"to": "COACH", "from": "COACH"}	2026-10-29 11:17:09.460486+07
8	1	VOUCHER_CAMPAIGN_CREATED	voucher_campaign	2	{"code": "CHAOMUNG20", "kind": "PERCENT", "appliesTo": "PACKAGE"}	2026-10-29 11:17:12.917324+07
9	1	VOUCHER_CAMPAIGN_CREATED	voucher_campaign	3	{"code": "COACH15", "kind": "PERCENT", "appliesTo": "ONE_ON_ONE"}	2026-10-29 11:17:12.930678+07
10	25	TRAINING_PLAN_CREATE	workout_plans	1	{"classId": 5, "exerciseCount": 3}	2026-10-29 11:17:22.42624+07
11	25	TRAINING_EVALUATION_SAVE	training_progress_evaluations	1	{"memberId": 16}	2026-10-29 11:17:22.467701+07
12	25	TRAINING_EVALUATION_SAVE	training_progress_evaluations	2	{"memberId": 18}	2026-10-29 11:17:22.490568+07
13	1	CLASS_CANCELED	sport_class	7	{"reason": "Coach nghỉ đột xuất, trung tâm hủy lớp", "refundTotal": 2400000, "canceledSessions": 8, "refundedRegistrations": 3, "canceledUnpaidRegistrations": 0}	2026-10-29 11:17:28.681038+07
14	1	COACH_SLOTS_SCHEDULED	user	21	{"created": 14, "skipped": 0, "sportCategoryId": 1}	2026-10-29 14:17:33.631173+07
15	1	COACH_SLOT_CANCELED	coach_slot	23	{"reason": "Phong bao tri", "memberId": 9, "refundAmount": 700000}	2026-10-29 14:17:48.650288+07
16	1	COACH_SLOTS_SCHEDULED	user	21	{"created": 2, "skipped": 0, "sportCategoryId": 1}	2026-10-29 14:19:17.724321+07
17	1	REFUND_COMPLETED	refund	1	{"amount": 800000, "method": "CASH", "paymentId": 24}	2026-10-31 10:23:44.81729+07
18	1	REFUND_COMPLETED	refund	2	{"amount": 800000, "method": "CASH", "paymentId": 25}	2026-10-31 10:23:45.760053+07
19	1	REFUND_COMPLETED	refund	3	{"amount": 800000, "method": "CASH", "paymentId": 26}	2026-10-31 10:23:46.414842+07
20	1	REFUND_COMPLETED	refund	4	{"amount": 700000, "method": "CASH", "paymentId": 28}	2026-10-31 10:23:46.750824+07
21	25	TRAINING_PLAN_UPDATE	workout_plans	1	{"classId": 5, "exerciseCount": 3}	2026-10-31 10:26:32.422483+07
22	25	TRAINING_EVALUATION_SAVE	training_progress_evaluations	1	{"memberId": 16}	2026-10-31 10:27:02.155388+07
\.


--
-- Data for Name: class_attendances; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.class_attendances (id, session_id, user_id, status, check_in_time, recorded_by, notes, recorded_at, updated_at) FROM stdin;
1	41	16	PRESENT	\N	25	Đúng giờ	2026-10-29 11:17:21.593234+07	2026-10-29 11:17:21.593234+07
2	41	18	PRESENT	\N	25	Đúng giờ	2026-10-29 11:17:21.655206+07	2026-10-29 11:17:21.655206+07
3	41	20	PRESENT	\N	25	Đúng giờ	2026-10-29 11:17:21.817533+07	2026-10-29 11:17:21.817533+07
4	41	12	LATE	\N	25	\N	2026-10-29 11:17:21.874549+07	2026-10-29 11:17:21.874549+07
5	42	16	PRESENT	\N	25	Đúng giờ	2026-10-29 11:17:22.006514+07	2026-10-29 11:17:22.006514+07
6	42	18	PRESENT	\N	25	Đúng giờ	2026-10-29 11:17:22.065905+07	2026-10-29 11:17:22.065905+07
7	42	20	ABSENT	\N	25	\N	2026-10-29 11:17:22.122101+07	2026-10-29 11:17:22.122101+07
8	42	12	PRESENT	\N	25	Đúng giờ	2026-10-29 11:17:22.177945+07	2026-10-29 11:17:22.177945+07
9	53	9	PRESENT	\N	22	\N	2026-10-29 11:17:23.084108+07	2026-10-29 11:17:23.084108+07
10	53	10	LATE	\N	22	\N	2026-10-29 11:17:23.144725+07	2026-10-29 11:17:23.144725+07
11	53	11	PRESENT	\N	22	\N	2026-10-29 11:17:23.214869+07	2026-10-29 11:17:23.214869+07
12	53	17	ABSENT	\N	22	\N	2026-10-29 11:17:23.286352+07	2026-10-29 11:17:23.286352+07
13	53	13	PRESENT	\N	22	\N	2026-10-29 11:17:23.447008+07	2026-10-29 11:17:23.447008+07
14	54	9	LATE	\N	22	\N	2026-10-29 11:17:23.59242+07	2026-10-29 11:17:23.59242+07
15	54	10	PRESENT	\N	22	\N	2026-10-29 11:17:23.655915+07	2026-10-29 11:17:23.655915+07
16	54	11	ABSENT	\N	22	\N	2026-10-29 11:17:23.820033+07	2026-10-29 11:17:23.820033+07
17	54	17	PRESENT	\N	22	\N	2026-10-29 11:17:23.877478+07	2026-10-29 11:17:23.877478+07
18	54	13	PRESENT	\N	22	\N	2026-10-29 11:17:23.947147+07	2026-10-29 11:17:23.947147+07
19	55	9	PRESENT	\N	22	\N	2026-10-29 11:17:24.082765+07	2026-10-29 11:17:24.082765+07
20	55	10	ABSENT	\N	22	\N	2026-10-29 11:17:24.143697+07	2026-10-29 11:17:24.143697+07
21	55	11	PRESENT	\N	22	\N	2026-10-29 11:17:24.208518+07	2026-10-29 11:17:24.208518+07
22	55	17	PRESENT	\N	22	\N	2026-10-29 11:17:24.270222+07	2026-10-29 11:17:24.270222+07
23	55	13	LATE	\N	22	\N	2026-10-29 11:17:24.340048+07	2026-10-29 11:17:24.340048+07
24	56	9	ABSENT	\N	22	\N	2026-10-29 11:17:24.594069+07	2026-10-29 11:17:24.594069+07
25	56	10	PRESENT	\N	22	\N	2026-10-29 11:17:24.671135+07	2026-10-29 11:17:24.671135+07
26	56	11	PRESENT	\N	22	\N	2026-10-29 11:17:24.735707+07	2026-10-29 11:17:24.735707+07
27	56	17	LATE	\N	22	\N	2026-10-29 11:17:24.905887+07	2026-10-29 11:17:24.905887+07
28	56	13	PRESENT	\N	22	\N	2026-10-29 11:17:25.075613+07	2026-10-29 11:17:25.075613+07
29	57	9	PRESENT	\N	22	\N	2026-10-29 11:17:25.324996+07	2026-10-29 11:17:25.324996+07
30	57	10	PRESENT	\N	22	\N	2026-10-29 11:17:25.504407+07	2026-10-29 11:17:25.504407+07
31	57	11	LATE	\N	22	\N	2026-10-29 11:17:25.572888+07	2026-10-29 11:17:25.572888+07
32	57	17	PRESENT	\N	22	\N	2026-10-29 11:17:25.637931+07	2026-10-29 11:17:25.637931+07
33	57	13	ABSENT	\N	22	\N	2026-10-29 11:17:25.702582+07	2026-10-29 11:17:25.702582+07
34	58	9	PRESENT	\N	22	\N	2026-10-29 11:17:25.94965+07	2026-10-29 11:17:25.94965+07
35	58	10	LATE	\N	22	\N	2026-10-29 11:17:26.009119+07	2026-10-29 11:17:26.009119+07
36	58	11	PRESENT	\N	22	\N	2026-10-29 11:17:26.178103+07	2026-10-29 11:17:26.178103+07
37	58	17	ABSENT	\N	22	\N	2026-10-29 11:17:26.238209+07	2026-10-29 11:17:26.238209+07
38	58	13	PRESENT	\N	22	\N	2026-10-29 11:17:26.41403+07	2026-10-29 11:17:26.41403+07
39	59	9	LATE	\N	22	\N	2026-10-29 11:17:26.547311+07	2026-10-29 11:17:26.547311+07
40	59	10	PRESENT	\N	22	\N	2026-10-29 11:17:26.606843+07	2026-10-29 11:17:26.606843+07
41	59	11	ABSENT	\N	22	\N	2026-10-29 11:17:26.662308+07	2026-10-29 11:17:26.662308+07
42	59	17	PRESENT	\N	22	\N	2026-10-29 11:17:26.826795+07	2026-10-29 11:17:26.826795+07
43	59	13	PRESENT	\N	22	\N	2026-10-29 11:17:26.892224+07	2026-10-29 11:17:26.892224+07
44	60	9	PRESENT	\N	22	\N	2026-10-29 11:17:27.023779+07	2026-10-29 11:17:27.023779+07
45	60	10	ABSENT	\N	22	\N	2026-10-29 11:17:27.082894+07	2026-10-29 11:17:27.082894+07
46	60	11	PRESENT	\N	22	\N	2026-10-29 11:17:27.143008+07	2026-10-29 11:17:27.143008+07
47	60	17	PRESENT	\N	22	\N	2026-10-29 11:17:27.313297+07	2026-10-29 11:17:27.313297+07
48	60	13	LATE	\N	22	\N	2026-10-29 11:17:27.37251+07	2026-10-29 11:17:27.37251+07
49	61	9	ABSENT	\N	22	\N	2026-10-29 11:17:27.627428+07	2026-10-29 11:17:27.627428+07
50	61	10	PRESENT	\N	22	\N	2026-10-29 11:17:27.786947+07	2026-10-29 11:17:27.786947+07
51	61	11	PRESENT	\N	22	\N	2026-10-29 11:17:27.84808+07	2026-10-29 11:17:27.84808+07
52	61	17	LATE	\N	22	\N	2026-10-29 11:17:27.909773+07	2026-10-29 11:17:27.909773+07
53	61	13	PRESENT	\N	22	\N	2026-10-29 11:17:27.970564+07	2026-10-29 11:17:27.970564+07
54	62	9	PRESENT	\N	22	\N	2026-10-29 11:17:28.215011+07	2026-10-29 11:17:28.215011+07
55	62	10	PRESENT	\N	22	\N	2026-10-29 11:17:28.276706+07	2026-10-29 11:17:28.276706+07
56	62	11	LATE	\N	22	\N	2026-10-29 11:17:28.339932+07	2026-10-29 11:17:28.339932+07
57	62	17	PRESENT	\N	22	\N	2026-10-29 11:17:28.400683+07	2026-10-29 11:17:28.400683+07
58	62	13	ABSENT	\N	22	\N	2026-10-29 11:17:28.462727+07	2026-10-29 11:17:28.462727+07
\.


--
-- Data for Name: class_evaluations; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.class_evaluations (id, registration_id, coach_id, grade, comment, created_at) FROM stdin;
1	15	22	A	Rất tốt, nên học lớp nâng cao	2026-10-29 11:17:56.61005+07
2	16	22	B	Tiến bộ đều	2026-10-29 11:17:56.62301+07
3	17	22	B	Cần chăm chỉ hơn	2026-10-29 11:17:56.633556+07
4	18	22	C	Nên tập thêm ở nhà	2026-10-29 11:17:56.650264+07
5	19	22	A	Xuất sắc	2026-10-29 11:17:56.663582+07
\.


--
-- Data for Name: class_registrations; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.class_registrations (id, class_id, user_id, registered_at, status, canceled_at, registered_by, hold_expires_at, waitlist_rank, offer_expires_at) FROM stdin;
1	2	9	2026-10-29 11:17:11.639864+07	REGISTERED	\N	2	\N	0	\N
2	2	10	2026-10-29 11:17:11.699889+07	REGISTERED	\N	2	\N	0	\N
3	2	11	2026-10-29 11:17:11.74331+07	REGISTERED	\N	2	\N	0	\N
4	1	9	2026-10-29 11:17:11.782204+07	REGISTERED	\N	2	\N	0	\N
5	3	14	2026-10-29 11:17:11.84186+07	REGISTERED	\N	2	\N	0	\N
6	3	15	2026-10-29 11:17:11.887319+07	REGISTERED	\N	2	\N	0	\N
7	1	16	2026-10-29 11:17:11.925176+07	REGISTERED	\N	2	\N	0	\N
8	4	15	2026-10-29 11:17:11.976801+07	REGISTERED	\N	2	\N	0	\N
9	4	17	2026-10-29 11:17:12.019218+07	REGISTERED	\N	2	\N	0	\N
10	4	19	2026-10-29 11:17:12.05675+07	REGISTERED	\N	2	\N	0	\N
23	2	13	2026-10-29 11:17:12.770597+07	WAITLISTED	\N	\N	\N	0	\N
20	7	18	2026-10-29 11:17:12.460011+07	CANCELED	2026-10-29 11:17:28.700969+07	2	\N	0	\N
21	7	19	2026-10-29 11:17:12.493323+07	CANCELED	2026-10-29 11:17:28.711864+07	2	\N	0	\N
22	7	20	2026-10-29 11:17:12.524666+07	CANCELED	2026-10-29 11:17:28.722288+07	2	\N	0	\N
11	5	16	2026-10-09 11:17:44.599759+07	REGISTERED	\N	2	\N	0	\N
12	5	18	2026-10-09 11:17:44.599759+07	REGISTERED	\N	2	\N	0	\N
13	5	20	2026-10-09 11:17:44.599759+07	REGISTERED	\N	2	\N	0	\N
14	5	12	2026-10-09 11:17:44.599759+07	REGISTERED	\N	2	\N	0	\N
15	6	9	2026-09-19 11:17:44.602996+07	REGISTERED	\N	2	\N	0	\N
16	6	10	2026-09-19 11:17:44.602996+07	REGISTERED	\N	2	\N	0	\N
17	6	11	2026-09-19 11:17:44.602996+07	REGISTERED	\N	2	\N	0	\N
18	6	17	2026-09-19 11:17:44.602996+07	REGISTERED	\N	2	\N	0	\N
19	6	13	2026-09-19 11:17:44.602996+07	REGISTERED	\N	2	\N	0	\N
24	3	20	2026-10-29 11:17:12.902062+07	CANCELED	2026-10-29 11:35:00.017896+07	\N	2026-10-29 11:32:12.902062+07	0	\N
25	1	10	2026-10-31 10:57:01.892681+07	CANCELED	2026-10-31 10:57:07.935667+07	\N	2026-10-31 11:12:01.891679+07	0	\N
26	4	10	2026-10-31 10:59:14.357427+07	CANCELED	2026-10-31 10:59:21.588051+07	\N	2026-10-31 11:14:14.357427+07	0	\N
\.


--
-- Data for Name: class_schedules; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.class_schedules (id, class_id, room_id, day_of_week, start_time, end_time, time_slot_id) FROM stdin;
1	1	1	1	10:00:00	11:45:00	3
2	1	1	3	10:00:00	11:45:00	3
3	1	1	5	10:00:00	11:45:00	3
4	2	2	2	18:00:00	19:45:00	7
5	2	2	4	18:00:00	19:45:00	7
6	3	9	6	10:00:00	11:45:00	3
7	3	9	7	10:00:00	11:45:00	3
8	4	10	1	18:00:00	19:45:00	7
9	4	10	3	18:00:00	19:45:00	7
10	5	8	2	18:00:00	19:45:00	7
11	5	8	4	18:00:00	19:45:00	7
12	6	3	1	14:00:00	15:45:00	5
13	6	3	3	14:00:00	15:45:00	5
14	7	4	2	12:00:00	13:45:00	4
15	7	4	4	12:00:00	13:45:00	4
\.


--
-- Data for Name: class_sessions; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.class_sessions (id, class_id, session_no, session_date, start_time, end_time, room_id, coach_id, status, is_makeup, cancel_reason, created_at, updated_at, attendance_finalized_at, training_version) FROM stdin;
1	1	1	2026-11-09	10:00:00	11:45:00	1	21	SCHEDULED	f	\N	2026-10-29 11:17:10.418822+07	2026-10-29 11:17:10.418822+07	\N	0
2	1	2	2026-11-11	10:00:00	11:45:00	1	21	SCHEDULED	f	\N	2026-10-29 11:17:10.418822+07	2026-10-29 11:17:10.418822+07	\N	0
3	1	3	2026-11-13	10:00:00	11:45:00	1	21	SCHEDULED	f	\N	2026-10-29 11:17:10.418822+07	2026-10-29 11:17:10.418822+07	\N	0
4	1	4	2026-11-16	10:00:00	11:45:00	1	21	SCHEDULED	f	\N	2026-10-29 11:17:10.418822+07	2026-10-29 11:17:10.418822+07	\N	0
5	1	5	2026-11-18	10:00:00	11:45:00	1	21	SCHEDULED	f	\N	2026-10-29 11:17:10.418822+07	2026-10-29 11:17:10.418822+07	\N	0
6	1	6	2026-11-20	10:00:00	11:45:00	1	21	SCHEDULED	f	\N	2026-10-29 11:17:10.418822+07	2026-10-29 11:17:10.418822+07	\N	0
7	1	7	2026-11-23	10:00:00	11:45:00	1	21	SCHEDULED	f	\N	2026-10-29 11:17:10.418822+07	2026-10-29 11:17:10.418822+07	\N	0
8	1	8	2026-11-25	10:00:00	11:45:00	1	21	SCHEDULED	f	\N	2026-10-29 11:17:10.418822+07	2026-10-29 11:17:10.418822+07	\N	0
9	1	9	2026-11-27	10:00:00	11:45:00	1	21	SCHEDULED	f	\N	2026-10-29 11:17:10.418822+07	2026-10-29 11:17:10.418822+07	\N	0
10	1	10	2026-11-30	10:00:00	11:45:00	1	21	SCHEDULED	f	\N	2026-10-29 11:17:10.418822+07	2026-10-29 11:17:10.418822+07	\N	0
11	1	11	2026-12-02	10:00:00	11:45:00	1	21	SCHEDULED	f	\N	2026-10-29 11:17:10.418822+07	2026-10-29 11:17:10.418822+07	\N	0
12	1	12	2026-12-04	10:00:00	11:45:00	1	21	SCHEDULED	f	\N	2026-10-29 11:17:10.418822+07	2026-10-29 11:17:10.418822+07	\N	0
13	2	1	2026-11-05	18:00:00	19:45:00	2	22	SCHEDULED	f	\N	2026-10-29 11:17:10.597577+07	2026-10-29 11:17:10.597577+07	\N	0
14	2	2	2026-11-10	18:00:00	19:45:00	2	22	SCHEDULED	f	\N	2026-10-29 11:17:10.597577+07	2026-10-29 11:17:10.597577+07	\N	0
15	2	3	2026-11-12	18:00:00	19:45:00	2	22	SCHEDULED	f	\N	2026-10-29 11:17:10.597577+07	2026-10-29 11:17:10.597577+07	\N	0
16	2	4	2026-11-17	18:00:00	19:45:00	2	22	SCHEDULED	f	\N	2026-10-29 11:17:10.597577+07	2026-10-29 11:17:10.597577+07	\N	0
17	2	5	2026-11-19	18:00:00	19:45:00	2	22	SCHEDULED	f	\N	2026-10-29 11:17:10.597577+07	2026-10-29 11:17:10.597577+07	\N	0
18	2	6	2026-11-24	18:00:00	19:45:00	2	22	SCHEDULED	f	\N	2026-10-29 11:17:10.597577+07	2026-10-29 11:17:10.597577+07	\N	0
19	2	7	2026-11-26	18:00:00	19:45:00	2	22	SCHEDULED	f	\N	2026-10-29 11:17:10.597577+07	2026-10-29 11:17:10.597577+07	\N	0
20	2	8	2026-12-01	18:00:00	19:45:00	2	22	SCHEDULED	f	\N	2026-10-29 11:17:10.597577+07	2026-10-29 11:17:10.597577+07	\N	0
21	2	9	2026-12-03	18:00:00	19:45:00	2	22	SCHEDULED	f	\N	2026-10-29 11:17:10.597577+07	2026-10-29 11:17:10.597577+07	\N	0
22	2	10	2026-12-08	18:00:00	19:45:00	2	22	SCHEDULED	f	\N	2026-10-29 11:17:10.597577+07	2026-10-29 11:17:10.597577+07	\N	0
23	3	1	2026-11-07	10:00:00	11:45:00	9	24	SCHEDULED	f	\N	2026-10-29 11:17:10.69424+07	2026-10-29 11:17:10.69424+07	\N	0
24	3	2	2026-11-08	10:00:00	11:45:00	9	24	SCHEDULED	f	\N	2026-10-29 11:17:10.69424+07	2026-10-29 11:17:10.69424+07	\N	0
25	3	3	2026-11-14	10:00:00	11:45:00	9	24	SCHEDULED	f	\N	2026-10-29 11:17:10.69424+07	2026-10-29 11:17:10.69424+07	\N	0
26	3	4	2026-11-15	10:00:00	11:45:00	9	24	SCHEDULED	f	\N	2026-10-29 11:17:10.69424+07	2026-10-29 11:17:10.69424+07	\N	0
27	3	5	2026-11-21	10:00:00	11:45:00	9	24	SCHEDULED	f	\N	2026-10-29 11:17:10.69424+07	2026-10-29 11:17:10.69424+07	\N	0
28	3	6	2026-11-22	10:00:00	11:45:00	9	24	SCHEDULED	f	\N	2026-10-29 11:17:10.69424+07	2026-10-29 11:17:10.69424+07	\N	0
29	3	7	2026-11-28	10:00:00	11:45:00	9	24	SCHEDULED	f	\N	2026-10-29 11:17:10.69424+07	2026-10-29 11:17:10.69424+07	\N	0
30	3	8	2026-11-29	10:00:00	11:45:00	9	24	SCHEDULED	f	\N	2026-10-29 11:17:10.69424+07	2026-10-29 11:17:10.69424+07	\N	0
31	4	1	2026-11-02	18:00:00	19:45:00	10	26	SCHEDULED	f	\N	2026-10-29 11:17:10.78111+07	2026-10-29 11:17:10.78111+07	\N	0
32	4	2	2026-11-04	18:00:00	19:45:00	10	26	SCHEDULED	f	\N	2026-10-29 11:17:10.78111+07	2026-10-29 11:17:10.78111+07	\N	0
33	4	3	2026-11-09	18:00:00	19:45:00	10	26	SCHEDULED	f	\N	2026-10-29 11:17:10.78111+07	2026-10-29 11:17:10.78111+07	\N	0
34	4	4	2026-11-11	18:00:00	19:45:00	10	26	SCHEDULED	f	\N	2026-10-29 11:17:10.78111+07	2026-10-29 11:17:10.78111+07	\N	0
35	4	5	2026-11-16	18:00:00	19:45:00	10	26	SCHEDULED	f	\N	2026-10-29 11:17:10.78111+07	2026-10-29 11:17:10.78111+07	\N	0
36	4	6	2026-11-18	18:00:00	19:45:00	10	26	SCHEDULED	f	\N	2026-10-29 11:17:10.78111+07	2026-10-29 11:17:10.78111+07	\N	0
37	4	7	2026-11-23	18:00:00	19:45:00	10	26	SCHEDULED	f	\N	2026-10-29 11:17:10.78111+07	2026-10-29 11:17:10.78111+07	\N	0
38	4	8	2026-11-25	18:00:00	19:45:00	10	26	SCHEDULED	f	\N	2026-10-29 11:17:10.78111+07	2026-10-29 11:17:10.78111+07	\N	0
39	4	9	2026-11-30	18:00:00	19:45:00	10	26	SCHEDULED	f	\N	2026-10-29 11:17:10.78111+07	2026-10-29 11:17:10.78111+07	\N	0
40	4	10	2026-12-02	18:00:00	19:45:00	10	26	SCHEDULED	f	\N	2026-10-29 11:17:10.78111+07	2026-10-29 11:17:10.78111+07	\N	0
53	6	1	2026-09-26	14:00:00	15:45:00	3	22	DONE	f	\N	2026-10-29 11:17:10.975211+07	2026-10-29 11:17:23.007893+07	2026-09-26 15:45:00+07	0
54	6	2	2026-09-28	14:00:00	15:45:00	3	22	DONE	f	\N	2026-10-29 11:17:10.975211+07	2026-10-29 11:17:23.509381+07	2026-09-28 15:45:00+07	0
55	6	3	2026-10-03	14:00:00	15:45:00	3	22	DONE	f	\N	2026-10-29 11:17:10.975211+07	2026-10-29 11:17:24.010807+07	2026-10-03 15:45:00+07	0
56	6	4	2026-10-05	14:00:00	15:45:00	3	22	DONE	f	\N	2026-10-29 11:17:10.975211+07	2026-10-29 11:17:24.400148+07	2026-10-05 15:45:00+07	0
57	6	5	2026-10-10	14:00:00	15:45:00	3	22	DONE	f	\N	2026-10-29 11:17:10.975211+07	2026-10-29 11:17:25.24854+07	2026-10-10 15:45:00+07	0
58	6	6	2026-10-12	14:00:00	15:45:00	3	22	DONE	f	\N	2026-10-29 11:17:10.975211+07	2026-10-29 11:17:25.878814+07	2026-10-12 15:45:00+07	0
59	6	7	2026-10-17	14:00:00	15:45:00	3	22	DONE	f	\N	2026-10-29 11:17:10.975211+07	2026-10-29 11:17:26.477208+07	2026-10-17 15:45:00+07	0
60	6	8	2026-10-19	14:00:00	15:45:00	3	22	DONE	f	\N	2026-10-29 11:17:10.975211+07	2026-10-29 11:17:26.952833+07	2026-10-19 15:45:00+07	0
61	6	9	2026-10-24	14:00:00	15:45:00	3	22	DONE	f	\N	2026-10-29 11:17:10.975211+07	2026-10-29 11:17:27.450996+07	2026-10-24 15:45:00+07	0
44	5	4	2026-10-31	18:00:00	19:45:00	8	25	SCHEDULED	f	\N	2026-10-29 11:17:10.875694+07	2026-10-29 11:17:20.886787+07	\N	0
45	5	5	2026-11-05	18:00:00	19:45:00	8	25	SCHEDULED	f	\N	2026-10-29 11:17:10.875694+07	2026-10-29 11:17:20.886787+07	\N	0
46	5	6	2026-11-07	18:00:00	19:45:00	8	25	SCHEDULED	f	\N	2026-10-29 11:17:10.875694+07	2026-10-29 11:17:20.886787+07	\N	0
47	5	7	2026-11-12	18:00:00	19:45:00	8	25	SCHEDULED	f	\N	2026-10-29 11:17:10.875694+07	2026-10-29 11:17:20.886787+07	\N	0
48	5	8	2026-11-14	18:00:00	19:45:00	8	25	SCHEDULED	f	\N	2026-10-29 11:17:10.875694+07	2026-10-29 11:17:20.886787+07	\N	0
49	5	9	2026-11-19	18:00:00	19:45:00	8	25	SCHEDULED	f	\N	2026-10-29 11:17:10.875694+07	2026-10-29 11:17:20.886787+07	\N	0
50	5	10	2026-11-21	18:00:00	19:45:00	8	25	SCHEDULED	f	\N	2026-10-29 11:17:10.875694+07	2026-10-29 11:17:20.886787+07	\N	0
51	5	11	2026-11-26	18:00:00	19:45:00	8	25	SCHEDULED	f	\N	2026-10-29 11:17:10.875694+07	2026-10-29 11:17:20.886787+07	\N	0
52	5	12	2026-11-28	18:00:00	19:45:00	8	25	SCHEDULED	f	\N	2026-10-29 11:17:10.875694+07	2026-10-29 11:17:20.886787+07	\N	0
43	5	3	2026-10-29	06:00:00	07:45:00	8	25	SCHEDULED	f	\N	2026-10-29 11:17:10.875694+07	2026-10-29 11:17:21.165287+07	\N	0
41	5	1	2026-10-22	18:00:00	19:45:00	8	25	DONE	f	\N	2026-10-29 11:17:10.875694+07	2026-10-29 11:17:21.52358+07	2026-10-22 19:45:00+07	0
42	5	2	2026-10-24	18:00:00	19:45:00	8	25	DONE	f	\N	2026-10-29 11:17:10.875694+07	2026-10-29 11:17:21.929796+07	2026-10-24 19:45:00+07	0
62	6	10	2026-10-26	14:00:00	15:45:00	3	22	DONE	f	\N	2026-10-29 11:17:10.975211+07	2026-10-29 11:17:28.032723+07	2026-10-26 15:45:00+07	0
63	7	1	2026-11-10	12:00:00	13:45:00	4	23	CANCELED_BY_CENTER	f	Coach nghỉ đột xuất, trung tâm hủy lớp	2026-10-29 11:17:11.049928+07	2026-10-29 11:17:28.681038+07	\N	0
64	7	2	2026-11-12	12:00:00	13:45:00	4	23	CANCELED_BY_CENTER	f	Coach nghỉ đột xuất, trung tâm hủy lớp	2026-10-29 11:17:11.049928+07	2026-10-29 11:17:28.681038+07	\N	0
65	7	3	2026-11-17	12:00:00	13:45:00	4	23	CANCELED_BY_CENTER	f	Coach nghỉ đột xuất, trung tâm hủy lớp	2026-10-29 11:17:11.049928+07	2026-10-29 11:17:28.681038+07	\N	0
66	7	4	2026-11-19	12:00:00	13:45:00	4	23	CANCELED_BY_CENTER	f	Coach nghỉ đột xuất, trung tâm hủy lớp	2026-10-29 11:17:11.049928+07	2026-10-29 11:17:28.681038+07	\N	0
67	7	5	2026-11-24	12:00:00	13:45:00	4	23	CANCELED_BY_CENTER	f	Coach nghỉ đột xuất, trung tâm hủy lớp	2026-10-29 11:17:11.049928+07	2026-10-29 11:17:28.681038+07	\N	0
68	7	6	2026-11-26	12:00:00	13:45:00	4	23	CANCELED_BY_CENTER	f	Coach nghỉ đột xuất, trung tâm hủy lớp	2026-10-29 11:17:11.049928+07	2026-10-29 11:17:28.681038+07	\N	0
69	7	7	2026-12-01	12:00:00	13:45:00	4	23	CANCELED_BY_CENTER	f	Coach nghỉ đột xuất, trung tâm hủy lớp	2026-10-29 11:17:11.049928+07	2026-10-29 11:17:28.681038+07	\N	0
70	7	8	2026-12-03	12:00:00	13:45:00	4	23	CANCELED_BY_CENTER	f	Coach nghỉ đột xuất, trung tâm hủy lớp	2026-10-29 11:17:11.049928+07	2026-10-29 11:17:28.681038+07	\N	0
\.


--
-- Data for Name: coach_profiles; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.coach_profiles (user_id, specialty, years_experience, certifications, rating, coach_rank) FROM stdin;
3	Thể lực, Bóng chuyền	1	ACE Certified Personal Trainer	\N	COACH
4	Yoga	3	RYT 200	\N	SENIOR
5	Bơi lội	6	Chứng chỉ huấn luyện viên bơi cấp quốc gia	\N	MASTER
21	Huấn luyện nâng cao	4	Chứng chỉ HLV quốc gia	\N	SENIOR
22	Huấn luyện cơ bản	1	Chứng chỉ sơ cấp	\N	COACH
23	Master coach	8	Chứng chỉ HLV quốc gia	\N	MASTER
24	Huấn luyện nâng cao	4	Chứng chỉ HLV quốc gia	\N	SENIOR
25	Huấn luyện cơ bản	1	Chứng chỉ sơ cấp	\N	COACH
26	Huấn luyện cơ bản	1	Chứng chỉ sơ cấp	\N	COACH
\.


--
-- Data for Name: coach_slots; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.coach_slots (id, coach_id, start_at, end_at, kind, status, member_id, held_until, price, canceled_by, canceled_at, created_at, room_id, category_id) FROM stdin;
2	21	2026-11-03 12:00:00+07	2026-11-03 13:45:00+07	ONE_ON_ONE	OPEN	\N	\N	\N	\N	\N	2026-10-29 11:17:13.429182+07	1	1
4	24	2026-11-04 16:00:00+07	2026-11-04 17:45:00+07	ONE_ON_ONE	OPEN	\N	\N	\N	\N	\N	2026-10-29 11:17:13.491523+07	9	6
5	25	2026-11-03 18:00:00+07	2026-11-03 19:45:00+07	ONE_ON_ONE	OPEN	\N	\N	\N	\N	\N	2026-10-29 11:17:13.517451+07	8	5
6	23	2026-11-04 20:00:00+07	2026-11-04 21:45:00+07	ONE_ON_ONE	OPEN	\N	\N	\N	\N	\N	2026-10-29 11:17:13.560978+07	4	3
7	26	2026-11-04 06:00:00+07	2026-11-04 07:45:00+07	ONE_ON_ONE	OPEN	\N	\N	\N	\N	\N	2026-10-29 11:17:13.605937+07	10	7
8	23	2026-11-05 08:00:00+07	2026-11-05 09:45:00+07	ONE_ON_ONE	OPEN	\N	\N	\N	\N	\N	2026-10-29 11:17:13.63828+07	4	3
1	21	2026-11-02 10:00:00+07	2026-11-02 11:45:00+07	ONE_ON_ONE	BOOKED	9	\N	700000	\N	\N	2026-10-29 11:17:13.383803+07	1	1
3	22	2026-11-02 14:00:00+07	2026-11-02 15:45:00+07	ONE_ON_ONE	CANCELED	10	2026-10-29 11:32:13.911122+07	437500	\N	2026-10-29 11:35:00.03+07	2026-10-29 11:17:13.464765+07	2	2
9	22	2026-11-02 14:00:00+07	2026-11-02 15:45:00+07	ONE_ON_ONE	OPEN	\N	\N	\N	\N	\N	2026-10-29 11:35:00.030445+07	2	2
11	21	2026-11-18 08:00:00+07	2026-11-18 09:45:00+07	ONE_ON_ONE	OPEN	\N	\N	\N	\N	\N	2026-10-29 14:17:33.631173+07	1	1
12	21	2026-11-19 06:00:00+07	2026-11-19 07:45:00+07	ONE_ON_ONE	OPEN	\N	\N	\N	\N	\N	2026-10-29 14:17:33.631173+07	1	1
13	21	2026-11-19 08:00:00+07	2026-11-19 09:45:00+07	ONE_ON_ONE	OPEN	\N	\N	\N	\N	\N	2026-10-29 14:17:33.631173+07	1	1
14	21	2026-11-20 06:00:00+07	2026-11-20 07:45:00+07	ONE_ON_ONE	OPEN	\N	\N	\N	\N	\N	2026-10-29 14:17:33.631173+07	1	1
15	21	2026-11-20 08:00:00+07	2026-11-20 09:45:00+07	ONE_ON_ONE	OPEN	\N	\N	\N	\N	\N	2026-10-29 14:17:33.631173+07	1	1
16	21	2026-11-21 06:00:00+07	2026-11-21 07:45:00+07	ONE_ON_ONE	OPEN	\N	\N	\N	\N	\N	2026-10-29 14:17:33.631173+07	1	1
17	21	2026-11-21 08:00:00+07	2026-11-21 09:45:00+07	ONE_ON_ONE	OPEN	\N	\N	\N	\N	\N	2026-10-29 14:17:33.631173+07	1	1
18	21	2026-11-22 06:00:00+07	2026-11-22 07:45:00+07	ONE_ON_ONE	OPEN	\N	\N	\N	\N	\N	2026-10-29 14:17:33.631173+07	1	1
19	21	2026-11-22 08:00:00+07	2026-11-22 09:45:00+07	ONE_ON_ONE	OPEN	\N	\N	\N	\N	\N	2026-10-29 14:17:33.631173+07	1	1
20	21	2026-11-23 06:00:00+07	2026-11-23 07:45:00+07	ONE_ON_ONE	OPEN	\N	\N	\N	\N	\N	2026-10-29 14:17:33.631173+07	1	1
21	21	2026-11-23 08:00:00+07	2026-11-23 09:45:00+07	ONE_ON_ONE	OPEN	\N	\N	\N	\N	\N	2026-10-29 14:17:33.631173+07	1	1
22	21	2026-11-24 06:00:00+07	2026-11-24 07:45:00+07	ONE_ON_ONE	OPEN	\N	\N	\N	\N	\N	2026-10-29 14:17:33.631173+07	1	1
23	21	2026-11-24 08:00:00+07	2026-11-24 09:45:00+07	ONE_ON_ONE	CANCELED	9	\N	700000	1	2026-10-29 14:17:48.652494+07	2026-10-29 14:17:33.631173+07	1	1
24	21	2026-11-30 08:00:00+07	2026-11-30 09:45:00+07	ONE_ON_ONE	OPEN	\N	\N	\N	\N	\N	2026-10-29 14:19:17.724321+07	1	1
25	21	2026-12-02 08:00:00+07	2026-12-02 09:45:00+07	ONE_ON_ONE	OPEN	\N	\N	\N	\N	\N	2026-10-29 14:19:17.724321+07	1	1
\.


--
-- Data for Name: coach_sports; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.coach_sports (coach_id, category_id) FROM stdin;
5	1
4	2
3	3
3	4
21	1
22	2
23	3
23	6
24	6
25	5
26	7
\.


--
-- Data for Name: courses; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.courses (id, category_id, name, description, level, total_sessions, default_tuition, prerequisite_course_id, is_active, created_at, updated_at) FROM stdin;
1	1	Bơi cơ bản	Làm quen với nước, bơi sải cơ bản	BEGINNER	12	1200000	\N	t	2026-10-23 18:14:43.228636+07	2026-10-23 18:14:43.228636+07
2	2	Yoga cơ bản	Các tư thế nền tảng và hít thở	BEGINNER	10	900000	\N	t	2026-10-23 18:14:43.228636+07	2026-10-23 18:14:43.228636+07
3	3	Bóng chuyền cơ bản	Chuyền, đệm, phát bóng	BEGINNER	8	800000	\N	t	2026-10-23 18:14:43.228636+07	2026-10-23 18:14:43.228636+07
4	1	Bơi nâng cao	Bơi ếch, bơi ngửa, kỹ thuật xuất phát	ADVANCED	12	1800000	1	t	2026-10-23 18:14:43.228636+07	2026-10-23 18:14:43.228636+07
5	6	Cầu lông cơ bản	Cầu lông cơ bản cho người mới bắt đầu	BEGINNER	8	700000	\N	t	2026-10-29 11:17:10.138756+07	2026-10-29 11:17:10.138756+07
6	7	Zumba nhập môn	Zumba nhập môn cho người mới bắt đầu	BEGINNER	10	600000	\N	t	2026-10-29 11:17:10.15699+07	2026-10-29 11:17:10.15699+07
7	5	Boxing cơ bản	Boxing cơ bản cho người mới bắt đầu	BEGINNER	12	1400000	\N	t	2026-10-29 11:17:10.182013+07	2026-10-29 11:17:10.182013+07
8	4	Thể lực nền tảng	Thể lực nền tảng cho người mới bắt đầu	BEGINNER	12	1000000	\N	t	2026-10-29 11:17:10.197415+07	2026-10-29 11:17:10.197415+07
9	2	Yoga nâng cao	Yoga nâng cao cho người mới bắt đầu	ADVANCED	10	1100000	2	t	2026-10-29 11:17:10.214173+07	2026-10-29 11:17:10.214173+07
\.


--
-- Data for Name: facility_attendances; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.facility_attendances (id, user_id, check_in_time, check_out_time, recorded_by, notes) FROM stdin;
\.


--
-- Data for Name: facility_rooms; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.facility_rooms (id, room_name, floor, capacity, description, maintenance_status) FROM stdin;
1	Hồ bơi A	Tầng trệt	30	Hồ bơi 25m	AVAILABLE
2	Phòng Yoga 1	Tầng 2	20	Phòng yoga có gương	AVAILABLE
3	Phòng Yoga 2	Tầng 2	20	Phòng yoga dự phòng	AVAILABLE
4	Sân bóng chuyền	Tầng 3	30	Sân trong nhà	AVAILABLE
5	Khu tập tự do	Tầng 1	80	Khu máy và tạ tự do	AVAILABLE
6	Phòng thể lực 1	Tầng 1	25	Phòng thể lực nhóm nhỏ	AVAILABLE
7	Phòng thể lực 2	Tầng 1	25	Phòng thể lực nhóm nhỏ, dự phòng	AVAILABLE
8	Phòng Boxing	Tầng 2	20	Phòng boxing có bao cát	AVAILABLE
9	Sân cầu lông	Tầng 3	24	Sân cầu lông trong nhà	AVAILABLE
10	Phòng Zumba	Tầng 2	30	Phòng nhảy có gương và loa	AVAILABLE
\.


--
-- Data for Name: member_profiles; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.member_profiles (user_id, join_date, height_cm, weight_kg, fitness_goal) FROM stdin;
6	2026-10-23	\N	\N	\N
7	2026-10-23	160.00	52.00	Cải thiện sức bền
8	2026-10-23	175.50	70.30	Tăng cơ giảm mỡ
9	2026-10-29	\N	\N	Giảm cân, tăng sức bền
10	2026-10-29	\N	\N	Tăng cơ
11	2026-10-29	\N	\N	Dẻo dai, giảm căng thẳng
12	2026-10-29	\N	\N	Học bơi
13	2026-10-29	\N	\N	Thể lực chung
14	2026-10-29	\N	\N	Chơi cầu lông
15	2026-10-29	\N	\N	Nhảy zumba giảm mỡ
16	2026-10-29	\N	\N	Học boxing
17	2026-10-29	\N	\N	Yoga buổi tối
18	2026-10-29	\N	\N	Giảm cân
19	2026-10-29	\N	\N	Bóng chuyền
20	2026-10-29	\N	\N	Tăng thể lực
\.


--
-- Data for Name: member_subscriptions; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.member_subscriptions (id, user_id, package_id, package_name_snapshot, tier_level_snapshot, months, purchase_type, upgrade_from_subscription_id, upgrade_credit_amount, payment_expires_at, total_amount, start_date, end_date, status, benefits_snapshot, created_by, created_at, updated_at) FROM stdin;
1	9	2	Bứt Tốc	2	3	NEW	\N	0	\N	2070000	2026-10-29	2027-01-29	ACTIVE	{"ai_level": "LIMITED", "menu_ai_level": "NONE", "has_personal_plan": false, "waitlist_priority": 1, "birthday_gift_kind": "PERCENT", "birthday_gift_value": 10, "early_register_days": 2, "tuition_discount_pct": 5, "one_on_one_discount_pct": 0, "early_leave_refund_sessions": 0}	2	2026-10-29 11:17:11.190958+07	2026-10-29 11:17:11.269625+07
6	9	2	Bứt Tốc	2	6	RENEWAL	\N	0	\N	3825000	\N	\N	PENDING	{"ai_level": "LIMITED", "menu_ai_level": "NONE", "has_personal_plan": false, "waitlist_priority": 1, "birthday_gift_kind": "PERCENT", "birthday_gift_value": 10, "early_register_days": 2, "tuition_discount_pct": 5, "one_on_one_discount_pct": 0, "early_leave_refund_sessions": 0}	\N	2026-10-31 10:29:23.099917+07	2026-10-31 10:29:23.099917+07
2	10	3	Vượt Đỉnh	3	6	NEW	\N	0	\N	6120000	2026-10-29	2027-04-29	ACTIVE	{"ai_level": "LIMITED", "menu_ai_level": "LIMITED", "has_personal_plan": true, "waitlist_priority": 2, "birthday_gift_kind": "PERCENT", "birthday_gift_value": 20, "early_register_days": 5, "tuition_discount_pct": 10, "one_on_one_discount_pct": 10, "early_leave_refund_sessions": 1}	2	2026-10-29 11:17:11.331937+07	2026-10-29 11:17:11.362844+07
3	11	4	Quán Quân	4	12	NEW	\N	0	\N	18000000	2026-10-29	2027-10-29	ACTIVE	{"ai_level": "UNLIMITED", "menu_ai_level": "UNLIMITED", "has_personal_plan": true, "waitlist_priority": 3, "birthday_gift_kind": "FREE_ONE_ON_ONE", "birthday_gift_value": 0, "early_register_days": 7, "tuition_discount_pct": 15, "one_on_one_discount_pct": 20, "early_leave_refund_sessions": 2}	2	2026-10-29 11:17:11.391891+07	2026-10-29 11:17:11.425817+07
4	12	1	Khởi Động	1	1	NEW	\N	0	\N	450000	2026-10-29	2026-11-03	ACTIVE	{"ai_level": "NONE", "menu_ai_level": "NONE", "has_personal_plan": false, "waitlist_priority": 0, "birthday_gift_kind": "NONE", "birthday_gift_value": 0, "early_register_days": 0, "tuition_discount_pct": 0, "one_on_one_discount_pct": 0, "early_leave_refund_sessions": 0}	2	2026-10-29 11:17:11.45518+07	2026-10-29 11:17:11.561524+07
5	13	2	Bứt Tốc	2	1	NEW	\N	0	\N	750000	\N	\N	CANCELED	{"ai_level": "LIMITED", "menu_ai_level": "NONE", "has_personal_plan": false, "waitlist_priority": 1, "birthday_gift_kind": "PERCENT", "birthday_gift_value": 10, "early_register_days": 2, "tuition_discount_pct": 5, "one_on_one_discount_pct": 0, "early_leave_refund_sessions": 0}	2	2026-10-29 11:17:11.500941+07	2026-10-31 10:00:00.104846+07
7	10	2	Bứt Tốc	2	1	RENEWAL	\N	0	\N	750000	\N	\N	CANCELED	{"ai_level": "LIMITED", "menu_ai_level": "NONE", "has_personal_plan": false, "waitlist_priority": 1, "birthday_gift_kind": "PERCENT", "birthday_gift_value": 10, "early_register_days": 2, "tuition_discount_pct": 5, "one_on_one_discount_pct": 0, "early_leave_refund_sessions": 0}	\N	2026-10-31 10:44:00.93809+07	2026-10-31 10:44:11.205062+07
\.


--
-- Data for Name: membership_packages; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.membership_packages (id, code, name, description, tier_level, monthly_price, early_register_days, tuition_discount_pct, one_on_one_discount_pct, has_personal_plan, ai_level, menu_ai_level, early_leave_refund_sessions, waitlist_priority, birthday_gift_kind, birthday_gift_value, is_active, created_at, updated_at) FROM stdin;
1	KHOI_DONG	Khởi Động	Vé vào khu tập tự do cho người mới.	1	450000	0	0	0	f	NONE	NONE	0	0	NONE	0	t	2026-10-23 18:14:43.228636+07	2026-10-23 18:14:43.228636+07
2	BUT_TOC	Bứt Tốc	Đăng ký lớp sớm 2 ngày, giảm học phí lớp, trợ lý AI giới hạn lượt, ưu tiên hàng chờ.	2	750000	2	5	0	f	LIMITED	NONE	0	1	PERCENT	10	t	2026-10-23 18:14:43.228636+07	2026-10-29 11:15:18.164525+07
3	VUOT_DINH	Vượt Đỉnh	Bài tập riêng từ coach, giảm giá lớp và buổi 1-1, trợ lý AI và gợi ý thực đơn giới hạn lượt.	3	1200000	5	10	10	t	LIMITED	LIMITED	1	2	PERCENT	20	t	2026-10-23 18:14:43.228636+07	2026-10-29 11:15:18.164525+07
4	QUAN_QUAN	Quán Quân	Trọn quyền lợi: trợ lý AI và gợi ý thực đơn không giới hạn, giảm sâu học phí và buổi 1-1, tặng 1 buổi 1-1 ngày sinh nhật.	4	2000000	7	15	20	t	UNLIMITED	UNLIMITED	2	3	FREE_ONE_ON_ONE	0	t	2026-10-23 18:14:43.228636+07	2026-10-29 11:15:18.164525+07
\.


--
-- Data for Name: notifications; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.notifications (id, recipient_id, sender_id, type, channel, title, content, is_read, created_at) FROM stdin;
1	8	\N	MEMBERSHIP	APP	Chào mừng bạn đến với Sport Center	Tài khoản của bạn đã sẵn sàng.	f	2026-10-23 18:14:43.228636+07
2	12	\N	WORKOUT_PLAN	APP	Kế hoạch tập luyện mới	Kế hoạch 4 tuần: nền tảng boxing	f	2026-10-29 11:17:22.42624+07
3	20	\N	WORKOUT_PLAN	APP	Kế hoạch tập luyện mới	Kế hoạch 4 tuần: nền tảng boxing	f	2026-10-29 11:17:22.42624+07
4	18	\N	WORKOUT_PLAN	APP	Kế hoạch tập luyện mới	Kế hoạch 4 tuần: nền tảng boxing	f	2026-10-29 11:17:22.42624+07
5	16	\N	WORKOUT_PLAN	APP	Kế hoạch tập luyện mới	Kế hoạch 4 tuần: nền tảng boxing	f	2026-10-29 11:17:22.42624+07
6	18	\N	ANNOUNCEMENT	APP	Class canceled	Class Bóng chuyền thử nghiệm (sẽ hủy) was canceled by the center (Coach nghỉ đột xuất, trung tâm hủy lớp).	f	2026-10-29 11:17:28.681038+07
7	19	\N	ANNOUNCEMENT	APP	Class canceled	Class Bóng chuyền thử nghiệm (sẽ hủy) was canceled by the center (Coach nghỉ đột xuất, trung tâm hủy lớp).	f	2026-10-29 11:17:28.681038+07
8	20	\N	ANNOUNCEMENT	APP	Class canceled	Class Bóng chuyền thử nghiệm (sẽ hủy) was canceled by the center (Coach nghỉ đột xuất, trung tâm hủy lớp).	f	2026-10-29 11:17:28.681038+07
9	9	\N	ANNOUNCEMENT	APP	Class evaluation	Your coach evaluated you in Yoga cơ bản - đợt trước (đã kết thúc): grade A.	f	2026-10-29 11:17:56.61005+07
11	11	\N	ANNOUNCEMENT	APP	Class evaluation	Your coach evaluated you in Yoga cơ bản - đợt trước (đã kết thúc): grade B.	f	2026-10-29 11:17:56.633556+07
12	17	\N	ANNOUNCEMENT	APP	Class evaluation	Your coach evaluated you in Yoga cơ bản - đợt trước (đã kết thúc): grade C.	f	2026-10-29 11:17:56.650264+07
13	13	\N	ANNOUNCEMENT	APP	Class evaluation	Your coach evaluated you in Yoga cơ bản - đợt trước (đã kết thúc): grade A.	f	2026-10-29 11:17:56.663582+07
14	21	\N	BOOKING	APP	New 1-1 sessions scheduled	14 new Bơi lội 1-1 session(s) were added to your schedule by the center.	f	2026-10-29 14:17:33.631173+07
15	21	\N	BOOKING	APP	New 1-1 booking	A member booked your 1-1 session on 24/11 08:00.	f	2026-10-29 14:17:48.564998+07
17	21	\N	BOOKING	APP	Session canceled by the center	Your 1-1 session on 24/11 08:00 was canceled by the center (Phong bao tri).	f	2026-10-29 14:17:48.650288+07
18	21	\N	BOOKING	APP	New 1-1 sessions scheduled	2 new Bơi lội 1-1 session(s) were added to your schedule by the center.	f	2026-10-29 14:19:17.724321+07
16	9	\N	BOOKING	APP	Session canceled by the center	Your session on 24/11 08:00 was canceled by the center (Phong bao tri).	t	2026-10-29 14:17:48.650288+07
10	10	\N	ANNOUNCEMENT	APP	Class evaluation	Your coach evaluated you in Yoga cơ bản - đợt trước (đã kết thúc): grade B.	t	2026-10-29 11:17:56.62301+07
\.


--
-- Data for Name: orders; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.orders (id, member_id, created_by, channel, subscription_id, registration_id, coach_slot_id, list_price, discount_kind, discount_amount, voucher_id, total_amount, status, created_at, updated_at) FROM stdin;
1	9	2	COUNTER	1	\N	\N	2070000	NONE	0	\N	2070000	PAID	2026-10-29 11:17:11.190958+07	2026-10-29 11:17:11.269625+07
2	10	2	COUNTER	2	\N	\N	6120000	NONE	0	\N	6120000	PAID	2026-10-29 11:17:11.331937+07	2026-10-29 11:17:11.362844+07
3	11	2	COUNTER	3	\N	\N	18000000	NONE	0	\N	18000000	PAID	2026-10-29 11:17:11.391891+07	2026-10-29 11:17:11.425817+07
4	12	2	COUNTER	4	\N	\N	450000	NONE	0	\N	450000	PAID	2026-10-29 11:17:11.45518+07	2026-10-29 11:17:11.47963+07
6	9	2	COUNTER	\N	1	\N	900000	TIER	45000	\N	855000	PAID	2026-10-29 11:17:11.659911+07	2026-10-29 11:17:11.659911+07
7	10	2	COUNTER	\N	2	\N	900000	TIER	90000	\N	810000	PAID	2026-10-29 11:17:11.713712+07	2026-10-29 11:17:11.713712+07
8	11	2	COUNTER	\N	3	\N	900000	TIER	135000	\N	765000	PAID	2026-10-29 11:17:11.754013+07	2026-10-29 11:17:11.754013+07
9	9	2	COUNTER	\N	4	\N	1200000	TIER	60000	\N	1140000	PAID	2026-10-29 11:17:11.795801+07	2026-10-29 11:17:11.795801+07
10	14	2	COUNTER	\N	5	\N	700000	NONE	0	\N	700000	PAID	2026-10-29 11:17:11.854182+07	2026-10-29 11:17:11.854182+07
11	15	2	COUNTER	\N	6	\N	700000	NONE	0	\N	700000	PAID	2026-10-29 11:17:11.896959+07	2026-10-29 11:17:11.896959+07
12	16	2	COUNTER	\N	7	\N	1200000	NONE	0	\N	1200000	PAID	2026-10-29 11:17:11.938356+07	2026-10-29 11:17:11.938356+07
13	15	2	COUNTER	\N	8	\N	600000	NONE	0	\N	600000	PAID	2026-10-29 11:17:11.990139+07	2026-10-29 11:17:11.990139+07
14	17	2	COUNTER	\N	9	\N	600000	NONE	0	\N	600000	PAID	2026-10-29 11:17:12.028111+07	2026-10-29 11:17:12.028111+07
15	19	2	COUNTER	\N	10	\N	600000	NONE	0	\N	600000	PAID	2026-10-29 11:17:12.065723+07	2026-10-29 11:17:12.065723+07
16	16	2	COUNTER	\N	11	\N	1400000	NONE	0	\N	1400000	PAID	2026-10-29 11:17:12.101418+07	2026-10-29 11:17:12.101418+07
17	18	2	COUNTER	\N	12	\N	1400000	NONE	0	\N	1400000	PAID	2026-10-29 11:17:12.136609+07	2026-10-29 11:17:12.136609+07
18	20	2	COUNTER	\N	13	\N	1400000	NONE	0	\N	1400000	PAID	2026-10-29 11:17:12.172118+07	2026-10-29 11:17:12.172118+07
19	12	2	COUNTER	\N	14	\N	1400000	NONE	0	\N	1400000	PAID	2026-10-29 11:17:12.207341+07	2026-10-29 11:17:12.207341+07
20	9	2	COUNTER	\N	15	\N	900000	TIER	45000	\N	855000	PAID	2026-10-29 11:17:12.241075+07	2026-10-29 11:17:12.241075+07
21	10	2	COUNTER	\N	16	\N	900000	TIER	90000	\N	810000	PAID	2026-10-29 11:17:12.28604+07	2026-10-29 11:17:12.28604+07
22	11	2	COUNTER	\N	17	\N	900000	TIER	135000	\N	765000	PAID	2026-10-29 11:17:12.334353+07	2026-10-29 11:17:12.334353+07
23	17	2	COUNTER	\N	18	\N	900000	NONE	0	\N	900000	PAID	2026-10-29 11:17:12.381038+07	2026-10-29 11:17:12.381038+07
24	13	2	COUNTER	\N	19	\N	900000	NONE	0	\N	900000	PAID	2026-10-29 11:17:12.431128+07	2026-10-29 11:17:12.431128+07
5	13	2	COUNTER	5	\N	\N	750000	NONE	0	\N	750000	EXPIRED	2026-10-29 11:17:11.500941+07	2026-10-31 10:00:00.104846+07
30	9	\N	ONLINE	6	\N	\N	3825000	NONE	0	\N	3825000	PENDING	2026-10-31 10:29:23.099917+07	2026-10-31 10:29:23.099917+07
28	9	2	COUNTER	\N	\N	1	700000	NONE	0	\N	700000	PAID	2026-10-29 11:17:13.891642+07	2026-10-29 11:17:13.891642+07
25	18	2	COUNTER	\N	20	\N	800000	NONE	0	\N	800000	CANCELED	2026-10-29 11:17:12.472974+07	2026-10-29 11:17:28.681038+07
26	19	2	COUNTER	\N	21	\N	800000	NONE	0	\N	800000	CANCELED	2026-10-29 11:17:12.50313+07	2026-10-29 11:17:28.681038+07
27	20	2	COUNTER	\N	22	\N	800000	NONE	0	\N	800000	CANCELED	2026-10-29 11:17:12.537071+07	2026-10-29 11:17:28.681038+07
31	10	\N	ONLINE	7	\N	\N	750000	NONE	0	\N	750000	CANCELED	2026-10-31 10:44:00.93809+07	2026-10-31 10:44:11.205062+07
29	9	2	COUNTER	\N	\N	23	700000	NONE	0	\N	700000	CANCELED	2026-10-29 14:17:48.564998+07	2026-10-29 14:17:48.650288+07
\.


--
-- Data for Name: payments; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.payments (id, order_id, payer_id, received_by, invoice_number, transaction_code, amount, payment_method, status, paid_at, gateway_response, gateway_order_code, payment_url, expires_at, created_at) FROM stdin;
1	1	9	2	INV-77269708f01b4063b818	CASH-77269708f01b4063b818ca232828d609	2070000	CASH	SUCCESS	2026-10-29 11:17:11.28451+07	\N	\N	\N	\N	2026-10-29 11:17:11.269625+07
2	2	10	2	INV-eae63b93ed9a47c68e92	CASH-eae63b93ed9a47c68e92e45366195942	6120000	CASH	SUCCESS	2026-10-29 11:17:11.368568+07	\N	\N	\N	\N	2026-10-29 11:17:11.362844+07
3	3	11	2	INV-a78953e6325e4c41856e	CASH-a78953e6325e4c41856e5814d4939523	18000000	CASH	SUCCESS	2026-10-29 11:17:11.432298+07	\N	\N	\N	\N	2026-10-29 11:17:11.425817+07
4	4	12	2	INV-a866051918974fbb83e3	CASH-a866051918974fbb83e3e005dd2cbb57	450000	CASH	SUCCESS	2026-10-29 11:17:11.484574+07	\N	\N	\N	\N	2026-10-29 11:17:11.47963+07
5	6	9	2	INV-a168b833682a475b8744	CASH-a168b833682a475b87445bb3fdf43747	855000	CASH	SUCCESS	2026-10-29 11:17:11.664341+07	\N	\N	\N	\N	2026-10-29 11:17:11.659911+07
6	7	10	2	INV-32c421e098b040739386	CASH-32c421e098b040739386341de819fa21	810000	CASH	SUCCESS	2026-10-29 11:17:11.715996+07	\N	\N	\N	\N	2026-10-29 11:17:11.713712+07
7	8	11	2	INV-842ffe9ce443490895e3	CASH-842ffe9ce443490895e3e719294b6485	765000	CASH	SUCCESS	2026-10-29 11:17:11.756059+07	\N	\N	\N	\N	2026-10-29 11:17:11.754013+07
8	9	9	2	INV-9c54697bc4a449539a00	CASH-9c54697bc4a449539a00c7ff391c06d4	1140000	CASH	SUCCESS	2026-10-29 11:17:11.798771+07	\N	\N	\N	\N	2026-10-29 11:17:11.795801+07
9	10	14	2	INV-5207f9aca3e0466ebe45	CASH-5207f9aca3e0466ebe45caee9cf67e80	700000	CASH	SUCCESS	2026-10-29 11:17:11.857274+07	\N	\N	\N	\N	2026-10-29 11:17:11.854182+07
10	11	15	2	INV-b6fad2e503fd48deacd6	CASH-b6fad2e503fd48deacd69f0ee8610f7a	700000	CASH	SUCCESS	2026-10-29 11:17:11.897629+07	\N	\N	\N	\N	2026-10-29 11:17:11.896959+07
11	12	16	2	INV-9f3dc45b18b14bb99ab2	CASH-9f3dc45b18b14bb99ab2b4fe660b79c2	1200000	CASH	SUCCESS	2026-10-29 11:17:11.94167+07	\N	\N	\N	\N	2026-10-29 11:17:11.938356+07
12	13	15	2	INV-b53a5314d53c43578fec	CASH-b53a5314d53c43578fec4c6528575380	600000	CASH	SUCCESS	2026-10-29 11:17:11.992599+07	\N	\N	\N	\N	2026-10-29 11:17:11.990139+07
13	14	17	2	INV-3bd54fc65dc447b79fdc	CASH-3bd54fc65dc447b79fdce63058bcf7fe	600000	CASH	SUCCESS	2026-10-29 11:17:12.029909+07	\N	\N	\N	\N	2026-10-29 11:17:12.028111+07
14	15	19	2	INV-6fb4c91ff22346479c37	CASH-6fb4c91ff22346479c379b859512b43f	600000	CASH	SUCCESS	2026-10-29 11:17:12.066765+07	\N	\N	\N	\N	2026-10-29 11:17:12.065723+07
15	16	16	2	INV-0ecd1d8e8cb9457e91a8	CASH-0ecd1d8e8cb9457e91a8004cded985ae	1400000	CASH	SUCCESS	2026-10-29 11:17:12.102638+07	\N	\N	\N	\N	2026-10-29 11:17:12.101418+07
16	17	18	2	INV-5803433548a44eda9e52	CASH-5803433548a44eda9e5277140b37da90	1400000	CASH	SUCCESS	2026-10-29 11:17:12.13895+07	\N	\N	\N	\N	2026-10-29 11:17:12.136609+07
17	18	20	2	INV-e111b27dedf44450ba88	CASH-e111b27dedf44450ba88ca36993b2146	1400000	CASH	SUCCESS	2026-10-29 11:17:12.173967+07	\N	\N	\N	\N	2026-10-29 11:17:12.172118+07
18	19	12	2	INV-28b0d8eba5214cd8a3bf	CASH-28b0d8eba5214cd8a3bfd87534773a11	1400000	CASH	SUCCESS	2026-10-29 11:17:12.208473+07	\N	\N	\N	\N	2026-10-29 11:17:12.207341+07
19	20	9	2	INV-262a9d9b00924195aa21	CASH-262a9d9b00924195aa21d58e2c49fc12	855000	CASH	SUCCESS	2026-10-29 11:17:12.24215+07	\N	\N	\N	\N	2026-10-29 11:17:12.241075+07
20	21	10	2	INV-d753cacedb374b7386da	CASH-d753cacedb374b7386dada30e85e4ce9	810000	CASH	SUCCESS	2026-10-29 11:17:12.288553+07	\N	\N	\N	\N	2026-10-29 11:17:12.28604+07
21	22	11	2	INV-282af7a242a74e7e855d	CASH-282af7a242a74e7e855def953e5889fa	765000	CASH	SUCCESS	2026-10-29 11:17:12.33684+07	\N	\N	\N	\N	2026-10-29 11:17:12.334353+07
22	23	17	2	INV-c22b0b47808646d8b53b	CASH-c22b0b47808646d8b53b7bc65d92bebc	900000	CASH	SUCCESS	2026-10-29 11:17:12.3827+07	\N	\N	\N	\N	2026-10-29 11:17:12.381038+07
23	24	13	2	INV-8f8744331827452f8a4d	CASH-8f8744331827452f8a4d75dfc50c44c3	900000	CASH	SUCCESS	2026-10-29 11:17:12.433802+07	\N	\N	\N	\N	2026-10-29 11:17:12.431128+07
27	28	9	2	INV-219988a3c5454e51aa78	CASH-219988a3c5454e51aa7870452ef09b62	700000	CASH	SUCCESS	2026-10-29 11:17:13.894096+07	\N	\N	\N	\N	2026-10-29 11:17:13.891642+07
24	25	18	2	INV-82e7cc01095e47e0b3cc	CASH-82e7cc01095e47e0b3cc63e392ae5474	800000	CASH	REFUNDED	2026-10-29 11:17:12.475229+07	\N	\N	\N	\N	2026-10-29 11:17:12.472974+07
25	26	19	2	INV-537d0588b43e4b5fbe31	CASH-537d0588b43e4b5fbe31652fc8845152	800000	CASH	REFUNDED	2026-10-29 11:17:12.503703+07	\N	\N	\N	\N	2026-10-29 11:17:12.50313+07
26	27	20	2	INV-5c45fed6013d490da5dd	CASH-5c45fed6013d490da5dd214e91c43340	800000	CASH	REFUNDED	2026-10-29 11:17:12.538089+07	\N	\N	\N	\N	2026-10-29 11:17:12.537071+07
28	29	9	2	INV-75028921e9b44a2aa1d7	CASH-75028921e9b44a2aa1d7a7b7535dd136	700000	CASH	REFUNDED	2026-10-29 14:17:48.571492+07	\N	\N	\N	\N	2026-10-29 14:17:48.564998+07
\.


--
-- Data for Name: permissions; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.permissions (id, code, name, description) FROM stdin;
1	USER_MANAGE	Manage users and staff	\N
2	ROLE_MANAGE	Manage roles and permissions	\N
3	CLASS_MANAGE	Manage sports, classes, rooms and schedules	\N
4	PACKAGE_MANAGE	Manage membership packages	\N
5	REPORT_VIEW	View reports	\N
6	AUDIT_VIEW	View activity history	\N
7	MEMBER_VIEW	Search and view member information	\N
8	MEMBER_REGISTER	Register members	\N
9	SUBSCRIPTION_MANAGE	Register / renew memberships	\N
10	BOOKING_MANAGE	Register or cancel classes for members	\N
11	ATTENDANCE_MANAGE	Record attendance	\N
12	PAYMENT_MANAGE	Record payments and invoices	\N
13	SUPPORT_MANAGE	Manage support requests	\N
14	STUDENT_VIEW	View assigned students	\N
15	WORKOUT_MANAGE	Manage workout plans and progress	\N
16	AI_USE	Use AI features	\N
\.


--
-- Data for Name: refunds; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.refunds (id, payment_id, amount, reason, method, status, requested_at, processed_by, processed_at) FROM stdin;
1	24	800000	Class canceled by the center: Coach nghỉ đột xuất, trung tâm hủy lớp	CASH	DONE	2026-10-29 11:17:28.681038+07	1	2026-10-31 10:23:44.820443+07
2	25	800000	Class canceled by the center: Coach nghỉ đột xuất, trung tâm hủy lớp	CASH	DONE	2026-10-29 11:17:28.681038+07	1	2026-10-31 10:23:45.760585+07
3	26	800000	Class canceled by the center: Coach nghỉ đột xuất, trung tâm hủy lớp	CASH	DONE	2026-10-29 11:17:28.681038+07	1	2026-10-31 10:23:46.415806+07
4	28	700000	1-1 session canceled by the center: Phong bao tri	CASH	DONE	2026-10-29 14:17:48.650288+07	1	2026-10-31 10:23:46.751782+07
\.


--
-- Data for Name: role_permissions; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.role_permissions (role_id, permission_id) FROM stdin;
1	16
1	15
1	14
1	13
1	12
1	11
1	10
1	9
1	8
1	7
1	6
1	5
1	4
1	3
1	2
1	1
2	13
2	12
2	11
2	10
2	9
2	8
2	7
3	16
3	15
3	14
3	11
4	16
\.


--
-- Data for Name: roles; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.roles (id, code, name, description) FROM stdin;
1	MANAGER	Center Manager	Full management access
2	RECEPTIONIST	Receptionist	Front-desk operations
3	COACH	Coach	Teaches classes and tracks students
4	MEMBER	Member	Default role for public registration
\.


--
-- Data for Name: room_sports; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.room_sports (room_id, category_id) FROM stdin;
1	1
3	2
2	2
4	3
7	4
6	4
5	4
8	5
9	6
10	7
\.


--
-- Data for Name: sport_categories; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.sport_categories (id, name, description) FROM stdin;
1	Bơi lội	Bơi cơ bản, bơi nâng cao
2	Yoga	Yoga thư giãn và cải thiện linh hoạt
3	Bóng chuyền	Bóng chuyền cơ bản và thi đấu
4	Thể lực	Tập luyện sức mạnh, thể lực tổng hợp
5	Boxing	Boxing cơ bản và cardio
6	Cầu lông	Cầu lông đơn, đôi và rèn phản xạ
7	Zumba	Nhảy theo nhạc, đốt calo
\.


--
-- Data for Name: sport_classes; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.sport_classes (id, course_id, coach_id, name, description, max_capacity, tuition, start_date, registration_opens_on, status, created_at, updated_at) FROM stdin;
1	1	21	Bơi cơ bản - Sáng 2-4-6	Bơi cơ bản - Sáng 2-4-6	12	1200000	2026-11-08	2026-10-29	OPEN	2026-10-29 11:17:10.262767+07	2026-10-29 11:17:11.092169+07
2	2	22	Yoga buổi tối (sĩ số nhỏ)	Yoga buổi tối (sĩ số nhỏ)	3	900000	2026-11-05	2026-10-29	OPEN	2026-10-29 11:17:10.558186+07	2026-10-29 11:17:11.105511+07
3	5	24	Cầu lông cơ bản cuối tuần	Cầu lông cơ bản cuối tuần	10	700000	2026-11-03	2026-10-29	OPEN	2026-10-29 11:17:10.660219+07	2026-10-29 11:17:11.114592+07
4	6	26	Zumba nhập môn tối 2-4	Zumba nhập môn tối 2-4	15	600000	2026-11-02	2026-10-29	OPEN	2026-10-29 11:17:10.748272+07	2026-10-29 11:17:11.125877+07
7	3	23	Bóng chuyền thử nghiệm (sẽ hủy)	Bóng chuyền thử nghiệm (sẽ hủy)	10	800000	2026-11-10	2026-10-29	CANCELED	2026-10-29 11:17:11.023117+07	2026-10-29 11:17:28.681038+07
5	7	25	Boxing cơ bản (đang học)	Boxing cơ bản (đang học)	8	1400000	2026-10-22	2026-10-01	IN_PROGRESS	2026-10-29 11:17:10.84088+07	2026-10-29 11:17:11.13554+07
6	2	22	Yoga cơ bản - đợt trước (đã kết thúc)	Yoga cơ bản - đợt trước (đã kết thúc)	8	900000	2026-09-26	2026-09-05	COMPLETED	2026-10-29 11:17:10.944549+07	2026-10-29 11:17:11.146638+07
\.


--
-- Data for Name: staff_profiles; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.staff_profiles (user_id, job_title, shift_schedule) FROM stdin;
1	Center Manager	08:00-17:00
2	Senior Receptionist	08:00-19:00
\.


--
-- Data for Name: support_requests; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.support_requests (id, member_id, handled_by, subject, content, status, created_at, resolved_at) FROM stdin;
\.


--
-- Data for Name: system_settings; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.system_settings (key, value, description, updated_by, updated_at) FROM stdin;
pricing.duration_discounts	[{"pct": 0, "max_months": 2, "min_months": 1}, {"pct": 8, "max_months": 5, "min_months": 3}, {"pct": 15, "max_months": 11, "min_months": 6}, {"pct": 25, "max_months": 12, "min_months": 12}]	Bậc giảm giá theo số tháng mua gói (BR-PRC-01)	\N	2026-10-23 18:14:43.228636+07
coach.rank_prices	{"COACH": 250000, "MASTER": 650000, "SENIOR": 400000}	Giá buổi 1-1 60 phút theo hạng coach (BR-ONE-02)	\N	2026-10-23 18:14:43.228636+07
auth.verify_link_minutes	1440	Hiệu lực link xác thực email (BR-ACC-04)	\N	2026-10-23 18:14:43.228636+07
auth.reset_link_minutes	30	Hiệu lực link đặt lại mật khẩu (BR-ACC-06)	\N	2026-10-23 18:14:43.228636+07
auth.verify_resend_cooldown_seconds	60	Khoảng cách tối thiểu giữa hai lần gửi lại email xác thực (BR-ACC-07)	\N	2026-10-23 18:14:43.228636+07
auth.verify_resend_per_day	5	Số lần gửi lại email xác thực tối đa mỗi ngày (BR-ACC-07)	\N	2026-10-23 18:14:43.228636+07
payment.hold_minutes	15	Thời gian mã QR và giữ chỗ khi thanh toán (BR-QR-02, BR-ENR-03)	\N	2026-10-23 18:14:43.228636+07
waitlist.hold_hours	12	Giữ chỗ cho người được báo có chỗ (BR-WL-03)	\N	2026-10-23 18:14:43.228636+07
waitlist.hold_hours_near_start	3	Giữ chỗ khi còn dưới near_start_days đến khai giảng (BR-WL-03)	\N	2026-10-23 18:14:43.228636+07
waitlist.near_start_days	3	Ngưỡng ngày coi là sát khai giảng (BR-WL-03)	\N	2026-10-23 18:14:43.228636+07
schedule.buffer_minutes	15	Khoảng nghỉ giữa hai buổi (BR-SCH-01). Chỉ để tham chiếu: constraint trong DB viết cứng 15 phút	\N	2026-10-23 18:14:43.228636+07
makeup.leave_threshold_pct	30	Số buổi hủy vượt ngưỡng này thì học viên được rời class (BR-MKP-07)	\N	2026-10-23 18:14:43.228636+07
makeup.max_days	30	Ngày bù cách buổi bị hủy quá số ngày này thì thay bằng bồi hoàn (BR-MKP-06)	\N	2026-10-23 18:14:43.228636+07
makeup.proposal_count	3	Số ngày bù hệ thống đề xuất cho manager (BR-MKP-02)	\N	2026-10-23 18:14:43.228636+07
subscription.expiry_notice_days	[3, 1]	Các mốc báo gói sắp hết hạn (BR-PKG-12)	\N	2026-10-23 18:14:43.228636+07
voucher.welcome	{"kind": "PERCENT", "value": 10, "applies_to": "CLASS", "valid_days": 30}	Voucher chào mừng (BR-VCH-02)	\N	2026-10-23 18:14:43.228636+07
voucher.completion	{"kind": "PERCENT", "value": 10, "applies_to": "CLASS", "valid_days": 60}	Voucher hoàn thành class (BR-VCH-02)	\N	2026-10-23 18:14:43.228636+07
voucher.compensation	{"kind": "PERCENT", "value": 10, "applies_to": "CLASS", "valid_days": 30}	Voucher bù lỗi trung tâm (BR-VCH-02)	\N	2026-10-23 18:14:43.228636+07
voucher.birthday_valid_days	30	Hạn dùng quà sinh nhật (BR-BEN-02)	\N	2026-10-23 18:14:43.228636+07
overview.notable_class	{"low_fill_pct": 50, "near_start_days": 7, "canceled_sessions": 2}	Ngưỡng class đáng chú ý (BR-OVW-05)	\N	2026-10-23 18:14:43.228636+07
overview.overdue_refund_days	3	Khoản chờ hoàn tiền quá số ngày này thì đánh dấu đỏ (BR-OVW-04)	\N	2026-10-23 18:14:43.228636+07
center.open_minute	360	Giờ mở cửa của trung tâm, tính bằng phút từ 00:00 (360 = 06:00)	\N	2026-10-29 11:15:17.162792+07
center.close_minute	1320	Giờ đóng cửa của trung tâm, tính bằng phút từ 00:00 (1320 = 22:00)	\N	2026-10-29 11:15:17.162792+07
voucher.early_renewal	{"kind": "PERCENT", "value": 5, "applies_to": "PACKAGE", "valid_days": 7}	Voucher gia hạn sớm: tặng khi gói còn ít ngày mà chưa gia hạn (BR-VCH-02)	\N	2026-10-29 11:15:17.41199+07
voucher.winback	{"kind": "PERCENT", "value": 10, "applies_to": "PACKAGE", "valid_days": 30}	Voucher quay lại: tặng khi gói đã hết hạn một thời gian mà chưa mua lại (BR-VCH-02)	\N	2026-10-29 11:15:17.41199+07
voucher.early_renewal_days	7	Gói còn tối đa bao nhiêu ngày thì được tặng voucher gia hạn sớm	\N	2026-10-29 11:15:17.41199+07
voucher.winback_after_days	30	Gói đã hết hạn bao nhiêu ngày thì được tặng voucher quay lại	\N	2026-10-29 11:15:17.41199+07
one_on_one.refund_before_hours	48	Hủy 1-1 trước giờ học tối thiểu bao nhiêu giờ thì hoàn tiền (BR-ONE-04)	\N	2026-10-29 11:15:18.373698+07
\.


--
-- Data for Name: time_slots; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.time_slots (id, slot_no, start_time, end_time, is_active) FROM stdin;
1	1	06:00:00	07:45:00	t
2	2	08:00:00	09:45:00	t
3	3	10:00:00	11:45:00	t
4	4	12:00:00	13:45:00	t
5	5	14:00:00	15:45:00	t
6	6	16:00:00	17:45:00	t
7	7	18:00:00	19:45:00	t
8	8	20:00:00	21:45:00	t
\.


--
-- Data for Name: training_plan_exercises; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.training_plan_exercises (id, plan_id, "position", name, instructions, sets, repetitions, duration_seconds, retired) FROM stdin;
1	1	0	Khởi động nhảy dây	Nhảy nhẹ nhàng, giữ nhịp thở	3	\N	180	f
2	1	1	Đấm thẳng vào bao	Xoay hông, thu tay nhanh	4	20	\N	f
3	1	2	Gập bụng	Giữ lưng thẳng	3	15	\N	f
\.


--
-- Data for Name: training_progress_evaluations; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.training_progress_evaluations (id, class_id, member_id, coach_id, evaluation_date, period_start, period_end, summary, rating, strengths, improvements, recommendations, feedback, created_at, updated_at, version) FROM stdin;
2	5	18	25	2026-10-29	2026-10-15	2026-10-29	Cần luyện thêm phản xạ phòng thủ	3	Chăm chỉ, đi học đều	Giữ nhịp thở	Tập thêm nhảy dây ở nhà	Cố gắng phát huy nhé	2026-10-29 11:17:22.490568+07	2026-10-29 11:17:22.490568+07	1
1	5	16	25	2026-10-29	2026-10-15	2026-10-29	Tiến bộ ổn định, giữ thế đứng tốt	4	Chăm chỉ, đi học đều	Giữ nhịp thở	Tập thêm nhảy dây ở nhà	Cố gắng phát huy nhé	2026-10-29 11:17:22.467701+07	2026-10-31 10:27:02.155388+07	2
\.


--
-- Data for Name: training_results; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.training_results (id, attendance_id, exercise_id, activity_key, activity_name, prescription, actual_sets, actual_repetitions, actual_duration_seconds, performance, completion_status, notes, recorded_by, recorded_at, updated_at) FROM stdin;
\.


--
-- Data for Name: users; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.users (id, role_id, full_name, email, phone, password_hash, avatar_url, gender, date_of_birth, is_active, email_verified_at, verify_token_hash, verify_token_expires_at, reset_token_hash, reset_token_expires_at, created_at, updated_at) FROM stdin;
1	1	Center Manager	manager@test.local	0913448760	$2a$10$NSxZ6dMWEQOn.vlcI5j4A.rnk/YBi2Kfa/4yNH32xvP/xAVU/Hkyy	\N	MALE	1985-05-10	t	2026-10-23 18:14:43.228636+07	\N	\N	\N	\N	2026-10-23 18:14:43.228636+07	2026-10-29 11:15:50.136705+07
2	2	Nguyễn Thị Lan	receptionist01@test.local	0934567890	$2a$10$NSxZ6dMWEQOn.vlcI5j4A.rnk/YBi2Kfa/4yNH32xvP/xAVU/Hkyy	\N	FEMALE	1998-03-21	t	2026-10-23 18:14:43.228636+07	\N	\N	\N	\N	2026-10-23 18:14:43.228636+07	2026-10-29 11:15:50.136705+07
3	3	Phạm Văn Thể Lực	coach03@test.local	0987654323	$2a$10$NSxZ6dMWEQOn.vlcI5j4A.rnk/YBi2Kfa/4yNH32xvP/xAVU/Hkyy	\N	MALE	1988-01-30	t	2026-10-23 18:14:43.228636+07	\N	\N	\N	\N	2026-10-23 18:14:43.228636+07	2026-10-29 11:15:50.136705+07
4	3	Lê Thị Yoga	coach02@test.local	0987654322	$2a$10$NSxZ6dMWEQOn.vlcI5j4A.rnk/YBi2Kfa/4yNH32xvP/xAVU/Hkyy	\N	FEMALE	1992-11-15	t	2026-10-23 18:14:43.228636+07	\N	\N	\N	\N	2026-10-23 18:14:43.228636+07	2026-10-29 11:15:50.136705+07
5	3	Trần Văn Bơi	coach01@test.local	0987654321	$2a$10$NSxZ6dMWEQOn.vlcI5j4A.rnk/YBi2Kfa/4yNH32xvP/xAVU/Hkyy	\N	MALE	1990-07-02	t	2026-10-23 18:14:43.228636+07	\N	\N	\N	\N	2026-10-23 18:14:43.228636+07	2026-10-29 11:15:50.136705+07
6	4	Lê Văn Chưa Xác Thực	member003@test.local	0912312312	$2a$10$NSxZ6dMWEQOn.vlcI5j4A.rnk/YBi2Kfa/4yNH32xvP/xAVU/Hkyy	\N	MALE	2001-12-01	t	2026-10-29 11:15:50.136705+07	\N	\N	\N	\N	2026-10-23 18:14:43.228636+07	2026-10-29 11:15:50.136705+07
7	4	Trần Thị Bình	member002@test.local	0933123321	$2a$10$NSxZ6dMWEQOn.vlcI5j4A.rnk/YBi2Kfa/4yNH32xvP/xAVU/Hkyy	\N	FEMALE	1999-09-09	t	2026-10-23 18:14:43.228636+07	\N	\N	\N	\N	2026-10-23 18:14:43.228636+07	2026-10-29 11:15:50.136705+07
8	4	Nguyễn Văn An	member001@test.local	0311231231	$2a$10$NSxZ6dMWEQOn.vlcI5j4A.rnk/YBi2Kfa/4yNH32xvP/xAVU/Hkyy	\N	MALE	2000-02-14	t	2026-10-23 18:14:43.228636+07	\N	\N	\N	\N	2026-10-23 18:14:43.228636+07	2026-10-29 11:15:50.136705+07
9	4	Nguyễn Thị Lan	lan.nguyen@demo.local	0901000001	$2a$10$NSxZ6dMWEQOn.vlcI5j4A.rnk/YBi2Kfa/4yNH32xvP/xAVU/Hkyy	\N	FEMALE	\N	t	2026-10-29 11:17:05.715151+07	\N	\N	\N	\N	2026-10-29 11:17:05.715151+07	2026-10-29 11:17:05.715151+07
10	4	Trần Văn Hùng	hung.tran@demo.local	0901000002	$2a$10$NSxZ6dMWEQOn.vlcI5j4A.rnk/YBi2Kfa/4yNH32xvP/xAVU/Hkyy	\N	MALE	\N	t	2026-10-29 11:17:05.715151+07	\N	\N	\N	\N	2026-10-29 11:17:05.715151+07	2026-10-29 11:17:05.715151+07
11	4	Lê Thị Mai	mai.le@demo.local	0901000003	$2a$10$NSxZ6dMWEQOn.vlcI5j4A.rnk/YBi2Kfa/4yNH32xvP/xAVU/Hkyy	\N	FEMALE	\N	t	2026-10-29 11:17:05.715151+07	\N	\N	\N	\N	2026-10-29 11:17:05.715151+07	2026-10-29 11:17:05.715151+07
12	4	Phạm Quang Huy	huy.pham@demo.local	0901000004	$2a$10$NSxZ6dMWEQOn.vlcI5j4A.rnk/YBi2Kfa/4yNH32xvP/xAVU/Hkyy	\N	MALE	\N	t	2026-10-29 11:17:05.715151+07	\N	\N	\N	\N	2026-10-29 11:17:05.715151+07	2026-10-29 11:17:05.715151+07
13	4	Võ Thị Hồng	hong.vo@demo.local	0901000005	$2a$10$NSxZ6dMWEQOn.vlcI5j4A.rnk/YBi2Kfa/4yNH32xvP/xAVU/Hkyy	\N	FEMALE	\N	t	2026-10-29 11:17:05.715151+07	\N	\N	\N	\N	2026-10-29 11:17:05.715151+07	2026-10-29 11:17:05.715151+07
14	4	Đặng Minh Khoa	khoa.dang@demo.local	0901000006	$2a$10$NSxZ6dMWEQOn.vlcI5j4A.rnk/YBi2Kfa/4yNH32xvP/xAVU/Hkyy	\N	MALE	\N	t	2026-10-29 11:17:05.715151+07	\N	\N	\N	\N	2026-10-29 11:17:05.715151+07	2026-10-29 11:17:05.715151+07
15	4	Bùi Thanh Tâm	tam.bui@demo.local	0901000007	$2a$10$NSxZ6dMWEQOn.vlcI5j4A.rnk/YBi2Kfa/4yNH32xvP/xAVU/Hkyy	\N	FEMALE	\N	t	2026-10-29 11:17:05.715151+07	\N	\N	\N	\N	2026-10-29 11:17:05.715151+07	2026-10-29 11:17:05.715151+07
16	4	Hoàng Gia Bảo	bao.hoang@demo.local	0901000008	$2a$10$NSxZ6dMWEQOn.vlcI5j4A.rnk/YBi2Kfa/4yNH32xvP/xAVU/Hkyy	\N	MALE	\N	t	2026-10-29 11:17:05.715151+07	\N	\N	\N	\N	2026-10-29 11:17:05.715151+07	2026-10-29 11:17:05.715151+07
17	4	Ngô Thu Trang	trang.ngo@demo.local	0901000009	$2a$10$NSxZ6dMWEQOn.vlcI5j4A.rnk/YBi2Kfa/4yNH32xvP/xAVU/Hkyy	\N	FEMALE	\N	t	2026-10-29 11:17:05.715151+07	\N	\N	\N	\N	2026-10-29 11:17:05.715151+07	2026-10-29 11:17:05.715151+07
18	4	Dương Văn Long	long.duong@demo.local	0901000010	$2a$10$NSxZ6dMWEQOn.vlcI5j4A.rnk/YBi2Kfa/4yNH32xvP/xAVU/Hkyy	\N	MALE	\N	t	2026-10-29 11:17:05.715151+07	\N	\N	\N	\N	2026-10-29 11:17:05.715151+07	2026-10-29 11:17:05.715151+07
19	4	Lý Mỹ Linh	linh.ly@demo.local	0901000011	$2a$10$NSxZ6dMWEQOn.vlcI5j4A.rnk/YBi2Kfa/4yNH32xvP/xAVU/Hkyy	\N	FEMALE	\N	t	2026-10-29 11:17:05.715151+07	\N	\N	\N	\N	2026-10-29 11:17:05.715151+07	2026-10-29 11:17:05.715151+07
20	4	Trương Đức Anh	anh.truong@demo.local	0901000012	$2a$10$NSxZ6dMWEQOn.vlcI5j4A.rnk/YBi2Kfa/4yNH32xvP/xAVU/Hkyy	\N	MALE	\N	t	2026-10-29 11:17:05.715151+07	\N	\N	\N	\N	2026-10-29 11:17:05.715151+07	2026-10-29 11:17:05.715151+07
21	3	Nguyễn Minh Tuấn	tuan.nguyen@demo.local	0902000001	$2a$10$Brb6J2NUCOaYxbEeX3NxbeA0sum9Eg5ylR0VRIhmmTMKy1uN2PRiW	\N	\N	\N	t	2026-10-29 11:17:07.394444+07	\N	\N	\N	\N	2026-10-29 11:17:07.304732+07	2026-10-29 11:17:07.304732+07
22	3	Trần Thu Hà	ha.tran@demo.local	0902000002	$2a$10$vSesrT3yh2d1ttO..mNYaeRYdYJnWvLHd9xtnIhcndkH3dedVXrmi	\N	\N	\N	t	2026-10-29 11:17:07.818381+07	\N	\N	\N	\N	2026-10-29 11:17:07.739735+07	2026-10-29 11:17:07.739735+07
23	3	Lê Quốc Bảo	bao.le@demo.local	0902000003	$2a$10$zuYSPjV.KDUGfcUyHc.lt.V3ZgIlkXt8eTRjXwgpHpqo0cH.nC0/6	\N	\N	\N	t	2026-10-29 11:17:08.254106+07	\N	\N	\N	\N	2026-10-29 11:17:08.147476+07	2026-10-29 11:17:08.147476+07
24	3	Phạm Ngọc Anh	anh.pham@demo.local	0902000004	$2a$10$i03c8on1T7yhvFGstE4Uz.M9xRLq1BhJN78Dk7jhubLa29wSoqsk2	\N	\N	\N	t	2026-10-29 11:17:08.567062+07	\N	\N	\N	\N	2026-10-29 11:17:08.465712+07	2026-10-29 11:17:08.465712+07
25	3	Võ Thanh Sơn	son.vo@demo.local	0902000005	$2a$10$Zdq5uIDLOMh4FAkPSkXjeuFgY4As5aEO27NhZryw04vnvQOg6qqGa	\N	\N	\N	t	2026-10-29 11:17:08.971146+07	\N	\N	\N	\N	2026-10-29 11:17:08.886548+07	2026-10-29 11:17:08.886548+07
26	3	Đặng Khánh Linh	linh.dang@demo.local	0902000006	$2a$10$Hihb71DzNPtHTAPSxBlD7.E672eFgV6MZbXdGo.d32zsEZW.cd.KK	\N	\N	\N	t	2026-10-29 11:17:09.37683+07	\N	\N	\N	\N	2026-10-29 11:17:09.288921+07	2026-10-29 11:17:09.288921+07
\.


--
-- Data for Name: voucher_campaigns; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.voucher_campaigns (id, code, kind, value, applies_to, max_uses, starts_at, ends_at, created_by, created_at) FROM stdin;
1	KHAIGIANG10	PERCENT	10	CLASS	100	2026-10-23 18:14:43.228636+07	2026-12-22 18:14:43.228636+07	1	2026-10-23 18:14:43.228636+07
2	CHAOMUNG20	PERCENT	20	PACKAGE	50	2026-10-29 10:17:12+07	2026-12-28 11:17:12+07	1	2026-10-29 11:17:12.917324+07
3	COACH15	PERCENT	15	ONE_ON_ONE	30	2026-10-29 10:17:12+07	2026-12-28 11:17:12+07	1	2026-10-29 11:17:12.930678+07
\.


--
-- Data for Name: vouchers; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.vouchers (id, user_id, source, kind, value, applies_to, campaign_id, issued_at, expires_at, used_at) FROM stdin;
1	8	WELCOME	PERCENT	10	CLASS	\N	2026-10-23 18:14:43.228636+07	2026-11-22 18:14:43.228636+07	\N
2	13	CAMPAIGN	PERCENT	20	PACKAGE	2	2026-10-29 11:17:13.025359+07	2026-12-28 11:17:12+07	\N
3	14	CAMPAIGN	PERCENT	10	CLASS	1	2026-10-29 11:17:13.117582+07	2026-12-22 18:14:43.228636+07	\N
4	15	CAMPAIGN	PERCENT	15	ONE_ON_ONE	3	2026-10-29 11:17:13.238619+07	2026-12-28 11:17:12+07	\N
5	18	CAMPAIGN	PERCENT	20	PACKAGE	2	2026-10-29 11:17:13.345731+07	2026-12-28 11:17:12+07	\N
6	9	COMPLETION	PERCENT	10	CLASS	\N	2026-10-29 11:17:56.61005+07	2026-12-28 11:17:56.61005+07	\N
7	10	COMPLETION	PERCENT	10	CLASS	\N	2026-10-29 11:17:56.62301+07	2026-12-28 11:17:56.62301+07	\N
8	11	COMPLETION	PERCENT	10	CLASS	\N	2026-10-29 11:17:56.633556+07	2026-12-28 11:17:56.633556+07	\N
9	17	COMPLETION	PERCENT	10	CLASS	\N	2026-10-29 11:17:56.650264+07	2026-12-28 11:17:56.650264+07	\N
10	13	COMPLETION	PERCENT	10	CLASS	\N	2026-10-29 11:17:56.663582+07	2026-12-28 11:17:56.663582+07	\N
11	9	COMPENSATION	PERCENT	10	CLASS	\N	2026-10-29 14:17:48.650288+07	2026-11-28 14:17:48.650288+07	\N
12	9	CAMPAIGN	PERCENT	15	ONE_ON_ONE	3	2026-10-31 10:30:53.507051+07	2026-12-28 11:17:12+07	\N
\.


--
-- Data for Name: workout_plans; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.workout_plans (id, member_id, coach_id, title, description, content, is_ai_generated, start_date, end_date, status, created_at, updated_at, class_id, objective, coach_notes, version) FROM stdin;
1	\N	25	Kế hoạch 4 tuần: nền tảng boxing	Giáo án cho người mới	\N	f	2026-10-15	2026-11-19	PUBLISHED	2026-10-29 11:17:22.42624+07	2026-10-31 10:26:32.422483+07	5	Đứng thế, đấm thẳng và phòng thủ cơ bản	Nhớ băng tay trước khi vào lớp	2
\.


--
-- Data for Name: workout_progress; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.workout_progress (id, plan_id, member_id, recorded_by, recorded_date, weight_kg, height_cm, body_fat_pct, muscle_mass_kg, notes) FROM stdin;
\.


--
-- Name: ai_chat_logs_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public.ai_chat_logs_id_seq', 1, false);


--
-- Name: audit_logs_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public.audit_logs_id_seq', 22, true);


--
-- Name: class_attendances_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public.class_attendances_id_seq', 58, true);


--
-- Name: class_evaluations_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public.class_evaluations_id_seq', 5, true);


--
-- Name: class_registrations_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public.class_registrations_id_seq', 26, true);


--
-- Name: class_schedules_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public.class_schedules_id_seq', 15, true);


--
-- Name: class_sessions_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public.class_sessions_id_seq', 70, true);


--
-- Name: coach_slots_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public.coach_slots_id_seq', 25, true);


--
-- Name: courses_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public.courses_id_seq', 9, true);


--
-- Name: facility_attendances_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public.facility_attendances_id_seq', 1, false);


--
-- Name: facility_rooms_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public.facility_rooms_id_seq', 10, true);


--
-- Name: member_subscriptions_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public.member_subscriptions_id_seq', 7, true);


--
-- Name: membership_packages_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public.membership_packages_id_seq', 4, true);


--
-- Name: notifications_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public.notifications_id_seq', 18, true);


--
-- Name: orders_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public.orders_id_seq', 31, true);


--
-- Name: payments_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public.payments_id_seq', 28, true);


--
-- Name: permissions_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public.permissions_id_seq', 16, true);


--
-- Name: refunds_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public.refunds_id_seq', 4, true);


--
-- Name: roles_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public.roles_id_seq', 4, true);


--
-- Name: sport_categories_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public.sport_categories_id_seq', 7, true);


--
-- Name: sport_classes_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public.sport_classes_id_seq', 7, true);


--
-- Name: support_requests_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public.support_requests_id_seq', 1, false);


--
-- Name: time_slots_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public.time_slots_id_seq', 8, true);


--
-- Name: training_plan_exercises_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public.training_plan_exercises_id_seq', 3, true);


--
-- Name: training_progress_evaluations_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public.training_progress_evaluations_id_seq', 2, true);


--
-- Name: training_results_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public.training_results_id_seq', 1, false);


--
-- Name: users_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public.users_id_seq', 26, true);


--
-- Name: voucher_campaigns_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public.voucher_campaigns_id_seq', 3, true);


--
-- Name: vouchers_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public.vouchers_id_seq', 12, true);


--
-- Name: workout_plans_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public.workout_plans_id_seq', 1, true);


--
-- Name: workout_progress_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public.workout_progress_id_seq', 1, false);


--
-- Name: ai_chat_logs ai_chat_logs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ai_chat_logs
    ADD CONSTRAINT ai_chat_logs_pkey PRIMARY KEY (id);


--
-- Name: audit_logs audit_logs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.audit_logs
    ADD CONSTRAINT audit_logs_pkey PRIMARY KEY (id);


--
-- Name: class_attendances class_attendances_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.class_attendances
    ADD CONSTRAINT class_attendances_pkey PRIMARY KEY (id);


--
-- Name: class_evaluations class_evaluations_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.class_evaluations
    ADD CONSTRAINT class_evaluations_pkey PRIMARY KEY (id);


--
-- Name: class_evaluations class_evaluations_registration_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.class_evaluations
    ADD CONSTRAINT class_evaluations_registration_id_key UNIQUE (registration_id);


--
-- Name: class_registrations class_registrations_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.class_registrations
    ADD CONSTRAINT class_registrations_pkey PRIMARY KEY (id);


--
-- Name: class_schedules class_schedules_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.class_schedules
    ADD CONSTRAINT class_schedules_pkey PRIMARY KEY (id);


--
-- Name: class_sessions class_sessions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.class_sessions
    ADD CONSTRAINT class_sessions_pkey PRIMARY KEY (id);


--
-- Name: coach_profiles coach_profiles_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.coach_profiles
    ADD CONSTRAINT coach_profiles_pkey PRIMARY KEY (user_id);


--
-- Name: coach_slots coach_slots_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.coach_slots
    ADD CONSTRAINT coach_slots_pkey PRIMARY KEY (id);


--
-- Name: coach_sports coach_sports_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.coach_sports
    ADD CONSTRAINT coach_sports_pkey PRIMARY KEY (coach_id, category_id);


--
-- Name: courses courses_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.courses
    ADD CONSTRAINT courses_pkey PRIMARY KEY (id);


--
-- Name: class_sessions ex_session_coach_overlap; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.class_sessions
    ADD CONSTRAINT ex_session_coach_overlap EXCLUDE USING gist (coach_id WITH =, tsrange((session_date + start_time), ((session_date + end_time) + '00:15:00'::interval)) WITH &&) WHERE (((status)::text = ANY (ARRAY[('SCHEDULED'::character varying)::text, ('DONE'::character varying)::text])));


--
-- Name: class_sessions ex_session_room_overlap; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.class_sessions
    ADD CONSTRAINT ex_session_room_overlap EXCLUDE USING gist (room_id WITH =, tsrange((session_date + start_time), ((session_date + end_time) + '00:15:00'::interval)) WITH &&) WHERE (((status)::text = ANY (ARRAY[('SCHEDULED'::character varying)::text, ('DONE'::character varying)::text])));


--
-- Name: coach_slots ex_slot_coach_overlap; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.coach_slots
    ADD CONSTRAINT ex_slot_coach_overlap EXCLUDE USING gist (coach_id WITH =, tsrange(timezone('UTC'::text, start_at), (timezone('UTC'::text, end_at) + '00:15:00'::interval)) WITH &&) WHERE (((status)::text <> 'CANCELED'::text));


--
-- Name: coach_slots ex_slot_room_overlap; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.coach_slots
    ADD CONSTRAINT ex_slot_room_overlap EXCLUDE USING gist (room_id WITH =, tsrange(timezone('UTC'::text, start_at), (timezone('UTC'::text, end_at) + '00:15:00'::interval)) WITH &&) WHERE (((room_id IS NOT NULL) AND ((status)::text <> 'CANCELED'::text)));


--
-- Name: facility_attendances facility_attendances_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.facility_attendances
    ADD CONSTRAINT facility_attendances_pkey PRIMARY KEY (id);


--
-- Name: facility_rooms facility_rooms_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.facility_rooms
    ADD CONSTRAINT facility_rooms_pkey PRIMARY KEY (id);


--
-- Name: facility_rooms facility_rooms_room_name_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.facility_rooms
    ADD CONSTRAINT facility_rooms_room_name_key UNIQUE (room_name);


--
-- Name: member_profiles member_profiles_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.member_profiles
    ADD CONSTRAINT member_profiles_pkey PRIMARY KEY (user_id);


--
-- Name: member_subscriptions member_subscriptions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.member_subscriptions
    ADD CONSTRAINT member_subscriptions_pkey PRIMARY KEY (id);


--
-- Name: membership_packages membership_packages_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.membership_packages
    ADD CONSTRAINT membership_packages_pkey PRIMARY KEY (id);


--
-- Name: notifications notifications_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.notifications
    ADD CONSTRAINT notifications_pkey PRIMARY KEY (id);


--
-- Name: orders orders_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.orders
    ADD CONSTRAINT orders_pkey PRIMARY KEY (id);


--
-- Name: payments payments_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payments
    ADD CONSTRAINT payments_pkey PRIMARY KEY (id);


--
-- Name: permissions permissions_code_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.permissions
    ADD CONSTRAINT permissions_code_key UNIQUE (code);


--
-- Name: permissions permissions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.permissions
    ADD CONSTRAINT permissions_pkey PRIMARY KEY (id);


--
-- Name: refunds refunds_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.refunds
    ADD CONSTRAINT refunds_pkey PRIMARY KEY (id);


--
-- Name: role_permissions role_permissions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.role_permissions
    ADD CONSTRAINT role_permissions_pkey PRIMARY KEY (role_id, permission_id);


--
-- Name: roles roles_code_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.roles
    ADD CONSTRAINT roles_code_key UNIQUE (code);


--
-- Name: roles roles_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.roles
    ADD CONSTRAINT roles_pkey PRIMARY KEY (id);


--
-- Name: room_sports room_sports_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.room_sports
    ADD CONSTRAINT room_sports_pkey PRIMARY KEY (room_id, category_id);


--
-- Name: sport_categories sport_categories_name_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sport_categories
    ADD CONSTRAINT sport_categories_name_key UNIQUE (name);


--
-- Name: sport_categories sport_categories_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sport_categories
    ADD CONSTRAINT sport_categories_pkey PRIMARY KEY (id);


--
-- Name: sport_classes sport_classes_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sport_classes
    ADD CONSTRAINT sport_classes_pkey PRIMARY KEY (id);


--
-- Name: staff_profiles staff_profiles_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.staff_profiles
    ADD CONSTRAINT staff_profiles_pkey PRIMARY KEY (user_id);


--
-- Name: support_requests support_requests_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.support_requests
    ADD CONSTRAINT support_requests_pkey PRIMARY KEY (id);


--
-- Name: system_settings system_settings_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.system_settings
    ADD CONSTRAINT system_settings_pkey PRIMARY KEY (key);


--
-- Name: time_slots time_slots_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.time_slots
    ADD CONSTRAINT time_slots_pkey PRIMARY KEY (id);


--
-- Name: time_slots time_slots_slot_no_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.time_slots
    ADD CONSTRAINT time_slots_slot_no_key UNIQUE (slot_no);


--
-- Name: training_plan_exercises training_plan_exercises_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.training_plan_exercises
    ADD CONSTRAINT training_plan_exercises_pkey PRIMARY KEY (id);


--
-- Name: training_progress_evaluations training_progress_evaluations_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.training_progress_evaluations
    ADD CONSTRAINT training_progress_evaluations_pkey PRIMARY KEY (id);


--
-- Name: training_results training_results_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.training_results
    ADD CONSTRAINT training_results_pkey PRIMARY KEY (id);


--
-- Name: voucher_campaigns uq_campaign_code; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.voucher_campaigns
    ADD CONSTRAINT uq_campaign_code UNIQUE (code);


--
-- Name: class_attendances uq_class_attendance; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.class_attendances
    ADD CONSTRAINT uq_class_attendance UNIQUE (session_id, user_id);


--
-- Name: class_sessions uq_class_session_no; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.class_sessions
    ADD CONSTRAINT uq_class_session_no UNIQUE (class_id, session_no);


--
-- Name: courses uq_course_name; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.courses
    ADD CONSTRAINT uq_course_name UNIQUE (category_id, name);


--
-- Name: membership_packages uq_package_code; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.membership_packages
    ADD CONSTRAINT uq_package_code UNIQUE (code);


--
-- Name: membership_packages uq_package_name; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.membership_packages
    ADD CONSTRAINT uq_package_name UNIQUE (name);


--
-- Name: membership_packages uq_package_tier; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.membership_packages
    ADD CONSTRAINT uq_package_tier UNIQUE (tier_level);


--
-- Name: payments uq_payment_gateway_order; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payments
    ADD CONSTRAINT uq_payment_gateway_order UNIQUE (gateway_order_code);


--
-- Name: payments uq_payment_invoice; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payments
    ADD CONSTRAINT uq_payment_invoice UNIQUE (invoice_number);


--
-- Name: payments uq_payment_transaction; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payments
    ADD CONSTRAINT uq_payment_transaction UNIQUE (transaction_code);


--
-- Name: training_results uq_training_result_activity; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.training_results
    ADD CONSTRAINT uq_training_result_activity UNIQUE (attendance_id, activity_key);


--
-- Name: users uq_users_email; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT uq_users_email UNIQUE (email);


--
-- Name: users uq_users_phone; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT uq_users_phone UNIQUE (phone);


--
-- Name: users users_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_pkey PRIMARY KEY (id);


--
-- Name: voucher_campaigns voucher_campaigns_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.voucher_campaigns
    ADD CONSTRAINT voucher_campaigns_pkey PRIMARY KEY (id);


--
-- Name: vouchers vouchers_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.vouchers
    ADD CONSTRAINT vouchers_pkey PRIMARY KEY (id);


--
-- Name: workout_plans workout_plans_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.workout_plans
    ADD CONSTRAINT workout_plans_pkey PRIMARY KEY (id);


--
-- Name: workout_progress workout_progress_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.workout_progress
    ADD CONSTRAINT workout_progress_pkey PRIMARY KEY (id);


--
-- Name: idx_ai_chat_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ai_chat_user ON public.ai_chat_logs USING btree (user_id, created_at);


--
-- Name: idx_audit_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_audit_created ON public.audit_logs USING btree (created_at);


--
-- Name: idx_audit_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_audit_user ON public.audit_logs USING btree (user_id, created_at);


--
-- Name: idx_class_att_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_class_att_user ON public.class_attendances USING btree (user_id);


--
-- Name: idx_classes_coach; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_classes_coach ON public.sport_classes USING btree (coach_id);


--
-- Name: idx_classes_course; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_classes_course ON public.sport_classes USING btree (course_id);


--
-- Name: idx_classes_status_start; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_classes_status_start ON public.sport_classes USING btree (status, start_date);


--
-- Name: idx_exercise_plan; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_exercise_plan ON public.training_plan_exercises USING btree (plan_id, retired, "position");


--
-- Name: idx_facility_att_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_facility_att_user ON public.facility_attendances USING btree (user_id, check_in_time);


--
-- Name: idx_notification_recipient; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_notification_recipient ON public.notifications USING btree (recipient_id, is_read);


--
-- Name: idx_order_member; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_order_member ON public.orders USING btree (member_id, created_at);


--
-- Name: idx_order_registration; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_order_registration ON public.orders USING btree (registration_id) WHERE (registration_id IS NOT NULL);


--
-- Name: idx_order_slot; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_order_slot ON public.orders USING btree (coach_slot_id) WHERE (coach_slot_id IS NOT NULL);


--
-- Name: idx_order_subscription; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_order_subscription ON public.orders USING btree (subscription_id) WHERE (subscription_id IS NOT NULL);


--
-- Name: idx_payment_order; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_payment_order ON public.payments USING btree (order_id);


--
-- Name: idx_payment_paid_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_payment_paid_at ON public.payments USING btree (paid_at);


--
-- Name: idx_payment_payer; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_payment_payer ON public.payments USING btree (payer_id);


--
-- Name: idx_payment_pending_expiry; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_payment_pending_expiry ON public.payments USING btree (expires_at) WHERE ((status)::text = ANY (ARRAY[('CREATING'::character varying)::text, ('PENDING'::character varying)::text]));


--
-- Name: idx_payment_pending_refund; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_payment_pending_refund ON public.payments USING btree (paid_at) WHERE ((status)::text = 'PENDING_REFUND'::text);


--
-- Name: idx_plan_class; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_plan_class ON public.workout_plans USING btree (class_id);


--
-- Name: idx_plan_coach; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_plan_coach ON public.workout_plans USING btree (coach_id);


--
-- Name: idx_plan_member; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_plan_member ON public.workout_plans USING btree (member_id);


--
-- Name: idx_progress_member; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_progress_member ON public.workout_progress USING btree (member_id, recorded_date);


--
-- Name: idx_refund_payment; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_refund_payment ON public.refunds USING btree (payment_id);


--
-- Name: idx_refund_pending; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_refund_pending ON public.refunds USING btree (requested_at) WHERE ((status)::text = 'PENDING'::text);


--
-- Name: idx_reg_class_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_reg_class_status ON public.class_registrations USING btree (class_id, status);


--
-- Name: idx_reg_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_reg_user ON public.class_registrations USING btree (user_id);


--
-- Name: idx_reg_waitlist; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_reg_waitlist ON public.class_registrations USING btree (class_id, waitlist_rank DESC, registered_at) WHERE ((status)::text = 'WAITLISTED'::text);


--
-- Name: idx_schedules_class; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_schedules_class ON public.class_schedules USING btree (class_id);


--
-- Name: idx_session_class_date; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_session_class_date ON public.class_sessions USING btree (class_id, session_date);


--
-- Name: idx_session_coach_date_flow4; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_session_coach_date_flow4 ON public.class_sessions USING btree (coach_id, session_date);


--
-- Name: idx_session_date; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_session_date ON public.class_sessions USING btree (session_date) WHERE ((status)::text = 'SCHEDULED'::text);


--
-- Name: idx_slot_member; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_slot_member ON public.coach_slots USING btree (member_id) WHERE (member_id IS NOT NULL);


--
-- Name: idx_slot_open; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_slot_open ON public.coach_slots USING btree (coach_id, start_at) WHERE ((status)::text = 'OPEN'::text);


--
-- Name: idx_sub_end_date; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sub_end_date ON public.member_subscriptions USING btree (end_date) WHERE ((status)::text = 'ACTIVE'::text);


--
-- Name: idx_sub_package; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sub_package ON public.member_subscriptions USING btree (package_id);


--
-- Name: idx_sub_upgrade_from; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sub_upgrade_from ON public.member_subscriptions USING btree (upgrade_from_subscription_id);


--
-- Name: idx_sub_user_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_sub_user_status ON public.member_subscriptions USING btree (user_id, status);


--
-- Name: idx_training_evaluation_class; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_training_evaluation_class ON public.training_progress_evaluations USING btree (class_id, member_id);


--
-- Name: idx_training_evaluation_member; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_training_evaluation_member ON public.training_progress_evaluations USING btree (member_id, evaluation_date DESC);


--
-- Name: idx_training_result_exercise; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_training_result_exercise ON public.training_results USING btree (exercise_id);


--
-- Name: idx_users_role; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_users_role ON public.users USING btree (role_id);


--
-- Name: idx_voucher_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_voucher_user ON public.vouchers USING btree (user_id, expires_at) WHERE (used_at IS NULL);


--
-- Name: uq_class_member; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_class_member ON public.class_registrations USING btree (class_id, user_id) WHERE ((status)::text <> ALL (ARRAY[('CANCELED'::character varying)::text, ('LEFT'::character varying)::text]));


--
-- Name: uq_one_active_subscription; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_one_active_subscription ON public.member_subscriptions USING btree (user_id) WHERE ((status)::text = 'ACTIVE'::text);


--
-- Name: uq_one_scheduled_subscription; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_one_scheduled_subscription ON public.member_subscriptions USING btree (user_id) WHERE ((status)::text = 'SCHEDULED'::text);


--
-- Name: uq_users_email_lower; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_users_email_lower ON public.users USING btree (lower((email)::text));


--
-- Name: uq_users_reset_token; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_users_reset_token ON public.users USING btree (reset_token_hash) WHERE (reset_token_hash IS NOT NULL);


--
-- Name: uq_users_verify_token; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_users_verify_token ON public.users USING btree (verify_token_hash) WHERE (verify_token_hash IS NOT NULL);


--
-- Name: uq_voucher_campaign_user; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_voucher_campaign_user ON public.vouchers USING btree (campaign_id, user_id) WHERE (campaign_id IS NOT NULL);


--
-- Name: audit_logs trg_audit_logs_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_audit_logs_immutable BEFORE DELETE OR UPDATE ON public.audit_logs FOR EACH ROW EXECUTE FUNCTION public.trg_audit_immutable();


--
-- Name: audit_logs trg_audit_logs_no_truncate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_audit_logs_no_truncate BEFORE TRUNCATE ON public.audit_logs FOR EACH STATEMENT EXECUTE FUNCTION public.trg_audit_immutable();


--
-- Name: class_registrations trg_class_registrations_check; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_class_registrations_check BEFORE INSERT OR UPDATE OF status ON public.class_registrations FOR EACH ROW EXECUTE FUNCTION public.trg_check_registration();


--
-- Name: class_schedules trg_class_schedules_check; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_class_schedules_check BEFORE INSERT OR DELETE OR UPDATE ON public.class_schedules FOR EACH ROW EXECUTE FUNCTION public.trg_check_class_schedule();


--
-- Name: class_sessions trg_class_sessions_check; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_class_sessions_check BEFORE INSERT OR UPDATE ON public.class_sessions FOR EACH ROW EXECUTE FUNCTION public.trg_check_class_session();


--
-- Name: sport_classes trg_classes_updated; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_classes_updated BEFORE UPDATE ON public.sport_classes FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();


--
-- Name: coach_slots trg_coach_slots_check; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_coach_slots_check BEFORE INSERT OR DELETE OR UPDATE ON public.coach_slots FOR EACH ROW EXECUTE FUNCTION public.trg_check_coach_slot();


--
-- Name: courses trg_courses_updated; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_courses_updated BEFORE UPDATE ON public.courses FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();


--
-- Name: orders trg_orders_updated; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_orders_updated BEFORE UPDATE ON public.orders FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();


--
-- Name: membership_packages trg_packages_updated; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_packages_updated BEFORE UPDATE ON public.membership_packages FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();


--
-- Name: workout_plans trg_plans_updated; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_plans_updated BEFORE UPDATE ON public.workout_plans FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();


--
-- Name: class_sessions trg_sessions_updated; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_sessions_updated BEFORE UPDATE ON public.class_sessions FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();


--
-- Name: system_settings trg_settings_updated; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_settings_updated BEFORE UPDATE ON public.system_settings FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();


--
-- Name: sport_classes trg_sport_classes_check; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_sport_classes_check BEFORE INSERT OR UPDATE ON public.sport_classes FOR EACH ROW EXECUTE FUNCTION public.trg_check_sport_class();


--
-- Name: member_subscriptions trg_subscriptions_updated; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_subscriptions_updated BEFORE UPDATE ON public.member_subscriptions FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();


--
-- Name: users trg_users_updated; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_users_updated BEFORE UPDATE ON public.users FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();


--
-- Name: ai_chat_logs ai_chat_logs_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ai_chat_logs
    ADD CONSTRAINT ai_chat_logs_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id);


--
-- Name: audit_logs audit_logs_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.audit_logs
    ADD CONSTRAINT audit_logs_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id);


--
-- Name: class_attendances class_attendances_recorded_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.class_attendances
    ADD CONSTRAINT class_attendances_recorded_by_fkey FOREIGN KEY (recorded_by) REFERENCES public.users(id);


--
-- Name: class_attendances class_attendances_session_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.class_attendances
    ADD CONSTRAINT class_attendances_session_id_fkey FOREIGN KEY (session_id) REFERENCES public.class_sessions(id) ON DELETE RESTRICT;


--
-- Name: class_attendances class_attendances_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.class_attendances
    ADD CONSTRAINT class_attendances_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id);


--
-- Name: class_evaluations class_evaluations_coach_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.class_evaluations
    ADD CONSTRAINT class_evaluations_coach_id_fkey FOREIGN KEY (coach_id) REFERENCES public.users(id);


--
-- Name: class_evaluations class_evaluations_registration_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.class_evaluations
    ADD CONSTRAINT class_evaluations_registration_id_fkey FOREIGN KEY (registration_id) REFERENCES public.class_registrations(id);


--
-- Name: class_registrations class_registrations_class_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.class_registrations
    ADD CONSTRAINT class_registrations_class_id_fkey FOREIGN KEY (class_id) REFERENCES public.sport_classes(id);


--
-- Name: class_registrations class_registrations_registered_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.class_registrations
    ADD CONSTRAINT class_registrations_registered_by_fkey FOREIGN KEY (registered_by) REFERENCES public.users(id);


--
-- Name: class_registrations class_registrations_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.class_registrations
    ADD CONSTRAINT class_registrations_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id);


--
-- Name: class_schedules class_schedules_class_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.class_schedules
    ADD CONSTRAINT class_schedules_class_id_fkey FOREIGN KEY (class_id) REFERENCES public.sport_classes(id) ON DELETE CASCADE;


--
-- Name: class_schedules class_schedules_room_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.class_schedules
    ADD CONSTRAINT class_schedules_room_id_fkey FOREIGN KEY (room_id) REFERENCES public.facility_rooms(id);


--
-- Name: class_schedules class_schedules_time_slot_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.class_schedules
    ADD CONSTRAINT class_schedules_time_slot_id_fkey FOREIGN KEY (time_slot_id) REFERENCES public.time_slots(id);


--
-- Name: class_sessions class_sessions_class_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.class_sessions
    ADD CONSTRAINT class_sessions_class_id_fkey FOREIGN KEY (class_id) REFERENCES public.sport_classes(id) ON DELETE CASCADE;


--
-- Name: class_sessions class_sessions_coach_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.class_sessions
    ADD CONSTRAINT class_sessions_coach_id_fkey FOREIGN KEY (coach_id) REFERENCES public.users(id);


--
-- Name: class_sessions class_sessions_room_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.class_sessions
    ADD CONSTRAINT class_sessions_room_id_fkey FOREIGN KEY (room_id) REFERENCES public.facility_rooms(id);


--
-- Name: coach_profiles coach_profiles_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.coach_profiles
    ADD CONSTRAINT coach_profiles_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;


--
-- Name: coach_slots coach_slots_canceled_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.coach_slots
    ADD CONSTRAINT coach_slots_canceled_by_fkey FOREIGN KEY (canceled_by) REFERENCES public.users(id);


--
-- Name: coach_slots coach_slots_category_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.coach_slots
    ADD CONSTRAINT coach_slots_category_id_fkey FOREIGN KEY (category_id) REFERENCES public.sport_categories(id);


--
-- Name: coach_slots coach_slots_coach_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.coach_slots
    ADD CONSTRAINT coach_slots_coach_id_fkey FOREIGN KEY (coach_id) REFERENCES public.users(id);


--
-- Name: coach_slots coach_slots_member_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.coach_slots
    ADD CONSTRAINT coach_slots_member_id_fkey FOREIGN KEY (member_id) REFERENCES public.users(id);


--
-- Name: coach_slots coach_slots_room_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.coach_slots
    ADD CONSTRAINT coach_slots_room_id_fkey FOREIGN KEY (room_id) REFERENCES public.facility_rooms(id);


--
-- Name: coach_sports coach_sports_category_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.coach_sports
    ADD CONSTRAINT coach_sports_category_id_fkey FOREIGN KEY (category_id) REFERENCES public.sport_categories(id);


--
-- Name: coach_sports coach_sports_coach_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.coach_sports
    ADD CONSTRAINT coach_sports_coach_id_fkey FOREIGN KEY (coach_id) REFERENCES public.users(id) ON DELETE CASCADE;


--
-- Name: courses courses_category_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.courses
    ADD CONSTRAINT courses_category_id_fkey FOREIGN KEY (category_id) REFERENCES public.sport_categories(id);


--
-- Name: courses courses_prerequisite_course_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.courses
    ADD CONSTRAINT courses_prerequisite_course_id_fkey FOREIGN KEY (prerequisite_course_id) REFERENCES public.courses(id);


--
-- Name: facility_attendances facility_attendances_recorded_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.facility_attendances
    ADD CONSTRAINT facility_attendances_recorded_by_fkey FOREIGN KEY (recorded_by) REFERENCES public.users(id);


--
-- Name: facility_attendances facility_attendances_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.facility_attendances
    ADD CONSTRAINT facility_attendances_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id);


--
-- Name: member_profiles member_profiles_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.member_profiles
    ADD CONSTRAINT member_profiles_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;


--
-- Name: member_subscriptions member_subscriptions_created_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.member_subscriptions
    ADD CONSTRAINT member_subscriptions_created_by_fkey FOREIGN KEY (created_by) REFERENCES public.users(id);


--
-- Name: member_subscriptions member_subscriptions_package_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.member_subscriptions
    ADD CONSTRAINT member_subscriptions_package_id_fkey FOREIGN KEY (package_id) REFERENCES public.membership_packages(id);


--
-- Name: member_subscriptions member_subscriptions_upgrade_from_subscription_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.member_subscriptions
    ADD CONSTRAINT member_subscriptions_upgrade_from_subscription_id_fkey FOREIGN KEY (upgrade_from_subscription_id) REFERENCES public.member_subscriptions(id);


--
-- Name: member_subscriptions member_subscriptions_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.member_subscriptions
    ADD CONSTRAINT member_subscriptions_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id);


--
-- Name: notifications notifications_recipient_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.notifications
    ADD CONSTRAINT notifications_recipient_id_fkey FOREIGN KEY (recipient_id) REFERENCES public.users(id) ON DELETE CASCADE;


--
-- Name: notifications notifications_sender_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.notifications
    ADD CONSTRAINT notifications_sender_id_fkey FOREIGN KEY (sender_id) REFERENCES public.users(id);


--
-- Name: orders orders_coach_slot_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.orders
    ADD CONSTRAINT orders_coach_slot_id_fkey FOREIGN KEY (coach_slot_id) REFERENCES public.coach_slots(id);


--
-- Name: orders orders_created_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.orders
    ADD CONSTRAINT orders_created_by_fkey FOREIGN KEY (created_by) REFERENCES public.users(id);


--
-- Name: orders orders_member_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.orders
    ADD CONSTRAINT orders_member_id_fkey FOREIGN KEY (member_id) REFERENCES public.users(id);


--
-- Name: orders orders_registration_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.orders
    ADD CONSTRAINT orders_registration_id_fkey FOREIGN KEY (registration_id) REFERENCES public.class_registrations(id);


--
-- Name: orders orders_subscription_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.orders
    ADD CONSTRAINT orders_subscription_id_fkey FOREIGN KEY (subscription_id) REFERENCES public.member_subscriptions(id);


--
-- Name: orders orders_voucher_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.orders
    ADD CONSTRAINT orders_voucher_id_fkey FOREIGN KEY (voucher_id) REFERENCES public.vouchers(id);


--
-- Name: payments payments_order_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payments
    ADD CONSTRAINT payments_order_id_fkey FOREIGN KEY (order_id) REFERENCES public.orders(id);


--
-- Name: payments payments_payer_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payments
    ADD CONSTRAINT payments_payer_id_fkey FOREIGN KEY (payer_id) REFERENCES public.users(id);


--
-- Name: payments payments_received_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.payments
    ADD CONSTRAINT payments_received_by_fkey FOREIGN KEY (received_by) REFERENCES public.users(id);


--
-- Name: refunds refunds_payment_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.refunds
    ADD CONSTRAINT refunds_payment_id_fkey FOREIGN KEY (payment_id) REFERENCES public.payments(id);


--
-- Name: refunds refunds_processed_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.refunds
    ADD CONSTRAINT refunds_processed_by_fkey FOREIGN KEY (processed_by) REFERENCES public.users(id);


--
-- Name: role_permissions role_permissions_permission_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.role_permissions
    ADD CONSTRAINT role_permissions_permission_id_fkey FOREIGN KEY (permission_id) REFERENCES public.permissions(id) ON DELETE CASCADE;


--
-- Name: role_permissions role_permissions_role_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.role_permissions
    ADD CONSTRAINT role_permissions_role_id_fkey FOREIGN KEY (role_id) REFERENCES public.roles(id) ON DELETE CASCADE;


--
-- Name: room_sports room_sports_category_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.room_sports
    ADD CONSTRAINT room_sports_category_id_fkey FOREIGN KEY (category_id) REFERENCES public.sport_categories(id);


--
-- Name: room_sports room_sports_room_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.room_sports
    ADD CONSTRAINT room_sports_room_id_fkey FOREIGN KEY (room_id) REFERENCES public.facility_rooms(id) ON DELETE CASCADE;


--
-- Name: sport_classes sport_classes_coach_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sport_classes
    ADD CONSTRAINT sport_classes_coach_id_fkey FOREIGN KEY (coach_id) REFERENCES public.users(id);


--
-- Name: sport_classes sport_classes_course_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sport_classes
    ADD CONSTRAINT sport_classes_course_id_fkey FOREIGN KEY (course_id) REFERENCES public.courses(id);


--
-- Name: staff_profiles staff_profiles_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.staff_profiles
    ADD CONSTRAINT staff_profiles_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;


--
-- Name: support_requests support_requests_handled_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.support_requests
    ADD CONSTRAINT support_requests_handled_by_fkey FOREIGN KEY (handled_by) REFERENCES public.users(id);


--
-- Name: support_requests support_requests_member_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.support_requests
    ADD CONSTRAINT support_requests_member_id_fkey FOREIGN KEY (member_id) REFERENCES public.users(id);


--
-- Name: system_settings system_settings_updated_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.system_settings
    ADD CONSTRAINT system_settings_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES public.users(id);


--
-- Name: training_plan_exercises training_plan_exercises_plan_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.training_plan_exercises
    ADD CONSTRAINT training_plan_exercises_plan_id_fkey FOREIGN KEY (plan_id) REFERENCES public.workout_plans(id) ON DELETE RESTRICT;


--
-- Name: training_progress_evaluations training_progress_evaluations_class_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.training_progress_evaluations
    ADD CONSTRAINT training_progress_evaluations_class_id_fkey FOREIGN KEY (class_id) REFERENCES public.sport_classes(id);


--
-- Name: training_progress_evaluations training_progress_evaluations_coach_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.training_progress_evaluations
    ADD CONSTRAINT training_progress_evaluations_coach_id_fkey FOREIGN KEY (coach_id) REFERENCES public.users(id);


--
-- Name: training_progress_evaluations training_progress_evaluations_member_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.training_progress_evaluations
    ADD CONSTRAINT training_progress_evaluations_member_id_fkey FOREIGN KEY (member_id) REFERENCES public.users(id);


--
-- Name: training_results training_results_attendance_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.training_results
    ADD CONSTRAINT training_results_attendance_id_fkey FOREIGN KEY (attendance_id) REFERENCES public.class_attendances(id) ON DELETE RESTRICT;


--
-- Name: training_results training_results_exercise_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.training_results
    ADD CONSTRAINT training_results_exercise_id_fkey FOREIGN KEY (exercise_id) REFERENCES public.training_plan_exercises(id) ON DELETE RESTRICT;


--
-- Name: training_results training_results_recorded_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.training_results
    ADD CONSTRAINT training_results_recorded_by_fkey FOREIGN KEY (recorded_by) REFERENCES public.users(id);


--
-- Name: users users_role_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_role_id_fkey FOREIGN KEY (role_id) REFERENCES public.roles(id);


--
-- Name: voucher_campaigns voucher_campaigns_created_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.voucher_campaigns
    ADD CONSTRAINT voucher_campaigns_created_by_fkey FOREIGN KEY (created_by) REFERENCES public.users(id);


--
-- Name: vouchers vouchers_campaign_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.vouchers
    ADD CONSTRAINT vouchers_campaign_id_fkey FOREIGN KEY (campaign_id) REFERENCES public.voucher_campaigns(id);


--
-- Name: vouchers vouchers_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.vouchers
    ADD CONSTRAINT vouchers_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id);


--
-- Name: workout_plans workout_plans_class_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.workout_plans
    ADD CONSTRAINT workout_plans_class_id_fkey FOREIGN KEY (class_id) REFERENCES public.sport_classes(id);


--
-- Name: workout_plans workout_plans_coach_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.workout_plans
    ADD CONSTRAINT workout_plans_coach_id_fkey FOREIGN KEY (coach_id) REFERENCES public.users(id);


--
-- Name: workout_plans workout_plans_member_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.workout_plans
    ADD CONSTRAINT workout_plans_member_id_fkey FOREIGN KEY (member_id) REFERENCES public.users(id);


--
-- Name: workout_progress workout_progress_member_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.workout_progress
    ADD CONSTRAINT workout_progress_member_id_fkey FOREIGN KEY (member_id) REFERENCES public.users(id);


--
-- Name: workout_progress workout_progress_plan_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.workout_progress
    ADD CONSTRAINT workout_progress_plan_id_fkey FOREIGN KEY (plan_id) REFERENCES public.workout_plans(id) ON DELETE SET NULL;


--
-- Name: workout_progress workout_progress_recorded_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.workout_progress
    ADD CONSTRAINT workout_progress_recorded_by_fkey FOREIGN KEY (recorded_by) REFERENCES public.users(id);


--
-- PostgreSQL database dump complete
--

\unrestrict IfHyrJOdaLHq2SFtlI4pdiFd5C1vOlJHiocQp03JawqtyCfcH1laT3njEmDwUpX

