--
-- PostgreSQL database dump
--

-- Dumped from database version 18.6
-- Dumped by pg_dump version 18.6

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET transaction_timeout = 0;
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
-- Name: EXTENSION btree_gist; Type: COMMENT; Schema: -; Owner: -
--

COMMENT ON EXTENSION btree_gist IS 'support for indexing common datatypes in GiST';


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
    CONSTRAINT ck_coach_rating CHECK (((rating IS NULL) OR ((rating >= (0)::numeric) AND (rating <= (5)::numeric)))),
    CONSTRAINT ck_coach_years CHECK (((years_experience IS NULL) OR (years_experience >= 0)))
);


--
-- Name: coach_slot_request_days; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.coach_slot_request_days (
    id bigint NOT NULL,
    request_id bigint NOT NULL,
    weekday smallint NOT NULL,
    time_slot_id bigint NOT NULL,
    CONSTRAINT ck_request_day_weekday CHECK (((weekday >= 1) AND (weekday <= 7)))
);


--
-- Name: coach_slot_request_days_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.coach_slot_request_days_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: coach_slot_request_days_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.coach_slot_request_days_id_seq OWNED BY public.coach_slot_request_days.id;


--
-- Name: coach_slot_requests; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.coach_slot_requests (
    id bigint NOT NULL,
    member_id bigint NOT NULL,
    coach_id bigint NOT NULL,
    category_id bigint NOT NULL,
    time_slot_id bigint NOT NULL,
    first_date date NOT NULL,
    sessions smallint NOT NULL,
    status character varying(10) DEFAULT 'PENDING'::character varying NOT NULL,
    decided_by bigint,
    decided_at timestamp with time zone,
    reject_reason character varying(500),
    expires_at timestamp with time zone NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT ck_request_reject CHECK ((((status)::text <> 'REJECTED'::text) OR (reject_reason IS NOT NULL))),
    CONSTRAINT ck_request_sessions CHECK ((sessions >= 1)),
    CONSTRAINT ck_request_status CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'APPROVED'::character varying, 'PAID'::character varying, 'REJECTED'::character varying, 'CANCELED'::character varying, 'EXPIRED'::character varying])::text[])))
);


--
-- Name: coach_slot_requests_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.coach_slot_requests_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: coach_slot_requests_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.coach_slot_requests_id_seq OWNED BY public.coach_slot_requests.id;


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
    request_id bigint,
    needs_reschedule boolean DEFAULT false NOT NULL,
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
    tuition_discount_pct smallint DEFAULT 0 NOT NULL,
    one_on_one_discount_pct smallint DEFAULT 0 NOT NULL,
    has_personal_plan boolean DEFAULT false NOT NULL,
    early_leave_refund_sessions smallint DEFAULT 0 NOT NULL,
    waitlist_priority smallint DEFAULT 0 NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT ck_package_121 CHECK (((one_on_one_discount_pct >= 0) AND (one_on_one_discount_pct <= 100))),
    CONSTRAINT ck_package_leave CHECK ((early_leave_refund_sessions >= 0)),
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
    total_amount numeric(12,0) NOT NULL,
    status character varying(10) DEFAULT 'PENDING'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT ck_order_amounts CHECK (((list_price >= (0)::numeric) AND (discount_amount >= (0)::numeric) AND (total_amount >= (0)::numeric) AND (total_amount = (list_price - discount_amount)))),
    CONSTRAINT ck_order_channel CHECK (((channel)::text = ANY (ARRAY[('ONLINE'::character varying)::text, ('COUNTER'::character varying)::text]))),
    CONSTRAINT ck_order_discount_kind CHECK (((discount_kind)::text = ANY (ARRAY[('NONE'::character varying)::text, ('TIER'::character varying)::text, ('VOUCHER'::character varying)::text]))),
    CONSTRAINT ck_order_item CHECK ((((((subscription_id IS NOT NULL))::integer + ((registration_id IS NOT NULL))::integer) + ((coach_slot_id IS NOT NULL))::integer) = 1)),
    CONSTRAINT ck_order_status CHECK (((status)::text = ANY (ARRAY[('PENDING'::character varying)::text, ('PAID'::character varying)::text, ('CANCELED'::character varying)::text, ('EXPIRED'::character varying)::text])))
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
    claim_failed_attempts smallint DEFAULT 0 NOT NULL,
    claim_locked_until timestamp with time zone,
    claim_code_sent_at timestamp with time zone,
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
    registration_opens_on date,
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
-- Name: workout_plans; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.workout_plans (
    id bigint NOT NULL,
    member_id bigint,
    coach_id bigint NOT NULL,
    title character varying(200) NOT NULL,
    description text,
    content jsonb,
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
-- Name: coach_slot_request_days id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.coach_slot_request_days ALTER COLUMN id SET DEFAULT nextval('public.coach_slot_request_days_id_seq'::regclass);


--
-- Name: coach_slot_requests id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.coach_slot_requests ALTER COLUMN id SET DEFAULT nextval('public.coach_slot_requests_id_seq'::regclass);


--
-- Data for Name: audit_logs; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.audit_logs (id, user_id, action, resource, resource_id, payload_diff, created_at) FROM stdin;
1	1	CLASS_CANCELED	sport_class	9	{"reason": "Không đủ học viên tối thiểu"}	2026-11-03 13:22:26.98448+07
2	2	REFUND_COMPLETED	refund	1	{"method": "CASH"}	2026-11-02 13:22:26.98448+07
3	1	COACH_REQUEST_APPROVED	coach_slot_request	2	\N	2026-11-07 10:22:26.98448+07
4	5	TRAINING_PLAN_CREATE	workout_plans	1	\N	2026-10-26 13:22:26.98448+07
5	5	TRAINING_ATTENDANCE_SAVE	class_sessions	1	\N	2026-10-27 13:22:26.98448+07
6	5	TRAINING_EVALUATION_SAVE	training_progress_evaluations	1	\N	2026-11-02 13:22:26.98448+07
\.


--
-- Data for Name: class_attendances; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.class_attendances (id, session_id, user_id, status, check_in_time, recorded_by, notes, recorded_at, updated_at) FROM stdin;
1	1	20	PRESENT	2026-09-21 07:55:00+07	4	\N	2026-09-21 10:15:00+07	2026-09-21 10:15:00+07
2	1	10	PRESENT	2026-09-21 07:55:00+07	4	\N	2026-09-21 10:15:00+07	2026-09-21 10:15:00+07
3	1	9	PRESENT	2026-09-21 07:55:00+07	4	\N	2026-09-21 10:15:00+07	2026-09-21 10:15:00+07
4	1	14	PRESENT	2026-09-21 07:55:00+07	4	\N	2026-09-21 10:15:00+07	2026-09-21 10:15:00+07
5	1	13	PRESENT	2026-09-21 07:55:00+07	4	\N	2026-09-21 10:15:00+07	2026-09-21 10:15:00+07
6	2	20	LATE	2026-09-23 08:12:00+07	4	Đến trễ 12 phút	2026-09-23 10:15:00+07	2026-09-23 10:15:00+07
7	2	10	EXCUSED	\N	4	Báo nghỉ có phép	2026-09-23 10:15:00+07	2026-09-23 10:15:00+07
8	2	9	LATE	2026-09-23 08:12:00+07	4	Đến trễ 12 phút	2026-09-23 10:15:00+07	2026-09-23 10:15:00+07
9	2	14	PRESENT	2026-09-23 07:55:00+07	4	\N	2026-09-23 10:15:00+07	2026-09-23 10:15:00+07
10	2	13	PRESENT	2026-09-23 07:55:00+07	4	\N	2026-09-23 10:15:00+07	2026-09-23 10:15:00+07
11	3	20	PRESENT	2026-09-28 07:55:00+07	4	\N	2026-09-28 10:15:00+07	2026-09-28 10:15:00+07
12	3	10	PRESENT	2026-09-28 07:55:00+07	4	\N	2026-09-28 10:15:00+07	2026-09-28 10:15:00+07
13	3	9	PRESENT	2026-09-28 07:55:00+07	4	\N	2026-09-28 10:15:00+07	2026-09-28 10:15:00+07
14	3	14	EXCUSED	\N	4	Báo nghỉ có phép	2026-09-28 10:15:00+07	2026-09-28 10:15:00+07
15	3	13	LATE	2026-09-28 08:12:00+07	4	Đến trễ 12 phút	2026-09-28 10:15:00+07	2026-09-28 10:15:00+07
16	4	20	PRESENT	2026-09-30 07:55:00+07	4	\N	2026-09-30 10:15:00+07	2026-09-30 10:15:00+07
17	4	10	PRESENT	2026-09-30 07:55:00+07	4	\N	2026-09-30 10:15:00+07	2026-09-30 10:15:00+07
18	4	9	PRESENT	2026-09-30 07:55:00+07	4	\N	2026-09-30 10:15:00+07	2026-09-30 10:15:00+07
19	4	14	PRESENT	2026-09-30 07:55:00+07	4	\N	2026-09-30 10:15:00+07	2026-09-30 10:15:00+07
20	4	13	PRESENT	2026-09-30 07:55:00+07	4	\N	2026-09-30 10:15:00+07	2026-09-30 10:15:00+07
21	5	20	ABSENT	\N	4	Vắng không báo	2026-10-05 10:15:00+07	2026-10-05 10:15:00+07
22	5	10	LATE	2026-10-05 08:12:00+07	4	Đến trễ 12 phút	2026-10-05 10:15:00+07	2026-10-05 10:15:00+07
23	5	9	ABSENT	\N	4	Vắng không báo	2026-10-05 10:15:00+07	2026-10-05 10:15:00+07
24	5	14	PRESENT	2026-10-05 07:55:00+07	4	\N	2026-10-05 10:15:00+07	2026-10-05 10:15:00+07
25	5	13	PRESENT	2026-10-05 07:55:00+07	4	\N	2026-10-05 10:15:00+07	2026-10-05 10:15:00+07
26	6	20	PRESENT	2026-10-07 07:55:00+07	4	\N	2026-10-07 10:15:00+07	2026-10-07 10:15:00+07
27	6	10	PRESENT	2026-10-07 07:55:00+07	4	\N	2026-10-07 10:15:00+07	2026-10-07 10:15:00+07
28	6	9	PRESENT	2026-10-07 07:55:00+07	4	\N	2026-10-07 10:15:00+07	2026-10-07 10:15:00+07
29	6	14	LATE	2026-10-07 08:12:00+07	4	Đến trễ 12 phút	2026-10-07 10:15:00+07	2026-10-07 10:15:00+07
30	6	13	ABSENT	\N	4	Vắng không báo	2026-10-07 10:15:00+07	2026-10-07 10:15:00+07
31	7	20	PRESENT	2026-10-12 07:55:00+07	4	\N	2026-10-12 10:15:00+07	2026-10-12 10:15:00+07
32	7	10	PRESENT	2026-10-12 07:55:00+07	4	\N	2026-10-12 10:15:00+07	2026-10-12 10:15:00+07
33	7	9	PRESENT	2026-10-12 07:55:00+07	4	\N	2026-10-12 10:15:00+07	2026-10-12 10:15:00+07
34	7	14	PRESENT	2026-10-12 07:55:00+07	4	\N	2026-10-12 10:15:00+07	2026-10-12 10:15:00+07
35	7	13	PRESENT	2026-10-12 07:55:00+07	4	\N	2026-10-12 10:15:00+07	2026-10-12 10:15:00+07
36	8	20	PRESENT	2026-10-14 07:55:00+07	4	\N	2026-10-14 10:15:00+07	2026-10-14 10:15:00+07
37	8	10	ABSENT	\N	4	Vắng không báo	2026-10-14 10:15:00+07	2026-10-14 10:15:00+07
38	8	9	PRESENT	2026-10-14 07:55:00+07	4	\N	2026-10-14 10:15:00+07	2026-10-14 10:15:00+07
39	8	14	PRESENT	2026-10-14 07:55:00+07	4	\N	2026-10-14 10:15:00+07	2026-10-14 10:15:00+07
40	8	13	PRESENT	2026-10-14 07:55:00+07	4	\N	2026-10-14 10:15:00+07	2026-10-14 10:15:00+07
41	9	20	PRESENT	2026-10-19 07:55:00+07	4	\N	2026-10-19 10:15:00+07	2026-10-19 10:15:00+07
42	9	10	PRESENT	2026-10-19 07:55:00+07	4	\N	2026-10-19 10:15:00+07	2026-10-19 10:15:00+07
43	9	9	PRESENT	2026-10-19 07:55:00+07	4	\N	2026-10-19 10:15:00+07	2026-10-19 10:15:00+07
44	9	14	ABSENT	\N	4	Vắng không báo	2026-10-19 10:15:00+07	2026-10-19 10:15:00+07
45	9	13	PRESENT	2026-10-19 07:55:00+07	4	\N	2026-10-19 10:15:00+07	2026-10-19 10:15:00+07
46	10	20	EXCUSED	\N	4	Báo nghỉ có phép	2026-10-21 10:15:00+07	2026-10-21 10:15:00+07
47	10	10	PRESENT	2026-10-21 07:55:00+07	4	\N	2026-10-21 10:15:00+07	2026-10-21 10:15:00+07
48	10	9	EXCUSED	\N	4	Báo nghỉ có phép	2026-10-21 10:15:00+07	2026-10-21 10:15:00+07
49	10	14	PRESENT	2026-10-21 07:55:00+07	4	\N	2026-10-21 10:15:00+07	2026-10-21 10:15:00+07
50	10	13	PRESENT	2026-10-21 07:55:00+07	4	\N	2026-10-21 10:15:00+07	2026-10-21 10:15:00+07
51	11	10	PRESENT	2026-10-20 17:55:00+07	3	\N	2026-10-20 20:15:00+07	2026-10-20 20:15:00+07
52	11	11	PRESENT	2026-10-20 17:55:00+07	3	\N	2026-10-20 20:15:00+07	2026-10-20 20:15:00+07
53	11	12	PRESENT	2026-10-20 17:55:00+07	3	\N	2026-10-20 20:15:00+07	2026-10-20 20:15:00+07
54	11	13	PRESENT	2026-10-20 17:55:00+07	3	\N	2026-10-20 20:15:00+07	2026-10-20 20:15:00+07
55	11	17	EXCUSED	\N	3	Báo nghỉ có phép	2026-10-20 20:15:00+07	2026-10-20 20:15:00+07
56	11	19	PRESENT	2026-10-20 17:55:00+07	3	\N	2026-10-20 20:15:00+07	2026-10-20 20:15:00+07
57	12	10	EXCUSED	\N	3	Báo nghỉ có phép	2026-10-22 20:15:00+07	2026-10-22 20:15:00+07
58	12	11	PRESENT	2026-10-22 17:55:00+07	3	\N	2026-10-22 20:15:00+07	2026-10-22 20:15:00+07
59	12	12	PRESENT	2026-10-22 17:55:00+07	3	\N	2026-10-22 20:15:00+07	2026-10-22 20:15:00+07
60	12	13	PRESENT	2026-10-22 17:55:00+07	3	\N	2026-10-22 20:15:00+07	2026-10-22 20:15:00+07
61	12	17	PRESENT	2026-10-22 17:55:00+07	3	\N	2026-10-22 20:15:00+07	2026-10-22 20:15:00+07
62	12	19	ABSENT	\N	3	Vắng không báo	2026-10-22 20:15:00+07	2026-10-22 20:15:00+07
63	13	10	PRESENT	2026-10-27 17:55:00+07	3	\N	2026-10-27 20:15:00+07	2026-10-27 20:15:00+07
64	13	11	PRESENT	2026-10-27 17:55:00+07	3	\N	2026-10-27 20:15:00+07	2026-10-27 20:15:00+07
65	13	12	ABSENT	\N	3	Vắng không báo	2026-10-27 20:15:00+07	2026-10-27 20:15:00+07
66	13	13	LATE	2026-10-27 18:12:00+07	3	Đến trễ 12 phút	2026-10-27 20:15:00+07	2026-10-27 20:15:00+07
67	13	17	PRESENT	2026-10-27 17:55:00+07	3	\N	2026-10-27 20:15:00+07	2026-10-27 20:15:00+07
68	13	19	PRESENT	2026-10-27 17:55:00+07	3	\N	2026-10-27 20:15:00+07	2026-10-27 20:15:00+07
69	14	10	PRESENT	2026-10-29 17:55:00+07	3	\N	2026-10-29 20:15:00+07	2026-10-29 20:15:00+07
70	14	11	PRESENT	2026-10-29 17:55:00+07	3	\N	2026-10-29 20:15:00+07	2026-10-29 20:15:00+07
71	14	12	PRESENT	2026-10-29 17:55:00+07	3	\N	2026-10-29 20:15:00+07	2026-10-29 20:15:00+07
72	14	13	PRESENT	2026-10-29 17:55:00+07	3	\N	2026-10-29 20:15:00+07	2026-10-29 20:15:00+07
73	14	17	LATE	2026-10-29 18:12:00+07	3	Đến trễ 12 phút	2026-10-29 20:15:00+07	2026-10-29 20:15:00+07
74	14	19	PRESENT	2026-10-29 17:55:00+07	3	\N	2026-10-29 20:15:00+07	2026-10-29 20:15:00+07
75	15	10	LATE	2026-11-03 18:12:00+07	3	Đến trễ 12 phút	2026-11-03 20:15:00+07	2026-11-03 20:15:00+07
76	15	11	EXCUSED	\N	3	Báo nghỉ có phép	2026-11-03 20:15:00+07	2026-11-03 20:15:00+07
77	15	12	PRESENT	2026-11-03 17:55:00+07	3	\N	2026-11-03 20:15:00+07	2026-11-03 20:15:00+07
78	15	13	PRESENT	2026-11-03 17:55:00+07	3	\N	2026-11-03 20:15:00+07	2026-11-03 20:15:00+07
79	15	17	PRESENT	2026-11-03 17:55:00+07	3	\N	2026-11-03 20:15:00+07	2026-11-03 20:15:00+07
80	15	19	PRESENT	2026-11-03 17:55:00+07	3	\N	2026-11-03 20:15:00+07	2026-11-03 20:15:00+07
81	16	10	PRESENT	2026-11-05 17:55:00+07	3	\N	2026-11-05 20:15:00+07	2026-11-05 20:15:00+07
82	16	11	PRESENT	2026-11-05 17:55:00+07	3	\N	2026-11-05 20:15:00+07	2026-11-05 20:15:00+07
83	16	12	PRESENT	2026-11-05 17:55:00+07	3	\N	2026-11-05 20:15:00+07	2026-11-05 20:15:00+07
84	16	13	ABSENT	\N	3	Vắng không báo	2026-11-05 20:15:00+07	2026-11-05 20:15:00+07
85	16	17	PRESENT	2026-11-05 17:55:00+07	3	\N	2026-11-05 20:15:00+07	2026-11-05 20:15:00+07
86	16	19	PRESENT	2026-11-05 17:55:00+07	3	\N	2026-11-05 20:15:00+07	2026-11-05 20:15:00+07
87	23	9	PRESENT	2026-10-26 15:55:00+07	5	\N	2026-10-26 18:15:00+07	2026-10-26 18:15:00+07
88	23	11	PRESENT	2026-10-26 15:55:00+07	5	\N	2026-10-26 18:15:00+07	2026-10-26 18:15:00+07
89	23	19	PRESENT	2026-10-26 15:55:00+07	5	\N	2026-10-26 18:15:00+07	2026-10-26 18:15:00+07
90	23	18	PRESENT	2026-10-26 15:55:00+07	5	\N	2026-10-26 18:15:00+07	2026-10-26 18:15:00+07
91	23	20	PRESENT	2026-10-26 15:55:00+07	5	\N	2026-10-26 18:15:00+07	2026-10-26 18:15:00+07
92	24	9	LATE	2026-10-28 16:12:00+07	5	Đến trễ 12 phút	2026-10-28 18:15:00+07	2026-10-28 18:15:00+07
93	24	11	PRESENT	2026-10-28 15:55:00+07	5	\N	2026-10-28 18:15:00+07	2026-10-28 18:15:00+07
94	24	19	ABSENT	\N	5	Vắng không báo	2026-10-28 18:15:00+07	2026-10-28 18:15:00+07
95	24	18	PRESENT	2026-10-28 15:55:00+07	5	\N	2026-10-28 18:15:00+07	2026-10-28 18:15:00+07
96	24	20	LATE	2026-10-28 16:12:00+07	5	Đến trễ 12 phút	2026-10-28 18:15:00+07	2026-10-28 18:15:00+07
97	25	9	PRESENT	2026-10-30 15:55:00+07	5	\N	2026-10-30 18:15:00+07	2026-10-30 18:15:00+07
98	25	11	PRESENT	2026-10-30 15:55:00+07	5	\N	2026-10-30 18:15:00+07	2026-10-30 18:15:00+07
99	25	19	PRESENT	2026-10-30 15:55:00+07	5	\N	2026-10-30 18:15:00+07	2026-10-30 18:15:00+07
100	25	18	PRESENT	2026-10-30 15:55:00+07	5	\N	2026-10-30 18:15:00+07	2026-10-30 18:15:00+07
101	25	20	PRESENT	2026-10-30 15:55:00+07	5	\N	2026-10-30 18:15:00+07	2026-10-30 18:15:00+07
102	26	9	PRESENT	2026-11-02 15:55:00+07	5	\N	2026-11-02 18:15:00+07	2026-11-02 18:15:00+07
103	26	11	PRESENT	2026-11-02 15:55:00+07	5	\N	2026-11-02 18:15:00+07	2026-11-02 18:15:00+07
104	26	19	PRESENT	2026-11-02 15:55:00+07	5	\N	2026-11-02 18:15:00+07	2026-11-02 18:15:00+07
105	26	18	EXCUSED	\N	5	Báo nghỉ có phép	2026-11-02 18:15:00+07	2026-11-02 18:15:00+07
106	26	20	PRESENT	2026-11-02 15:55:00+07	5	\N	2026-11-02 18:15:00+07	2026-11-02 18:15:00+07
107	27	9	ABSENT	\N	5	Vắng không báo	2026-11-04 18:15:00+07	2026-11-04 18:15:00+07
108	27	11	EXCUSED	\N	5	Báo nghỉ có phép	2026-11-04 18:15:00+07	2026-11-04 18:15:00+07
109	27	19	PRESENT	2026-11-04 15:55:00+07	5	\N	2026-11-04 18:15:00+07	2026-11-04 18:15:00+07
110	27	18	PRESENT	2026-11-04 15:55:00+07	5	\N	2026-11-04 18:15:00+07	2026-11-04 18:15:00+07
111	27	20	ABSENT	\N	5	Vắng không báo	2026-11-04 18:15:00+07	2026-11-04 18:15:00+07
112	28	9	PRESENT	2026-11-06 15:55:00+07	5	\N	2026-11-06 18:15:00+07	2026-11-06 18:15:00+07
113	28	11	PRESENT	2026-11-06 15:55:00+07	5	\N	2026-11-06 18:15:00+07	2026-11-06 18:15:00+07
114	28	19	PRESENT	2026-11-06 15:55:00+07	5	\N	2026-11-06 18:15:00+07	2026-11-06 18:15:00+07
115	28	18	PRESENT	2026-11-06 15:55:00+07	5	\N	2026-11-06 18:15:00+07	2026-11-06 18:15:00+07
116	28	20	PRESENT	2026-11-06 15:55:00+07	5	\N	2026-11-06 18:15:00+07	2026-11-06 18:15:00+07
117	35	9	PRESENT	2026-10-24 15:55:00+07	7	\N	2026-10-24 18:15:00+07	2026-10-24 18:15:00+07
118	35	10	PRESENT	2026-10-24 15:55:00+07	7	\N	2026-10-24 18:15:00+07	2026-10-24 18:15:00+07
119	35	12	PRESENT	2026-10-24 15:55:00+07	7	\N	2026-10-24 18:15:00+07	2026-10-24 18:15:00+07
120	35	17	EXCUSED	\N	7	Báo nghỉ có phép	2026-10-24 18:15:00+07	2026-10-24 18:15:00+07
121	36	9	LATE	2026-10-25 16:12:00+07	7	Đến trễ 12 phút	2026-10-25 18:15:00+07	2026-10-25 18:15:00+07
122	36	10	EXCUSED	\N	7	Báo nghỉ có phép	2026-10-25 18:15:00+07	2026-10-25 18:15:00+07
123	36	12	PRESENT	2026-10-25 15:55:00+07	7	\N	2026-10-25 18:15:00+07	2026-10-25 18:15:00+07
124	36	17	PRESENT	2026-10-25 15:55:00+07	7	\N	2026-10-25 18:15:00+07	2026-10-25 18:15:00+07
125	37	9	PRESENT	2026-10-31 15:55:00+07	7	\N	2026-10-31 18:15:00+07	2026-10-31 18:15:00+07
126	37	10	PRESENT	2026-10-31 15:55:00+07	7	\N	2026-10-31 18:15:00+07	2026-10-31 18:15:00+07
127	37	12	ABSENT	\N	7	Vắng không báo	2026-10-31 18:15:00+07	2026-10-31 18:15:00+07
128	37	17	PRESENT	2026-10-31 15:55:00+07	7	\N	2026-10-31 18:15:00+07	2026-10-31 18:15:00+07
129	38	9	PRESENT	2026-11-01 15:55:00+07	7	\N	2026-11-01 18:15:00+07	2026-11-01 18:15:00+07
130	38	10	PRESENT	2026-11-01 15:55:00+07	7	\N	2026-11-01 18:15:00+07	2026-11-01 18:15:00+07
131	38	12	PRESENT	2026-11-01 15:55:00+07	7	\N	2026-11-01 18:15:00+07	2026-11-01 18:15:00+07
132	38	17	LATE	2026-11-01 16:12:00+07	7	Đến trễ 12 phút	2026-11-01 18:15:00+07	2026-11-01 18:15:00+07
\.


--
-- Data for Name: class_evaluations; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.class_evaluations (id, registration_id, coach_id, grade, comment, created_at) FROM stdin;
1	1	4	B	Cần luyện thêm độ dẻo phần lưng, rất chăm chỉ.	2026-10-22 10:00:00+07
2	2	4	A	Tập trung tốt, nên giữ nhịp tập đều đặn.	2026-10-22 10:00:00+07
3	3	4	C	Đi học chưa đều, cần cố gắng hơn ở lớp sau.	2026-10-22 10:00:00+07
4	4	4	B	Có tiến bộ ở các tư thế thăng bằng.	2026-10-22 10:00:00+07
5	5	4	A	Tư thế chuẩn, hơi thở đều, tiến bộ rõ rệt.	2026-10-22 10:00:00+07
\.


--
-- Data for Name: class_registrations; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.class_registrations (id, class_id, user_id, registered_at, status, canceled_at, registered_by, hold_expires_at, waitlist_rank, offer_expires_at) FROM stdin;
1	1	20	2026-09-11 10:00:00+07	REGISTERED	\N	\N	\N	0	\N
2	1	10	2026-09-11 10:00:00+07	REGISTERED	\N	\N	\N	0	\N
3	1	9	2026-09-11 10:00:00+07	REGISTERED	\N	2	\N	0	\N
4	1	14	2026-09-11 10:00:00+07	REGISTERED	\N	2	\N	0	\N
5	1	13	2026-09-11 10:00:00+07	REGISTERED	\N	\N	\N	0	\N
6	2	10	2026-10-12 10:00:00+07	REGISTERED	\N	\N	\N	0	\N
7	2	11	2026-10-12 10:00:00+07	REGISTERED	\N	\N	\N	0	\N
8	2	12	2026-10-12 10:00:00+07	REGISTERED	\N	\N	\N	0	\N
9	2	13	2026-10-12 10:00:00+07	REGISTERED	\N	\N	\N	0	\N
10	2	17	2026-10-12 10:00:00+07	REGISTERED	\N	\N	\N	0	\N
11	2	19	2026-10-12 10:00:00+07	REGISTERED	\N	\N	\N	0	\N
12	3	9	2026-10-18 10:00:00+07	REGISTERED	\N	2	\N	0	\N
13	3	11	2026-10-18 10:00:00+07	REGISTERED	\N	\N	\N	0	\N
14	3	19	2026-10-18 10:00:00+07	REGISTERED	\N	\N	\N	0	\N
15	3	18	2026-10-18 10:00:00+07	REGISTERED	\N	\N	\N	0	\N
16	3	20	2026-10-18 10:00:00+07	REGISTERED	\N	2	\N	0	\N
17	4	9	2026-10-16 10:00:00+07	REGISTERED	\N	2	\N	0	\N
18	4	10	2026-10-16 10:00:00+07	REGISTERED	\N	\N	\N	0	\N
19	4	12	2026-10-16 10:00:00+07	REGISTERED	\N	\N	\N	0	\N
20	4	17	2026-10-16 10:00:00+07	REGISTERED	\N	\N	\N	0	\N
21	5	13	2026-11-04 13:22:26.98448+07	REGISTERED	\N	\N	\N	0	\N
22	5	18	2026-11-05 13:22:26.98448+07	REGISTERED	\N	\N	\N	0	\N
23	5	16	2026-11-07 13:17:26.98448+07	PENDING_PAYMENT	\N	\N	2026-11-07 19:22:26.98448+07	0	\N
24	6	9	2026-11-03 13:22:26.98448+07	REGISTERED	\N	2	\N	0	\N
25	6	10	2026-11-03 13:22:26.98448+07	REGISTERED	\N	\N	\N	0	\N
26	6	12	2026-11-03 13:22:26.98448+07	REGISTERED	\N	\N	\N	0	\N
27	6	20	2026-11-03 13:22:26.98448+07	REGISTERED	\N	\N	\N	0	\N
28	6	13	2026-11-05 13:22:26.98448+07	WAITLISTED	\N	\N	\N	1	\N
29	6	17	2026-11-06 13:22:26.98448+07	WAITLISTED	\N	\N	\N	2	\N
30	7	20	2026-11-06 13:22:26.98448+07	REGISTERED	\N	2	\N	0	\N
31	9	14	2026-10-29 13:22:26.98448+07	CANCELED	2026-11-01 13:22:26.98448+07	2	\N	0	\N
32	9	13	2026-10-30 13:22:26.98448+07	CANCELED	2026-11-03 13:22:26.98448+07	\N	\N	0	\N
\.


--
-- Data for Name: class_schedules; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.class_schedules (id, class_id, room_id, day_of_week, start_time, end_time, time_slot_id) FROM stdin;
1	1	2	1	08:00:00	09:45:00	2
2	1	2	3	08:00:00	09:45:00	2
3	2	1	2	18:00:00	19:45:00	7
4	2	1	4	18:00:00	19:45:00	7
5	3	8	1	16:00:00	17:45:00	6
6	3	8	3	16:00:00	17:45:00	6
7	3	8	5	16:00:00	17:45:00	6
8	4	6	6	16:00:00	17:45:00	6
9	4	6	7	16:00:00	17:45:00	6
10	5	9	2	08:00:00	09:45:00	2
11	5	9	4	08:00:00	09:45:00	2
12	6	10	1	20:00:00	21:45:00	8
13	6	10	3	20:00:00	21:45:00	8
14	7	3	2	18:00:00	19:45:00	7
15	7	3	4	18:00:00	19:45:00	7
16	8	4	6	14:00:00	15:45:00	5
17	9	1	1	10:00:00	11:45:00	3
18	9	1	3	10:00:00	11:45:00	3
\.


--
-- Data for Name: class_sessions; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.class_sessions (id, class_id, session_no, session_date, start_time, end_time, room_id, coach_id, status, is_makeup, cancel_reason, created_at, updated_at, attendance_finalized_at, training_version) FROM stdin;
1	1	1	2026-09-21	08:00:00	09:45:00	2	4	DONE	f	\N	2026-11-07 13:22:26.98448+07	2026-11-07 13:22:26.98448+07	2026-09-21 10:15:00+07	0
2	1	2	2026-09-23	08:00:00	09:45:00	2	4	DONE	f	\N	2026-11-07 13:22:26.98448+07	2026-11-07 13:22:26.98448+07	2026-09-23 10:15:00+07	0
3	1	3	2026-09-28	08:00:00	09:45:00	2	4	DONE	f	\N	2026-11-07 13:22:26.98448+07	2026-11-07 13:22:26.98448+07	2026-09-28 10:15:00+07	0
4	1	4	2026-09-30	08:00:00	09:45:00	2	4	DONE	f	\N	2026-11-07 13:22:26.98448+07	2026-11-07 13:22:26.98448+07	2026-09-30 10:15:00+07	0
5	1	5	2026-10-05	08:00:00	09:45:00	2	4	DONE	f	\N	2026-11-07 13:22:26.98448+07	2026-11-07 13:22:26.98448+07	2026-10-05 10:15:00+07	0
6	1	6	2026-10-07	08:00:00	09:45:00	2	4	DONE	f	\N	2026-11-07 13:22:26.98448+07	2026-11-07 13:22:26.98448+07	2026-10-07 10:15:00+07	0
7	1	7	2026-10-12	08:00:00	09:45:00	2	4	DONE	f	\N	2026-11-07 13:22:26.98448+07	2026-11-07 13:22:26.98448+07	2026-10-12 10:15:00+07	0
8	1	8	2026-10-14	08:00:00	09:45:00	2	4	DONE	f	\N	2026-11-07 13:22:26.98448+07	2026-11-07 13:22:26.98448+07	2026-10-14 10:15:00+07	0
9	1	9	2026-10-19	08:00:00	09:45:00	2	4	DONE	f	\N	2026-11-07 13:22:26.98448+07	2026-11-07 13:22:26.98448+07	2026-10-19 10:15:00+07	0
10	1	10	2026-10-21	08:00:00	09:45:00	2	4	DONE	f	\N	2026-11-07 13:22:26.98448+07	2026-11-07 13:22:26.98448+07	2026-10-21 10:15:00+07	0
11	2	1	2026-10-20	18:00:00	19:45:00	1	3	DONE	f	\N	2026-11-07 13:22:26.98448+07	2026-11-07 13:22:26.98448+07	2026-10-20 20:15:00+07	0
12	2	2	2026-10-22	18:00:00	19:45:00	1	3	DONE	f	\N	2026-11-07 13:22:26.98448+07	2026-11-07 13:22:26.98448+07	2026-10-22 20:15:00+07	0
13	2	3	2026-10-27	18:00:00	19:45:00	1	3	DONE	f	\N	2026-11-07 13:22:26.98448+07	2026-11-07 13:22:26.98448+07	2026-10-27 20:15:00+07	0
14	2	4	2026-10-29	18:00:00	19:45:00	1	3	DONE	f	\N	2026-11-07 13:22:26.98448+07	2026-11-07 13:22:26.98448+07	2026-10-29 20:15:00+07	0
15	2	5	2026-11-03	18:00:00	19:45:00	1	3	DONE	f	\N	2026-11-07 13:22:26.98448+07	2026-11-07 13:22:26.98448+07	2026-11-03 20:15:00+07	0
16	2	6	2026-11-05	18:00:00	19:45:00	1	3	DONE	f	\N	2026-11-07 13:22:26.98448+07	2026-11-07 13:22:26.98448+07	2026-11-05 20:15:00+07	0
17	2	7	2026-11-10	18:00:00	19:45:00	1	3	SCHEDULED	f	\N	2026-11-07 13:22:26.98448+07	2026-11-07 13:22:26.98448+07	\N	0
19	2	9	2026-11-17	18:00:00	19:45:00	1	3	SCHEDULED	f	\N	2026-11-07 13:22:26.98448+07	2026-11-07 13:22:26.98448+07	\N	0
20	2	10	2026-11-19	18:00:00	19:45:00	1	3	SCHEDULED	f	\N	2026-11-07 13:22:26.98448+07	2026-11-07 13:22:26.98448+07	\N	0
21	2	11	2026-11-24	18:00:00	19:45:00	1	3	SCHEDULED	f	\N	2026-11-07 13:22:26.98448+07	2026-11-07 13:22:26.98448+07	\N	0
22	2	12	2026-11-26	18:00:00	19:45:00	1	3	SCHEDULED	f	\N	2026-11-07 13:22:26.98448+07	2026-11-07 13:22:26.98448+07	\N	0
23	3	1	2026-10-26	16:00:00	17:45:00	8	5	DONE	f	\N	2026-11-07 13:22:26.98448+07	2026-11-07 13:22:26.98448+07	2026-10-26 18:15:00+07	0
24	3	2	2026-10-28	16:00:00	17:45:00	8	5	DONE	f	\N	2026-11-07 13:22:26.98448+07	2026-11-07 13:22:26.98448+07	2026-10-28 18:15:00+07	0
25	3	3	2026-10-30	16:00:00	17:45:00	8	5	DONE	f	\N	2026-11-07 13:22:26.98448+07	2026-11-07 13:22:26.98448+07	2026-10-30 18:15:00+07	0
26	3	4	2026-11-02	16:00:00	17:45:00	8	5	DONE	f	\N	2026-11-07 13:22:26.98448+07	2026-11-07 13:22:26.98448+07	2026-11-02 18:15:00+07	0
27	3	5	2026-11-04	16:00:00	17:45:00	8	5	DONE	f	\N	2026-11-07 13:22:26.98448+07	2026-11-07 13:22:26.98448+07	2026-11-04 18:15:00+07	0
28	3	6	2026-11-06	16:00:00	17:45:00	8	5	DONE	f	\N	2026-11-07 13:22:26.98448+07	2026-11-07 13:22:26.98448+07	2026-11-06 18:15:00+07	0
29	3	7	2026-11-09	16:00:00	17:45:00	8	5	SCHEDULED	f	\N	2026-11-07 13:22:26.98448+07	2026-11-07 13:22:26.98448+07	\N	0
30	3	8	2026-11-11	16:00:00	17:45:00	8	5	SCHEDULED	f	\N	2026-11-07 13:22:26.98448+07	2026-11-07 13:22:26.98448+07	\N	0
31	3	9	2026-11-13	16:00:00	17:45:00	8	5	SCHEDULED	f	\N	2026-11-07 13:22:26.98448+07	2026-11-07 13:22:26.98448+07	\N	0
32	3	10	2026-11-16	16:00:00	17:45:00	8	5	SCHEDULED	f	\N	2026-11-07 13:22:26.98448+07	2026-11-07 13:22:26.98448+07	\N	0
33	3	11	2026-11-18	16:00:00	17:45:00	8	5	SCHEDULED	f	\N	2026-11-07 13:22:26.98448+07	2026-11-07 13:22:26.98448+07	\N	0
34	3	12	2026-11-20	16:00:00	17:45:00	8	5	SCHEDULED	f	\N	2026-11-07 13:22:26.98448+07	2026-11-07 13:22:26.98448+07	\N	0
35	4	1	2026-10-24	16:00:00	17:45:00	6	7	DONE	f	\N	2026-11-07 13:22:26.98448+07	2026-11-07 13:22:26.98448+07	2026-10-24 18:15:00+07	0
36	4	2	2026-10-25	16:00:00	17:45:00	6	7	DONE	f	\N	2026-11-07 13:22:26.98448+07	2026-11-07 13:22:26.98448+07	2026-10-25 18:15:00+07	0
37	4	3	2026-10-31	16:00:00	17:45:00	6	7	DONE	f	\N	2026-11-07 13:22:26.98448+07	2026-11-07 13:22:26.98448+07	2026-10-31 18:15:00+07	0
38	4	4	2026-11-01	16:00:00	17:45:00	6	7	DONE	f	\N	2026-11-07 13:22:26.98448+07	2026-11-07 13:22:26.98448+07	2026-11-01 18:15:00+07	0
39	4	5	2026-11-07	16:00:00	17:45:00	6	7	SCHEDULED	f	\N	2026-11-07 13:22:26.98448+07	2026-11-07 13:22:26.98448+07	\N	0
40	4	6	2026-11-08	16:00:00	17:45:00	6	7	SCHEDULED	f	\N	2026-11-07 13:22:26.98448+07	2026-11-07 13:22:26.98448+07	\N	0
41	4	7	2026-11-14	16:00:00	17:45:00	6	7	SCHEDULED	f	\N	2026-11-07 13:22:26.98448+07	2026-11-07 13:22:26.98448+07	\N	0
42	4	8	2026-11-15	16:00:00	17:45:00	6	7	SCHEDULED	f	\N	2026-11-07 13:22:26.98448+07	2026-11-07 13:22:26.98448+07	\N	0
43	4	9	2026-11-21	16:00:00	17:45:00	6	7	SCHEDULED	f	\N	2026-11-07 13:22:26.98448+07	2026-11-07 13:22:26.98448+07	\N	0
44	4	10	2026-11-22	16:00:00	17:45:00	6	7	SCHEDULED	f	\N	2026-11-07 13:22:26.98448+07	2026-11-07 13:22:26.98448+07	\N	0
45	4	11	2026-11-28	16:00:00	17:45:00	6	7	SCHEDULED	f	\N	2026-11-07 13:22:26.98448+07	2026-11-07 13:22:26.98448+07	\N	0
46	4	12	2026-11-29	16:00:00	17:45:00	6	7	SCHEDULED	f	\N	2026-11-07 13:22:26.98448+07	2026-11-07 13:22:26.98448+07	\N	0
47	5	1	2026-11-17	08:00:00	09:45:00	9	6	SCHEDULED	f	\N	2026-11-07 13:22:26.98448+07	2026-11-07 13:22:26.98448+07	\N	0
48	5	2	2026-11-19	08:00:00	09:45:00	9	6	SCHEDULED	f	\N	2026-11-07 13:22:26.98448+07	2026-11-07 13:22:26.98448+07	\N	0
49	5	3	2026-11-24	08:00:00	09:45:00	9	6	SCHEDULED	f	\N	2026-11-07 13:22:26.98448+07	2026-11-07 13:22:26.98448+07	\N	0
50	5	4	2026-11-26	08:00:00	09:45:00	9	6	SCHEDULED	f	\N	2026-11-07 13:22:26.98448+07	2026-11-07 13:22:26.98448+07	\N	0
51	5	5	2026-12-01	08:00:00	09:45:00	9	6	SCHEDULED	f	\N	2026-11-07 13:22:26.98448+07	2026-11-07 13:22:26.98448+07	\N	0
52	5	6	2026-12-03	08:00:00	09:45:00	9	6	SCHEDULED	f	\N	2026-11-07 13:22:26.98448+07	2026-11-07 13:22:26.98448+07	\N	0
53	5	7	2026-12-08	08:00:00	09:45:00	9	6	SCHEDULED	f	\N	2026-11-07 13:22:26.98448+07	2026-11-07 13:22:26.98448+07	\N	0
54	5	8	2026-12-10	08:00:00	09:45:00	9	6	SCHEDULED	f	\N	2026-11-07 13:22:26.98448+07	2026-11-07 13:22:26.98448+07	\N	0
55	6	1	2026-11-16	20:00:00	21:45:00	10	4	SCHEDULED	f	\N	2026-11-07 13:22:26.98448+07	2026-11-07 13:22:26.98448+07	\N	0
56	6	2	2026-11-18	20:00:00	21:45:00	10	4	SCHEDULED	f	\N	2026-11-07 13:22:26.98448+07	2026-11-07 13:22:26.98448+07	\N	0
57	6	3	2026-11-23	20:00:00	21:45:00	10	4	SCHEDULED	f	\N	2026-11-07 13:22:26.98448+07	2026-11-07 13:22:26.98448+07	\N	0
58	6	4	2026-11-25	20:00:00	21:45:00	10	4	SCHEDULED	f	\N	2026-11-07 13:22:26.98448+07	2026-11-07 13:22:26.98448+07	\N	0
59	6	5	2026-11-30	20:00:00	21:45:00	10	4	SCHEDULED	f	\N	2026-11-07 13:22:26.98448+07	2026-11-07 13:22:26.98448+07	\N	0
60	6	6	2026-12-02	20:00:00	21:45:00	10	4	SCHEDULED	f	\N	2026-11-07 13:22:26.98448+07	2026-11-07 13:22:26.98448+07	\N	0
61	6	7	2026-12-07	20:00:00	21:45:00	10	4	SCHEDULED	f	\N	2026-11-07 13:22:26.98448+07	2026-11-07 13:22:26.98448+07	\N	0
62	6	8	2026-12-09	20:00:00	21:45:00	10	4	SCHEDULED	f	\N	2026-11-07 13:22:26.98448+07	2026-11-07 13:22:26.98448+07	\N	0
63	6	9	2026-12-14	20:00:00	21:45:00	10	4	SCHEDULED	f	\N	2026-11-07 13:22:26.98448+07	2026-11-07 13:22:26.98448+07	\N	0
64	6	10	2026-12-16	20:00:00	21:45:00	10	4	SCHEDULED	f	\N	2026-11-07 13:22:26.98448+07	2026-11-07 13:22:26.98448+07	\N	0
65	7	1	2026-11-24	18:00:00	19:45:00	3	4	SCHEDULED	f	\N	2026-11-07 13:22:26.98448+07	2026-11-07 13:22:26.98448+07	\N	0
66	7	2	2026-11-26	18:00:00	19:45:00	3	4	SCHEDULED	f	\N	2026-11-07 13:22:26.98448+07	2026-11-07 13:22:26.98448+07	\N	0
67	7	3	2026-12-01	18:00:00	19:45:00	3	4	SCHEDULED	f	\N	2026-11-07 13:22:26.98448+07	2026-11-07 13:22:26.98448+07	\N	0
68	7	4	2026-12-03	18:00:00	19:45:00	3	4	SCHEDULED	f	\N	2026-11-07 13:22:26.98448+07	2026-11-07 13:22:26.98448+07	\N	0
69	7	5	2026-12-08	18:00:00	19:45:00	3	4	SCHEDULED	f	\N	2026-11-07 13:22:26.98448+07	2026-11-07 13:22:26.98448+07	\N	0
70	7	6	2026-12-10	18:00:00	19:45:00	3	4	SCHEDULED	f	\N	2026-11-07 13:22:26.98448+07	2026-11-07 13:22:26.98448+07	\N	0
71	7	7	2026-12-15	18:00:00	19:45:00	3	4	SCHEDULED	f	\N	2026-11-07 13:22:26.98448+07	2026-11-07 13:22:26.98448+07	\N	0
72	7	8	2026-12-17	18:00:00	19:45:00	3	4	SCHEDULED	f	\N	2026-11-07 13:22:26.98448+07	2026-11-07 13:22:26.98448+07	\N	0
73	7	9	2026-12-22	18:00:00	19:45:00	3	4	SCHEDULED	f	\N	2026-11-07 13:22:26.98448+07	2026-11-07 13:22:26.98448+07	\N	0
74	7	10	2026-12-24	18:00:00	19:45:00	3	4	SCHEDULED	f	\N	2026-11-07 13:22:26.98448+07	2026-11-07 13:22:26.98448+07	\N	0
75	9	1	2026-11-09	10:00:00	11:45:00	1	7	CANCELED_BY_CENTER	f	Lớp bị hủy do không đủ học viên tối thiểu	2026-11-07 13:22:26.98448+07	2026-11-07 13:22:26.98448+07	\N	0
76	9	2	2026-11-11	10:00:00	11:45:00	1	7	CANCELED_BY_CENTER	f	Lớp bị hủy do không đủ học viên tối thiểu	2026-11-07 13:22:26.98448+07	2026-11-07 13:22:26.98448+07	\N	0
77	9	3	2026-11-16	10:00:00	11:45:00	1	7	CANCELED_BY_CENTER	f	Lớp bị hủy do không đủ học viên tối thiểu	2026-11-07 13:22:26.98448+07	2026-11-07 13:22:26.98448+07	\N	0
78	9	4	2026-11-18	10:00:00	11:45:00	1	7	CANCELED_BY_CENTER	f	Lớp bị hủy do không đủ học viên tối thiểu	2026-11-07 13:22:26.98448+07	2026-11-07 13:22:26.98448+07	\N	0
79	9	5	2026-11-23	10:00:00	11:45:00	1	7	CANCELED_BY_CENTER	f	Lớp bị hủy do không đủ học viên tối thiểu	2026-11-07 13:22:26.98448+07	2026-11-07 13:22:26.98448+07	\N	0
80	9	6	2026-11-25	10:00:00	11:45:00	1	7	CANCELED_BY_CENTER	f	Lớp bị hủy do không đủ học viên tối thiểu	2026-11-07 13:22:26.98448+07	2026-11-07 13:22:26.98448+07	\N	0
81	9	7	2026-11-30	10:00:00	11:45:00	1	7	CANCELED_BY_CENTER	f	Lớp bị hủy do không đủ học viên tối thiểu	2026-11-07 13:22:26.98448+07	2026-11-07 13:22:26.98448+07	\N	0
82	9	8	2026-12-02	10:00:00	11:45:00	1	7	CANCELED_BY_CENTER	f	Lớp bị hủy do không đủ học viên tối thiểu	2026-11-07 13:22:26.98448+07	2026-11-07 13:22:26.98448+07	\N	0
83	9	9	2026-12-07	10:00:00	11:45:00	1	7	CANCELED_BY_CENTER	f	Lớp bị hủy do không đủ học viên tối thiểu	2026-11-07 13:22:26.98448+07	2026-11-07 13:22:26.98448+07	\N	0
84	9	10	2026-12-09	10:00:00	11:45:00	1	7	CANCELED_BY_CENTER	f	Lớp bị hủy do không đủ học viên tối thiểu	2026-11-07 13:22:26.98448+07	2026-11-07 13:22:26.98448+07	\N	0
85	9	11	2026-12-14	10:00:00	11:45:00	1	7	CANCELED_BY_CENTER	f	Lớp bị hủy do không đủ học viên tối thiểu	2026-11-07 13:22:26.98448+07	2026-11-07 13:22:26.98448+07	\N	0
86	9	12	2026-12-16	10:00:00	11:45:00	1	7	CANCELED_BY_CENTER	f	Lớp bị hủy do không đủ học viên tối thiểu	2026-11-07 13:22:26.98448+07	2026-11-07 13:22:26.98448+07	\N	0
18	2	8	2026-11-12	18:00:00	19:45:00	1	3	CANCELED_BY_CENTER	f	Hồ bơi bảo trì đột xuất	2026-11-07 13:22:26.98448+07	2026-11-07 13:22:26.98448+07	\N	0
87	2	13	2026-11-27	18:00:00	19:45:00	1	3	SCHEDULED	t	\N	2026-11-07 13:22:26.98448+07	2026-11-07 13:22:26.98448+07	\N	0
\.


--
-- Data for Name: coach_profiles; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.coach_profiles (user_id, specialty, years_experience, certifications, rating) FROM stdin;
3	Bơi lội	9	Chứng chỉ HLV bơi lội quốc gia	\N
4	Yoga, Zumba	6	RYT-200 Yoga Alliance, Zumba Instructor	\N
5	Boxing, Thể lực	7	ACE Personal Trainer, HLV Boxing hạng II	\N
6	Cầu lông, Bóng chuyền	5	HLV cầu lông cấp tỉnh	\N
7	Bơi lội, Thể lực	4	Cứu hộ bơi lội, NASM-CPT	\N
\.


--
-- Data for Name: coach_slot_request_days; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.coach_slot_request_days (id, request_id, weekday, time_slot_id) FROM stdin;
1	1	3	3
2	2	4	3
3	3	5	4
4	4	2	5
5	4	5	5
6	5	1	7
\.


--
-- Data for Name: coach_slot_requests; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.coach_slot_requests (id, member_id, coach_id, category_id, time_slot_id, first_date, sessions, status, decided_by, decided_at, reject_reason, expires_at, created_at) FROM stdin;
1	18	3	1	3	2026-11-11	3	PENDING	\N	\N	\N	2026-11-08 11:22:26.98448+07	2026-11-07 11:22:26.98448+07
2	19	5	5	3	2026-11-12	4	APPROVED	1	2026-11-07 12:22:26.98448+07	\N	2026-11-08 10:22:26.98448+07	2026-11-07 10:22:26.98448+07
3	11	4	2	4	2026-10-30	3	PAID	1	2026-10-27 15:22:26.98448+07	\N	2026-10-28 13:22:26.98448+07	2026-10-27 13:22:26.98448+07
4	12	5	5	5	2026-11-03	6	PAID	1	2026-10-30 15:22:26.98448+07	\N	2026-10-31 13:22:26.98448+07	2026-10-30 13:22:26.98448+07
5	15	3	1	7	2026-11-16	2	REJECTED	1	2026-11-06 15:22:26.98448+07	Coach kín lịch khung giờ này, bạn vui lòng chọn khung giờ khác.	2026-11-07 13:22:26.98448+07	2026-11-06 13:22:26.98448+07
\.


--
-- Data for Name: coach_slots; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.coach_slots (id, coach_id, start_at, end_at, kind, status, member_id, held_until, price, canceled_by, canceled_at, created_at, room_id, category_id, request_id, needs_reschedule) FROM stdin;
1	5	2026-11-12 10:00:00+07	2026-11-12 11:00:00+07	ONE_ON_ONE	HELD	19	2026-11-07 15:22:26.98448+07	300000	\N	\N	2026-11-07 12:22:26.98448+07	8	5	2	f
2	5	2026-11-19 10:00:00+07	2026-11-19 11:00:00+07	ONE_ON_ONE	HELD	19	2026-11-07 15:22:26.98448+07	300000	\N	\N	2026-11-07 12:22:26.98448+07	8	5	2	f
3	5	2026-11-26 10:00:00+07	2026-11-26 11:00:00+07	ONE_ON_ONE	HELD	19	2026-11-07 15:22:26.98448+07	300000	\N	\N	2026-11-07 12:22:26.98448+07	8	5	2	f
4	5	2026-12-03 10:00:00+07	2026-12-03 11:00:00+07	ONE_ON_ONE	HELD	19	2026-11-07 15:22:26.98448+07	300000	\N	\N	2026-11-07 12:22:26.98448+07	8	5	2	f
5	4	2026-10-30 12:00:00+07	2026-10-30 13:00:00+07	ONE_ON_ONE	BOOKED	11	\N	300000	\N	\N	2026-10-27 15:22:26.98448+07	2	2	3	f
6	4	2026-11-06 12:00:00+07	2026-11-06 13:00:00+07	ONE_ON_ONE	BOOKED	11	\N	300000	\N	\N	2026-10-27 15:22:26.98448+07	2	2	3	f
7	4	2026-11-13 12:00:00+07	2026-11-13 13:00:00+07	ONE_ON_ONE	BOOKED	11	\N	300000	\N	\N	2026-10-27 15:22:26.98448+07	2	2	3	f
8	5	2026-11-03 14:00:00+07	2026-11-03 15:00:00+07	ONE_ON_ONE	BOOKED	12	\N	300000	\N	\N	2026-10-30 15:22:26.98448+07	8	5	4	f
9	5	2026-11-06 14:00:00+07	2026-11-06 15:00:00+07	ONE_ON_ONE	BOOKED	12	\N	300000	\N	\N	2026-10-30 15:22:26.98448+07	8	5	4	f
10	5	2026-11-10 14:00:00+07	2026-11-10 15:00:00+07	ONE_ON_ONE	BOOKED	12	\N	300000	\N	\N	2026-10-30 15:22:26.98448+07	8	5	4	f
11	5	2026-11-13 14:00:00+07	2026-11-13 15:00:00+07	ONE_ON_ONE	BOOKED	12	\N	300000	\N	\N	2026-10-30 15:22:26.98448+07	8	5	4	f
12	5	2026-11-17 14:00:00+07	2026-11-17 15:00:00+07	ONE_ON_ONE	BOOKED	12	\N	300000	\N	\N	2026-10-30 15:22:26.98448+07	8	5	4	f
13	5	2026-11-20 14:00:00+07	2026-11-20 15:00:00+07	ONE_ON_ONE	BOOKED	12	\N	300000	\N	\N	2026-10-30 15:22:26.98448+07	8	5	4	t
\.


--
-- Data for Name: coach_sports; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.coach_sports (coach_id, category_id) FROM stdin;
3	1
4	2
4	7
5	5
5	4
6	6
6	3
7	1
7	4
\.


--
-- Data for Name: courses; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.courses (id, category_id, name, description, level, total_sessions, default_tuition, prerequisite_course_id, is_active, created_at, updated_at) FROM stdin;
1	1	Bơi cơ bản	Làm quen với nước, bơi sải cơ bản	BEGINNER	12	1200000	\N	t	2026-10-23 18:14:43.228636+07	2026-10-23 18:14:43.228636+07
2	2	Yoga cơ bản	Các tư thế nền tảng và hít thở	BEGINNER	10	900000	\N	t	2026-10-23 18:14:43.228636+07	2026-10-23 18:14:43.228636+07
3	3	Bóng chuyền cơ bản	Chuyền, đệm, phát bóng	BEGINNER	8	800000	\N	t	2026-10-23 18:14:43.228636+07	2026-10-23 18:14:43.228636+07
4	1	Bơi nâng cao	Bơi ếch, bơi ngửa, kỹ thuật xuất phát	ADVANCED	12	1800000	1	t	2026-10-23 18:14:43.228636+07	2026-10-23 18:14:43.228636+07
5	6	Cầu lông cơ bản	Cầm vợt, di chuyển, giao cầu và các cú đánh cơ bản	BEGINNER	8	700000	\N	t	2026-11-07 13:22:26.493344+07	2026-11-07 13:22:26.493344+07
6	4	Thể lực nền tảng	Xây nền sức mạnh, sức bền và độ linh hoạt	BEGINNER	12	1000000	\N	t	2026-11-07 13:22:26.493344+07	2026-11-07 13:22:26.493344+07
8	5	Boxing cơ bản	Đứng thế, đấm thẳng, phòng thủ và cardio	BEGINNER	12	1400000	\N	t	2026-11-07 13:22:26.493344+07	2026-11-07 13:22:26.493344+07
9	7	Zumba nhập môn	Nhảy theo nhạc Latin sôi động	BEGINNER	10	600000	\N	t	2026-11-07 13:22:26.493344+07	2026-11-07 13:22:26.493344+07
7	2	Yoga nâng cao	Các tư thế nâng cao, thăng bằng và kiểm soát hơi thở	ADVANCED	10	1100000	2	t	2026-11-07 13:22:26.493344+07	2026-11-07 13:22:26.493344+07
\.


--
-- Data for Name: facility_attendances; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.facility_attendances (id, user_id, check_in_time, check_out_time, recorded_by, notes) FROM stdin;
1	9	2026-11-07 10:22:26.98448+07	\N	2	\N
2	11	2026-11-07 11:22:26.98448+07	\N	2	Tập khu tạ tự do
3	10	2026-11-07 09:22:26.98448+07	2026-11-07 11:22:26.98448+07	2	\N
4	12	2026-11-06 10:22:26.98448+07	2026-11-06 12:22:26.98448+07	2	\N
5	17	2026-11-06 08:22:26.98448+07	2026-11-06 10:22:26.98448+07	2	\N
6	18	2026-11-05 11:22:26.98448+07	2026-11-05 13:22:26.98448+07	2	\N
7	13	2026-11-05 09:22:26.98448+07	2026-11-05 11:22:26.98448+07	2	\N
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
8	2026-11-06	\N	\N	\N
9	2026-09-08	172.00	68.00	Tăng cơ, giảm mỡ bụng
10	2026-09-23	160.00	52.00	Bơi tốt hơn, giữ dáng
11	2026-09-18	178.00	80.00	Giảm 8kg trước Tết
12	2026-08-19	165.00	57.00	Duy trì sức khỏe
13	2026-10-03	175.00	74.00	Chơi cầu lông giỏi hơn
14	2026-08-29	158.00	50.00	Linh hoạt, giảm căng thẳng
15	2026-11-05	170.00	72.00	Tập thể lực cho sức bền
16	2026-10-30	162.00	55.00	Học cầu lông
17	2026-10-08	180.00	85.00	Giảm cân, tập boxing
18	2026-10-13	159.00	53.00	Học bơi và boxing
19	2026-09-28	174.00	77.00	Cải thiện thể lực tổng hợp
20	2026-09-13	163.00	54.00	Yoga và nhảy Zumba
21	2026-08-09	171.00	70.00	\N
\.


--
-- Data for Name: member_subscriptions; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.member_subscriptions (id, user_id, package_id, package_name_snapshot, tier_level_snapshot, months, purchase_type, upgrade_from_subscription_id, upgrade_credit_amount, payment_expires_at, total_amount, start_date, end_date, status, benefits_snapshot, created_by, created_at, updated_at) FROM stdin;
1	9	1	Khởi Động	1	1	NEW	\N	0	\N	450000	2026-10-28	2026-11-27	ACTIVE	{"has_personal_plan": false, "waitlist_priority": 0, "tuition_discount_pct": 0, "one_on_one_discount_pct": 0, "early_leave_refund_sessions": 0}	2	2026-10-28 13:22:26.98448+07	2026-11-07 13:22:26.98448+07
2	10	2	Bứt Tốc	2	3	NEW	\N	0	\N	2070000	2026-10-08	2027-01-06	ACTIVE	{"has_personal_plan": false, "waitlist_priority": 1, "tuition_discount_pct": 5, "one_on_one_discount_pct": 0, "early_leave_refund_sessions": 0}	\N	2026-10-08 13:22:26.98448+07	2026-11-07 13:22:26.98448+07
3	11	3	Vượt Đỉnh	3	1	NEW	\N	0	\N	1200000	2026-10-26	2026-11-25	ACTIVE	{"has_personal_plan": true, "waitlist_priority": 2, "tuition_discount_pct": 10, "one_on_one_discount_pct": 10, "early_leave_refund_sessions": 1}	\N	2026-10-26 13:22:26.98448+07	2026-11-07 13:22:26.98448+07
4	12	4	Quán Quân	4	6	NEW	\N	0	\N	10200000	2026-09-28	2027-03-27	ACTIVE	{"has_personal_plan": true, "waitlist_priority": 3, "tuition_discount_pct": 15, "one_on_one_discount_pct": 20, "early_leave_refund_sessions": 2}	\N	2026-09-28 13:22:26.98448+07	2026-11-07 13:22:26.98448+07
5	13	2	Bứt Tốc	2	1	NEW	\N	0	\N	750000	2026-10-10	2026-11-09	ACTIVE	{"has_personal_plan": false, "waitlist_priority": 1, "tuition_discount_pct": 5, "one_on_one_discount_pct": 0, "early_leave_refund_sessions": 0}	2	2026-10-10 13:22:26.98448+07	2026-11-07 13:22:26.98448+07
6	14	1	Khởi Động	1	1	NEW	\N	0	\N	450000	2026-10-01	2026-10-31	EXPIRED	{"has_personal_plan": false, "waitlist_priority": 0, "tuition_discount_pct": 0, "one_on_one_discount_pct": 0, "early_leave_refund_sessions": 0}	2	2026-10-01 13:22:26.98448+07	2026-11-07 13:22:26.98448+07
7	15	2	Bứt Tốc	2	1	NEW	\N	0	2026-11-08 13:22:26.98448+07	750000	\N	\N	PENDING	{"has_personal_plan": false, "waitlist_priority": 1, "tuition_discount_pct": 5, "one_on_one_discount_pct": 0, "early_leave_refund_sessions": 0}	2	2026-11-07 12:22:26.98448+07	2026-11-07 13:22:26.98448+07
8	17	3	Vượt Đỉnh	3	1	NEW	\N	0	\N	1200000	2026-10-29	2026-11-28	ACTIVE	{"has_personal_plan": true, "waitlist_priority": 2, "tuition_discount_pct": 10, "one_on_one_discount_pct": 10, "early_leave_refund_sessions": 1}	\N	2026-10-29 13:22:26.98448+07	2026-11-07 13:22:26.98448+07
9	18	2	Bứt Tốc	2	1	NEW	\N	0	\N	750000	2026-11-01	2026-12-01	ACTIVE	{"has_personal_plan": false, "waitlist_priority": 1, "tuition_discount_pct": 5, "one_on_one_discount_pct": 0, "early_leave_refund_sessions": 0}	\N	2026-11-01 13:22:26.98448+07	2026-11-07 13:22:26.98448+07
10	20	1	Khởi Động	1	1	NEW	\N	0	\N	450000	2026-11-04	2026-12-04	ACTIVE	{"has_personal_plan": false, "waitlist_priority": 0, "tuition_discount_pct": 0, "one_on_one_discount_pct": 0, "early_leave_refund_sessions": 0}	\N	2026-11-04 13:22:26.98448+07	2026-11-07 13:22:26.98448+07
11	19	2	Bứt Tốc	2	1	NEW	\N	0	\N	750000	2026-10-18	2026-11-02	UPGRADED	{"has_personal_plan": false, "waitlist_priority": 1, "tuition_discount_pct": 5, "one_on_one_discount_pct": 0, "early_leave_refund_sessions": 0}	\N	2026-10-18 13:22:26.98448+07	2026-11-07 13:22:26.98448+07
12	19	3	Vượt Đỉnh	3	1	UPGRADE	11	375000	\N	825000	2026-11-02	2026-12-02	ACTIVE	{"has_personal_plan": true, "waitlist_priority": 2, "tuition_discount_pct": 10, "one_on_one_discount_pct": 10, "early_leave_refund_sessions": 1}	\N	2026-11-02 13:22:26.98448+07	2026-11-07 13:22:26.98448+07
\.


--
-- Data for Name: membership_packages; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.membership_packages (id, code, name, description, tier_level, monthly_price, tuition_discount_pct, one_on_one_discount_pct, has_personal_plan, early_leave_refund_sessions, waitlist_priority, is_active, created_at, updated_at) FROM stdin;
1	KHOI_DONG	Khởi Động	Vé vào khu tập tự do cho người mới.	1	450000	0	0	f	0	0	t	2026-10-23 18:14:43.228636+07	2026-10-23 18:14:43.228636+07
2	BUT_TOC	Bứt Tốc	Giảm học phí lớp, ưu tiên hàng chờ.	2	750000	5	0	f	0	1	t	2026-10-23 18:14:43.228636+07	2026-11-07 13:21:48.388665+07
3	VUOT_DINH	Vượt Đỉnh	Bài tập riêng từ coach, giảm giá lớp và buổi 1-1, ưu tiên hàng chờ cao hơn.	3	1200000	10	10	t	1	2	t	2026-10-23 18:14:43.228636+07	2026-11-07 13:21:48.388665+07
4	QUAN_QUAN	Quán Quân	Trọn quyền lợi: giảm sâu học phí và buổi 1-1, bài tập riêng từ coach, ưu tiên hàng chờ cao nhất.	4	2000000	15	20	t	2	3	t	2026-10-23 18:14:43.228636+07	2026-11-07 13:21:48.388665+07
\.


--
-- Data for Name: notifications; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.notifications (id, recipient_id, sender_id, type, channel, title, content, is_read, created_at) FROM stdin;
1	13	\N	MEMBERSHIP	APP	Gói Bứt Tốc sắp hết hạn	Gói của bạn còn 2 ngày. Gia hạn sớm để không gián đoạn việc tập luyện.	f	2026-11-07 10:22:26.98448+07
2	13	\N	PAYMENT	APP	Lớp Bơi nâng cao bị hủy, chờ hoàn tiền	Lớp bị hủy do không đủ học viên. Lễ tân sẽ hoàn lại học phí cho bạn.	f	2026-11-03 13:22:26.98448+07
3	14	\N	PAYMENT	APP	Đã hoàn tiền lớp Bơi nâng cao	Trung tâm đã hoàn học phí cho bạn bằng tiền mặt.	t	2026-11-02 13:22:26.98448+07
4	17	\N	WAITLIST	APP	Bạn đang trong danh sách chờ lớp Zumba	Khi có chỗ trống, hệ thống sẽ mời bạn theo thứ tự ưu tiên.	f	2026-11-06 13:22:26.98448+07
5	16	\N	BOOKING	APP	Đang giữ chỗ lớp Cầu lông cơ bản	Vui lòng thanh toán trước khi hết hạn giữ chỗ.	f	2026-11-07 13:17:26.98448+07
6	10	\N	MAKEUP	APP	Buổi Bơi bị hủy, đã xếp buổi bù	Buổi ngày 12/11 bị hủy do hồ bảo trì. Buổi bù vào ngày 27/11.	f	2026-11-05 13:22:26.98448+07
7	11	5	WORKOUT_PLAN	APP	Giáo án mới: giảm mỡ riêng cho Anh	Coach đã đăng giáo án riêng cho bạn, xem trong mục Tập luyện.	f	2026-10-28 13:22:26.98448+07
8	19	\N	BOOKING	APP	Yêu cầu 1-1 đã được duyệt	Vui lòng thanh toán trong 3 giờ để giữ lịch.	f	2026-11-07 12:22:26.98448+07
9	9	5	ANNOUNCEMENT	APP	Mang theo băng tay	Cả lớp Boxing nhớ mang băng tay từ buổi tới nhé.	t	2026-11-04 13:22:26.98448+07
10	18	\N	BOOKING	APP	Đã gửi yêu cầu 1-1	Yêu cầu của bạn đang chờ quản lý duyệt, thường trong vòng 24 giờ.	f	2026-11-07 11:22:26.98448+07
\.


--
-- Data for Name: orders; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.orders (id, member_id, created_by, channel, subscription_id, registration_id, coach_slot_id, list_price, discount_kind, discount_amount, total_amount, status, created_at, updated_at) FROM stdin;
1	9	2	COUNTER	1	\N	\N	450000	NONE	0	450000	PAID	2026-10-28 13:22:26.98448+07	2026-11-07 13:22:26.98448+07
2	10	\N	ONLINE	2	\N	\N	2070000	NONE	0	2070000	PAID	2026-10-08 13:22:26.98448+07	2026-11-07 13:22:26.98448+07
3	11	\N	ONLINE	3	\N	\N	1200000	NONE	0	1200000	PAID	2026-10-26 13:22:26.98448+07	2026-11-07 13:22:26.98448+07
4	12	\N	ONLINE	4	\N	\N	10200000	NONE	0	10200000	PAID	2026-09-28 13:22:26.98448+07	2026-11-07 13:22:26.98448+07
5	13	2	COUNTER	5	\N	\N	750000	NONE	0	750000	PAID	2026-10-10 13:22:26.98448+07	2026-11-07 13:22:26.98448+07
6	14	2	COUNTER	6	\N	\N	450000	NONE	0	450000	PAID	2026-10-01 13:22:26.98448+07	2026-11-07 13:22:26.98448+07
7	15	2	COUNTER	7	\N	\N	750000	NONE	0	750000	PENDING	2026-11-07 12:22:26.98448+07	2026-11-07 13:22:26.98448+07
8	17	\N	ONLINE	8	\N	\N	1200000	NONE	0	1200000	PAID	2026-10-29 13:22:26.98448+07	2026-11-07 13:22:26.98448+07
9	18	\N	ONLINE	9	\N	\N	750000	NONE	0	750000	PAID	2026-11-01 13:22:26.98448+07	2026-11-07 13:22:26.98448+07
10	20	\N	ONLINE	10	\N	\N	450000	NONE	0	450000	PAID	2026-11-04 13:22:26.98448+07	2026-11-07 13:22:26.98448+07
11	19	\N	ONLINE	11	\N	\N	750000	NONE	0	750000	PAID	2026-10-18 13:22:26.98448+07	2026-11-07 13:22:26.98448+07
12	19	\N	ONLINE	12	\N	\N	825000	NONE	0	825000	PAID	2026-11-02 13:22:26.98448+07	2026-11-07 13:22:26.98448+07
13	20	\N	ONLINE	\N	1	\N	900000	NONE	0	900000	PAID	2026-09-11 10:00:00+07	2026-11-07 13:22:26.98448+07
14	10	\N	ONLINE	\N	2	\N	900000	TIER	45000	855000	PAID	2026-09-11 10:00:00+07	2026-11-07 13:22:26.98448+07
15	9	2	COUNTER	\N	3	\N	900000	NONE	0	900000	PAID	2026-09-11 10:00:00+07	2026-11-07 13:22:26.98448+07
16	14	2	COUNTER	\N	4	\N	900000	NONE	0	900000	PAID	2026-09-11 10:00:00+07	2026-11-07 13:22:26.98448+07
17	13	\N	ONLINE	\N	5	\N	900000	TIER	45000	855000	PAID	2026-09-11 10:00:00+07	2026-11-07 13:22:26.98448+07
18	10	\N	ONLINE	\N	6	\N	1200000	TIER	60000	1140000	PAID	2026-10-12 10:00:00+07	2026-11-07 13:22:26.98448+07
19	11	\N	ONLINE	\N	7	\N	1200000	TIER	120000	1080000	PAID	2026-10-12 10:00:00+07	2026-11-07 13:22:26.98448+07
20	12	\N	ONLINE	\N	8	\N	1200000	TIER	180000	1020000	PAID	2026-10-12 10:00:00+07	2026-11-07 13:22:26.98448+07
21	13	\N	ONLINE	\N	9	\N	1200000	TIER	60000	1140000	PAID	2026-10-12 10:00:00+07	2026-11-07 13:22:26.98448+07
22	17	\N	ONLINE	\N	10	\N	1200000	TIER	120000	1080000	PAID	2026-10-12 10:00:00+07	2026-11-07 13:22:26.98448+07
23	19	\N	ONLINE	\N	11	\N	1200000	TIER	120000	1080000	PAID	2026-10-12 10:00:00+07	2026-11-07 13:22:26.98448+07
24	9	2	COUNTER	\N	12	\N	1400000	NONE	0	1400000	PAID	2026-10-18 10:00:00+07	2026-11-07 13:22:26.98448+07
25	11	\N	ONLINE	\N	13	\N	1400000	TIER	140000	1260000	PAID	2026-10-18 10:00:00+07	2026-11-07 13:22:26.98448+07
26	19	\N	ONLINE	\N	14	\N	1400000	TIER	140000	1260000	PAID	2026-10-18 10:00:00+07	2026-11-07 13:22:26.98448+07
27	18	\N	ONLINE	\N	15	\N	1400000	TIER	70000	1330000	PAID	2026-10-18 10:00:00+07	2026-11-07 13:22:26.98448+07
28	20	2	COUNTER	\N	16	\N	1400000	NONE	0	1400000	PAID	2026-10-18 10:00:00+07	2026-11-07 13:22:26.98448+07
29	9	2	COUNTER	\N	17	\N	1000000	NONE	0	1000000	PAID	2026-10-16 10:00:00+07	2026-11-07 13:22:26.98448+07
30	10	\N	ONLINE	\N	18	\N	1000000	TIER	50000	950000	PAID	2026-10-16 10:00:00+07	2026-11-07 13:22:26.98448+07
31	12	\N	ONLINE	\N	19	\N	1000000	TIER	150000	850000	PAID	2026-10-16 10:00:00+07	2026-11-07 13:22:26.98448+07
32	17	\N	ONLINE	\N	20	\N	1000000	TIER	100000	900000	PAID	2026-10-16 10:00:00+07	2026-11-07 13:22:26.98448+07
33	13	\N	ONLINE	\N	21	\N	700000	TIER	35000	665000	PAID	2026-11-04 13:22:26.98448+07	2026-11-07 13:22:26.98448+07
34	18	\N	ONLINE	\N	22	\N	700000	TIER	35000	665000	PAID	2026-11-05 13:22:26.98448+07	2026-11-07 13:22:26.98448+07
35	16	\N	ONLINE	\N	23	\N	700000	NONE	0	700000	PENDING	2026-11-07 13:17:26.98448+07	2026-11-07 13:22:26.98448+07
36	9	2	COUNTER	\N	24	\N	600000	NONE	0	600000	PAID	2026-11-03 13:22:26.98448+07	2026-11-07 13:22:26.98448+07
37	10	\N	ONLINE	\N	25	\N	600000	TIER	30000	570000	PAID	2026-11-03 13:22:26.98448+07	2026-11-07 13:22:26.98448+07
38	12	\N	ONLINE	\N	26	\N	600000	TIER	90000	510000	PAID	2026-11-03 13:22:26.98448+07	2026-11-07 13:22:26.98448+07
39	20	\N	ONLINE	\N	27	\N	600000	NONE	0	600000	PAID	2026-11-03 13:22:26.98448+07	2026-11-07 13:22:26.98448+07
40	20	2	COUNTER	\N	30	\N	1100000	NONE	0	1100000	PAID	2026-11-06 13:22:26.98448+07	2026-11-07 13:22:26.98448+07
41	14	2	COUNTER	\N	31	\N	1800000	NONE	0	1800000	CANCELED	2026-10-29 13:22:26.98448+07	2026-11-07 13:22:26.98448+07
42	13	\N	ONLINE	\N	32	\N	1800000	TIER	90000	1710000	CANCELED	2026-10-30 13:22:26.98448+07	2026-11-07 13:22:26.98448+07
43	19	\N	ONLINE	\N	\N	1	1200000	TIER	120000	1080000	PENDING	2026-11-07 12:22:26.98448+07	2026-11-07 13:22:26.98448+07
44	11	\N	ONLINE	\N	\N	5	900000	TIER	90000	810000	PAID	2026-10-27 15:22:26.98448+07	2026-11-07 13:22:26.98448+07
45	12	\N	ONLINE	\N	\N	8	1800000	TIER	360000	1440000	PAID	2026-10-30 15:22:26.98448+07	2026-11-07 13:22:26.98448+07
\.


--
-- Data for Name: payments; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.payments (id, order_id, payer_id, received_by, invoice_number, transaction_code, amount, payment_method, status, paid_at, gateway_response, gateway_order_code, payment_url, expires_at, created_at) FROM stdin;
1	1	9	2	INV-c0a5cd8ec8b876ee68ec	CASH-329fa6622c2c720d9232e6fb94e47cb7	450000	CASH	SUCCESS	2026-10-28 13:24:26.98448+07	\N	\N	\N	\N	2026-10-28 13:21:26.98448+07
2	2	10	\N	INV-883bce2fefe76cd00d57	PAYOS-7d1dcd21dc053ac53d680669b618c66b	2070000	QR_GATEWAY	SUCCESS	2026-10-08 13:24:26.98448+07	\N	1760000001	\N	\N	2026-10-08 13:21:26.98448+07
3	3	11	\N	INV-efbddf192a945b6e5eda	PAYOS-492f4fd457adb225805e7bfb0f5c6e74	1200000	QR_GATEWAY	SUCCESS	2026-10-26 13:24:26.98448+07	\N	1760000002	\N	\N	2026-10-26 13:21:26.98448+07
4	4	12	\N	INV-d0903cf66acc05b821e7	PAYOS-f44a7c9e6fbb05e66ab6afc77d4193de	10200000	QR_GATEWAY	SUCCESS	2026-09-28 13:24:26.98448+07	\N	1760000003	\N	\N	2026-09-28 13:21:26.98448+07
5	5	13	2	INV-3842bb85b1131225e5bf	CASH-54d0e068ed9936d9721a613ce149f999	750000	CASH	SUCCESS	2026-10-10 13:24:26.98448+07	\N	\N	\N	\N	2026-10-10 13:21:26.98448+07
6	6	14	2	INV-25fc94276633d5eb351c	CASH-ac79a74ae72906cef02696ec7c063eeb	450000	CASH	SUCCESS	2026-10-01 13:24:26.98448+07	\N	\N	\N	\N	2026-10-01 13:21:26.98448+07
7	8	17	\N	INV-81a1ae6b444d4a5eb80e	PAYOS-3c05dcea2debbfacb546caeb5cfb7b00	1200000	QR_GATEWAY	SUCCESS	2026-10-29 13:24:26.98448+07	\N	1760000004	\N	\N	2026-10-29 13:21:26.98448+07
8	9	18	\N	INV-72816c29355010a2fac8	PAYOS-92235c2b36e30996a5cf2fbe17c9f347	750000	QR_GATEWAY	SUCCESS	2026-11-01 13:24:26.98448+07	\N	1760000005	\N	\N	2026-11-01 13:21:26.98448+07
9	10	20	\N	INV-642d02ba8bf088c0f67e	PAYOS-59efd10cfd9870206730bf723aaf9d70	450000	QR_GATEWAY	SUCCESS	2026-11-04 13:24:26.98448+07	\N	1760000006	\N	\N	2026-11-04 13:21:26.98448+07
10	11	19	\N	INV-fa9affe28514a423cd03	PAYOS-783d2f78870d396ae07633a9102d6f63	750000	QR_GATEWAY	SUCCESS	2026-10-18 13:24:26.98448+07	\N	1760000007	\N	\N	2026-10-18 13:21:26.98448+07
11	12	19	\N	INV-9f8bb2ecb2a110bba330	PAYOS-010ce23aff862f94211862467daf1787	825000	QR_GATEWAY	SUCCESS	2026-11-02 13:24:26.98448+07	\N	1760000008	\N	\N	2026-11-02 13:21:26.98448+07
12	13	20	\N	INV-86acf0ed5fcd2ed3bd39	PAYOS-3e8ed2fb37d829f9f56656f8a6ada118	900000	QR_GATEWAY	SUCCESS	2026-09-11 10:02:00+07	\N	1760000009	\N	\N	2026-09-11 09:59:00+07
13	14	10	\N	INV-a5475f9eed374bc5a300	PAYOS-a18f5eeab5f5ee635cdabb92640d6467	855000	QR_GATEWAY	SUCCESS	2026-09-11 10:02:00+07	\N	1760000010	\N	\N	2026-09-11 09:59:00+07
14	15	9	2	INV-11b6876ff825b64ca90e	CASH-d7acc9c3f69ffe54c9f3f475032314dc	900000	CASH	SUCCESS	2026-09-11 10:02:00+07	\N	\N	\N	\N	2026-09-11 09:59:00+07
15	16	14	2	INV-d77e8d37655fca2ed7ab	CASH-55240a40bfc6fa101ff970350159eb0e	900000	CASH	SUCCESS	2026-09-11 10:02:00+07	\N	\N	\N	\N	2026-09-11 09:59:00+07
16	17	13	\N	INV-fec31a261021f4fc2074	PAYOS-7629f0c8fbc33f9a35dea85bffbd5079	855000	QR_GATEWAY	SUCCESS	2026-09-11 10:02:00+07	\N	1760000011	\N	\N	2026-09-11 09:59:00+07
17	18	10	\N	INV-3395cc76134024212b8b	PAYOS-09b4ecb3fa4391179fc3c81b29f32b31	1140000	QR_GATEWAY	SUCCESS	2026-10-12 10:02:00+07	\N	1760000012	\N	\N	2026-10-12 09:59:00+07
18	19	11	\N	INV-baa1a5685f17c03d5298	PAYOS-c610afe8007d68ad7bf2da736177e40a	1080000	QR_GATEWAY	SUCCESS	2026-10-12 10:02:00+07	\N	1760000013	\N	\N	2026-10-12 09:59:00+07
19	20	12	\N	INV-dd5dadab4617de76beab	PAYOS-e8c3ec860bae0587ecdb969b5db29354	1020000	QR_GATEWAY	SUCCESS	2026-10-12 10:02:00+07	\N	1760000014	\N	\N	2026-10-12 09:59:00+07
20	21	13	\N	INV-1a39c9b4e692242c2a45	PAYOS-4442fff10171809e86d2d0a7174c921b	1140000	QR_GATEWAY	SUCCESS	2026-10-12 10:02:00+07	\N	1760000015	\N	\N	2026-10-12 09:59:00+07
21	22	17	\N	INV-212e835bf494ba70c97f	PAYOS-4059679c0dd227b09ae1e4faf60fc296	1080000	QR_GATEWAY	SUCCESS	2026-10-12 10:02:00+07	\N	1760000016	\N	\N	2026-10-12 09:59:00+07
22	23	19	\N	INV-7202fa906b2b4a8a28d6	PAYOS-07ce01c62936c1cb20b4ecf87b41742d	1080000	QR_GATEWAY	SUCCESS	2026-10-12 10:02:00+07	\N	1760000017	\N	\N	2026-10-12 09:59:00+07
23	24	9	2	INV-b45c94e8e5866144554b	CASH-042d673dc9ccf5106c575a952e38e193	1400000	CASH	SUCCESS	2026-10-18 10:02:00+07	\N	\N	\N	\N	2026-10-18 09:59:00+07
24	25	11	\N	INV-fb32d307a29d198715e4	PAYOS-d0312650cdb87afca4c12130e18e0b02	1260000	QR_GATEWAY	SUCCESS	2026-10-18 10:02:00+07	\N	1760000018	\N	\N	2026-10-18 09:59:00+07
25	26	19	\N	INV-918299f44af06f39dd70	PAYOS-d2c6308500ede466e46485a79e932451	1260000	QR_GATEWAY	SUCCESS	2026-10-18 10:02:00+07	\N	1760000019	\N	\N	2026-10-18 09:59:00+07
26	27	18	\N	INV-4e10c91a828ab5c32a42	PAYOS-dc620a5dd932716bdb9d20ae37a1e419	1330000	QR_GATEWAY	SUCCESS	2026-10-18 10:02:00+07	\N	1760000020	\N	\N	2026-10-18 09:59:00+07
27	28	20	2	INV-2ff85486fd4b96102e89	CASH-7a129356167d97e13f3e46a5e1794037	1400000	CASH	SUCCESS	2026-10-18 10:02:00+07	\N	\N	\N	\N	2026-10-18 09:59:00+07
28	29	9	2	INV-6dd0c8537417aeb74f7b	CASH-f25eff479cb1cd970edeaea19c8e25eb	1000000	CASH	SUCCESS	2026-10-16 10:02:00+07	\N	\N	\N	\N	2026-10-16 09:59:00+07
29	30	10	\N	INV-bf41e94309a18056cff2	PAYOS-85b72ad19b51c941795274d55b2a15a8	950000	QR_GATEWAY	SUCCESS	2026-10-16 10:02:00+07	\N	1760000021	\N	\N	2026-10-16 09:59:00+07
30	31	12	\N	INV-76c73265e7fd1b213dda	PAYOS-e9a7745cd2cdc200c5e9a4d206ebce3a	850000	QR_GATEWAY	SUCCESS	2026-10-16 10:02:00+07	\N	1760000022	\N	\N	2026-10-16 09:59:00+07
31	32	17	\N	INV-7ffd03146d0d2f8ee599	PAYOS-d35e21da0cde806e3f89e039073749a3	900000	QR_GATEWAY	SUCCESS	2026-10-16 10:02:00+07	\N	1760000023	\N	\N	2026-10-16 09:59:00+07
32	33	13	\N	INV-ddf71d846426bfd22c8c	PAYOS-e314b65140e8113999b3c6ad146aba44	665000	QR_GATEWAY	SUCCESS	2026-11-04 13:24:26.98448+07	\N	1760000024	\N	\N	2026-11-04 13:21:26.98448+07
33	34	18	\N	INV-01ee6b3b393bf396a9ea	PAYOS-4001fb088af696c370838709373637ce	665000	QR_GATEWAY	SUCCESS	2026-11-05 13:24:26.98448+07	\N	1760000025	\N	\N	2026-11-05 13:21:26.98448+07
34	36	9	2	INV-e9e0b3626389316aab99	CASH-9cb3fd71313a171fb8b4f80650675372	600000	CASH	SUCCESS	2026-11-03 13:24:26.98448+07	\N	\N	\N	\N	2026-11-03 13:21:26.98448+07
35	37	10	\N	INV-66e5602dbf6449432d36	PAYOS-fe227f00d260477d979a42e7f7f36e10	570000	QR_GATEWAY	SUCCESS	2026-11-03 13:24:26.98448+07	\N	1760000026	\N	\N	2026-11-03 13:21:26.98448+07
36	38	12	\N	INV-6c1fb4be685bf3f32420	PAYOS-c023abe9d4458e046ac7b9faa2bae545	510000	QR_GATEWAY	SUCCESS	2026-11-03 13:24:26.98448+07	\N	1760000027	\N	\N	2026-11-03 13:21:26.98448+07
37	39	20	\N	INV-6450deeffd1b2c47d398	PAYOS-5db6dba8ca92b8c4b31fca4c36c38a07	600000	QR_GATEWAY	SUCCESS	2026-11-03 13:24:26.98448+07	\N	1760000028	\N	\N	2026-11-03 13:21:26.98448+07
38	40	20	2	INV-77c7fe29b45ded18cada	CASH-616e15ed539439c599f67b1e8395a81a	1100000	CASH	SUCCESS	2026-11-06 13:24:26.98448+07	\N	\N	\N	\N	2026-11-06 13:21:26.98448+07
39	41	14	2	INV-eccfffca57e169838e68	CASH-9fe73fd385233319b0036215134cb45b	1800000	CASH	REFUNDED	2026-10-29 13:24:26.98448+07	\N	\N	\N	\N	2026-10-29 13:21:26.98448+07
40	42	13	\N	INV-9f3df8917a885372657c	PAYOS-97de27701efc24df870dcc1705c4ae4f	1710000	QR_GATEWAY	PENDING_REFUND	2026-10-30 13:24:26.98448+07	\N	1760000029	\N	\N	2026-10-30 13:21:26.98448+07
41	44	11	\N	INV-daf493689ccc0a09c78e	PAYOS-92847f88b0ce3e1dcd449d407fa29323	810000	QR_GATEWAY	SUCCESS	2026-10-27 16:02:26.98448+07	\N	1760000030	\N	\N	2026-10-27 15:59:26.98448+07
42	45	12	\N	INV-9d6b29ef8b8fdd60294d	PAYOS-4a51dfda0b9256a4f8176c0e62b1165a	1440000	QR_GATEWAY	SUCCESS	2026-10-30 16:02:26.98448+07	\N	1760000031	\N	\N	2026-10-30 15:59:26.98448+07
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
\.


--
-- Data for Name: refunds; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.refunds (id, payment_id, amount, reason, method, status, requested_at, processed_by, processed_at, claim_failed_attempts, claim_locked_until, claim_code_sent_at) FROM stdin;
1	39	1800000	Lớp bị hủy do không đủ học viên	CASH	DONE	2026-11-01 13:22:26.98448+07	2	2026-11-02 13:22:26.98448+07	0	\N	\N
2	40	1710000	Lớp bị hủy do không đủ học viên	TRANSFER	PENDING	2026-11-03 13:22:26.98448+07	\N	\N	0	\N	\N
\.


--
-- Data for Name: role_permissions; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.role_permissions (role_id, permission_id) FROM stdin;
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
3	15
3	14
3	11
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
1	2	4	Yoga cơ bản - Sáng T2/T4	Làm quen các tư thế yoga cơ bản, hơi thở và thư giãn.	12	900000	2026-09-21	\N	COMPLETED	2026-08-22 09:00:00+07	2026-11-07 13:22:26.98448+07
2	1	3	Bơi cơ bản - Tối T3/T5	Làm quen với nước, bơi sải và bơi ếch cơ bản.	15	1200000	2026-10-20	\N	IN_PROGRESS	2026-09-20 09:00:00+07	2026-11-07 13:22:26.98448+07
3	8	5	Boxing cơ bản - Chiều T2/T4/T6	Đứng thế, đấm thẳng, phòng thủ và cardio với bao cát.	12	1400000	2026-10-26	\N	IN_PROGRESS	2026-09-26 09:00:00+07	2026-11-07 13:22:26.98448+07
4	6	7	Thể lực nền tảng - Chiều T7/CN	Xây nền sức mạnh, sức bền và độ linh hoạt cho người mới.	15	1000000	2026-10-24	\N	IN_PROGRESS	2026-09-24 09:00:00+07	2026-11-07 13:22:26.98448+07
5	5	6	Cầu lông cơ bản - Sáng T3/T5	Cầm vợt, di chuyển, giao cầu và các cú đánh cơ bản.	16	700000	2026-11-17	\N	OPEN	2026-10-08 09:00:00+07	2026-11-07 13:22:26.98448+07
6	9	4	Zumba nhập môn - Tối T2/T4	Nhảy theo nhạc Latin sôi động, đốt calo nhẹ nhàng.	4	600000	2026-11-16	\N	OPEN	2026-10-08 09:00:00+07	2026-11-07 13:22:26.98448+07
7	7	4	Yoga nâng cao - Tối T3/T5	Các tư thế nâng cao, thăng bằng và kiểm soát hơi thở sâu.	12	1100000	2026-11-24	\N	OPEN	2026-10-08 09:00:00+07	2026-11-07 13:22:26.98448+07
8	3	6	Bóng chuyền cơ bản - Chiều T7	Chuyền bóng, đệm bóng, phát bóng và phối hợp đồng đội.	20	800000	2026-11-28	\N	DRAFT	2026-10-08 09:00:00+07	2026-11-07 13:22:26.98448+07
9	4	7	Bơi nâng cao - Sáng T2/T4	Hoàn thiện bốn kiểu bơi, kỹ thuật xuất phát và quay đầu.	10	1800000	2026-11-09	\N	CANCELED	2026-10-08 09:00:00+07	2026-11-07 13:22:26.98448+07
\.


--
-- Data for Name: staff_profiles; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.staff_profiles (user_id, job_title, shift_schedule) FROM stdin;
1	Center Manager	08:00-17:00
2	Receptionist	06:00-14:00
\.


--
-- Data for Name: support_requests; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.support_requests (id, member_id, handled_by, subject, content, status, created_at, resolved_at) FROM stdin;
1	8	\N	Hỏi cách đăng ký gói	Mình mới tham gia, nên chọn gói nào để vừa tập tự do vừa học bơi?	OPEN	2026-11-07 12:22:26.98448+07	\N
2	9	2	Tủ đồ số 12 bị kẹt khóa	Tủ số 12 khu nam không mở được, nhờ trung tâm kiểm tra.	IN_PROGRESS	2026-11-07 08:22:26.98448+07	\N
3	10	2	Đổi giờ gặp coach	Mình muốn hỏi cách dời lịch 1-1 sang tuần sau.	RESOLVED	2026-11-05 13:22:26.98448+07	2026-11-05 13:52:26.98448+07
4	14	2	Gia hạn gói Khởi Động	Gói của mình hết hạn tuần trước, muốn gia hạn trả tiền mặt.	CLOSED	2026-11-01 13:22:26.98448+07	2026-11-02 13:22:26.98448+07
\.


--
-- Data for Name: system_settings; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.system_settings (key, value, description, updated_by, updated_at) FROM stdin;
pricing.duration_discounts	[{"pct": 0, "max_months": 2, "min_months": 1}, {"pct": 8, "max_months": 5, "min_months": 3}, {"pct": 15, "max_months": 11, "min_months": 6}, {"pct": 25, "max_months": 12, "min_months": 12}]	Bậc giảm giá theo số tháng mua gói (BR-PRC-01)	\N	2026-11-07 13:22:26.98448+07
auth.verify_link_minutes	1440	Hiệu lực link xác thực email (BR-ACC-04)	\N	2026-11-07 13:22:26.98448+07
auth.reset_link_minutes	30	Hiệu lực link đặt lại mật khẩu (BR-ACC-06)	\N	2026-11-07 13:22:26.98448+07
auth.verify_resend_cooldown_seconds	60	Khoảng cách tối thiểu giữa hai lần gửi lại email xác thực (BR-ACC-07)	\N	2026-11-07 13:22:26.98448+07
auth.verify_resend_per_day	5	Số lần gửi lại email xác thực tối đa mỗi ngày (BR-ACC-07)	\N	2026-11-07 13:22:26.98448+07
payment.hold_minutes	15	Thời gian mã QR và giữ chỗ khi thanh toán (BR-QR-02, BR-ENR-03)	\N	2026-11-07 13:22:26.98448+07
waitlist.near_start_days	3	Ngưỡng ngày coi là sát khai giảng (BR-WL-03)	\N	2026-11-07 13:22:26.98448+07
waitlist.hold_hours	12	Giữ chỗ cho người được báo có chỗ (BR-WL-03)	\N	2026-11-07 13:22:26.98448+07
waitlist.hold_hours_near_start	3	Giữ chỗ khi còn dưới near_start_days đến khai giảng (BR-WL-03)	\N	2026-11-07 13:22:26.98448+07
schedule.buffer_minutes	15	Khoảng nghỉ giữa hai buổi (BR-SCH-01). Chỉ để tham chiếu: constraint trong DB viết cứng 15 phút	\N	2026-11-07 13:22:26.98448+07
makeup.max_days	30	Ngày bù cách buổi bị hủy quá số ngày này thì thay bằng bồi hoàn (BR-MKP-06)	\N	2026-11-07 13:22:26.98448+07
makeup.proposal_count	3	Số ngày bù hệ thống đề xuất cho manager (BR-MKP-02)	\N	2026-11-07 13:22:26.98448+07
one_on_one.max_sessions_per_request	12	Số buổi tối đa trong một yêu cầu 1-1	\N	2026-11-07 13:22:26.98448+07
subscription.expiry_notice_days	[3, 1]	Các mốc báo gói sắp hết hạn (BR-PKG-12)	\N	2026-11-07 13:22:26.98448+07
overview.notable_class	{"low_fill_pct": 50, "near_start_days": 7, "canceled_sessions": 2}	Ngưỡng class đáng chú ý (BR-OVW-05)	\N	2026-11-07 13:22:26.98448+07
overview.overdue_refund_days	3	Khoản chờ hoàn tiền quá số ngày này thì đánh dấu đỏ (BR-OVW-04)	\N	2026-11-07 13:22:26.98448+07
center.open_minute	360	Giờ mở cửa của trung tâm, tính bằng phút từ 00:00 (360 = 06:00)	\N	2026-11-07 13:22:26.98448+07
center.close_minute	1320	Giờ đóng cửa của trung tâm, tính bằng phút từ 00:00 (1320 = 22:00)	\N	2026-11-07 13:22:26.98448+07
one_on_one.refund_before_hours	48	Hủy 1-1 trước giờ học tối thiểu bao nhiêu giờ thì hoàn tiền (BR-ONE-04)	\N	2026-11-07 13:22:26.98448+07
one_on_one.slot_minutes	60	Độ dài một suất 1-1 (phút), lấy từ đầu khung giờ khi tạo suất (BR-ONE-02)	\N	2026-11-07 13:22:26.98448+07
one_on_one.hourly_price	300000	Giá một giờ buổi 1-1, chung cho mọi coach; giá suất = giá giờ x số phút / 60 (BR-ONE-02)	\N	2026-11-07 13:22:26.98448+07
one_on_one.request_min_lead_hours	48	Buổi đầu của yêu cầu 1-1 phải cách lúc gửi ít nhất bấy nhiêu giờ	\N	2026-11-07 13:22:26.98448+07
one_on_one.request_expire_hours	24	Yêu cầu 1-1 quá bấy nhiêu giờ chưa được duyệt thì tự hết hạn	\N	2026-11-07 13:22:26.98448+07
one_on_one.request_pay_hours	3	Sau khi manager duyệt, member có bấy nhiêu giờ để trả tiền, quá hạn thì các suất được nhả	\N	2026-11-07 13:22:26.98448+07
one_on_one.reschedule_before_hours	24	Hội viên báo dời buổi 1-1 trước giờ học ít nhất bấy nhiêu giờ thì được dời, muộn hơn thì mất buổi (BR-ONE-04)	\N	2026-11-07 13:22:26.98448+07
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
2	1	1	Đấm thẳng (jab-cross)	Xoay hông, thu tay nhanh về thế thủ	4	20	\N	f
3	1	2	Di chuyển chân bước ngang	Giữ trọng tâm thấp, không bắt chéo chân	3	\N	120	f
4	1	3	Giãn cơ cuối buổi	Giữ mỗi tư thế 30 giây	1	\N	300	f
5	2	0	Chạy bộ nhẹ trên máy	Giữ nhịp tim ở mức nói chuyện được	1	\N	900	f
6	2	1	Squat với tạ đòn	Lưng thẳng, gối không vượt mũi chân	4	12	\N	f
7	2	2	Plank	Giữ thân thẳng, siết bụng	3	\N	60	f
\.


--
-- Data for Name: training_progress_evaluations; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.training_progress_evaluations (id, class_id, member_id, coach_id, evaluation_date, period_start, period_end, summary, rating, strengths, improvements, recommendations, feedback, created_at, updated_at, version) FROM stdin;
1	3	11	5	2026-11-02	2026-10-26	2026-11-01	Tuần đầu làm quen tốt, đi học đầy đủ.	4	Thể lực nền tốt, học nhanh	Cần xoay hông khi đấm	Tập thêm bài chân bước ngang ở nhà	\N	2026-11-07 13:22:26.98448+07	2026-11-07 13:22:26.98448+07	1
2	3	9	5	2026-11-02	2026-10-26	2026-11-01	Có tinh thần nhưng còn lúng túng thế đứng.	3	Chăm chỉ, ham học	Giữ thế thủ tay chưa ổn	Soi gương luyện thế đứng 10 phút mỗi ngày	\N	2026-11-07 13:22:26.98448+07	2026-11-07 13:22:26.98448+07	1
\.


--
-- Data for Name: training_results; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.training_results (id, attendance_id, exercise_id, activity_key, activity_name, prescription, actual_sets, actual_repetitions, actual_duration_seconds, performance, completion_status, notes, recorded_by, recorded_at, updated_at) FROM stdin;
1	87	1	exercise:1	Khởi động nhảy dây	{"planId": 1, "version": 1, "exercise": {"id": 1, "name": "Khởi động nhảy dây", "sets": 3, "repetitions": null, "instructions": "Nhảy nhẹ nhàng, giữ nhịp thở", "durationSeconds": 180}}	3	\N	180	\N	COMPLETED	\N	5	2026-10-26 18:15:00+07	2026-10-26 18:15:00+07
2	87	2	exercise:2	Đấm thẳng (jab-cross)	{"planId": 1, "version": 1, "exercise": {"id": 2, "name": "Đấm thẳng (jab-cross)", "sets": 4, "repetitions": 20, "instructions": "Xoay hông, thu tay nhanh về thế thủ", "durationSeconds": null}}	4	20	\N	Đấm đúng kỹ thuật, cần xoay hông nhiều hơn	PARTIAL	\N	5	2026-10-26 18:15:00+07	2026-10-26 18:15:00+07
3	87	3	exercise:3	Di chuyển chân bước ngang	{"planId": 1, "version": 1, "exercise": {"id": 3, "name": "Di chuyển chân bước ngang", "sets": 3, "repetitions": null, "instructions": "Giữ trọng tâm thấp, không bắt chéo chân", "durationSeconds": 120}}	3	\N	120	\N	COMPLETED	\N	5	2026-10-26 18:15:00+07	2026-10-26 18:15:00+07
4	87	4	exercise:4	Giãn cơ cuối buổi	{"planId": 1, "version": 1, "exercise": {"id": 4, "name": "Giãn cơ cuối buổi", "sets": 1, "repetitions": null, "instructions": "Giữ mỗi tư thế 30 giây", "durationSeconds": 300}}	1	\N	300	\N	COMPLETED	\N	5	2026-10-26 18:15:00+07	2026-10-26 18:15:00+07
5	88	1	exercise:1	Khởi động nhảy dây	{"planId": 1, "version": 1, "exercise": {"id": 1, "name": "Khởi động nhảy dây", "sets": 3, "repetitions": null, "instructions": "Nhảy nhẹ nhàng, giữ nhịp thở", "durationSeconds": 180}}	3	\N	180	\N	COMPLETED	\N	5	2026-10-26 18:15:00+07	2026-10-26 18:15:00+07
6	88	2	exercise:2	Đấm thẳng (jab-cross)	{"planId": 1, "version": 1, "exercise": {"id": 2, "name": "Đấm thẳng (jab-cross)", "sets": 4, "repetitions": 20, "instructions": "Xoay hông, thu tay nhanh về thế thủ", "durationSeconds": null}}	4	20	\N	Đấm đúng kỹ thuật, cần xoay hông nhiều hơn	COMPLETED	\N	5	2026-10-26 18:15:00+07	2026-10-26 18:15:00+07
7	88	3	exercise:3	Di chuyển chân bước ngang	{"planId": 1, "version": 1, "exercise": {"id": 3, "name": "Di chuyển chân bước ngang", "sets": 3, "repetitions": null, "instructions": "Giữ trọng tâm thấp, không bắt chéo chân", "durationSeconds": 120}}	3	\N	120	\N	COMPLETED	\N	5	2026-10-26 18:15:00+07	2026-10-26 18:15:00+07
8	88	4	exercise:4	Giãn cơ cuối buổi	{"planId": 1, "version": 1, "exercise": {"id": 4, "name": "Giãn cơ cuối buổi", "sets": 1, "repetitions": null, "instructions": "Giữ mỗi tư thế 30 giây", "durationSeconds": 300}}	1	\N	300	\N	COMPLETED	\N	5	2026-10-26 18:15:00+07	2026-10-26 18:15:00+07
9	90	1	exercise:1	Khởi động nhảy dây	{"planId": 1, "version": 1, "exercise": {"id": 1, "name": "Khởi động nhảy dây", "sets": 3, "repetitions": null, "instructions": "Nhảy nhẹ nhàng, giữ nhịp thở", "durationSeconds": 180}}	3	\N	180	\N	COMPLETED	\N	5	2026-10-26 18:15:00+07	2026-10-26 18:15:00+07
10	90	2	exercise:2	Đấm thẳng (jab-cross)	{"planId": 1, "version": 1, "exercise": {"id": 2, "name": "Đấm thẳng (jab-cross)", "sets": 4, "repetitions": 20, "instructions": "Xoay hông, thu tay nhanh về thế thủ", "durationSeconds": null}}	4	20	\N	Đấm đúng kỹ thuật, cần xoay hông nhiều hơn	COMPLETED	\N	5	2026-10-26 18:15:00+07	2026-10-26 18:15:00+07
11	90	3	exercise:3	Di chuyển chân bước ngang	{"planId": 1, "version": 1, "exercise": {"id": 3, "name": "Di chuyển chân bước ngang", "sets": 3, "repetitions": null, "instructions": "Giữ trọng tâm thấp, không bắt chéo chân", "durationSeconds": 120}}	3	\N	120	\N	PARTIAL	\N	5	2026-10-26 18:15:00+07	2026-10-26 18:15:00+07
12	90	4	exercise:4	Giãn cơ cuối buổi	{"planId": 1, "version": 1, "exercise": {"id": 4, "name": "Giãn cơ cuối buổi", "sets": 1, "repetitions": null, "instructions": "Giữ mỗi tư thế 30 giây", "durationSeconds": 300}}	1	\N	300	\N	COMPLETED	\N	5	2026-10-26 18:15:00+07	2026-10-26 18:15:00+07
13	89	1	exercise:1	Khởi động nhảy dây	{"planId": 1, "version": 1, "exercise": {"id": 1, "name": "Khởi động nhảy dây", "sets": 3, "repetitions": null, "instructions": "Nhảy nhẹ nhàng, giữ nhịp thở", "durationSeconds": 180}}	3	\N	180	\N	COMPLETED	\N	5	2026-10-26 18:15:00+07	2026-10-26 18:15:00+07
14	89	2	exercise:2	Đấm thẳng (jab-cross)	{"planId": 1, "version": 1, "exercise": {"id": 2, "name": "Đấm thẳng (jab-cross)", "sets": 4, "repetitions": 20, "instructions": "Xoay hông, thu tay nhanh về thế thủ", "durationSeconds": null}}	4	20	\N	Đấm đúng kỹ thuật, cần xoay hông nhiều hơn	PARTIAL	\N	5	2026-10-26 18:15:00+07	2026-10-26 18:15:00+07
15	89	3	exercise:3	Di chuyển chân bước ngang	{"planId": 1, "version": 1, "exercise": {"id": 3, "name": "Di chuyển chân bước ngang", "sets": 3, "repetitions": null, "instructions": "Giữ trọng tâm thấp, không bắt chéo chân", "durationSeconds": 120}}	3	\N	120	\N	COMPLETED	\N	5	2026-10-26 18:15:00+07	2026-10-26 18:15:00+07
16	89	4	exercise:4	Giãn cơ cuối buổi	{"planId": 1, "version": 1, "exercise": {"id": 4, "name": "Giãn cơ cuối buổi", "sets": 1, "repetitions": null, "instructions": "Giữ mỗi tư thế 30 giây", "durationSeconds": 300}}	1	\N	300	\N	COMPLETED	\N	5	2026-10-26 18:15:00+07	2026-10-26 18:15:00+07
17	91	1	exercise:1	Khởi động nhảy dây	{"planId": 1, "version": 1, "exercise": {"id": 1, "name": "Khởi động nhảy dây", "sets": 3, "repetitions": null, "instructions": "Nhảy nhẹ nhàng, giữ nhịp thở", "durationSeconds": 180}}	3	\N	180	\N	PARTIAL	\N	5	2026-10-26 18:15:00+07	2026-10-26 18:15:00+07
18	91	2	exercise:2	Đấm thẳng (jab-cross)	{"planId": 1, "version": 1, "exercise": {"id": 2, "name": "Đấm thẳng (jab-cross)", "sets": 4, "repetitions": 20, "instructions": "Xoay hông, thu tay nhanh về thế thủ", "durationSeconds": null}}	4	20	\N	Đấm đúng kỹ thuật, cần xoay hông nhiều hơn	COMPLETED	\N	5	2026-10-26 18:15:00+07	2026-10-26 18:15:00+07
19	91	3	exercise:3	Di chuyển chân bước ngang	{"planId": 1, "version": 1, "exercise": {"id": 3, "name": "Di chuyển chân bước ngang", "sets": 3, "repetitions": null, "instructions": "Giữ trọng tâm thấp, không bắt chéo chân", "durationSeconds": 120}}	3	\N	120	\N	COMPLETED	\N	5	2026-10-26 18:15:00+07	2026-10-26 18:15:00+07
20	91	4	exercise:4	Giãn cơ cuối buổi	{"planId": 1, "version": 1, "exercise": {"id": 4, "name": "Giãn cơ cuối buổi", "sets": 1, "repetitions": null, "instructions": "Giữ mỗi tư thế 30 giây", "durationSeconds": 300}}	1	\N	300	\N	COMPLETED	\N	5	2026-10-26 18:15:00+07	2026-10-26 18:15:00+07
\.


--
-- Data for Name: users; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.users (id, role_id, full_name, email, phone, password_hash, avatar_url, gender, date_of_birth, is_active, email_verified_at, verify_token_hash, verify_token_expires_at, reset_token_hash, reset_token_expires_at, created_at, updated_at) FROM stdin;
1	1	Nguyễn Hoàng Long	manager@demo.local	0901000001	$2a$10$SlppGAjPyHSjLjfi09QQh.j7rdXP8NDPHOCxfPZDRYefYjUBC82ee	\N	MALE	1985-03-12	t	2026-11-07 13:22:26.98448+07	\N	\N	\N	\N	2026-08-09 13:22:26.98448+07	2026-11-07 13:22:26.98448+07
2	2	Trần Thị Mai Anh	letan@demo.local	0901000002	$2a$10$SlppGAjPyHSjLjfi09QQh.j7rdXP8NDPHOCxfPZDRYefYjUBC82ee	\N	FEMALE	1996-07-21	t	2026-11-07 13:22:26.98448+07	\N	\N	\N	\N	2026-08-09 13:22:26.98448+07	2026-11-07 13:22:26.98448+07
3	3	Lê Văn Hùng	hung.le@demo.local	0902000001	$2a$10$SlppGAjPyHSjLjfi09QQh.j7rdXP8NDPHOCxfPZDRYefYjUBC82ee	\N	MALE	1988-01-15	t	2026-11-07 13:22:26.98448+07	\N	\N	\N	\N	2026-08-09 13:22:26.98448+07	2026-11-07 13:22:26.98448+07
4	3	Phạm Thu Trang	trang.pham@demo.local	0902000002	$2a$10$SlppGAjPyHSjLjfi09QQh.j7rdXP8NDPHOCxfPZDRYefYjUBC82ee	\N	FEMALE	1991-09-03	t	2026-11-07 13:22:26.98448+07	\N	\N	\N	\N	2026-08-09 13:22:26.98448+07	2026-11-07 13:22:26.98448+07
5	3	Đỗ Quang Vinh	vinh.do@demo.local	0902000003	$2a$10$SlppGAjPyHSjLjfi09QQh.j7rdXP8NDPHOCxfPZDRYefYjUBC82ee	\N	MALE	1989-05-27	t	2026-11-07 13:22:26.98448+07	\N	\N	\N	\N	2026-08-09 13:22:26.98448+07	2026-11-07 13:22:26.98448+07
6	3	Nguyễn Hải Yến	yen.nguyen@demo.local	0902000004	$2a$10$SlppGAjPyHSjLjfi09QQh.j7rdXP8NDPHOCxfPZDRYefYjUBC82ee	\N	FEMALE	1993-12-08	t	2026-11-07 13:22:26.98448+07	\N	\N	\N	\N	2026-08-09 13:22:26.98448+07	2026-11-07 13:22:26.98448+07
7	3	Võ Minh Khôi	khoi.vo@demo.local	0902000005	$2a$10$SlppGAjPyHSjLjfi09QQh.j7rdXP8NDPHOCxfPZDRYefYjUBC82ee	\N	MALE	1992-04-19	t	2026-11-07 13:22:26.98448+07	\N	\N	\N	\N	2026-08-09 13:22:26.98448+07	2026-11-07 13:22:26.98448+07
8	4	Bùi Gia Hân	han.bui@demo.local	0903000001	$2a$10$SlppGAjPyHSjLjfi09QQh.j7rdXP8NDPHOCxfPZDRYefYjUBC82ee	\N	FEMALE	2003-05-14	t	2026-11-07 13:22:26.98448+07	\N	\N	\N	\N	2026-08-09 13:22:26.98448+07	2026-11-07 13:22:26.98448+07
9	4	Đặng Minh Quân	quan.dang@demo.local	0903000002	$2a$10$SlppGAjPyHSjLjfi09QQh.j7rdXP8NDPHOCxfPZDRYefYjUBC82ee	\N	MALE	1999-09-02	t	2026-11-07 13:22:26.98448+07	\N	\N	\N	\N	2026-08-09 13:22:26.98448+07	2026-11-07 13:22:26.98448+07
10	4	Ngô Thanh Thảo	thao.ngo@demo.local	0903000003	$2a$10$SlppGAjPyHSjLjfi09QQh.j7rdXP8NDPHOCxfPZDRYefYjUBC82ee	\N	FEMALE	1997-02-18	t	2026-11-07 13:22:26.98448+07	\N	\N	\N	\N	2026-08-09 13:22:26.98448+07	2026-11-07 13:22:26.98448+07
11	4	Trịnh Đức Anh	anh.trinh@demo.local	0903000004	$2a$10$SlppGAjPyHSjLjfi09QQh.j7rdXP8NDPHOCxfPZDRYefYjUBC82ee	\N	MALE	1994-11-30	t	2026-11-07 13:22:26.98448+07	\N	\N	\N	\N	2026-08-09 13:22:26.98448+07	2026-11-07 13:22:26.98448+07
12	4	Lý Bảo Châu	chau.ly@demo.local	0903000005	$2a$10$SlppGAjPyHSjLjfi09QQh.j7rdXP8NDPHOCxfPZDRYefYjUBC82ee	\N	FEMALE	1990-11-10	t	2026-11-07 13:22:26.98448+07	\N	\N	\N	\N	2026-08-09 13:22:26.98448+07	2026-11-07 13:22:26.98448+07
13	4	Phan Quốc Việt	viet.phan@demo.local	0903000006	$2a$10$SlppGAjPyHSjLjfi09QQh.j7rdXP8NDPHOCxfPZDRYefYjUBC82ee	\N	MALE	1992-06-25	t	2026-11-07 13:22:26.98448+07	\N	\N	\N	\N	2026-08-09 13:22:26.98448+07	2026-11-07 13:22:26.98448+07
14	4	Hoàng Thị Kim Ngân	ngan.hoang@demo.local	0903000007	$2a$10$SlppGAjPyHSjLjfi09QQh.j7rdXP8NDPHOCxfPZDRYefYjUBC82ee	\N	FEMALE	2000-08-08	t	2026-11-07 13:22:26.98448+07	\N	\N	\N	\N	2026-08-09 13:22:26.98448+07	2026-11-07 13:22:26.98448+07
15	4	Vũ Đình Khang	khang.vu@demo.local	0903000008	$2a$10$SlppGAjPyHSjLjfi09QQh.j7rdXP8NDPHOCxfPZDRYefYjUBC82ee	\N	MALE	1996-01-19	t	2026-11-07 13:22:26.98448+07	\N	\N	\N	\N	2026-08-09 13:22:26.98448+07	2026-11-07 13:22:26.98448+07
16	4	Đinh Ngọc Bích	bich.dinh@demo.local	0903000009	$2a$10$SlppGAjPyHSjLjfi09QQh.j7rdXP8NDPHOCxfPZDRYefYjUBC82ee	\N	FEMALE	2001-12-03	t	2026-11-07 13:22:26.98448+07	\N	\N	\N	\N	2026-08-09 13:22:26.98448+07	2026-11-07 13:22:26.98448+07
17	4	Cao Thái Sơn	son.cao@demo.local	0903000010	$2a$10$SlppGAjPyHSjLjfi09QQh.j7rdXP8NDPHOCxfPZDRYefYjUBC82ee	\N	MALE	1995-04-07	t	2026-11-07 13:22:26.98448+07	\N	\N	\N	\N	2026-08-09 13:22:26.98448+07	2026-11-07 13:22:26.98448+07
18	4	Lương Mỹ Linh	linh.luong@demo.local	0903000011	$2a$10$SlppGAjPyHSjLjfi09QQh.j7rdXP8NDPHOCxfPZDRYefYjUBC82ee	\N	FEMALE	1998-10-22	t	2026-11-07 13:22:26.98448+07	\N	\N	\N	\N	2026-08-09 13:22:26.98448+07	2026-11-07 13:22:26.98448+07
19	4	Tạ Hữu Phước	phuoc.ta@demo.local	0903000012	$2a$10$SlppGAjPyHSjLjfi09QQh.j7rdXP8NDPHOCxfPZDRYefYjUBC82ee	\N	MALE	1993-03-15	t	2026-11-07 13:22:26.98448+07	\N	\N	\N	\N	2026-08-09 13:22:26.98448+07	2026-11-07 13:22:26.98448+07
20	4	Mai Khánh Vy	vy.mai@demo.local	0903000013	$2a$10$SlppGAjPyHSjLjfi09QQh.j7rdXP8NDPHOCxfPZDRYefYjUBC82ee	\N	FEMALE	2002-07-29	t	2026-11-07 13:22:26.98448+07	\N	\N	\N	\N	2026-08-09 13:22:26.98448+07	2026-11-07 13:22:26.98448+07
21	4	Dương Văn Tín	tin.duong@demo.local	0903000014	$2a$10$SlppGAjPyHSjLjfi09QQh.j7rdXP8NDPHOCxfPZDRYefYjUBC82ee	\N	MALE	1991-09-09	f	2026-11-07 13:22:26.98448+07	\N	\N	\N	\N	2026-08-09 13:22:26.98448+07	2026-11-07 13:22:26.98448+07
\.


--
-- Data for Name: workout_plans; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.workout_plans (id, member_id, coach_id, title, description, content, start_date, end_date, status, created_at, updated_at, class_id, objective, coach_notes, version) FROM stdin;
1	\N	5	Kế hoạch 4 tuần: nền tảng boxing	Giáo án cho người mới	\N	2026-10-26	2026-11-23	PUBLISHED	2026-10-26 13:22:26.98448+07	2026-11-07 13:22:26.98448+07	3	Đứng thế, đấm thẳng và phòng thủ cơ bản	Nhớ quấn băng tay trước khi vào lớp	1
2	11	5	Giáo án giảm mỡ riêng cho Anh	Kết hợp cardio và sức mạnh	\N	2026-10-28	2026-11-25	PUBLISHED	2026-10-28 13:22:26.98448+07	2026-11-07 13:22:26.98448+07	3	Giảm 8kg trong 2 tháng, giữ cơ	Ăn đủ đạm, ngủ trước 23h	1
\.


--
-- Data for Name: workout_progress; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.workout_progress (id, plan_id, member_id, recorded_by, recorded_date, weight_kg, height_cm, body_fat_pct, muscle_mass_kg, notes) FROM stdin;
\.


--
-- Name: audit_logs_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public.audit_logs_id_seq', 6, true);


--
-- Name: class_attendances_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public.class_attendances_id_seq', 132, true);


--
-- Name: class_evaluations_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public.class_evaluations_id_seq', 5, true);


--
-- Name: class_registrations_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public.class_registrations_id_seq', 32, true);


--
-- Name: class_schedules_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public.class_schedules_id_seq', 18, true);


--
-- Name: class_sessions_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public.class_sessions_id_seq', 87, true);


--
-- Name: coach_slot_request_days_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public.coach_slot_request_days_id_seq', 6, true);


--
-- Name: coach_slot_requests_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public.coach_slot_requests_id_seq', 5, true);


--
-- Name: coach_slots_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public.coach_slots_id_seq', 13, true);


--
-- Name: courses_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public.courses_id_seq', 9, true);


--
-- Name: facility_attendances_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public.facility_attendances_id_seq', 7, true);


--
-- Name: facility_rooms_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public.facility_rooms_id_seq', 10, true);


--
-- Name: member_subscriptions_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public.member_subscriptions_id_seq', 12, true);


--
-- Name: membership_packages_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public.membership_packages_id_seq', 4, true);


--
-- Name: notifications_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public.notifications_id_seq', 10, true);


--
-- Name: orders_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public.orders_id_seq', 45, true);


--
-- Name: payments_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public.payments_id_seq', 42, true);


--
-- Name: permissions_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public.permissions_id_seq', 16, true);


--
-- Name: refunds_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public.refunds_id_seq', 2, true);


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

SELECT pg_catalog.setval('public.sport_classes_id_seq', 9, true);


--
-- Name: support_requests_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public.support_requests_id_seq', 4, true);


--
-- Name: time_slots_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public.time_slots_id_seq', 8, true);


--
-- Name: training_plan_exercises_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public.training_plan_exercises_id_seq', 7, true);


--
-- Name: training_progress_evaluations_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public.training_progress_evaluations_id_seq', 2, true);


--
-- Name: training_results_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public.training_results_id_seq', 20, true);


--
-- Name: users_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public.users_id_seq', 21, true);


--
-- Name: workout_plans_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public.workout_plans_id_seq', 2, true);


--
-- Name: workout_progress_id_seq; Type: SEQUENCE SET; Schema: public; Owner: -
--

SELECT pg_catalog.setval('public.workout_progress_id_seq', 1, false);


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
-- Name: coach_slot_request_days coach_slot_request_days_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.coach_slot_request_days
    ADD CONSTRAINT coach_slot_request_days_pkey PRIMARY KEY (id);


--
-- Name: coach_slot_requests coach_slot_requests_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.coach_slot_requests
    ADD CONSTRAINT coach_slot_requests_pkey PRIMARY KEY (id);


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
-- Name: coach_slot_request_days uq_request_day; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.coach_slot_request_days
    ADD CONSTRAINT uq_request_day UNIQUE (request_id, weekday);


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
-- Name: idx_request_day_request; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_request_day_request ON public.coach_slot_request_days USING btree (request_id);


--
-- Name: idx_request_member; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_request_member ON public.coach_slot_requests USING btree (member_id);


--
-- Name: idx_request_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_request_status ON public.coach_slot_requests USING btree (status);


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
-- Name: idx_slot_request; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_slot_request ON public.coach_slots USING btree (request_id) WHERE (request_id IS NOT NULL);


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
-- Name: coach_slot_request_days coach_slot_request_days_request_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.coach_slot_request_days
    ADD CONSTRAINT coach_slot_request_days_request_id_fkey FOREIGN KEY (request_id) REFERENCES public.coach_slot_requests(id) ON DELETE CASCADE;


--
-- Name: coach_slot_request_days coach_slot_request_days_time_slot_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.coach_slot_request_days
    ADD CONSTRAINT coach_slot_request_days_time_slot_id_fkey FOREIGN KEY (time_slot_id) REFERENCES public.time_slots(id);


--
-- Name: coach_slot_requests coach_slot_requests_category_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.coach_slot_requests
    ADD CONSTRAINT coach_slot_requests_category_id_fkey FOREIGN KEY (category_id) REFERENCES public.sport_categories(id);


--
-- Name: coach_slot_requests coach_slot_requests_coach_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.coach_slot_requests
    ADD CONSTRAINT coach_slot_requests_coach_id_fkey FOREIGN KEY (coach_id) REFERENCES public.users(id);


--
-- Name: coach_slot_requests coach_slot_requests_decided_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.coach_slot_requests
    ADD CONSTRAINT coach_slot_requests_decided_by_fkey FOREIGN KEY (decided_by) REFERENCES public.users(id);


--
-- Name: coach_slot_requests coach_slot_requests_member_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.coach_slot_requests
    ADD CONSTRAINT coach_slot_requests_member_id_fkey FOREIGN KEY (member_id) REFERENCES public.users(id);


--
-- Name: coach_slot_requests coach_slot_requests_time_slot_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.coach_slot_requests
    ADD CONSTRAINT coach_slot_requests_time_slot_id_fkey FOREIGN KEY (time_slot_id) REFERENCES public.time_slots(id);


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
-- Name: coach_slots coach_slots_request_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.coach_slots
    ADD CONSTRAINT coach_slots_request_id_fkey FOREIGN KEY (request_id) REFERENCES public.coach_slot_requests(id);


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

