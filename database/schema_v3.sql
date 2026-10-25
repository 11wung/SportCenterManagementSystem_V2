--
-- PostgreSQL database dump
--

\restrict 44e1kvbwn4FSzzKClxKGTEyfuZtfpdyg4jub3qhIJMo50nmmWfA8xLSrmeWnh1F

-- Dumped from database version 18.6
-- Dumped by pg_dump version 18.6

-- Started on 2026-10-23 18:24:09

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
-- TOC entry 2 (class 3079 OID 21614)
-- Name: btree_gist; Type: EXTENSION; Schema: -; Owner: -
--

CREATE EXTENSION IF NOT EXISTS btree_gist WITH SCHEMA public;


--
-- TOC entry 5940 (class 0 OID 0)
-- Dependencies: 2
-- Name: EXTENSION btree_gist; Type: COMMENT; Schema: -; Owner: 
--

COMMENT ON EXTENSION btree_gist IS 'support for indexing common datatypes in GiST';


--
-- TOC entry 298 (class 1255 OID 22340)
-- Name: set_updated_at(); Type: FUNCTION; Schema: public; Owner: postgres
--

CREATE FUNCTION public.set_updated_at() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    NEW.updated_at := now();
    RETURN NEW;
END;
$$;


ALTER FUNCTION public.set_updated_at() OWNER TO postgres;

--
-- TOC entry 390 (class 1255 OID 22341)
-- Name: trg_audit_immutable(); Type: FUNCTION; Schema: public; Owner: postgres
--

CREATE FUNCTION public.trg_audit_immutable() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    RAISE EXCEPTION 'BR-AUD-01: audit_logs chỉ được thêm, không sửa, không xóa';
END;
$$;


ALTER FUNCTION public.trg_audit_immutable() OWNER TO postgres;

--
-- TOC entry 284 (class 1255 OID 23387)
-- Name: trg_check_class_schedule(); Type: FUNCTION; Schema: public; Owner: postgres
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


ALTER FUNCTION public.trg_check_class_schedule() OWNER TO postgres;

--
-- TOC entry 511 (class 1255 OID 23389)
-- Name: trg_check_class_session(); Type: FUNCTION; Schema: public; Owner: postgres
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
    END IF;
    RETURN NEW;
END;
$$;


ALTER FUNCTION public.trg_check_class_session() OWNER TO postgres;

--
-- TOC entry 427 (class 1255 OID 23391)
-- Name: trg_check_coach_slot(); Type: FUNCTION; Schema: public; Owner: postgres
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
       AND (NEW.coach_id <> OLD.coach_id OR NEW.start_at <> OLD.start_at OR NEW.end_at <> OLD.end_at) THEN
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
    RETURN NEW;
END;
$$;


ALTER FUNCTION public.trg_check_coach_slot() OWNER TO postgres;

--
-- TOC entry 479 (class 1255 OID 23395)
-- Name: trg_check_freeze(); Type: FUNCTION; Schema: public; Owner: postgres
--

CREATE FUNCTION public.trg_check_freeze() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    v_quota   integer;
    v_min     integer;
    v_max     integer;
    v_count   integer;
    v_used    integer;
    v_days    integer;
BEGIN
    IF NEW.status = 'CANCELED' THEN
        RETURN NEW;
    END IF;

    SELECT COALESCE((benefits_snapshot ->> 'freeze_days')::integer, 0) INTO v_quota
    FROM member_subscriptions WHERE id = NEW.subscription_id;
    IF v_quota IS NULL OR v_quota = 0 THEN
        RAISE EXCEPTION 'BR-FRZ-01: gói này không có quỹ bảo lưu';
    END IF;

    SELECT COALESCE((SELECT (value #>> '{}')::integer FROM system_settings WHERE key = 'freeze.min_days'), 2),
           COALESCE((SELECT (value #>> '{}')::integer FROM system_settings WHERE key = 'freeze.max_periods'), 3)
      INTO v_min, v_max;

    v_days := COALESCE(NEW.ended_on, NEW.end_date) - NEW.start_date + 1;
    IF v_days < v_min THEN
        RAISE EXCEPTION 'BR-FRZ-02: mỗi đợt bảo lưu tối thiểu % ngày', v_min;
    END IF;

    SELECT count(*), COALESCE(sum(days_used), 0) INTO v_count, v_used
    FROM subscription_freezes
    WHERE subscription_id = NEW.subscription_id AND status <> 'CANCELED'
      AND id <> COALESCE(NEW.id, -1);

    IF TG_OP = 'INSERT' AND v_count >= v_max THEN
        RAISE EXCEPTION 'BR-FRZ-01: mỗi kỳ gói chỉ bảo lưu tối đa % đợt', v_max;
    END IF;
    IF v_used + v_days > v_quota THEN
        RAISE EXCEPTION 'BR-FRZ-01: vượt quỹ bảo lưu (% ngày, đã dùng %, đợt này %)', v_quota, v_used, v_days;
    END IF;
    RETURN NEW;
END;
$$;


ALTER FUNCTION public.trg_check_freeze() OWNER TO postgres;

--
-- TOC entry 433 (class 1255 OID 23393)
-- Name: trg_check_registration(); Type: FUNCTION; Schema: public; Owner: postgres
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


ALTER FUNCTION public.trg_check_registration() OWNER TO postgres;

--
-- TOC entry 472 (class 1255 OID 23385)
-- Name: trg_check_sport_class(); Type: FUNCTION; Schema: public; Owner: postgres
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


ALTER FUNCTION public.trg_check_sport_class() OWNER TO postgres;

--
-- TOC entry 381 (class 1255 OID 22342)
-- Name: vn_today(); Type: FUNCTION; Schema: public; Owner: postgres
--

CREATE FUNCTION public.vn_today() RETURNS date
    LANGUAGE sql STABLE
    AS $$
    SELECT (now() AT TIME ZONE 'Asia/Ho_Chi_Minh')::date;
$$;


ALTER FUNCTION public.vn_today() OWNER TO postgres;

SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- TOC entry 280 (class 1259 OID 23326)
-- Name: ai_chat_logs; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.ai_chat_logs (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    question text NOT NULL,
    answer text,
    status character varying(20) DEFAULT 'SUCCESS'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT ck_ai_status CHECK (((status)::text = ANY ((ARRAY['SUCCESS'::character varying, 'REJECTED'::character varying, 'ERROR'::character varying])::text[])))
);


ALTER TABLE public.ai_chat_logs OWNER TO postgres;

--
-- TOC entry 279 (class 1259 OID 23325)
-- Name: ai_chat_logs_id_seq; Type: SEQUENCE; Schema: public; Owner: postgres
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
-- TOC entry 269 (class 1259 OID 23169)
-- Name: audit_logs; Type: TABLE; Schema: public; Owner: postgres
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


ALTER TABLE public.audit_logs OWNER TO postgres;

--
-- TOC entry 268 (class 1259 OID 23168)
-- Name: audit_logs_id_seq; Type: SEQUENCE; Schema: public; Owner: postgres
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
-- TOC entry 272 (class 1259 OID 23205)
-- Name: class_attendances; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.class_attendances (
    id bigint NOT NULL,
    session_id bigint NOT NULL,
    user_id bigint NOT NULL,
    status character varying(20) NOT NULL,
    check_in_time timestamp with time zone,
    recorded_by bigint,
    notes character varying(500),
    CONSTRAINT ck_attendance_status CHECK (((status)::text = ANY ((ARRAY['PRESENT'::character varying, 'ABSENT'::character varying, 'LATE'::character varying, 'EXCUSED'::character varying, 'DECLINED_MAKEUP'::character varying])::text[])))
);


ALTER TABLE public.class_attendances OWNER TO postgres;

--
-- TOC entry 271 (class 1259 OID 23204)
-- Name: class_attendances_id_seq; Type: SEQUENCE; Schema: public; Owner: postgres
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
-- TOC entry 257 (class 1259 OID 22929)
-- Name: class_evaluations; Type: TABLE; Schema: public; Owner: postgres
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


ALTER TABLE public.class_evaluations OWNER TO postgres;

--
-- TOC entry 256 (class 1259 OID 22928)
-- Name: class_evaluations_id_seq; Type: SEQUENCE; Schema: public; Owner: postgres
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
-- TOC entry 255 (class 1259 OID 22892)
-- Name: class_registrations; Type: TABLE; Schema: public; Owner: postgres
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
    CONSTRAINT ck_reg_canceled CHECK ((((status)::text <> ALL ((ARRAY['CANCELED'::character varying, 'LEFT'::character varying])::text[])) OR (canceled_at IS NOT NULL))),
    CONSTRAINT ck_reg_hold CHECK ((((status)::text <> 'PENDING_PAYMENT'::text) OR (hold_expires_at IS NOT NULL))),
    CONSTRAINT ck_reg_status CHECK (((status)::text = ANY ((ARRAY['PENDING_PAYMENT'::character varying, 'REGISTERED'::character varying, 'WAITLISTED'::character varying, 'CANCELED'::character varying, 'LEFT'::character varying])::text[])))
);


ALTER TABLE public.class_registrations OWNER TO postgres;

--
-- TOC entry 254 (class 1259 OID 22891)
-- Name: class_registrations_id_seq; Type: SEQUENCE; Schema: public; Owner: postgres
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
-- TOC entry 251 (class 1259 OID 22819)
-- Name: class_schedules; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.class_schedules (
    id bigint NOT NULL,
    class_id bigint NOT NULL,
    room_id bigint NOT NULL,
    day_of_week smallint NOT NULL,
    start_time time without time zone NOT NULL,
    end_time time without time zone NOT NULL,
    CONSTRAINT ck_schedule_day CHECK (((day_of_week >= 1) AND (day_of_week <= 7))),
    CONSTRAINT ck_schedule_time CHECK ((end_time > start_time))
);


ALTER TABLE public.class_schedules OWNER TO postgres;

--
-- TOC entry 250 (class 1259 OID 22818)
-- Name: class_schedules_id_seq; Type: SEQUENCE; Schema: public; Owner: postgres
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
-- TOC entry 253 (class 1259 OID 22844)
-- Name: class_sessions; Type: TABLE; Schema: public; Owner: postgres
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
    CONSTRAINT ck_session_proposed CHECK ((((status)::text <> 'PROPOSED'::text) OR is_makeup)),
    CONSTRAINT ck_session_status CHECK (((status)::text = ANY ((ARRAY['PROPOSED'::character varying, 'SCHEDULED'::character varying, 'DONE'::character varying, 'CANCELED_BY_CENTER'::character varying])::text[]))),
    CONSTRAINT ck_session_time CHECK ((end_time > start_time))
);


ALTER TABLE public.class_sessions OWNER TO postgres;

--
-- TOC entry 252 (class 1259 OID 22843)
-- Name: class_sessions_id_seq; Type: SEQUENCE; Schema: public; Owner: postgres
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
-- TOC entry 228 (class 1259 OID 22435)
-- Name: coach_profiles; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.coach_profiles (
    user_id bigint NOT NULL,
    specialty character varying(255),
    years_experience smallint,
    certifications text,
    rating numeric(2,1),
    coach_rank character varying(10) DEFAULT 'COACH'::character varying NOT NULL,
    CONSTRAINT ck_coach_rank CHECK (((coach_rank)::text = ANY ((ARRAY['COACH'::character varying, 'SENIOR'::character varying, 'MASTER'::character varying])::text[]))),
    CONSTRAINT ck_coach_rating CHECK (((rating IS NULL) OR ((rating >= (0)::numeric) AND (rating <= (5)::numeric)))),
    CONSTRAINT ck_coach_years CHECK (((years_experience IS NULL) OR (years_experience >= 0)))
);


ALTER TABLE public.coach_profiles OWNER TO postgres;

--
-- TOC entry 259 (class 1259 OID 22956)
-- Name: coach_slots; Type: TABLE; Schema: public; Owner: postgres
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
    CONSTRAINT ck_slot_held CHECK ((((status)::text <> 'HELD'::text) OR (held_until IS NOT NULL))),
    CONSTRAINT ck_slot_kind CHECK (((kind)::text = ANY ((ARRAY['ONE_ON_ONE'::character varying, 'ASSESSMENT'::character varying])::text[]))),
    CONSTRAINT ck_slot_member CHECK (((((status)::text = 'OPEN'::text) AND (member_id IS NULL)) OR (((status)::text = ANY ((ARRAY['HELD'::character varying, 'BOOKED'::character varying])::text[])) AND (member_id IS NOT NULL)) OR ((status)::text = 'CANCELED'::text))),
    CONSTRAINT ck_slot_price CHECK (((price IS NULL) OR (price >= (0)::numeric))),
    CONSTRAINT ck_slot_status CHECK (((status)::text = ANY ((ARRAY['OPEN'::character varying, 'HELD'::character varying, 'BOOKED'::character varying, 'CANCELED'::character varying])::text[]))),
    CONSTRAINT ck_slot_time CHECK ((end_at > start_at))
);


ALTER TABLE public.coach_slots OWNER TO postgres;

--
-- TOC entry 258 (class 1259 OID 22955)
-- Name: coach_slots_id_seq; Type: SEQUENCE; Schema: public; Owner: postgres
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
-- TOC entry 235 (class 1259 OID 22510)
-- Name: coach_sports; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.coach_sports (
    coach_id bigint NOT NULL,
    category_id bigint NOT NULL
);


ALTER TABLE public.coach_sports OWNER TO postgres;

--
-- TOC entry 247 (class 1259 OID 22743)
-- Name: courses; Type: TABLE; Schema: public; Owner: postgres
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
    CONSTRAINT ck_course_level CHECK (((level)::text = ANY ((ARRAY['BEGINNER'::character varying, 'INTERMEDIATE'::character varying, 'ADVANCED'::character varying])::text[]))),
    CONSTRAINT ck_course_prereq CHECK (((prerequisite_course_id IS NULL) OR (prerequisite_course_id <> id))),
    CONSTRAINT ck_course_sessions CHECK ((total_sessions > 0)),
    CONSTRAINT ck_course_tuition CHECK ((default_tuition >= (0)::numeric))
);


ALTER TABLE public.courses OWNER TO postgres;

--
-- TOC entry 246 (class 1259 OID 22742)
-- Name: courses_id_seq; Type: SEQUENCE; Schema: public; Owner: postgres
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
-- TOC entry 274 (class 1259 OID 23236)
-- Name: facility_attendances; Type: TABLE; Schema: public; Owner: postgres
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


ALTER TABLE public.facility_attendances OWNER TO postgres;

--
-- TOC entry 273 (class 1259 OID 23235)
-- Name: facility_attendances_id_seq; Type: SEQUENCE; Schema: public; Owner: postgres
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
-- TOC entry 233 (class 1259 OID 22477)
-- Name: facility_rooms; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.facility_rooms (
    id bigint NOT NULL,
    room_name character varying(100) NOT NULL,
    floor character varying(20),
    capacity integer NOT NULL,
    description character varying(500),
    maintenance_status character varying(20) DEFAULT 'AVAILABLE'::character varying NOT NULL,
    CONSTRAINT ck_room_status CHECK (((maintenance_status)::text = ANY ((ARRAY['AVAILABLE'::character varying, 'MAINTENANCE'::character varying, 'CLOSED'::character varying])::text[]))),
    CONSTRAINT facility_rooms_capacity_check CHECK ((capacity > 0))
);


ALTER TABLE public.facility_rooms OWNER TO postgres;

--
-- TOC entry 232 (class 1259 OID 22476)
-- Name: facility_rooms_id_seq; Type: SEQUENCE; Schema: public; Owner: postgres
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
-- TOC entry 227 (class 1259 OID 22418)
-- Name: member_profiles; Type: TABLE; Schema: public; Owner: postgres
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


ALTER TABLE public.member_profiles OWNER TO postgres;

--
-- TOC entry 239 (class 1259 OID 22587)
-- Name: member_subscriptions; Type: TABLE; Schema: public; Owner: postgres
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
    CONSTRAINT ck_sub_purchase_type CHECK (((purchase_type)::text = ANY ((ARRAY['NEW'::character varying, 'RENEWAL'::character varying, 'UPGRADE'::character varying])::text[]))),
    CONSTRAINT ck_sub_status CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'SCHEDULED'::character varying, 'ACTIVE'::character varying, 'EXPIRED'::character varying, 'UPGRADED'::character varying, 'CANCELED'::character varying])::text[]))),
    CONSTRAINT ck_sub_tier_snapshot CHECK ((tier_level_snapshot >= 1)),
    CONSTRAINT ck_sub_upgrade_credit CHECK ((upgrade_credit_amount >= (0)::numeric)),
    CONSTRAINT ck_sub_upgrade_ref CHECK ((((purchase_type)::text <> 'UPGRADE'::text) OR (upgrade_from_subscription_id IS NOT NULL)))
);


ALTER TABLE public.member_subscriptions OWNER TO postgres;

--
-- TOC entry 238 (class 1259 OID 22586)
-- Name: member_subscriptions_id_seq; Type: SEQUENCE; Schema: public; Owner: postgres
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
-- TOC entry 237 (class 1259 OID 22528)
-- Name: membership_packages; Type: TABLE; Schema: public; Owner: postgres
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
    assessment_every_months smallint,
    freeze_days smallint DEFAULT 0 NOT NULL,
    early_leave_refund_sessions smallint DEFAULT 0 NOT NULL,
    waitlist_priority smallint DEFAULT 0 NOT NULL,
    birthday_gift_kind character varying(20) DEFAULT 'NONE'::character varying NOT NULL,
    birthday_gift_value numeric(12,0) DEFAULT 0 NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT ck_package_121 CHECK (((one_on_one_discount_pct >= 0) AND (one_on_one_discount_pct <= 100))),
    CONSTRAINT ck_package_ai CHECK (((ai_level)::text = ANY ((ARRAY['NONE'::character varying, 'LIMITED'::character varying, 'UNLIMITED'::character varying])::text[]))),
    CONSTRAINT ck_package_assess CHECK (((assessment_every_months IS NULL) OR (assessment_every_months > 0))),
    CONSTRAINT ck_package_early CHECK ((early_register_days >= 0)),
    CONSTRAINT ck_package_freeze CHECK ((freeze_days >= 0)),
    CONSTRAINT ck_package_gift CHECK (((birthday_gift_kind)::text = ANY ((ARRAY['NONE'::character varying, 'PERCENT'::character varying, 'FREE_ONE_ON_ONE'::character varying])::text[]))),
    CONSTRAINT ck_package_gift_value CHECK ((birthday_gift_value >= (0)::numeric)),
    CONSTRAINT ck_package_leave CHECK ((early_leave_refund_sessions >= 0)),
    CONSTRAINT ck_package_menu_ai CHECK (((menu_ai_level)::text = ANY ((ARRAY['NONE'::character varying, 'LIMITED'::character varying, 'UNLIMITED'::character varying])::text[]))),
    CONSTRAINT ck_package_price CHECK ((monthly_price >= (0)::numeric)),
    CONSTRAINT ck_package_tier CHECK ((tier_level >= 1)),
    CONSTRAINT ck_package_tuition CHECK (((tuition_discount_pct >= 0) AND (tuition_discount_pct <= 100)))
);


ALTER TABLE public.membership_packages OWNER TO postgres;

--
-- TOC entry 236 (class 1259 OID 22527)
-- Name: membership_packages_id_seq; Type: SEQUENCE; Schema: public; Owner: postgres
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
-- TOC entry 267 (class 1259 OID 23138)
-- Name: notifications; Type: TABLE; Schema: public; Owner: postgres
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
    CONSTRAINT ck_notification_channel CHECK (((channel)::text = ANY ((ARRAY['APP'::character varying, 'EMAIL'::character varying])::text[]))),
    CONSTRAINT ck_notification_type CHECK (((type)::text = ANY ((ARRAY['ANNOUNCEMENT'::character varying, 'HOMEWORK'::character varying, 'WORKOUT_PLAN'::character varying, 'MEMBERSHIP'::character varying, 'BOOKING'::character varying, 'PAYMENT'::character varying, 'WAITLIST'::character varying, 'MAKEUP'::character varying, 'SYSTEM'::character varying])::text[])))
);


ALTER TABLE public.notifications OWNER TO postgres;

--
-- TOC entry 266 (class 1259 OID 23137)
-- Name: notifications_id_seq; Type: SEQUENCE; Schema: public; Owner: postgres
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
-- TOC entry 261 (class 1259 OID 22997)
-- Name: orders; Type: TABLE; Schema: public; Owner: postgres
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
    CONSTRAINT ck_order_channel CHECK (((channel)::text = ANY ((ARRAY['ONLINE'::character varying, 'COUNTER'::character varying])::text[]))),
    CONSTRAINT ck_order_discount_kind CHECK (((discount_kind)::text = ANY ((ARRAY['NONE'::character varying, 'TIER'::character varying, 'VOUCHER'::character varying])::text[]))),
    CONSTRAINT ck_order_item CHECK ((((((subscription_id IS NOT NULL))::integer + ((registration_id IS NOT NULL))::integer) + ((coach_slot_id IS NOT NULL))::integer) = 1)),
    CONSTRAINT ck_order_status CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'PAID'::character varying, 'CANCELED'::character varying, 'EXPIRED'::character varying])::text[]))),
    CONSTRAINT ck_order_voucher CHECK ((((discount_kind)::text = 'VOUCHER'::text) = (voucher_id IS NOT NULL)))
);


ALTER TABLE public.orders OWNER TO postgres;

--
-- TOC entry 260 (class 1259 OID 22996)
-- Name: orders_id_seq; Type: SEQUENCE; Schema: public; Owner: postgres
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
-- TOC entry 263 (class 1259 OID 23058)
-- Name: payments; Type: TABLE; Schema: public; Owner: postgres
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
    CONSTRAINT ck_payment_method CHECK (((payment_method)::text = ANY ((ARRAY['CASH'::character varying, 'BANK_TRANSFER'::character varying, 'QR_GATEWAY'::character varying])::text[]))),
    CONSTRAINT ck_payment_paid_at CHECK ((((status)::text <> ALL ((ARRAY['SUCCESS'::character varying, 'PENDING_REFUND'::character varying, 'REFUNDED'::character varying])::text[])) OR (paid_at IS NOT NULL))),
    CONSTRAINT ck_payment_status CHECK (((status)::text = ANY ((ARRAY['CREATING'::character varying, 'PENDING'::character varying, 'SUCCESS'::character varying, 'FAILED'::character varying, 'CANCELED'::character varying, 'EXPIRED'::character varying, 'PENDING_REFUND'::character varying, 'REFUNDED'::character varying])::text[])))
);


ALTER TABLE public.payments OWNER TO postgres;

--
-- TOC entry 262 (class 1259 OID 23057)
-- Name: payments_id_seq; Type: SEQUENCE; Schema: public; Owner: postgres
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
-- TOC entry 223 (class 1259 OID 22355)
-- Name: permissions; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.permissions (
    id bigint NOT NULL,
    code character varying(60) NOT NULL,
    name character varying(100) NOT NULL,
    description character varying(255)
);


ALTER TABLE public.permissions OWNER TO postgres;

--
-- TOC entry 222 (class 1259 OID 22354)
-- Name: permissions_id_seq; Type: SEQUENCE; Schema: public; Owner: postgres
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
-- TOC entry 265 (class 1259 OID 23107)
-- Name: refunds; Type: TABLE; Schema: public; Owner: postgres
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
    CONSTRAINT ck_refund_method CHECK (((method)::text = ANY ((ARRAY['CASH'::character varying, 'TRANSFER'::character varying])::text[]))),
    CONSTRAINT ck_refund_status CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'DONE'::character varying])::text[])))
);


ALTER TABLE public.refunds OWNER TO postgres;

--
-- TOC entry 264 (class 1259 OID 23106)
-- Name: refunds_id_seq; Type: SEQUENCE; Schema: public; Owner: postgres
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
-- TOC entry 224 (class 1259 OID 22365)
-- Name: role_permissions; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.role_permissions (
    role_id bigint NOT NULL,
    permission_id bigint NOT NULL
);


ALTER TABLE public.role_permissions OWNER TO postgres;

--
-- TOC entry 221 (class 1259 OID 22344)
-- Name: roles; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.roles (
    id bigint NOT NULL,
    code character varying(30) NOT NULL,
    name character varying(100) NOT NULL,
    description character varying(255)
);


ALTER TABLE public.roles OWNER TO postgres;

--
-- TOC entry 220 (class 1259 OID 22343)
-- Name: roles_id_seq; Type: SEQUENCE; Schema: public; Owner: postgres
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
-- TOC entry 234 (class 1259 OID 22493)
-- Name: room_sports; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.room_sports (
    room_id bigint NOT NULL,
    category_id bigint NOT NULL
);


ALTER TABLE public.room_sports OWNER TO postgres;

--
-- TOC entry 231 (class 1259 OID 22465)
-- Name: sport_categories; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.sport_categories (
    id bigint NOT NULL,
    name character varying(100) NOT NULL,
    description character varying(500)
);


ALTER TABLE public.sport_categories OWNER TO postgres;

--
-- TOC entry 230 (class 1259 OID 22464)
-- Name: sport_categories_id_seq; Type: SEQUENCE; Schema: public; Owner: postgres
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
-- TOC entry 249 (class 1259 OID 22780)
-- Name: sport_classes; Type: TABLE; Schema: public; Owner: postgres
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
    CONSTRAINT ck_class_status CHECK (((status)::text = ANY ((ARRAY['DRAFT'::character varying, 'OPEN'::character varying, 'IN_PROGRESS'::character varying, 'COMPLETED'::character varying, 'CANCELED'::character varying])::text[]))),
    CONSTRAINT ck_class_tuition CHECK ((tuition >= (0)::numeric))
);


ALTER TABLE public.sport_classes OWNER TO postgres;

--
-- TOC entry 248 (class 1259 OID 22779)
-- Name: sport_classes_id_seq; Type: SEQUENCE; Schema: public; Owner: postgres
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
-- TOC entry 229 (class 1259 OID 22453)
-- Name: staff_profiles; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.staff_profiles (
    user_id bigint NOT NULL,
    job_title character varying(100),
    shift_schedule character varying(255)
);


ALTER TABLE public.staff_profiles OWNER TO postgres;

--
-- TOC entry 241 (class 1259 OID 22649)
-- Name: subscription_freezes; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.subscription_freezes (
    id bigint NOT NULL,
    subscription_id bigint NOT NULL,
    start_date date NOT NULL,
    end_date date NOT NULL,
    ended_on date,
    status character varying(10) DEFAULT 'SCHEDULED'::character varying NOT NULL,
    created_by bigint,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    days_used smallint GENERATED ALWAYS AS ((
CASE
    WHEN ((status)::text = 'CANCELED'::text) THEN 0
    ELSE ((COALESCE(ended_on, end_date) - start_date) + 1)
END)::smallint) STORED,
    CONSTRAINT ck_freeze_dates CHECK ((end_date >= start_date)),
    CONSTRAINT ck_freeze_ended CHECK (((ended_on IS NULL) OR ((ended_on >= start_date) AND (ended_on <= end_date)))),
    CONSTRAINT ck_freeze_ended_status CHECK ((((status)::text <> 'ENDED'::text) OR (ended_on IS NOT NULL))),
    CONSTRAINT ck_freeze_status CHECK (((status)::text = ANY ((ARRAY['SCHEDULED'::character varying, 'ACTIVE'::character varying, 'ENDED'::character varying, 'CANCELED'::character varying])::text[])))
);


ALTER TABLE public.subscription_freezes OWNER TO postgres;

--
-- TOC entry 240 (class 1259 OID 22648)
-- Name: subscription_freezes_id_seq; Type: SEQUENCE; Schema: public; Owner: postgres
--

ALTER TABLE public.subscription_freezes ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.subscription_freezes_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- TOC entry 282 (class 1259 OID 23348)
-- Name: support_requests; Type: TABLE; Schema: public; Owner: postgres
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
    CONSTRAINT ck_support_status CHECK (((status)::text = ANY ((ARRAY['OPEN'::character varying, 'IN_PROGRESS'::character varying, 'RESOLVED'::character varying, 'CLOSED'::character varying])::text[])))
);


ALTER TABLE public.support_requests OWNER TO postgres;

--
-- TOC entry 281 (class 1259 OID 23347)
-- Name: support_requests_id_seq; Type: SEQUENCE; Schema: public; Owner: postgres
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
-- TOC entry 270 (class 1259 OID 23188)
-- Name: system_settings; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.system_settings (
    key character varying(100) NOT NULL,
    value jsonb NOT NULL,
    description character varying(255),
    updated_by bigint,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


ALTER TABLE public.system_settings OWNER TO postgres;

--
-- TOC entry 226 (class 1259 OID 22383)
-- Name: users; Type: TABLE; Schema: public; Owner: postgres
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
    CONSTRAINT ck_users_gender CHECK (((gender IS NULL) OR ((gender)::text = ANY ((ARRAY['MALE'::character varying, 'FEMALE'::character varying, 'OTHER'::character varying])::text[])))),
    CONSTRAINT ck_users_phone CHECK (((phone)::text ~ '^(03|05|07|08|09)[0-9]{8}$'::text))
);


ALTER TABLE public.users OWNER TO postgres;

--
-- TOC entry 225 (class 1259 OID 22382)
-- Name: users_id_seq; Type: SEQUENCE; Schema: public; Owner: postgres
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
-- TOC entry 243 (class 1259 OID 22681)
-- Name: voucher_campaigns; Type: TABLE; Schema: public; Owner: postgres
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
    CONSTRAINT ck_campaign_applies CHECK (((applies_to)::text = ANY ((ARRAY['CLASS'::character varying, 'ONE_ON_ONE'::character varying, 'PACKAGE'::character varying])::text[]))),
    CONSTRAINT ck_campaign_dates CHECK ((ends_at > starts_at)),
    CONSTRAINT ck_campaign_kind CHECK (((kind)::text = ANY ((ARRAY['PERCENT'::character varying, 'AMOUNT'::character varying, 'FREE_ONE_ON_ONE'::character varying])::text[]))),
    CONSTRAINT ck_campaign_uses CHECK (((max_uses IS NULL) OR (max_uses > 0))),
    CONSTRAINT ck_campaign_value CHECK (((value >= (0)::numeric) AND (((kind)::text <> 'PERCENT'::text) OR (value <= (100)::numeric))))
);


ALTER TABLE public.voucher_campaigns OWNER TO postgres;

--
-- TOC entry 242 (class 1259 OID 22680)
-- Name: voucher_campaigns_id_seq; Type: SEQUENCE; Schema: public; Owner: postgres
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
-- TOC entry 245 (class 1259 OID 22709)
-- Name: vouchers; Type: TABLE; Schema: public; Owner: postgres
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
    CONSTRAINT ck_voucher_applies CHECK (((applies_to)::text = ANY ((ARRAY['CLASS'::character varying, 'ONE_ON_ONE'::character varying, 'PACKAGE'::character varying])::text[]))),
    CONSTRAINT ck_voucher_campaign CHECK ((((source)::text = 'CAMPAIGN'::text) = (campaign_id IS NOT NULL))),
    CONSTRAINT ck_voucher_expiry CHECK ((expires_at > issued_at)),
    CONSTRAINT ck_voucher_kind CHECK (((kind)::text = ANY ((ARRAY['PERCENT'::character varying, 'AMOUNT'::character varying, 'FREE_ONE_ON_ONE'::character varying])::text[]))),
    CONSTRAINT ck_voucher_source CHECK (((source)::text = ANY ((ARRAY['BIRTHDAY'::character varying, 'WELCOME'::character varying, 'COMPLETION'::character varying, 'COMPENSATION'::character varying, 'CAMPAIGN'::character varying])::text[]))),
    CONSTRAINT ck_voucher_value CHECK (((value >= (0)::numeric) AND (((kind)::text <> 'PERCENT'::text) OR (value <= (100)::numeric))))
);


ALTER TABLE public.vouchers OWNER TO postgres;

--
-- TOC entry 244 (class 1259 OID 22708)
-- Name: vouchers_id_seq; Type: SEQUENCE; Schema: public; Owner: postgres
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
-- TOC entry 276 (class 1259 OID 23260)
-- Name: workout_plans; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.workout_plans (
    id bigint NOT NULL,
    member_id bigint NOT NULL,
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
    CONSTRAINT ck_plan_dates CHECK (((end_date IS NULL) OR (start_date IS NULL) OR (end_date >= start_date))),
    CONSTRAINT ck_plan_status CHECK (((status)::text = ANY ((ARRAY['DRAFT'::character varying, 'PUBLISHED'::character varying, 'COMPLETED'::character varying, 'ARCHIVED'::character varying])::text[])))
);


ALTER TABLE public.workout_plans OWNER TO postgres;

--
-- TOC entry 275 (class 1259 OID 23259)
-- Name: workout_plans_id_seq; Type: SEQUENCE; Schema: public; Owner: postgres
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
-- TOC entry 278 (class 1259 OID 23294)
-- Name: workout_progress; Type: TABLE; Schema: public; Owner: postgres
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


ALTER TABLE public.workout_progress OWNER TO postgres;

--
-- TOC entry 277 (class 1259 OID 23293)
-- Name: workout_progress_id_seq; Type: SEQUENCE; Schema: public; Owner: postgres
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
-- TOC entry 5932 (class 0 OID 23326)
-- Dependencies: 280
-- Data for Name: ai_chat_logs; Type: TABLE DATA; Schema: public; Owner: postgres
--



--
-- TOC entry 5921 (class 0 OID 23169)
-- Dependencies: 269
-- Data for Name: audit_logs; Type: TABLE DATA; Schema: public; Owner: postgres
--

INSERT INTO public.audit_logs OVERRIDING SYSTEM VALUE VALUES (1, 1, 'SEED', 'database', 'v3', '{"note": "nạp dữ liệu mẫu scms_database_v3"}', '2026-10-23 18:14:43.228636+07');


--
-- TOC entry 5924 (class 0 OID 23205)
-- Dependencies: 272
-- Data for Name: class_attendances; Type: TABLE DATA; Schema: public; Owner: postgres
--



--
-- TOC entry 5909 (class 0 OID 22929)
-- Dependencies: 257
-- Data for Name: class_evaluations; Type: TABLE DATA; Schema: public; Owner: postgres
--



--
-- TOC entry 5907 (class 0 OID 22892)
-- Dependencies: 255
-- Data for Name: class_registrations; Type: TABLE DATA; Schema: public; Owner: postgres
--

INSERT INTO public.class_registrations OVERRIDING SYSTEM VALUE VALUES (1, 1, 8, '2026-10-23 18:14:43.228636+07', 'REGISTERED', NULL, 2, NULL, 0, NULL);
INSERT INTO public.class_registrations OVERRIDING SYSTEM VALUE VALUES (2, 2, 7, '2026-10-23 18:14:43.228636+07', 'REGISTERED', NULL, NULL, NULL, 0, NULL);


--
-- TOC entry 5903 (class 0 OID 22819)
-- Dependencies: 251
-- Data for Name: class_schedules; Type: TABLE DATA; Schema: public; Owner: postgres
--

INSERT INTO public.class_schedules OVERRIDING SYSTEM VALUE VALUES (1, 1, 1, 1, '18:00:00', '19:30:00');
INSERT INTO public.class_schedules OVERRIDING SYSTEM VALUE VALUES (2, 1, 1, 3, '18:00:00', '19:30:00');
INSERT INTO public.class_schedules OVERRIDING SYSTEM VALUE VALUES (3, 1, 1, 5, '18:00:00', '19:30:00');
INSERT INTO public.class_schedules OVERRIDING SYSTEM VALUE VALUES (4, 2, 2, 2, '18:00:00', '19:00:00');
INSERT INTO public.class_schedules OVERRIDING SYSTEM VALUE VALUES (5, 2, 2, 4, '18:00:00', '19:00:00');
INSERT INTO public.class_schedules OVERRIDING SYSTEM VALUE VALUES (6, 3, 4, 6, '17:00:00', '19:00:00');


--
-- TOC entry 5905 (class 0 OID 22844)
-- Dependencies: 253
-- Data for Name: class_sessions; Type: TABLE DATA; Schema: public; Owner: postgres
--

INSERT INTO public.class_sessions OVERRIDING SYSTEM VALUE VALUES (1, 1, 1, '2026-11-06', '18:00:00', '19:30:00', 1, 5, 'SCHEDULED', false, NULL, '2026-10-23 18:14:43.228636+07', '2026-10-23 18:14:43.228636+07');
INSERT INTO public.class_sessions OVERRIDING SYSTEM VALUE VALUES (2, 1, 2, '2026-11-09', '18:00:00', '19:30:00', 1, 5, 'SCHEDULED', false, NULL, '2026-10-23 18:14:43.228636+07', '2026-10-23 18:14:43.228636+07');
INSERT INTO public.class_sessions OVERRIDING SYSTEM VALUE VALUES (3, 1, 3, '2026-11-11', '18:00:00', '19:30:00', 1, 5, 'SCHEDULED', false, NULL, '2026-10-23 18:14:43.228636+07', '2026-10-23 18:14:43.228636+07');
INSERT INTO public.class_sessions OVERRIDING SYSTEM VALUE VALUES (4, 1, 4, '2026-11-13', '18:00:00', '19:30:00', 1, 5, 'SCHEDULED', false, NULL, '2026-10-23 18:14:43.228636+07', '2026-10-23 18:14:43.228636+07');
INSERT INTO public.class_sessions OVERRIDING SYSTEM VALUE VALUES (5, 1, 5, '2026-11-16', '18:00:00', '19:30:00', 1, 5, 'SCHEDULED', false, NULL, '2026-10-23 18:14:43.228636+07', '2026-10-23 18:14:43.228636+07');
INSERT INTO public.class_sessions OVERRIDING SYSTEM VALUE VALUES (6, 1, 6, '2026-11-18', '18:00:00', '19:30:00', 1, 5, 'SCHEDULED', false, NULL, '2026-10-23 18:14:43.228636+07', '2026-10-23 18:14:43.228636+07');
INSERT INTO public.class_sessions OVERRIDING SYSTEM VALUE VALUES (7, 1, 7, '2026-11-20', '18:00:00', '19:30:00', 1, 5, 'SCHEDULED', false, NULL, '2026-10-23 18:14:43.228636+07', '2026-10-23 18:14:43.228636+07');
INSERT INTO public.class_sessions OVERRIDING SYSTEM VALUE VALUES (8, 1, 8, '2026-11-23', '18:00:00', '19:30:00', 1, 5, 'SCHEDULED', false, NULL, '2026-10-23 18:14:43.228636+07', '2026-10-23 18:14:43.228636+07');
INSERT INTO public.class_sessions OVERRIDING SYSTEM VALUE VALUES (9, 1, 9, '2026-11-25', '18:00:00', '19:30:00', 1, 5, 'SCHEDULED', false, NULL, '2026-10-23 18:14:43.228636+07', '2026-10-23 18:14:43.228636+07');
INSERT INTO public.class_sessions OVERRIDING SYSTEM VALUE VALUES (10, 1, 10, '2026-11-27', '18:00:00', '19:30:00', 1, 5, 'SCHEDULED', false, NULL, '2026-10-23 18:14:43.228636+07', '2026-10-23 18:14:43.228636+07');
INSERT INTO public.class_sessions OVERRIDING SYSTEM VALUE VALUES (11, 1, 11, '2026-11-30', '18:00:00', '19:30:00', 1, 5, 'SCHEDULED', false, NULL, '2026-10-23 18:14:43.228636+07', '2026-10-23 18:14:43.228636+07');
INSERT INTO public.class_sessions OVERRIDING SYSTEM VALUE VALUES (12, 1, 12, '2026-12-02', '18:00:00', '19:30:00', 1, 5, 'SCHEDULED', false, NULL, '2026-10-23 18:14:43.228636+07', '2026-10-23 18:14:43.228636+07');
INSERT INTO public.class_sessions OVERRIDING SYSTEM VALUE VALUES (13, 2, 1, '2026-11-03', '18:00:00', '19:00:00', 2, 4, 'SCHEDULED', false, NULL, '2026-10-23 18:14:43.228636+07', '2026-10-23 18:14:43.228636+07');
INSERT INTO public.class_sessions OVERRIDING SYSTEM VALUE VALUES (14, 2, 2, '2026-11-05', '18:00:00', '19:00:00', 2, 4, 'SCHEDULED', false, NULL, '2026-10-23 18:14:43.228636+07', '2026-10-23 18:14:43.228636+07');
INSERT INTO public.class_sessions OVERRIDING SYSTEM VALUE VALUES (15, 2, 3, '2026-11-10', '18:00:00', '19:00:00', 2, 4, 'SCHEDULED', false, NULL, '2026-10-23 18:14:43.228636+07', '2026-10-23 18:14:43.228636+07');
INSERT INTO public.class_sessions OVERRIDING SYSTEM VALUE VALUES (16, 2, 4, '2026-11-12', '18:00:00', '19:00:00', 2, 4, 'SCHEDULED', false, NULL, '2026-10-23 18:14:43.228636+07', '2026-10-23 18:14:43.228636+07');
INSERT INTO public.class_sessions OVERRIDING SYSTEM VALUE VALUES (17, 2, 5, '2026-11-17', '18:00:00', '19:00:00', 2, 4, 'SCHEDULED', false, NULL, '2026-10-23 18:14:43.228636+07', '2026-10-23 18:14:43.228636+07');
INSERT INTO public.class_sessions OVERRIDING SYSTEM VALUE VALUES (18, 2, 6, '2026-11-19', '18:00:00', '19:00:00', 2, 4, 'SCHEDULED', false, NULL, '2026-10-23 18:14:43.228636+07', '2026-10-23 18:14:43.228636+07');
INSERT INTO public.class_sessions OVERRIDING SYSTEM VALUE VALUES (19, 2, 7, '2026-11-24', '18:00:00', '19:00:00', 2, 4, 'SCHEDULED', false, NULL, '2026-10-23 18:14:43.228636+07', '2026-10-23 18:14:43.228636+07');
INSERT INTO public.class_sessions OVERRIDING SYSTEM VALUE VALUES (20, 2, 8, '2026-11-26', '18:00:00', '19:00:00', 2, 4, 'SCHEDULED', false, NULL, '2026-10-23 18:14:43.228636+07', '2026-10-23 18:14:43.228636+07');
INSERT INTO public.class_sessions OVERRIDING SYSTEM VALUE VALUES (21, 2, 9, '2026-12-01', '18:00:00', '19:00:00', 2, 4, 'SCHEDULED', false, NULL, '2026-10-23 18:14:43.228636+07', '2026-10-23 18:14:43.228636+07');
INSERT INTO public.class_sessions OVERRIDING SYSTEM VALUE VALUES (22, 2, 10, '2026-12-03', '18:00:00', '19:00:00', 2, 4, 'SCHEDULED', false, NULL, '2026-10-23 18:14:43.228636+07', '2026-10-23 18:14:43.228636+07');


--
-- TOC entry 5880 (class 0 OID 22435)
-- Dependencies: 228
-- Data for Name: coach_profiles; Type: TABLE DATA; Schema: public; Owner: postgres
--

INSERT INTO public.coach_profiles VALUES (3, 'Thể lực, Bóng chuyền', 1, 'ACE Certified Personal Trainer', NULL, 'COACH');
INSERT INTO public.coach_profiles VALUES (4, 'Yoga', 3, 'RYT 200', NULL, 'SENIOR');
INSERT INTO public.coach_profiles VALUES (5, 'Bơi lội', 6, 'Chứng chỉ huấn luyện viên bơi cấp quốc gia', NULL, 'MASTER');


--
-- TOC entry 5911 (class 0 OID 22956)
-- Dependencies: 259
-- Data for Name: coach_slots; Type: TABLE DATA; Schema: public; Owner: postgres
--

INSERT INTO public.coach_slots OVERRIDING SYSTEM VALUE VALUES (1, 5, '2026-10-24 09:00:00+07', '2026-10-24 10:00:00+07', 'ONE_ON_ONE', 'OPEN', NULL, NULL, NULL, NULL, NULL, '2026-10-23 18:14:43.228636+07');
INSERT INTO public.coach_slots OVERRIDING SYSTEM VALUE VALUES (2, 5, '2026-10-25 09:00:00+07', '2026-10-25 10:00:00+07', 'ONE_ON_ONE', 'OPEN', NULL, NULL, NULL, NULL, NULL, '2026-10-23 18:14:43.228636+07');
INSERT INTO public.coach_slots OVERRIDING SYSTEM VALUE VALUES (3, 5, '2026-10-26 09:00:00+07', '2026-10-26 10:00:00+07', 'ONE_ON_ONE', 'OPEN', NULL, NULL, NULL, NULL, NULL, '2026-10-23 18:14:43.228636+07');
INSERT INTO public.coach_slots OVERRIDING SYSTEM VALUE VALUES (4, 3, '2026-10-25 14:00:00+07', '2026-10-25 15:00:00+07', 'ASSESSMENT', 'OPEN', NULL, NULL, NULL, NULL, NULL, '2026-10-23 18:14:43.228636+07');


--
-- TOC entry 5887 (class 0 OID 22510)
-- Dependencies: 235
-- Data for Name: coach_sports; Type: TABLE DATA; Schema: public; Owner: postgres
--

INSERT INTO public.coach_sports VALUES (5, 1);
INSERT INTO public.coach_sports VALUES (4, 2);
INSERT INTO public.coach_sports VALUES (3, 3);
INSERT INTO public.coach_sports VALUES (3, 4);


--
-- TOC entry 5899 (class 0 OID 22743)
-- Dependencies: 247
-- Data for Name: courses; Type: TABLE DATA; Schema: public; Owner: postgres
--

INSERT INTO public.courses OVERRIDING SYSTEM VALUE VALUES (1, 1, 'Bơi cơ bản', 'Làm quen với nước, bơi sải cơ bản', 'BEGINNER', 12, 1200000, NULL, true, '2026-10-23 18:14:43.228636+07', '2026-10-23 18:14:43.228636+07');
INSERT INTO public.courses OVERRIDING SYSTEM VALUE VALUES (2, 2, 'Yoga cơ bản', 'Các tư thế nền tảng và hít thở', 'BEGINNER', 10, 900000, NULL, true, '2026-10-23 18:14:43.228636+07', '2026-10-23 18:14:43.228636+07');
INSERT INTO public.courses OVERRIDING SYSTEM VALUE VALUES (3, 3, 'Bóng chuyền cơ bản', 'Chuyền, đệm, phát bóng', 'BEGINNER', 8, 800000, NULL, true, '2026-10-23 18:14:43.228636+07', '2026-10-23 18:14:43.228636+07');
INSERT INTO public.courses OVERRIDING SYSTEM VALUE VALUES (4, 1, 'Bơi nâng cao', 'Bơi ếch, bơi ngửa, kỹ thuật xuất phát', 'ADVANCED', 12, 1800000, 1, true, '2026-10-23 18:14:43.228636+07', '2026-10-23 18:14:43.228636+07');


--
-- TOC entry 5926 (class 0 OID 23236)
-- Dependencies: 274
-- Data for Name: facility_attendances; Type: TABLE DATA; Schema: public; Owner: postgres
--



--
-- TOC entry 5885 (class 0 OID 22477)
-- Dependencies: 233
-- Data for Name: facility_rooms; Type: TABLE DATA; Schema: public; Owner: postgres
--

INSERT INTO public.facility_rooms OVERRIDING SYSTEM VALUE VALUES (1, 'Hồ bơi A', 'Tầng trệt', 30, 'Hồ bơi 25m', 'AVAILABLE');
INSERT INTO public.facility_rooms OVERRIDING SYSTEM VALUE VALUES (2, 'Phòng Yoga 1', 'Tầng 2', 20, 'Phòng yoga có gương', 'AVAILABLE');
INSERT INTO public.facility_rooms OVERRIDING SYSTEM VALUE VALUES (3, 'Phòng Yoga 2', 'Tầng 2', 20, 'Phòng yoga dự phòng', 'AVAILABLE');
INSERT INTO public.facility_rooms OVERRIDING SYSTEM VALUE VALUES (4, 'Sân bóng chuyền', 'Tầng 3', 30, 'Sân trong nhà', 'AVAILABLE');
INSERT INTO public.facility_rooms OVERRIDING SYSTEM VALUE VALUES (5, 'Khu tập tự do', 'Tầng 1', 80, 'Khu máy và tạ tự do', 'AVAILABLE');
INSERT INTO public.facility_rooms OVERRIDING SYSTEM VALUE VALUES (6, 'Phòng thể lực 1', 'Tầng 1', 25, 'Phòng thể lực nhóm nhỏ', 'AVAILABLE');
INSERT INTO public.facility_rooms OVERRIDING SYSTEM VALUE VALUES (7, 'Phòng thể lực 2', 'Tầng 1', 25, 'Phòng thể lực nhóm nhỏ, dự phòng', 'AVAILABLE');
INSERT INTO public.facility_rooms OVERRIDING SYSTEM VALUE VALUES (8, 'Phòng Boxing', 'Tầng 2', 20, 'Phòng boxing có bao cát', 'AVAILABLE');


--
-- TOC entry 5879 (class 0 OID 22418)
-- Dependencies: 227
-- Data for Name: member_profiles; Type: TABLE DATA; Schema: public; Owner: postgres
--

INSERT INTO public.member_profiles VALUES (6, '2026-10-23', NULL, NULL, NULL);
INSERT INTO public.member_profiles VALUES (7, '2026-10-23', 160.00, 52.00, 'Cải thiện sức bền');
INSERT INTO public.member_profiles VALUES (8, '2026-10-23', 175.50, 70.30, 'Tăng cơ giảm mỡ');


--
-- TOC entry 5891 (class 0 OID 22587)
-- Dependencies: 239
-- Data for Name: member_subscriptions; Type: TABLE DATA; Schema: public; Owner: postgres
--

INSERT INTO public.member_subscriptions OVERRIDING SYSTEM VALUE VALUES (1, 7, 2, 'Bứt Tốc', 2, 3, 'NEW', NULL, 0, NULL, 2070000, '2026-10-13', '2027-01-13', 'ACTIVE', '{"ai_level": "LIMITED", "freeze_days": 7, "menu_ai_level": "NONE", "monthly_price": 750000, "has_personal_plan": false, "waitlist_priority": 1, "birthday_gift_kind": "PERCENT", "birthday_gift_value": 10, "early_register_days": 2, "tuition_discount_pct": 5, "assessment_every_months": null, "one_on_one_discount_pct": 0, "early_leave_refund_sessions": 0}', 2, '2026-10-23 18:14:43.228636+07', '2026-10-23 18:14:43.228636+07');


--
-- TOC entry 5889 (class 0 OID 22528)
-- Dependencies: 237
-- Data for Name: membership_packages; Type: TABLE DATA; Schema: public; Owner: postgres
--

INSERT INTO public.membership_packages OVERRIDING SYSTEM VALUE VALUES (1, 'KHOI_DONG', 'Khởi Động', 'Vé vào khu tập tự do cho người mới.', 1, 450000, 0, 0, 0, false, 'NONE', 'NONE', NULL, 0, 0, 0, 'NONE', 0, true, '2026-10-23 18:14:43.228636+07', '2026-10-23 18:14:43.228636+07');
INSERT INTO public.membership_packages OVERRIDING SYSTEM VALUE VALUES (2, 'BUT_TOC', 'Bứt Tốc', 'Thêm đăng ký lớp sớm, giảm học phí, AI giới hạn và bảo lưu 7 ngày.', 2, 750000, 2, 5, 0, false, 'LIMITED', 'NONE', NULL, 7, 0, 1, 'PERCENT', 10, true, '2026-10-23 18:14:43.228636+07', '2026-10-23 18:14:43.228636+07');
INSERT INTO public.membership_packages OVERRIDING SYSTEM VALUE VALUES (3, 'VUOT_DINH', 'Vượt Đỉnh', 'Bài tập riêng từ coach, ưu tiên 1-1, đánh giá thể lực mỗi quý.', 3, 1200000, 5, 10, 10, true, 'LIMITED', 'LIMITED', 3, 14, 1, 2, 'PERCENT', 20, true, '2026-10-23 18:14:43.228636+07', '2026-10-23 18:14:43.228636+07');
INSERT INTO public.membership_packages OVERRIDING SYSTEM VALUE VALUES (4, 'QUAN_QUAN', 'Quán Quân', 'Trọn quyền lợi: AI không giới hạn, đánh giá thể lực mỗi tháng, bảo lưu 30 ngày.', 4, 2000000, 7, 15, 20, true, 'UNLIMITED', 'UNLIMITED', 1, 30, 2, 3, 'FREE_ONE_ON_ONE', 0, true, '2026-10-23 18:14:43.228636+07', '2026-10-23 18:14:43.228636+07');


--
-- TOC entry 5919 (class 0 OID 23138)
-- Dependencies: 267
-- Data for Name: notifications; Type: TABLE DATA; Schema: public; Owner: postgres
--

INSERT INTO public.notifications OVERRIDING SYSTEM VALUE VALUES (1, 8, NULL, 'MEMBERSHIP', 'APP', 'Chào mừng bạn đến với Sport Center', 'Tài khoản của bạn đã sẵn sàng.', false, '2026-10-23 18:14:43.228636+07');


--
-- TOC entry 5913 (class 0 OID 22997)
-- Dependencies: 261
-- Data for Name: orders; Type: TABLE DATA; Schema: public; Owner: postgres
--

INSERT INTO public.orders OVERRIDING SYSTEM VALUE VALUES (1, 8, 2, 'COUNTER', NULL, 1, NULL, 1200000, 'NONE', 0, NULL, 1200000, 'PAID', '2026-10-23 18:14:43.228636+07', '2026-10-23 18:14:43.228636+07');
INSERT INTO public.orders OVERRIDING SYSTEM VALUE VALUES (2, 7, NULL, 'ONLINE', NULL, 2, NULL, 900000, 'NONE', 0, NULL, 900000, 'PAID', '2026-10-23 18:14:43.228636+07', '2026-10-23 18:14:43.228636+07');
INSERT INTO public.orders OVERRIDING SYSTEM VALUE VALUES (3, 7, 2, 'COUNTER', 1, NULL, NULL, 2070000, 'NONE', 0, NULL, 2070000, 'PAID', '2026-10-23 18:14:43.228636+07', '2026-10-23 18:14:43.228636+07');


--
-- TOC entry 5915 (class 0 OID 23058)
-- Dependencies: 263
-- Data for Name: payments; Type: TABLE DATA; Schema: public; Owner: postgres
--

INSERT INTO public.payments OVERRIDING SYSTEM VALUE VALUES (1, 1, 8, 2, 'INV-SEED-0001', 'CASH-SEED-0001', 1200000, 'CASH', 'SUCCESS', '2026-10-23 18:14:43.228636+07', NULL, NULL, NULL, NULL, '2026-10-23 18:14:43.228636+07');
INSERT INTO public.payments OVERRIDING SYSTEM VALUE VALUES (2, 2, 7, NULL, 'INV-SEED-0002', 'QR-SEED-0002', 900000, 'QR_GATEWAY', 'SUCCESS', '2026-10-23 18:14:43.228636+07', NULL, 1000000001, NULL, NULL, '2026-10-23 18:14:43.228636+07');
INSERT INTO public.payments OVERRIDING SYSTEM VALUE VALUES (3, 3, 7, 2, 'INV-SEED-0003', 'CASH-SEED-0003', 2070000, 'CASH', 'SUCCESS', '2026-10-23 18:14:43.228636+07', NULL, NULL, NULL, NULL, '2026-10-23 18:14:43.228636+07');


--
-- TOC entry 5875 (class 0 OID 22355)
-- Dependencies: 223
-- Data for Name: permissions; Type: TABLE DATA; Schema: public; Owner: postgres
--

INSERT INTO public.permissions OVERRIDING SYSTEM VALUE VALUES (1, 'USER_MANAGE', 'Manage users and staff', NULL);
INSERT INTO public.permissions OVERRIDING SYSTEM VALUE VALUES (2, 'ROLE_MANAGE', 'Manage roles and permissions', NULL);
INSERT INTO public.permissions OVERRIDING SYSTEM VALUE VALUES (3, 'CLASS_MANAGE', 'Manage sports, classes, rooms and schedules', NULL);
INSERT INTO public.permissions OVERRIDING SYSTEM VALUE VALUES (4, 'PACKAGE_MANAGE', 'Manage membership packages', NULL);
INSERT INTO public.permissions OVERRIDING SYSTEM VALUE VALUES (5, 'REPORT_VIEW', 'View reports', NULL);
INSERT INTO public.permissions OVERRIDING SYSTEM VALUE VALUES (6, 'AUDIT_VIEW', 'View activity history', NULL);
INSERT INTO public.permissions OVERRIDING SYSTEM VALUE VALUES (7, 'MEMBER_VIEW', 'Search and view member information', NULL);
INSERT INTO public.permissions OVERRIDING SYSTEM VALUE VALUES (8, 'MEMBER_REGISTER', 'Register members', NULL);
INSERT INTO public.permissions OVERRIDING SYSTEM VALUE VALUES (9, 'SUBSCRIPTION_MANAGE', 'Register / renew memberships', NULL);
INSERT INTO public.permissions OVERRIDING SYSTEM VALUE VALUES (10, 'BOOKING_MANAGE', 'Register or cancel classes for members', NULL);
INSERT INTO public.permissions OVERRIDING SYSTEM VALUE VALUES (11, 'ATTENDANCE_MANAGE', 'Record attendance', NULL);
INSERT INTO public.permissions OVERRIDING SYSTEM VALUE VALUES (12, 'PAYMENT_MANAGE', 'Record payments and invoices', NULL);
INSERT INTO public.permissions OVERRIDING SYSTEM VALUE VALUES (13, 'SUPPORT_MANAGE', 'Manage support requests', NULL);
INSERT INTO public.permissions OVERRIDING SYSTEM VALUE VALUES (14, 'STUDENT_VIEW', 'View assigned students', NULL);
INSERT INTO public.permissions OVERRIDING SYSTEM VALUE VALUES (15, 'WORKOUT_MANAGE', 'Manage workout plans and progress', NULL);
INSERT INTO public.permissions OVERRIDING SYSTEM VALUE VALUES (16, 'AI_USE', 'Use AI features', NULL);


--
-- TOC entry 5917 (class 0 OID 23107)
-- Dependencies: 265
-- Data for Name: refunds; Type: TABLE DATA; Schema: public; Owner: postgres
--



--
-- TOC entry 5876 (class 0 OID 22365)
-- Dependencies: 224
-- Data for Name: role_permissions; Type: TABLE DATA; Schema: public; Owner: postgres
--

INSERT INTO public.role_permissions VALUES (1, 16);
INSERT INTO public.role_permissions VALUES (1, 15);
INSERT INTO public.role_permissions VALUES (1, 14);
INSERT INTO public.role_permissions VALUES (1, 13);
INSERT INTO public.role_permissions VALUES (1, 12);
INSERT INTO public.role_permissions VALUES (1, 11);
INSERT INTO public.role_permissions VALUES (1, 10);
INSERT INTO public.role_permissions VALUES (1, 9);
INSERT INTO public.role_permissions VALUES (1, 8);
INSERT INTO public.role_permissions VALUES (1, 7);
INSERT INTO public.role_permissions VALUES (1, 6);
INSERT INTO public.role_permissions VALUES (1, 5);
INSERT INTO public.role_permissions VALUES (1, 4);
INSERT INTO public.role_permissions VALUES (1, 3);
INSERT INTO public.role_permissions VALUES (1, 2);
INSERT INTO public.role_permissions VALUES (1, 1);
INSERT INTO public.role_permissions VALUES (2, 13);
INSERT INTO public.role_permissions VALUES (2, 12);
INSERT INTO public.role_permissions VALUES (2, 11);
INSERT INTO public.role_permissions VALUES (2, 10);
INSERT INTO public.role_permissions VALUES (2, 9);
INSERT INTO public.role_permissions VALUES (2, 8);
INSERT INTO public.role_permissions VALUES (2, 7);
INSERT INTO public.role_permissions VALUES (3, 16);
INSERT INTO public.role_permissions VALUES (3, 15);
INSERT INTO public.role_permissions VALUES (3, 14);
INSERT INTO public.role_permissions VALUES (3, 11);
INSERT INTO public.role_permissions VALUES (4, 16);


--
-- TOC entry 5873 (class 0 OID 22344)
-- Dependencies: 221
-- Data for Name: roles; Type: TABLE DATA; Schema: public; Owner: postgres
--

INSERT INTO public.roles OVERRIDING SYSTEM VALUE VALUES (1, 'MANAGER', 'Center Manager', 'Full management access');
INSERT INTO public.roles OVERRIDING SYSTEM VALUE VALUES (2, 'RECEPTIONIST', 'Receptionist', 'Front-desk operations');
INSERT INTO public.roles OVERRIDING SYSTEM VALUE VALUES (3, 'COACH', 'Coach', 'Teaches classes and tracks students');
INSERT INTO public.roles OVERRIDING SYSTEM VALUE VALUES (4, 'MEMBER', 'Member', 'Default role for public registration');


--
-- TOC entry 5886 (class 0 OID 22493)
-- Dependencies: 234
-- Data for Name: room_sports; Type: TABLE DATA; Schema: public; Owner: postgres
--

INSERT INTO public.room_sports VALUES (1, 1);
INSERT INTO public.room_sports VALUES (3, 2);
INSERT INTO public.room_sports VALUES (2, 2);
INSERT INTO public.room_sports VALUES (4, 3);
INSERT INTO public.room_sports VALUES (7, 4);
INSERT INTO public.room_sports VALUES (6, 4);
INSERT INTO public.room_sports VALUES (5, 4);
INSERT INTO public.room_sports VALUES (8, 5);


--
-- TOC entry 5883 (class 0 OID 22465)
-- Dependencies: 231
-- Data for Name: sport_categories; Type: TABLE DATA; Schema: public; Owner: postgres
--

INSERT INTO public.sport_categories OVERRIDING SYSTEM VALUE VALUES (1, 'Bơi lội', 'Bơi cơ bản, bơi nâng cao');
INSERT INTO public.sport_categories OVERRIDING SYSTEM VALUE VALUES (2, 'Yoga', 'Yoga thư giãn và cải thiện linh hoạt');
INSERT INTO public.sport_categories OVERRIDING SYSTEM VALUE VALUES (3, 'Bóng chuyền', 'Bóng chuyền cơ bản và thi đấu');
INSERT INTO public.sport_categories OVERRIDING SYSTEM VALUE VALUES (4, 'Thể lực', 'Tập luyện sức mạnh, thể lực tổng hợp');
INSERT INTO public.sport_categories OVERRIDING SYSTEM VALUE VALUES (5, 'Boxing', 'Boxing cơ bản và cardio');


--
-- TOC entry 5901 (class 0 OID 22780)
-- Dependencies: 249
-- Data for Name: sport_classes; Type: TABLE DATA; Schema: public; Owner: postgres
--

INSERT INTO public.sport_classes OVERRIDING SYSTEM VALUE VALUES (1, 1, 5, 'Bơi cơ bản - đợt 1', NULL, 15, 1200000, '2026-11-06', '2026-10-22', 'OPEN', '2026-10-23 18:14:43.228636+07', '2026-10-23 18:14:43.228636+07');
INSERT INTO public.sport_classes OVERRIDING SYSTEM VALUE VALUES (2, 2, 4, 'Yoga cơ bản - đợt 1', NULL, 12, 900000, '2026-11-02', '2026-10-22', 'OPEN', '2026-10-23 18:14:43.228636+07', '2026-10-23 18:14:43.228636+07');
INSERT INTO public.sport_classes OVERRIDING SYSTEM VALUE VALUES (3, 3, 3, 'Bóng chuyền cơ bản - đợt 1', NULL, 20, 800000, '2026-11-22', '2026-10-30', 'DRAFT', '2026-10-23 18:14:43.228636+07', '2026-10-23 18:14:43.228636+07');


--
-- TOC entry 5881 (class 0 OID 22453)
-- Dependencies: 229
-- Data for Name: staff_profiles; Type: TABLE DATA; Schema: public; Owner: postgres
--

INSERT INTO public.staff_profiles VALUES (1, 'Center Manager', '08:00-17:00');
INSERT INTO public.staff_profiles VALUES (2, 'Senior Receptionist', '08:00-19:00');


--
-- TOC entry 5893 (class 0 OID 22649)
-- Dependencies: 241
-- Data for Name: subscription_freezes; Type: TABLE DATA; Schema: public; Owner: postgres
--



--
-- TOC entry 5934 (class 0 OID 23348)
-- Dependencies: 282
-- Data for Name: support_requests; Type: TABLE DATA; Schema: public; Owner: postgres
--



--
-- TOC entry 5922 (class 0 OID 23188)
-- Dependencies: 270
-- Data for Name: system_settings; Type: TABLE DATA; Schema: public; Owner: postgres
--

INSERT INTO public.system_settings VALUES ('pricing.duration_discounts', '[{"pct": 0, "max_months": 2, "min_months": 1}, {"pct": 8, "max_months": 5, "min_months": 3}, {"pct": 15, "max_months": 11, "min_months": 6}, {"pct": 25, "max_months": 12, "min_months": 12}]', 'Bậc giảm giá theo số tháng mua gói (BR-PRC-01)', NULL, '2026-10-23 18:14:43.228636+07');
INSERT INTO public.system_settings VALUES ('coach.rank_prices', '{"COACH": 250000, "MASTER": 650000, "SENIOR": 400000}', 'Giá buổi 1-1 60 phút theo hạng coach (BR-ONE-02)', NULL, '2026-10-23 18:14:43.228636+07');
INSERT INTO public.system_settings VALUES ('auth.verify_link_minutes', '1440', 'Hiệu lực link xác thực email (BR-ACC-04)', NULL, '2026-10-23 18:14:43.228636+07');
INSERT INTO public.system_settings VALUES ('auth.reset_link_minutes', '30', 'Hiệu lực link đặt lại mật khẩu (BR-ACC-06)', NULL, '2026-10-23 18:14:43.228636+07');
INSERT INTO public.system_settings VALUES ('auth.verify_resend_cooldown_seconds', '60', 'Khoảng cách tối thiểu giữa hai lần gửi lại email xác thực (BR-ACC-07)', NULL, '2026-10-23 18:14:43.228636+07');
INSERT INTO public.system_settings VALUES ('auth.verify_resend_per_day', '5', 'Số lần gửi lại email xác thực tối đa mỗi ngày (BR-ACC-07)', NULL, '2026-10-23 18:14:43.228636+07');
INSERT INTO public.system_settings VALUES ('payment.hold_minutes', '15', 'Thời gian mã QR và giữ chỗ khi thanh toán (BR-QR-02, BR-ENR-03)', NULL, '2026-10-23 18:14:43.228636+07');
INSERT INTO public.system_settings VALUES ('waitlist.hold_hours', '12', 'Giữ chỗ cho người được báo có chỗ (BR-WL-03)', NULL, '2026-10-23 18:14:43.228636+07');
INSERT INTO public.system_settings VALUES ('waitlist.hold_hours_near_start', '3', 'Giữ chỗ khi còn dưới near_start_days đến khai giảng (BR-WL-03)', NULL, '2026-10-23 18:14:43.228636+07');
INSERT INTO public.system_settings VALUES ('waitlist.near_start_days', '3', 'Ngưỡng ngày coi là sát khai giảng (BR-WL-03)', NULL, '2026-10-23 18:14:43.228636+07');
INSERT INTO public.system_settings VALUES ('one_on_one.refund_before_hours', '24', 'Hủy 1-1 trước giờ học tối thiểu bao nhiêu giờ thì hoàn tiền (BR-ONE-04)', NULL, '2026-10-23 18:14:43.228636+07');
INSERT INTO public.system_settings VALUES ('schedule.buffer_minutes', '15', 'Khoảng nghỉ giữa hai buổi (BR-SCH-01). Chỉ để tham chiếu: constraint trong DB viết cứng 15 phút', NULL, '2026-10-23 18:14:43.228636+07');
INSERT INTO public.system_settings VALUES ('makeup.leave_threshold_pct', '30', 'Số buổi hủy vượt ngưỡng này thì học viên được rời class (BR-MKP-07)', NULL, '2026-10-23 18:14:43.228636+07');
INSERT INTO public.system_settings VALUES ('makeup.max_days', '30', 'Ngày bù cách buổi bị hủy quá số ngày này thì thay bằng bồi hoàn (BR-MKP-06)', NULL, '2026-10-23 18:14:43.228636+07');
INSERT INTO public.system_settings VALUES ('makeup.proposal_count', '3', 'Số ngày bù hệ thống đề xuất cho manager (BR-MKP-02)', NULL, '2026-10-23 18:14:43.228636+07');
INSERT INTO public.system_settings VALUES ('freeze.min_days', '2', 'Số ngày tối thiểu mỗi đợt bảo lưu (BR-FRZ-02)', NULL, '2026-10-23 18:14:43.228636+07');
INSERT INTO public.system_settings VALUES ('freeze.max_periods', '3', 'Số đợt bảo lưu tối đa mỗi kỳ gói (BR-FRZ-01)', NULL, '2026-10-23 18:14:43.228636+07');
INSERT INTO public.system_settings VALUES ('subscription.expiry_notice_days', '[3, 1]', 'Các mốc báo gói sắp hết hạn (BR-PKG-12)', NULL, '2026-10-23 18:14:43.228636+07');
INSERT INTO public.system_settings VALUES ('voucher.welcome', '{"kind": "PERCENT", "value": 10, "applies_to": "CLASS", "valid_days": 30}', 'Voucher chào mừng (BR-VCH-02)', NULL, '2026-10-23 18:14:43.228636+07');
INSERT INTO public.system_settings VALUES ('voucher.completion', '{"kind": "PERCENT", "value": 10, "applies_to": "CLASS", "valid_days": 60}', 'Voucher hoàn thành class (BR-VCH-02)', NULL, '2026-10-23 18:14:43.228636+07');
INSERT INTO public.system_settings VALUES ('voucher.compensation', '{"kind": "PERCENT", "value": 10, "applies_to": "CLASS", "valid_days": 30}', 'Voucher bù lỗi trung tâm (BR-VCH-02)', NULL, '2026-10-23 18:14:43.228636+07');
INSERT INTO public.system_settings VALUES ('voucher.birthday_valid_days', '30', 'Hạn dùng quà sinh nhật (BR-BEN-02)', NULL, '2026-10-23 18:14:43.228636+07');
INSERT INTO public.system_settings VALUES ('overview.notable_class', '{"low_fill_pct": 50, "near_start_days": 7, "canceled_sessions": 2}', 'Ngưỡng class đáng chú ý (BR-OVW-05)', NULL, '2026-10-23 18:14:43.228636+07');
INSERT INTO public.system_settings VALUES ('overview.overdue_refund_days', '3', 'Khoản chờ hoàn tiền quá số ngày này thì đánh dấu đỏ (BR-OVW-04)', NULL, '2026-10-23 18:14:43.228636+07');


--
-- TOC entry 5878 (class 0 OID 22383)
-- Dependencies: 226
-- Data for Name: users; Type: TABLE DATA; Schema: public; Owner: postgres
--

INSERT INTO public.users OVERRIDING SYSTEM VALUE VALUES (1, 1, 'Center Manager', 'manager@test.local', '0913448760', '$2a$10$8Rkk8SPAi2YXRQYBnss8OeT0rWzPf3vY9csZDYdaHjdi1rZdIlfVG', NULL, 'MALE', '1985-05-10', true, '2026-10-23 18:14:43.228636+07', NULL, NULL, NULL, NULL, '2026-10-23 18:14:43.228636+07', '2026-10-23 18:14:43.228636+07');
INSERT INTO public.users OVERRIDING SYSTEM VALUE VALUES (2, 2, 'Nguyễn Thị Lan', 'receptionist01@test.local', '0934567890', '$2a$10$8Rkk8SPAi2YXRQYBnss8OeT0rWzPf3vY9csZDYdaHjdi1rZdIlfVG', NULL, 'FEMALE', '1998-03-21', true, '2026-10-23 18:14:43.228636+07', NULL, NULL, NULL, NULL, '2026-10-23 18:14:43.228636+07', '2026-10-23 18:14:43.228636+07');
INSERT INTO public.users OVERRIDING SYSTEM VALUE VALUES (3, 3, 'Phạm Văn Thể Lực', 'coach03@test.local', '0987654323', '$2a$10$8Rkk8SPAi2YXRQYBnss8OeT0rWzPf3vY9csZDYdaHjdi1rZdIlfVG', NULL, 'MALE', '1988-01-30', true, '2026-10-23 18:14:43.228636+07', NULL, NULL, NULL, NULL, '2026-10-23 18:14:43.228636+07', '2026-10-23 18:14:43.228636+07');
INSERT INTO public.users OVERRIDING SYSTEM VALUE VALUES (4, 3, 'Lê Thị Yoga', 'coach02@test.local', '0987654322', '$2a$10$8Rkk8SPAi2YXRQYBnss8OeT0rWzPf3vY9csZDYdaHjdi1rZdIlfVG', NULL, 'FEMALE', '1992-11-15', true, '2026-10-23 18:14:43.228636+07', NULL, NULL, NULL, NULL, '2026-10-23 18:14:43.228636+07', '2026-10-23 18:14:43.228636+07');
INSERT INTO public.users OVERRIDING SYSTEM VALUE VALUES (5, 3, 'Trần Văn Bơi', 'coach01@test.local', '0987654321', '$2a$10$8Rkk8SPAi2YXRQYBnss8OeT0rWzPf3vY9csZDYdaHjdi1rZdIlfVG', NULL, 'MALE', '1990-07-02', true, '2026-10-23 18:14:43.228636+07', NULL, NULL, NULL, NULL, '2026-10-23 18:14:43.228636+07', '2026-10-23 18:14:43.228636+07');
INSERT INTO public.users OVERRIDING SYSTEM VALUE VALUES (6, 4, 'Lê Văn Chưa Xác Thực', 'member003@test.local', '0912312312', '$2a$10$8Rkk8SPAi2YXRQYBnss8OeT0rWzPf3vY9csZDYdaHjdi1rZdIlfVG', NULL, 'MALE', '2001-12-01', true, NULL, NULL, NULL, NULL, NULL, '2026-10-23 18:14:43.228636+07', '2026-10-23 18:14:43.228636+07');
INSERT INTO public.users OVERRIDING SYSTEM VALUE VALUES (7, 4, 'Trần Thị Bình', 'member002@test.local', '0933123321', '$2a$10$8Rkk8SPAi2YXRQYBnss8OeT0rWzPf3vY9csZDYdaHjdi1rZdIlfVG', NULL, 'FEMALE', '1999-09-09', true, '2026-10-23 18:14:43.228636+07', NULL, NULL, NULL, NULL, '2026-10-23 18:14:43.228636+07', '2026-10-23 18:14:43.228636+07');
INSERT INTO public.users OVERRIDING SYSTEM VALUE VALUES (8, 4, 'Nguyễn Văn An', 'member001@test.local', '0311231231', '$2a$10$8Rkk8SPAi2YXRQYBnss8OeT0rWzPf3vY9csZDYdaHjdi1rZdIlfVG', NULL, 'MALE', '2000-02-14', true, '2026-10-23 18:14:43.228636+07', NULL, NULL, NULL, NULL, '2026-10-23 18:14:43.228636+07', '2026-10-23 18:14:43.228636+07');


--
-- TOC entry 5895 (class 0 OID 22681)
-- Dependencies: 243
-- Data for Name: voucher_campaigns; Type: TABLE DATA; Schema: public; Owner: postgres
--

INSERT INTO public.voucher_campaigns OVERRIDING SYSTEM VALUE VALUES (1, 'KHAIGIANG10', 'PERCENT', 10, 'CLASS', 100, '2026-10-23 18:14:43.228636+07', '2026-12-22 18:14:43.228636+07', 1, '2026-10-23 18:14:43.228636+07');


--
-- TOC entry 5897 (class 0 OID 22709)
-- Dependencies: 245
-- Data for Name: vouchers; Type: TABLE DATA; Schema: public; Owner: postgres
--

INSERT INTO public.vouchers OVERRIDING SYSTEM VALUE VALUES (1, 8, 'WELCOME', 'PERCENT', 10, 'CLASS', NULL, '2026-10-23 18:14:43.228636+07', '2026-11-22 18:14:43.228636+07', NULL);


--
-- TOC entry 5928 (class 0 OID 23260)
-- Dependencies: 276
-- Data for Name: workout_plans; Type: TABLE DATA; Schema: public; Owner: postgres
--



--
-- TOC entry 5930 (class 0 OID 23294)
-- Dependencies: 278
-- Data for Name: workout_progress; Type: TABLE DATA; Schema: public; Owner: postgres
--



--
-- TOC entry 5941 (class 0 OID 0)
-- Dependencies: 279
-- Name: ai_chat_logs_id_seq; Type: SEQUENCE SET; Schema: public; Owner: postgres
--

SELECT pg_catalog.setval('public.ai_chat_logs_id_seq', 1, false);


--
-- TOC entry 5942 (class 0 OID 0)
-- Dependencies: 268
-- Name: audit_logs_id_seq; Type: SEQUENCE SET; Schema: public; Owner: postgres
--

SELECT pg_catalog.setval('public.audit_logs_id_seq', 1, true);


--
-- TOC entry 5943 (class 0 OID 0)
-- Dependencies: 271
-- Name: class_attendances_id_seq; Type: SEQUENCE SET; Schema: public; Owner: postgres
--

SELECT pg_catalog.setval('public.class_attendances_id_seq', 1, false);


--
-- TOC entry 5944 (class 0 OID 0)
-- Dependencies: 256
-- Name: class_evaluations_id_seq; Type: SEQUENCE SET; Schema: public; Owner: postgres
--

SELECT pg_catalog.setval('public.class_evaluations_id_seq', 1, false);


--
-- TOC entry 5945 (class 0 OID 0)
-- Dependencies: 254
-- Name: class_registrations_id_seq; Type: SEQUENCE SET; Schema: public; Owner: postgres
--

SELECT pg_catalog.setval('public.class_registrations_id_seq', 2, true);


--
-- TOC entry 5946 (class 0 OID 0)
-- Dependencies: 250
-- Name: class_schedules_id_seq; Type: SEQUENCE SET; Schema: public; Owner: postgres
--

SELECT pg_catalog.setval('public.class_schedules_id_seq', 6, true);


--
-- TOC entry 5947 (class 0 OID 0)
-- Dependencies: 252
-- Name: class_sessions_id_seq; Type: SEQUENCE SET; Schema: public; Owner: postgres
--

SELECT pg_catalog.setval('public.class_sessions_id_seq', 22, true);


--
-- TOC entry 5948 (class 0 OID 0)
-- Dependencies: 258
-- Name: coach_slots_id_seq; Type: SEQUENCE SET; Schema: public; Owner: postgres
--

SELECT pg_catalog.setval('public.coach_slots_id_seq', 4, true);


--
-- TOC entry 5949 (class 0 OID 0)
-- Dependencies: 246
-- Name: courses_id_seq; Type: SEQUENCE SET; Schema: public; Owner: postgres
--

SELECT pg_catalog.setval('public.courses_id_seq', 4, true);


--
-- TOC entry 5950 (class 0 OID 0)
-- Dependencies: 273
-- Name: facility_attendances_id_seq; Type: SEQUENCE SET; Schema: public; Owner: postgres
--

SELECT pg_catalog.setval('public.facility_attendances_id_seq', 1, false);


--
-- TOC entry 5951 (class 0 OID 0)
-- Dependencies: 232
-- Name: facility_rooms_id_seq; Type: SEQUENCE SET; Schema: public; Owner: postgres
--

SELECT pg_catalog.setval('public.facility_rooms_id_seq', 8, true);


--
-- TOC entry 5952 (class 0 OID 0)
-- Dependencies: 238
-- Name: member_subscriptions_id_seq; Type: SEQUENCE SET; Schema: public; Owner: postgres
--

SELECT pg_catalog.setval('public.member_subscriptions_id_seq', 1, true);


--
-- TOC entry 5953 (class 0 OID 0)
-- Dependencies: 236
-- Name: membership_packages_id_seq; Type: SEQUENCE SET; Schema: public; Owner: postgres
--

SELECT pg_catalog.setval('public.membership_packages_id_seq', 4, true);


--
-- TOC entry 5954 (class 0 OID 0)
-- Dependencies: 266
-- Name: notifications_id_seq; Type: SEQUENCE SET; Schema: public; Owner: postgres
--

SELECT pg_catalog.setval('public.notifications_id_seq', 1, true);


--
-- TOC entry 5955 (class 0 OID 0)
-- Dependencies: 260
-- Name: orders_id_seq; Type: SEQUENCE SET; Schema: public; Owner: postgres
--

SELECT pg_catalog.setval('public.orders_id_seq', 3, true);


--
-- TOC entry 5956 (class 0 OID 0)
-- Dependencies: 262
-- Name: payments_id_seq; Type: SEQUENCE SET; Schema: public; Owner: postgres
--

SELECT pg_catalog.setval('public.payments_id_seq', 3, true);


--
-- TOC entry 5957 (class 0 OID 0)
-- Dependencies: 222
-- Name: permissions_id_seq; Type: SEQUENCE SET; Schema: public; Owner: postgres
--

SELECT pg_catalog.setval('public.permissions_id_seq', 16, true);


--
-- TOC entry 5958 (class 0 OID 0)
-- Dependencies: 264
-- Name: refunds_id_seq; Type: SEQUENCE SET; Schema: public; Owner: postgres
--

SELECT pg_catalog.setval('public.refunds_id_seq', 1, false);


--
-- TOC entry 5959 (class 0 OID 0)
-- Dependencies: 220
-- Name: roles_id_seq; Type: SEQUENCE SET; Schema: public; Owner: postgres
--

SELECT pg_catalog.setval('public.roles_id_seq', 4, true);


--
-- TOC entry 5960 (class 0 OID 0)
-- Dependencies: 230
-- Name: sport_categories_id_seq; Type: SEQUENCE SET; Schema: public; Owner: postgres
--

SELECT pg_catalog.setval('public.sport_categories_id_seq', 5, true);


--
-- TOC entry 5961 (class 0 OID 0)
-- Dependencies: 248
-- Name: sport_classes_id_seq; Type: SEQUENCE SET; Schema: public; Owner: postgres
--

SELECT pg_catalog.setval('public.sport_classes_id_seq', 3, true);


--
-- TOC entry 5962 (class 0 OID 0)
-- Dependencies: 240
-- Name: subscription_freezes_id_seq; Type: SEQUENCE SET; Schema: public; Owner: postgres
--

SELECT pg_catalog.setval('public.subscription_freezes_id_seq', 1, false);


--
-- TOC entry 5963 (class 0 OID 0)
-- Dependencies: 281
-- Name: support_requests_id_seq; Type: SEQUENCE SET; Schema: public; Owner: postgres
--

SELECT pg_catalog.setval('public.support_requests_id_seq', 1, false);


--
-- TOC entry 5964 (class 0 OID 0)
-- Dependencies: 225
-- Name: users_id_seq; Type: SEQUENCE SET; Schema: public; Owner: postgres
--

SELECT pg_catalog.setval('public.users_id_seq', 8, true);


--
-- TOC entry 5965 (class 0 OID 0)
-- Dependencies: 242
-- Name: voucher_campaigns_id_seq; Type: SEQUENCE SET; Schema: public; Owner: postgres
--

SELECT pg_catalog.setval('public.voucher_campaigns_id_seq', 1, true);


--
-- TOC entry 5966 (class 0 OID 0)
-- Dependencies: 244
-- Name: vouchers_id_seq; Type: SEQUENCE SET; Schema: public; Owner: postgres
--

SELECT pg_catalog.setval('public.vouchers_id_seq', 1, true);


--
-- TOC entry 5967 (class 0 OID 0)
-- Dependencies: 275
-- Name: workout_plans_id_seq; Type: SEQUENCE SET; Schema: public; Owner: postgres
--

SELECT pg_catalog.setval('public.workout_plans_id_seq', 1, false);


--
-- TOC entry 5968 (class 0 OID 0)
-- Dependencies: 277
-- Name: workout_progress_id_seq; Type: SEQUENCE SET; Schema: public; Owner: postgres
--

SELECT pg_catalog.setval('public.workout_progress_id_seq', 1, false);


--
-- TOC entry 5640 (class 2606 OID 23340)
-- Name: ai_chat_logs ai_chat_logs_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.ai_chat_logs
    ADD CONSTRAINT ai_chat_logs_pkey PRIMARY KEY (id);


--
-- TOC entry 5619 (class 2606 OID 23180)
-- Name: audit_logs audit_logs_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.audit_logs
    ADD CONSTRAINT audit_logs_pkey PRIMARY KEY (id);


--
-- TOC entry 5625 (class 2606 OID 23216)
-- Name: class_attendances class_attendances_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.class_attendances
    ADD CONSTRAINT class_attendances_pkey PRIMARY KEY (id);


--
-- TOC entry 5583 (class 2606 OID 22942)
-- Name: class_evaluations class_evaluations_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.class_evaluations
    ADD CONSTRAINT class_evaluations_pkey PRIMARY KEY (id);


--
-- TOC entry 5585 (class 2606 OID 22944)
-- Name: class_evaluations class_evaluations_registration_id_key; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.class_evaluations
    ADD CONSTRAINT class_evaluations_registration_id_key UNIQUE (registration_id);


--
-- TOC entry 5577 (class 2606 OID 22908)
-- Name: class_registrations class_registrations_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.class_registrations
    ADD CONSTRAINT class_registrations_pkey PRIMARY KEY (id);


--
-- TOC entry 5564 (class 2606 OID 22831)
-- Name: class_schedules class_schedules_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.class_schedules
    ADD CONSTRAINT class_schedules_pkey PRIMARY KEY (id);


--
-- TOC entry 5567 (class 2606 OID 22867)
-- Name: class_sessions class_sessions_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.class_sessions
    ADD CONSTRAINT class_sessions_pkey PRIMARY KEY (id);


--
-- TOC entry 5510 (class 2606 OID 22447)
-- Name: coach_profiles coach_profiles_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.coach_profiles
    ADD CONSTRAINT coach_profiles_pkey PRIMARY KEY (user_id);


--
-- TOC entry 5587 (class 2606 OID 22976)
-- Name: coach_slots coach_slots_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.coach_slots
    ADD CONSTRAINT coach_slots_pkey PRIMARY KEY (id);


--
-- TOC entry 5524 (class 2606 OID 22516)
-- Name: coach_sports coach_sports_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.coach_sports
    ADD CONSTRAINT coach_sports_pkey PRIMARY KEY (coach_id, category_id);


--
-- TOC entry 5555 (class 2606 OID 22766)
-- Name: courses courses_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.courses
    ADD CONSTRAINT courses_pkey PRIMARY KEY (id);


--
-- TOC entry 5542 (class 2606 OID 22668)
-- Name: subscription_freezes ex_freeze_overlap; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.subscription_freezes
    ADD CONSTRAINT ex_freeze_overlap EXCLUDE USING gist (subscription_id WITH =, daterange(start_date, COALESCE(ended_on, end_date), '[]'::text) WITH &&) WHERE (((status)::text <> 'CANCELED'::text));


--
-- TOC entry 5569 (class 2606 OID 22871)
-- Name: class_sessions ex_session_coach_overlap; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.class_sessions
    ADD CONSTRAINT ex_session_coach_overlap EXCLUDE USING gist (coach_id WITH =, tsrange((session_date + start_time), ((session_date + end_time) + '00:15:00'::interval)) WITH &&) WHERE (((status)::text = ANY ((ARRAY['SCHEDULED'::character varying, 'DONE'::character varying])::text[])));


--
-- TOC entry 5571 (class 2606 OID 22873)
-- Name: class_sessions ex_session_room_overlap; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.class_sessions
    ADD CONSTRAINT ex_session_room_overlap EXCLUDE USING gist (room_id WITH =, tsrange((session_date + start_time), ((session_date + end_time) + '00:15:00'::interval)) WITH &&) WHERE (((status)::text = ANY ((ARRAY['SCHEDULED'::character varying, 'DONE'::character varying])::text[])));


--
-- TOC entry 5589 (class 2606 OID 22978)
-- Name: coach_slots ex_slot_coach_overlap; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.coach_slots
    ADD CONSTRAINT ex_slot_coach_overlap EXCLUDE USING gist (coach_id WITH =, tsrange(timezone('UTC'::text, start_at), (timezone('UTC'::text, end_at) + '00:15:00'::interval)) WITH &&) WHERE (((status)::text <> 'CANCELED'::text));


--
-- TOC entry 5630 (class 2606 OID 23247)
-- Name: facility_attendances facility_attendances_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.facility_attendances
    ADD CONSTRAINT facility_attendances_pkey PRIMARY KEY (id);


--
-- TOC entry 5518 (class 2606 OID 22490)
-- Name: facility_rooms facility_rooms_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.facility_rooms
    ADD CONSTRAINT facility_rooms_pkey PRIMARY KEY (id);


--
-- TOC entry 5520 (class 2606 OID 22492)
-- Name: facility_rooms facility_rooms_room_name_key; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.facility_rooms
    ADD CONSTRAINT facility_rooms_room_name_key UNIQUE (room_name);


--
-- TOC entry 5508 (class 2606 OID 22429)
-- Name: member_profiles member_profiles_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.member_profiles
    ADD CONSTRAINT member_profiles_pkey PRIMARY KEY (user_id);


--
-- TOC entry 5538 (class 2606 OID 22621)
-- Name: member_subscriptions member_subscriptions_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.member_subscriptions
    ADD CONSTRAINT member_subscriptions_pkey PRIMARY KEY (id);


--
-- TOC entry 5526 (class 2606 OID 22579)
-- Name: membership_packages membership_packages_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.membership_packages
    ADD CONSTRAINT membership_packages_pkey PRIMARY KEY (id);


--
-- TOC entry 5617 (class 2606 OID 23156)
-- Name: notifications notifications_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.notifications
    ADD CONSTRAINT notifications_pkey PRIMARY KEY (id);


--
-- TOC entry 5597 (class 2606 OID 23022)
-- Name: orders orders_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.orders
    ADD CONSTRAINT orders_pkey PRIMARY KEY (id);


--
-- TOC entry 5604 (class 2606 OID 23079)
-- Name: payments payments_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.payments
    ADD CONSTRAINT payments_pkey PRIMARY KEY (id);


--
-- TOC entry 5492 (class 2606 OID 22364)
-- Name: permissions permissions_code_key; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.permissions
    ADD CONSTRAINT permissions_code_key UNIQUE (code);


--
-- TOC entry 5494 (class 2606 OID 22362)
-- Name: permissions permissions_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.permissions
    ADD CONSTRAINT permissions_pkey PRIMARY KEY (id);


--
-- TOC entry 5614 (class 2606 OID 23124)
-- Name: refunds refunds_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.refunds
    ADD CONSTRAINT refunds_pkey PRIMARY KEY (id);


--
-- TOC entry 5496 (class 2606 OID 22371)
-- Name: role_permissions role_permissions_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.role_permissions
    ADD CONSTRAINT role_permissions_pkey PRIMARY KEY (role_id, permission_id);


--
-- TOC entry 5488 (class 2606 OID 22353)
-- Name: roles roles_code_key; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.roles
    ADD CONSTRAINT roles_code_key UNIQUE (code);


--
-- TOC entry 5490 (class 2606 OID 22351)
-- Name: roles roles_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.roles
    ADD CONSTRAINT roles_pkey PRIMARY KEY (id);


--
-- TOC entry 5522 (class 2606 OID 22499)
-- Name: room_sports room_sports_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.room_sports
    ADD CONSTRAINT room_sports_pkey PRIMARY KEY (room_id, category_id);


--
-- TOC entry 5514 (class 2606 OID 22475)
-- Name: sport_categories sport_categories_name_key; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.sport_categories
    ADD CONSTRAINT sport_categories_name_key UNIQUE (name);


--
-- TOC entry 5516 (class 2606 OID 22473)
-- Name: sport_categories sport_categories_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.sport_categories
    ADD CONSTRAINT sport_categories_pkey PRIMARY KEY (id);


--
-- TOC entry 5562 (class 2606 OID 22804)
-- Name: sport_classes sport_classes_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.sport_classes
    ADD CONSTRAINT sport_classes_pkey PRIMARY KEY (id);


--
-- TOC entry 5512 (class 2606 OID 22458)
-- Name: staff_profiles staff_profiles_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.staff_profiles
    ADD CONSTRAINT staff_profiles_pkey PRIMARY KEY (user_id);


--
-- TOC entry 5545 (class 2606 OID 22666)
-- Name: subscription_freezes subscription_freezes_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.subscription_freezes
    ADD CONSTRAINT subscription_freezes_pkey PRIMARY KEY (id);


--
-- TOC entry 5643 (class 2606 OID 23363)
-- Name: support_requests support_requests_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.support_requests
    ADD CONSTRAINT support_requests_pkey PRIMARY KEY (id);


--
-- TOC entry 5623 (class 2606 OID 23198)
-- Name: system_settings system_settings_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.system_settings
    ADD CONSTRAINT system_settings_pkey PRIMARY KEY (key);


--
-- TOC entry 5547 (class 2606 OID 22702)
-- Name: voucher_campaigns uq_campaign_code; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.voucher_campaigns
    ADD CONSTRAINT uq_campaign_code UNIQUE (code);


--
-- TOC entry 5628 (class 2606 OID 23218)
-- Name: class_attendances uq_class_attendance; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.class_attendances
    ADD CONSTRAINT uq_class_attendance UNIQUE (session_id, user_id);


--
-- TOC entry 5575 (class 2606 OID 22869)
-- Name: class_sessions uq_class_session_no; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.class_sessions
    ADD CONSTRAINT uq_class_session_no UNIQUE (class_id, session_no);


--
-- TOC entry 5557 (class 2606 OID 22768)
-- Name: courses uq_course_name; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.courses
    ADD CONSTRAINT uq_course_name UNIQUE (category_id, name);


--
-- TOC entry 5528 (class 2606 OID 22581)
-- Name: membership_packages uq_package_code; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.membership_packages
    ADD CONSTRAINT uq_package_code UNIQUE (code);


--
-- TOC entry 5530 (class 2606 OID 22583)
-- Name: membership_packages uq_package_name; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.membership_packages
    ADD CONSTRAINT uq_package_name UNIQUE (name);


--
-- TOC entry 5532 (class 2606 OID 22585)
-- Name: membership_packages uq_package_tier; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.membership_packages
    ADD CONSTRAINT uq_package_tier UNIQUE (tier_level);


--
-- TOC entry 5606 (class 2606 OID 23085)
-- Name: payments uq_payment_gateway_order; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.payments
    ADD CONSTRAINT uq_payment_gateway_order UNIQUE (gateway_order_code);


--
-- TOC entry 5608 (class 2606 OID 23081)
-- Name: payments uq_payment_invoice; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.payments
    ADD CONSTRAINT uq_payment_invoice UNIQUE (invoice_number);


--
-- TOC entry 5610 (class 2606 OID 23083)
-- Name: payments uq_payment_transaction; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.payments
    ADD CONSTRAINT uq_payment_transaction UNIQUE (transaction_code);


--
-- TOC entry 5499 (class 2606 OID 22406)
-- Name: users uq_users_email; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT uq_users_email UNIQUE (email);


--
-- TOC entry 5502 (class 2606 OID 22408)
-- Name: users uq_users_phone; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT uq_users_phone UNIQUE (phone);


--
-- TOC entry 5506 (class 2606 OID 22404)
-- Name: users users_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_pkey PRIMARY KEY (id);


--
-- TOC entry 5549 (class 2606 OID 22700)
-- Name: voucher_campaigns voucher_campaigns_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.voucher_campaigns
    ADD CONSTRAINT voucher_campaigns_pkey PRIMARY KEY (id);


--
-- TOC entry 5553 (class 2606 OID 22729)
-- Name: vouchers vouchers_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.vouchers
    ADD CONSTRAINT vouchers_pkey PRIMARY KEY (id);


--
-- TOC entry 5635 (class 2606 OID 23280)
-- Name: workout_plans workout_plans_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.workout_plans
    ADD CONSTRAINT workout_plans_pkey PRIMARY KEY (id);


--
-- TOC entry 5638 (class 2606 OID 23308)
-- Name: workout_progress workout_progress_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.workout_progress
    ADD CONSTRAINT workout_progress_pkey PRIMARY KEY (id);


--
-- TOC entry 5641 (class 1259 OID 23346)
-- Name: idx_ai_chat_user; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_ai_chat_user ON public.ai_chat_logs USING btree (user_id, created_at);


--
-- TOC entry 5620 (class 1259 OID 23187)
-- Name: idx_audit_created; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_audit_created ON public.audit_logs USING btree (created_at);


--
-- TOC entry 5621 (class 1259 OID 23186)
-- Name: idx_audit_user; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_audit_user ON public.audit_logs USING btree (user_id, created_at);


--
-- TOC entry 5626 (class 1259 OID 23234)
-- Name: idx_class_att_user; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_class_att_user ON public.class_attendances USING btree (user_id);


--
-- TOC entry 5558 (class 1259 OID 22816)
-- Name: idx_classes_coach; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_classes_coach ON public.sport_classes USING btree (coach_id);


--
-- TOC entry 5559 (class 1259 OID 22815)
-- Name: idx_classes_course; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_classes_course ON public.sport_classes USING btree (course_id);


--
-- TOC entry 5560 (class 1259 OID 22817)
-- Name: idx_classes_status_start; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_classes_status_start ON public.sport_classes USING btree (status, start_date);


--
-- TOC entry 5631 (class 1259 OID 23258)
-- Name: idx_facility_att_user; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_facility_att_user ON public.facility_attendances USING btree (user_id, check_in_time);


--
-- TOC entry 5543 (class 1259 OID 22679)
-- Name: idx_freeze_subscription; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_freeze_subscription ON public.subscription_freezes USING btree (subscription_id);


--
-- TOC entry 5615 (class 1259 OID 23167)
-- Name: idx_notification_recipient; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_notification_recipient ON public.notifications USING btree (recipient_id, is_read);


--
-- TOC entry 5592 (class 1259 OID 23053)
-- Name: idx_order_member; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_order_member ON public.orders USING btree (member_id, created_at);


--
-- TOC entry 5593 (class 1259 OID 23055)
-- Name: idx_order_registration; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_order_registration ON public.orders USING btree (registration_id) WHERE (registration_id IS NOT NULL);


--
-- TOC entry 5594 (class 1259 OID 23056)
-- Name: idx_order_slot; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_order_slot ON public.orders USING btree (coach_slot_id) WHERE (coach_slot_id IS NOT NULL);


--
-- TOC entry 5595 (class 1259 OID 23054)
-- Name: idx_order_subscription; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_order_subscription ON public.orders USING btree (subscription_id) WHERE (subscription_id IS NOT NULL);


--
-- TOC entry 5598 (class 1259 OID 23101)
-- Name: idx_payment_order; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_payment_order ON public.payments USING btree (order_id);


--
-- TOC entry 5599 (class 1259 OID 23103)
-- Name: idx_payment_paid_at; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_payment_paid_at ON public.payments USING btree (paid_at);


--
-- TOC entry 5600 (class 1259 OID 23102)
-- Name: idx_payment_payer; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_payment_payer ON public.payments USING btree (payer_id);


--
-- TOC entry 5601 (class 1259 OID 23104)
-- Name: idx_payment_pending_expiry; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_payment_pending_expiry ON public.payments USING btree (expires_at) WHERE ((status)::text = ANY ((ARRAY['CREATING'::character varying, 'PENDING'::character varying])::text[]));


--
-- TOC entry 5602 (class 1259 OID 23105)
-- Name: idx_payment_pending_refund; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_payment_pending_refund ON public.payments USING btree (paid_at) WHERE ((status)::text = 'PENDING_REFUND'::text);


--
-- TOC entry 5632 (class 1259 OID 23292)
-- Name: idx_plan_coach; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_plan_coach ON public.workout_plans USING btree (coach_id);


--
-- TOC entry 5633 (class 1259 OID 23291)
-- Name: idx_plan_member; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_plan_member ON public.workout_plans USING btree (member_id);


--
-- TOC entry 5636 (class 1259 OID 23324)
-- Name: idx_progress_member; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_progress_member ON public.workout_progress USING btree (member_id, recorded_date);


--
-- TOC entry 5611 (class 1259 OID 23136)
-- Name: idx_refund_payment; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_refund_payment ON public.refunds USING btree (payment_id);


--
-- TOC entry 5612 (class 1259 OID 23135)
-- Name: idx_refund_pending; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_refund_pending ON public.refunds USING btree (requested_at) WHERE ((status)::text = 'PENDING'::text);


--
-- TOC entry 5578 (class 1259 OID 22925)
-- Name: idx_reg_class_status; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_reg_class_status ON public.class_registrations USING btree (class_id, status);


--
-- TOC entry 5579 (class 1259 OID 22924)
-- Name: idx_reg_user; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_reg_user ON public.class_registrations USING btree (user_id);


--
-- TOC entry 5580 (class 1259 OID 22926)
-- Name: idx_reg_waitlist; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_reg_waitlist ON public.class_registrations USING btree (class_id, waitlist_rank DESC, registered_at) WHERE ((status)::text = 'WAITLISTED'::text);


--
-- TOC entry 5565 (class 1259 OID 22842)
-- Name: idx_schedules_class; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_schedules_class ON public.class_schedules USING btree (class_id);


--
-- TOC entry 5572 (class 1259 OID 22889)
-- Name: idx_session_class_date; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_session_class_date ON public.class_sessions USING btree (class_id, session_date);


--
-- TOC entry 5573 (class 1259 OID 22890)
-- Name: idx_session_date; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_session_date ON public.class_sessions USING btree (session_date) WHERE ((status)::text = 'SCHEDULED'::text);


--
-- TOC entry 5590 (class 1259 OID 22995)
-- Name: idx_slot_member; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_slot_member ON public.coach_slots USING btree (member_id) WHERE (member_id IS NOT NULL);


--
-- TOC entry 5591 (class 1259 OID 22994)
-- Name: idx_slot_open; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_slot_open ON public.coach_slots USING btree (coach_id, start_at) WHERE ((status)::text = 'OPEN'::text);


--
-- TOC entry 5533 (class 1259 OID 22645)
-- Name: idx_sub_end_date; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_sub_end_date ON public.member_subscriptions USING btree (end_date) WHERE ((status)::text = 'ACTIVE'::text);


--
-- TOC entry 5534 (class 1259 OID 22643)
-- Name: idx_sub_package; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_sub_package ON public.member_subscriptions USING btree (package_id);


--
-- TOC entry 5535 (class 1259 OID 22644)
-- Name: idx_sub_upgrade_from; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_sub_upgrade_from ON public.member_subscriptions USING btree (upgrade_from_subscription_id);


--
-- TOC entry 5536 (class 1259 OID 22642)
-- Name: idx_sub_user_status; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_sub_user_status ON public.member_subscriptions USING btree (user_id, status);


--
-- TOC entry 5497 (class 1259 OID 22414)
-- Name: idx_users_role; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_users_role ON public.users USING btree (role_id);


--
-- TOC entry 5550 (class 1259 OID 22740)
-- Name: idx_voucher_user; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_voucher_user ON public.vouchers USING btree (user_id, expires_at) WHERE (used_at IS NULL);


--
-- TOC entry 5581 (class 1259 OID 22927)
-- Name: uq_class_member; Type: INDEX; Schema: public; Owner: postgres
--

CREATE UNIQUE INDEX uq_class_member ON public.class_registrations USING btree (class_id, user_id) WHERE ((status)::text <> ALL ((ARRAY['CANCELED'::character varying, 'LEFT'::character varying])::text[]));


--
-- TOC entry 5539 (class 1259 OID 22646)
-- Name: uq_one_active_subscription; Type: INDEX; Schema: public; Owner: postgres
--

CREATE UNIQUE INDEX uq_one_active_subscription ON public.member_subscriptions USING btree (user_id) WHERE ((status)::text = 'ACTIVE'::text);


--
-- TOC entry 5540 (class 1259 OID 22647)
-- Name: uq_one_scheduled_subscription; Type: INDEX; Schema: public; Owner: postgres
--

CREATE UNIQUE INDEX uq_one_scheduled_subscription ON public.member_subscriptions USING btree (user_id) WHERE ((status)::text = 'SCHEDULED'::text);


--
-- TOC entry 5500 (class 1259 OID 22415)
-- Name: uq_users_email_lower; Type: INDEX; Schema: public; Owner: postgres
--

CREATE UNIQUE INDEX uq_users_email_lower ON public.users USING btree (lower((email)::text));


--
-- TOC entry 5503 (class 1259 OID 22417)
-- Name: uq_users_reset_token; Type: INDEX; Schema: public; Owner: postgres
--

CREATE UNIQUE INDEX uq_users_reset_token ON public.users USING btree (reset_token_hash) WHERE (reset_token_hash IS NOT NULL);


--
-- TOC entry 5504 (class 1259 OID 22416)
-- Name: uq_users_verify_token; Type: INDEX; Schema: public; Owner: postgres
--

CREATE UNIQUE INDEX uq_users_verify_token ON public.users USING btree (verify_token_hash) WHERE (verify_token_hash IS NOT NULL);


--
-- TOC entry 5551 (class 1259 OID 22741)
-- Name: uq_voucher_campaign_user; Type: INDEX; Schema: public; Owner: postgres
--

CREATE UNIQUE INDEX uq_voucher_campaign_user ON public.vouchers USING btree (campaign_id, user_id) WHERE (campaign_id IS NOT NULL);


--
-- TOC entry 5721 (class 2620 OID 23383)
-- Name: audit_logs trg_audit_logs_immutable; Type: TRIGGER; Schema: public; Owner: postgres
--

CREATE TRIGGER trg_audit_logs_immutable BEFORE DELETE OR UPDATE ON public.audit_logs FOR EACH ROW EXECUTE FUNCTION public.trg_audit_immutable();


--
-- TOC entry 5722 (class 2620 OID 23384)
-- Name: audit_logs trg_audit_logs_no_truncate; Type: TRIGGER; Schema: public; Owner: postgres
--

CREATE TRIGGER trg_audit_logs_no_truncate BEFORE TRUNCATE ON public.audit_logs FOR EACH STATEMENT EXECUTE FUNCTION public.trg_audit_immutable();


--
-- TOC entry 5718 (class 2620 OID 23394)
-- Name: class_registrations trg_class_registrations_check; Type: TRIGGER; Schema: public; Owner: postgres
--

CREATE TRIGGER trg_class_registrations_check BEFORE INSERT OR UPDATE OF status ON public.class_registrations FOR EACH ROW EXECUTE FUNCTION public.trg_check_registration();


--
-- TOC entry 5715 (class 2620 OID 23388)
-- Name: class_schedules trg_class_schedules_check; Type: TRIGGER; Schema: public; Owner: postgres
--

CREATE TRIGGER trg_class_schedules_check BEFORE INSERT OR DELETE OR UPDATE ON public.class_schedules FOR EACH ROW EXECUTE FUNCTION public.trg_check_class_schedule();


--
-- TOC entry 5716 (class 2620 OID 23390)
-- Name: class_sessions trg_class_sessions_check; Type: TRIGGER; Schema: public; Owner: postgres
--

CREATE TRIGGER trg_class_sessions_check BEFORE INSERT OR UPDATE ON public.class_sessions FOR EACH ROW EXECUTE FUNCTION public.trg_check_class_session();


--
-- TOC entry 5713 (class 2620 OID 23378)
-- Name: sport_classes trg_classes_updated; Type: TRIGGER; Schema: public; Owner: postgres
--

CREATE TRIGGER trg_classes_updated BEFORE UPDATE ON public.sport_classes FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();


--
-- TOC entry 5719 (class 2620 OID 23392)
-- Name: coach_slots trg_coach_slots_check; Type: TRIGGER; Schema: public; Owner: postgres
--

CREATE TRIGGER trg_coach_slots_check BEFORE INSERT OR DELETE OR UPDATE ON public.coach_slots FOR EACH ROW EXECUTE FUNCTION public.trg_check_coach_slot();


--
-- TOC entry 5712 (class 2620 OID 23377)
-- Name: courses trg_courses_updated; Type: TRIGGER; Schema: public; Owner: postgres
--

CREATE TRIGGER trg_courses_updated BEFORE UPDATE ON public.courses FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();


--
-- TOC entry 5711 (class 2620 OID 23396)
-- Name: subscription_freezes trg_freezes_check; Type: TRIGGER; Schema: public; Owner: postgres
--

CREATE TRIGGER trg_freezes_check BEFORE INSERT OR UPDATE ON public.subscription_freezes FOR EACH ROW EXECUTE FUNCTION public.trg_check_freeze();


--
-- TOC entry 5720 (class 2620 OID 23380)
-- Name: orders trg_orders_updated; Type: TRIGGER; Schema: public; Owner: postgres
--

CREATE TRIGGER trg_orders_updated BEFORE UPDATE ON public.orders FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();


--
-- TOC entry 5709 (class 2620 OID 23375)
-- Name: membership_packages trg_packages_updated; Type: TRIGGER; Schema: public; Owner: postgres
--

CREATE TRIGGER trg_packages_updated BEFORE UPDATE ON public.membership_packages FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();


--
-- TOC entry 5724 (class 2620 OID 23381)
-- Name: workout_plans trg_plans_updated; Type: TRIGGER; Schema: public; Owner: postgres
--

CREATE TRIGGER trg_plans_updated BEFORE UPDATE ON public.workout_plans FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();


--
-- TOC entry 5717 (class 2620 OID 23379)
-- Name: class_sessions trg_sessions_updated; Type: TRIGGER; Schema: public; Owner: postgres
--

CREATE TRIGGER trg_sessions_updated BEFORE UPDATE ON public.class_sessions FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();


--
-- TOC entry 5723 (class 2620 OID 23382)
-- Name: system_settings trg_settings_updated; Type: TRIGGER; Schema: public; Owner: postgres
--

CREATE TRIGGER trg_settings_updated BEFORE UPDATE ON public.system_settings FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();


--
-- TOC entry 5714 (class 2620 OID 23386)
-- Name: sport_classes trg_sport_classes_check; Type: TRIGGER; Schema: public; Owner: postgres
--

CREATE TRIGGER trg_sport_classes_check BEFORE INSERT OR UPDATE ON public.sport_classes FOR EACH ROW EXECUTE FUNCTION public.trg_check_sport_class();


--
-- TOC entry 5710 (class 2620 OID 23376)
-- Name: member_subscriptions trg_subscriptions_updated; Type: TRIGGER; Schema: public; Owner: postgres
--

CREATE TRIGGER trg_subscriptions_updated BEFORE UPDATE ON public.member_subscriptions FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();


--
-- TOC entry 5708 (class 2620 OID 23374)
-- Name: users trg_users_updated; Type: TRIGGER; Schema: public; Owner: postgres
--

CREATE TRIGGER trg_users_updated BEFORE UPDATE ON public.users FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();


--
-- TOC entry 5705 (class 2606 OID 23341)
-- Name: ai_chat_logs ai_chat_logs_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.ai_chat_logs
    ADD CONSTRAINT ai_chat_logs_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id);


--
-- TOC entry 5693 (class 2606 OID 23181)
-- Name: audit_logs audit_logs_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.audit_logs
    ADD CONSTRAINT audit_logs_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id);


--
-- TOC entry 5695 (class 2606 OID 23229)
-- Name: class_attendances class_attendances_recorded_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.class_attendances
    ADD CONSTRAINT class_attendances_recorded_by_fkey FOREIGN KEY (recorded_by) REFERENCES public.users(id);


--
-- TOC entry 5696 (class 2606 OID 23219)
-- Name: class_attendances class_attendances_session_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.class_attendances
    ADD CONSTRAINT class_attendances_session_id_fkey FOREIGN KEY (session_id) REFERENCES public.class_sessions(id) ON DELETE CASCADE;


--
-- TOC entry 5697 (class 2606 OID 23224)
-- Name: class_attendances class_attendances_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.class_attendances
    ADD CONSTRAINT class_attendances_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id);


--
-- TOC entry 5675 (class 2606 OID 22950)
-- Name: class_evaluations class_evaluations_coach_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.class_evaluations
    ADD CONSTRAINT class_evaluations_coach_id_fkey FOREIGN KEY (coach_id) REFERENCES public.users(id);


--
-- TOC entry 5676 (class 2606 OID 22945)
-- Name: class_evaluations class_evaluations_registration_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.class_evaluations
    ADD CONSTRAINT class_evaluations_registration_id_fkey FOREIGN KEY (registration_id) REFERENCES public.class_registrations(id);


--
-- TOC entry 5672 (class 2606 OID 22909)
-- Name: class_registrations class_registrations_class_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.class_registrations
    ADD CONSTRAINT class_registrations_class_id_fkey FOREIGN KEY (class_id) REFERENCES public.sport_classes(id);


--
-- TOC entry 5673 (class 2606 OID 22919)
-- Name: class_registrations class_registrations_registered_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.class_registrations
    ADD CONSTRAINT class_registrations_registered_by_fkey FOREIGN KEY (registered_by) REFERENCES public.users(id);


--
-- TOC entry 5674 (class 2606 OID 22914)
-- Name: class_registrations class_registrations_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.class_registrations
    ADD CONSTRAINT class_registrations_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id);


--
-- TOC entry 5667 (class 2606 OID 22832)
-- Name: class_schedules class_schedules_class_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.class_schedules
    ADD CONSTRAINT class_schedules_class_id_fkey FOREIGN KEY (class_id) REFERENCES public.sport_classes(id) ON DELETE CASCADE;


--
-- TOC entry 5668 (class 2606 OID 22837)
-- Name: class_schedules class_schedules_room_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.class_schedules
    ADD CONSTRAINT class_schedules_room_id_fkey FOREIGN KEY (room_id) REFERENCES public.facility_rooms(id);


--
-- TOC entry 5669 (class 2606 OID 22874)
-- Name: class_sessions class_sessions_class_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.class_sessions
    ADD CONSTRAINT class_sessions_class_id_fkey FOREIGN KEY (class_id) REFERENCES public.sport_classes(id) ON DELETE CASCADE;


--
-- TOC entry 5670 (class 2606 OID 22884)
-- Name: class_sessions class_sessions_coach_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.class_sessions
    ADD CONSTRAINT class_sessions_coach_id_fkey FOREIGN KEY (coach_id) REFERENCES public.users(id);


--
-- TOC entry 5671 (class 2606 OID 22879)
-- Name: class_sessions class_sessions_room_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.class_sessions
    ADD CONSTRAINT class_sessions_room_id_fkey FOREIGN KEY (room_id) REFERENCES public.facility_rooms(id);


--
-- TOC entry 5648 (class 2606 OID 22448)
-- Name: coach_profiles coach_profiles_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.coach_profiles
    ADD CONSTRAINT coach_profiles_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;


--
-- TOC entry 5677 (class 2606 OID 22989)
-- Name: coach_slots coach_slots_canceled_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.coach_slots
    ADD CONSTRAINT coach_slots_canceled_by_fkey FOREIGN KEY (canceled_by) REFERENCES public.users(id);


--
-- TOC entry 5678 (class 2606 OID 22979)
-- Name: coach_slots coach_slots_coach_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.coach_slots
    ADD CONSTRAINT coach_slots_coach_id_fkey FOREIGN KEY (coach_id) REFERENCES public.users(id);


--
-- TOC entry 5679 (class 2606 OID 22984)
-- Name: coach_slots coach_slots_member_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.coach_slots
    ADD CONSTRAINT coach_slots_member_id_fkey FOREIGN KEY (member_id) REFERENCES public.users(id);


--
-- TOC entry 5652 (class 2606 OID 22522)
-- Name: coach_sports coach_sports_category_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.coach_sports
    ADD CONSTRAINT coach_sports_category_id_fkey FOREIGN KEY (category_id) REFERENCES public.sport_categories(id);


--
-- TOC entry 5653 (class 2606 OID 22517)
-- Name: coach_sports coach_sports_coach_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.coach_sports
    ADD CONSTRAINT coach_sports_coach_id_fkey FOREIGN KEY (coach_id) REFERENCES public.users(id) ON DELETE CASCADE;


--
-- TOC entry 5663 (class 2606 OID 22769)
-- Name: courses courses_category_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.courses
    ADD CONSTRAINT courses_category_id_fkey FOREIGN KEY (category_id) REFERENCES public.sport_categories(id);


--
-- TOC entry 5664 (class 2606 OID 22774)
-- Name: courses courses_prerequisite_course_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.courses
    ADD CONSTRAINT courses_prerequisite_course_id_fkey FOREIGN KEY (prerequisite_course_id) REFERENCES public.courses(id);


--
-- TOC entry 5698 (class 2606 OID 23253)
-- Name: facility_attendances facility_attendances_recorded_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.facility_attendances
    ADD CONSTRAINT facility_attendances_recorded_by_fkey FOREIGN KEY (recorded_by) REFERENCES public.users(id);


--
-- TOC entry 5699 (class 2606 OID 23248)
-- Name: facility_attendances facility_attendances_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.facility_attendances
    ADD CONSTRAINT facility_attendances_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id);


--
-- TOC entry 5647 (class 2606 OID 22430)
-- Name: member_profiles member_profiles_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.member_profiles
    ADD CONSTRAINT member_profiles_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;


--
-- TOC entry 5654 (class 2606 OID 22637)
-- Name: member_subscriptions member_subscriptions_created_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.member_subscriptions
    ADD CONSTRAINT member_subscriptions_created_by_fkey FOREIGN KEY (created_by) REFERENCES public.users(id);


--
-- TOC entry 5655 (class 2606 OID 22627)
-- Name: member_subscriptions member_subscriptions_package_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.member_subscriptions
    ADD CONSTRAINT member_subscriptions_package_id_fkey FOREIGN KEY (package_id) REFERENCES public.membership_packages(id);


--
-- TOC entry 5656 (class 2606 OID 22632)
-- Name: member_subscriptions member_subscriptions_upgrade_from_subscription_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.member_subscriptions
    ADD CONSTRAINT member_subscriptions_upgrade_from_subscription_id_fkey FOREIGN KEY (upgrade_from_subscription_id) REFERENCES public.member_subscriptions(id);


--
-- TOC entry 5657 (class 2606 OID 22622)
-- Name: member_subscriptions member_subscriptions_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.member_subscriptions
    ADD CONSTRAINT member_subscriptions_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id);


--
-- TOC entry 5691 (class 2606 OID 23157)
-- Name: notifications notifications_recipient_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.notifications
    ADD CONSTRAINT notifications_recipient_id_fkey FOREIGN KEY (recipient_id) REFERENCES public.users(id) ON DELETE CASCADE;


--
-- TOC entry 5692 (class 2606 OID 23162)
-- Name: notifications notifications_sender_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.notifications
    ADD CONSTRAINT notifications_sender_id_fkey FOREIGN KEY (sender_id) REFERENCES public.users(id);


--
-- TOC entry 5680 (class 2606 OID 23043)
-- Name: orders orders_coach_slot_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.orders
    ADD CONSTRAINT orders_coach_slot_id_fkey FOREIGN KEY (coach_slot_id) REFERENCES public.coach_slots(id);


--
-- TOC entry 5681 (class 2606 OID 23028)
-- Name: orders orders_created_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.orders
    ADD CONSTRAINT orders_created_by_fkey FOREIGN KEY (created_by) REFERENCES public.users(id);


--
-- TOC entry 5682 (class 2606 OID 23023)
-- Name: orders orders_member_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.orders
    ADD CONSTRAINT orders_member_id_fkey FOREIGN KEY (member_id) REFERENCES public.users(id);


--
-- TOC entry 5683 (class 2606 OID 23038)
-- Name: orders orders_registration_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.orders
    ADD CONSTRAINT orders_registration_id_fkey FOREIGN KEY (registration_id) REFERENCES public.class_registrations(id);


--
-- TOC entry 5684 (class 2606 OID 23033)
-- Name: orders orders_subscription_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.orders
    ADD CONSTRAINT orders_subscription_id_fkey FOREIGN KEY (subscription_id) REFERENCES public.member_subscriptions(id);


--
-- TOC entry 5685 (class 2606 OID 23048)
-- Name: orders orders_voucher_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.orders
    ADD CONSTRAINT orders_voucher_id_fkey FOREIGN KEY (voucher_id) REFERENCES public.vouchers(id);


--
-- TOC entry 5686 (class 2606 OID 23086)
-- Name: payments payments_order_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.payments
    ADD CONSTRAINT payments_order_id_fkey FOREIGN KEY (order_id) REFERENCES public.orders(id);


--
-- TOC entry 5687 (class 2606 OID 23091)
-- Name: payments payments_payer_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.payments
    ADD CONSTRAINT payments_payer_id_fkey FOREIGN KEY (payer_id) REFERENCES public.users(id);


--
-- TOC entry 5688 (class 2606 OID 23096)
-- Name: payments payments_received_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.payments
    ADD CONSTRAINT payments_received_by_fkey FOREIGN KEY (received_by) REFERENCES public.users(id);


--
-- TOC entry 5689 (class 2606 OID 23125)
-- Name: refunds refunds_payment_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.refunds
    ADD CONSTRAINT refunds_payment_id_fkey FOREIGN KEY (payment_id) REFERENCES public.payments(id);


--
-- TOC entry 5690 (class 2606 OID 23130)
-- Name: refunds refunds_processed_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.refunds
    ADD CONSTRAINT refunds_processed_by_fkey FOREIGN KEY (processed_by) REFERENCES public.users(id);


--
-- TOC entry 5644 (class 2606 OID 22377)
-- Name: role_permissions role_permissions_permission_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.role_permissions
    ADD CONSTRAINT role_permissions_permission_id_fkey FOREIGN KEY (permission_id) REFERENCES public.permissions(id) ON DELETE CASCADE;


--
-- TOC entry 5645 (class 2606 OID 22372)
-- Name: role_permissions role_permissions_role_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.role_permissions
    ADD CONSTRAINT role_permissions_role_id_fkey FOREIGN KEY (role_id) REFERENCES public.roles(id) ON DELETE CASCADE;


--
-- TOC entry 5650 (class 2606 OID 22505)
-- Name: room_sports room_sports_category_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.room_sports
    ADD CONSTRAINT room_sports_category_id_fkey FOREIGN KEY (category_id) REFERENCES public.sport_categories(id);


--
-- TOC entry 5651 (class 2606 OID 22500)
-- Name: room_sports room_sports_room_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.room_sports
    ADD CONSTRAINT room_sports_room_id_fkey FOREIGN KEY (room_id) REFERENCES public.facility_rooms(id) ON DELETE CASCADE;


--
-- TOC entry 5665 (class 2606 OID 22810)
-- Name: sport_classes sport_classes_coach_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.sport_classes
    ADD CONSTRAINT sport_classes_coach_id_fkey FOREIGN KEY (coach_id) REFERENCES public.users(id);


--
-- TOC entry 5666 (class 2606 OID 22805)
-- Name: sport_classes sport_classes_course_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.sport_classes
    ADD CONSTRAINT sport_classes_course_id_fkey FOREIGN KEY (course_id) REFERENCES public.courses(id);


--
-- TOC entry 5649 (class 2606 OID 22459)
-- Name: staff_profiles staff_profiles_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.staff_profiles
    ADD CONSTRAINT staff_profiles_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;


--
-- TOC entry 5658 (class 2606 OID 22674)
-- Name: subscription_freezes subscription_freezes_created_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.subscription_freezes
    ADD CONSTRAINT subscription_freezes_created_by_fkey FOREIGN KEY (created_by) REFERENCES public.users(id);


--
-- TOC entry 5659 (class 2606 OID 22669)
-- Name: subscription_freezes subscription_freezes_subscription_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.subscription_freezes
    ADD CONSTRAINT subscription_freezes_subscription_id_fkey FOREIGN KEY (subscription_id) REFERENCES public.member_subscriptions(id);


--
-- TOC entry 5706 (class 2606 OID 23369)
-- Name: support_requests support_requests_handled_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.support_requests
    ADD CONSTRAINT support_requests_handled_by_fkey FOREIGN KEY (handled_by) REFERENCES public.users(id);


--
-- TOC entry 5707 (class 2606 OID 23364)
-- Name: support_requests support_requests_member_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.support_requests
    ADD CONSTRAINT support_requests_member_id_fkey FOREIGN KEY (member_id) REFERENCES public.users(id);


--
-- TOC entry 5694 (class 2606 OID 23199)
-- Name: system_settings system_settings_updated_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.system_settings
    ADD CONSTRAINT system_settings_updated_by_fkey FOREIGN KEY (updated_by) REFERENCES public.users(id);


--
-- TOC entry 5646 (class 2606 OID 22409)
-- Name: users users_role_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_role_id_fkey FOREIGN KEY (role_id) REFERENCES public.roles(id);


--
-- TOC entry 5660 (class 2606 OID 22703)
-- Name: voucher_campaigns voucher_campaigns_created_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.voucher_campaigns
    ADD CONSTRAINT voucher_campaigns_created_by_fkey FOREIGN KEY (created_by) REFERENCES public.users(id);


--
-- TOC entry 5661 (class 2606 OID 22735)
-- Name: vouchers vouchers_campaign_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.vouchers
    ADD CONSTRAINT vouchers_campaign_id_fkey FOREIGN KEY (campaign_id) REFERENCES public.voucher_campaigns(id);


--
-- TOC entry 5662 (class 2606 OID 22730)
-- Name: vouchers vouchers_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.vouchers
    ADD CONSTRAINT vouchers_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id);


--
-- TOC entry 5700 (class 2606 OID 23286)
-- Name: workout_plans workout_plans_coach_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.workout_plans
    ADD CONSTRAINT workout_plans_coach_id_fkey FOREIGN KEY (coach_id) REFERENCES public.users(id);


--
-- TOC entry 5701 (class 2606 OID 23281)
-- Name: workout_plans workout_plans_member_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.workout_plans
    ADD CONSTRAINT workout_plans_member_id_fkey FOREIGN KEY (member_id) REFERENCES public.users(id);


--
-- TOC entry 5702 (class 2606 OID 23314)
-- Name: workout_progress workout_progress_member_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.workout_progress
    ADD CONSTRAINT workout_progress_member_id_fkey FOREIGN KEY (member_id) REFERENCES public.users(id);


--
-- TOC entry 5703 (class 2606 OID 23309)
-- Name: workout_progress workout_progress_plan_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.workout_progress
    ADD CONSTRAINT workout_progress_plan_id_fkey FOREIGN KEY (plan_id) REFERENCES public.workout_plans(id) ON DELETE SET NULL;


--
-- TOC entry 5704 (class 2606 OID 23319)
-- Name: workout_progress workout_progress_recorded_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.workout_progress
    ADD CONSTRAINT workout_progress_recorded_by_fkey FOREIGN KEY (recorded_by) REFERENCES public.users(id);


-- Completed on 2026-10-23 18:24:10

--
-- PostgreSQL database dump complete
--

\unrestrict 44e1kvbwn4FSzzKClxKGTEyfuZtfpdyg4jub3qhIJMo50nmmWfA8xLSrmeWnh1F

