-- =====================================================================
-- SCMS - one-shot database setup (drop + create + schema + seed data)
-- WARNING: deletes ALL existing data in scms_database. Stop the app first.
-- Usage (from repo root, any OS):
--   psql -U postgres -d postgres -v ON_ERROR_STOP=1 -f database/scms_setup.sql
-- =====================================================================
DROP DATABASE IF EXISTS scms_database WITH (FORCE);
CREATE DATABASE scms_database;
\connect scms_database

-- =====================================================================
-- SCMS database - merged schema (team dump + Backend1 changes)
-- Usage: DROP DATABASE scms_database; CREATE DATABASE scms_database;
--        then run this file once (psql -U postgres -d scms_database -f <file>)
-- Changes on top of the team's scms_database.sql:
--   membership_packages : + tier_level
--   member_subscriptions: + duration_months_snapshot, tier_level_snapshot,
--                         purchase_type, upgrade_from_subscription_id,
--                         upgrade_credit_amount, payment_expires_at;
--                         statuses + SCHEDULED, UPGRADED
--   payments            : + gateway_order_code (unique), payment_url, expires_at;
--                         status + CREATING
--   removed             : trg_payments_activate / trg_activate_subscription
--                         (subscription activation is done in PaymentService)
-- =====================================================================

--
-- PostgreSQL database dump
--


-- Dumped from database version 18.6
-- Dumped by pg_dump version 18.6

-- Started on 2026-10-16 18:22:31

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
-- TOC entry 2 (class 3079 OID 18127)
-- Name: btree_gist; Type: EXTENSION; Schema: -; Owner: -
--

CREATE EXTENSION IF NOT EXISTS btree_gist WITH SCHEMA public;


--
-- TOC entry 5660 (class 0 OID 0)
-- Dependencies: 2
-- Name: EXTENSION btree_gist; Type: COMMENT; Schema: -; Owner: 
--

COMMENT ON EXTENSION btree_gist IS 'support for indexing common datatypes in GiST';


--
-- TOC entry 465 (class 1255 OID 18853)
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
-- TOC entry 378 (class 1255 OID 19440)
-- Name: trg_audit_immutable(); Type: FUNCTION; Schema: public; Owner: postgres
--

CREATE FUNCTION public.trg_audit_immutable() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    RAISE EXCEPTION 'BR-SYS-01: audit_logs is append-only';
END;
$$;


ALTER FUNCTION public.trg_audit_immutable() OWNER TO postgres;

--
-- TOC entry 318 (class 1255 OID 19063)
-- Name: trg_check_class_schedule(); Type: FUNCTION; Schema: public; Owner: postgres
--

CREATE FUNCTION public.trg_check_class_schedule() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    v_room_cap  INTEGER;
    v_max_cap   INTEGER;
    v_coach     BIGINT;
BEGIN
    SELECT capacity INTO v_room_cap FROM facility_rooms WHERE id = NEW.room_id;
    SELECT max_capacity, coach_id INTO v_max_cap, v_coach FROM sport_classes WHERE id = NEW.class_id;

    IF v_max_cap > v_room_cap THEN
        RAISE EXCEPTION 'BR-CLS-01: class max_capacity (%) exceeds room capacity (%)', v_max_cap, v_room_cap;
    END IF;

    IF EXISTS (
        SELECT 1
        FROM class_schedules cs
        JOIN sport_classes c ON c.id = cs.class_id
        WHERE c.coach_id = v_coach
          AND cs.id <> COALESCE(NEW.id, -1)
          AND cs.day_of_week = NEW.day_of_week
          AND cs.start_time < NEW.end_time
          AND cs.end_time   > NEW.start_time
    ) THEN
        RAISE EXCEPTION 'BR-CLS-02: coach already has a class in this time slot';
    END IF;
    RETURN NEW;
END;
$$;


ALTER FUNCTION public.trg_check_class_schedule() OWNER TO postgres;

--
-- TOC entry 421 (class 1255 OID 19218)
-- Name: trg_check_registration(); Type: FUNCTION; Schema: public; Owner: postgres
--

CREATE FUNCTION public.trg_check_registration() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    v_cap     INTEGER;
    v_count   INTEGER;
    v_weekly  SMALLINT;
    v_used    INTEGER;
BEGIN
    IF NEW.status <> 'REGISTERED' THEN
        RETURN NEW;
    END IF;
    IF TG_OP = 'UPDATE' AND OLD.status = 'REGISTERED' THEN
        RETURN NEW;
    END IF;

    SELECT max_classes_per_week_snapshot INTO v_weekly
    FROM member_subscriptions
    WHERE user_id = NEW.user_id AND status = 'ACTIVE' AND CURRENT_DATE BETWEEN start_date AND end_date;
    IF v_weekly IS NULL THEN
        RAISE EXCEPTION 'BR-BKG-01: member has no ACTIVE membership';
    END IF;

    SELECT max_capacity INTO v_cap FROM sport_classes WHERE id = NEW.class_id FOR UPDATE;
    SELECT count(*) INTO v_count FROM class_registrations
     WHERE class_id = NEW.class_id AND status = 'REGISTERED' AND id <> COALESCE(NEW.id, -1);
    IF v_count >= v_cap THEN
        RAISE EXCEPTION 'BR-BKG-02: class is full';
    END IF;

    SELECT count(*) INTO v_used FROM class_registrations
     WHERE user_id = NEW.user_id AND status = 'REGISTERED'
       AND id <> COALESCE(NEW.id, -1)
       AND date_trunc('week', registered_at) = date_trunc('week', now());
    IF v_used >= v_weekly THEN
        RAISE EXCEPTION 'BR-BKG-03: weekly class limit (%) reached', v_weekly;
    END IF;
    RETURN NEW;
END;
$$;


ALTER FUNCTION public.trg_check_registration() OWNER TO postgres;

--
-- TOC entry 376 (class 1255 OID 19065)
-- Name: trg_check_sport_class(); Type: FUNCTION; Schema: public; Owner: postgres
--

CREATE FUNCTION public.trg_check_sport_class() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM users u JOIN roles r ON r.id = u.role_id
        WHERE u.id = NEW.coach_id AND r.code = 'COACH'
    ) THEN
        RAISE EXCEPTION 'coach_id % does not belong to a user with role COACH', NEW.coach_id;
    END IF;

    IF TG_OP = 'UPDATE' AND EXISTS (
        SELECT 1 FROM class_schedules cs JOIN facility_rooms fr ON fr.id = cs.room_id
        WHERE cs.class_id = NEW.id AND NEW.max_capacity > fr.capacity
    ) THEN
        RAISE EXCEPTION 'BR-CLS-01: max_capacity exceeds capacity of an assigned room';
    END IF;
    RETURN NEW;
END;
$$;


ALTER FUNCTION public.trg_check_sport_class() OWNER TO postgres;

SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- TOC entry 259 (class 1259 OID 19399)
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
-- TOC entry 258 (class 1259 OID 19398)
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
-- TOC entry 261 (class 1259 OID 19421)
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
-- TOC entry 260 (class 1259 OID 19420)
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
-- TOC entry 247 (class 1259 OID 19221)
-- Name: class_attendances; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.class_attendances (
    id bigint NOT NULL,
    class_id bigint NOT NULL,
    user_id bigint NOT NULL,
    session_date date NOT NULL,
    status character varying(10) NOT NULL,
    check_in_time timestamp with time zone,
    recorded_by bigint,
    notes character varying(500),
    CONSTRAINT ck_attendance_status CHECK (((status)::text = ANY ((ARRAY['PRESENT'::character varying, 'ABSENT'::character varying, 'LATE'::character varying, 'EXCUSED'::character varying])::text[])))
);


ALTER TABLE public.class_attendances OWNER TO postgres;

--
-- TOC entry 246 (class 1259 OID 19220)
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
-- TOC entry 245 (class 1259 OID 19185)
-- Name: class_registrations; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.class_registrations (
    id bigint NOT NULL,
    class_id bigint NOT NULL,
    user_id bigint NOT NULL,
    registered_at timestamp with time zone DEFAULT now() NOT NULL,
    status character varying(20) DEFAULT 'REGISTERED'::character varying NOT NULL,
    canceled_at timestamp with time zone,
    is_penalized boolean DEFAULT false NOT NULL,
    registered_by bigint,
    CONSTRAINT ck_reg_status CHECK (((status)::text = ANY ((ARRAY['REGISTERED'::character varying, 'WAITLISTED'::character varying, 'CANCELED'::character varying, 'LATE_CANCELED'::character varying])::text[])))
);


ALTER TABLE public.class_registrations OWNER TO postgres;

--
-- TOC entry 244 (class 1259 OID 19184)
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
-- TOC entry 237 (class 1259 OID 19037)
-- Name: class_schedules; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.class_schedules (
    id bigint NOT NULL,
    class_id bigint NOT NULL,
    room_id bigint NOT NULL,
    day_of_week smallint NOT NULL,
    start_time time without time zone NOT NULL,
    end_time time without time zone NOT NULL,
    CONSTRAINT ck_schedule_time CHECK ((end_time > start_time)),
    CONSTRAINT class_schedules_day_of_week_check CHECK (((day_of_week >= 1) AND (day_of_week <= 7)))
);


ALTER TABLE public.class_schedules OWNER TO postgres;

--
-- TOC entry 236 (class 1259 OID 19036)
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
-- TOC entry 228 (class 1259 OID 18944)
-- Name: coach_profiles; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.coach_profiles (
    user_id bigint NOT NULL,
    specialty character varying(255),
    years_experience smallint,
    certifications text,
    rating numeric(2,1),
    CONSTRAINT coach_profiles_rating_check CHECK (((rating >= (0)::numeric) AND (rating <= (5)::numeric))),
    CONSTRAINT coach_profiles_years_experience_check CHECK ((years_experience >= 0))
);


ALTER TABLE public.coach_profiles OWNER TO postgres;

--
-- TOC entry 249 (class 1259 OID 19253)
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
-- TOC entry 248 (class 1259 OID 19252)
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
-- TOC entry 233 (class 1259 OID 18983)
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
-- TOC entry 232 (class 1259 OID 18982)
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
-- TOC entry 227 (class 1259 OID 18927)
-- Name: member_profiles; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.member_profiles (
    user_id bigint NOT NULL,
    join_date date DEFAULT CURRENT_DATE NOT NULL,
    height_cm numeric(5,2),
    weight_kg numeric(5,2),
    fitness_goal character varying(500),
    membership_tier character varying(30),
    CONSTRAINT member_profiles_height_cm_check CHECK ((height_cm > (0)::numeric)),
    CONSTRAINT member_profiles_weight_kg_check CHECK ((weight_kg > (0)::numeric))
);


ALTER TABLE public.member_profiles OWNER TO postgres;

--
-- TOC entry 241 (class 1259 OID 19097)
-- Name: member_subscriptions; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.member_subscriptions (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    package_id bigint NOT NULL,
    package_name_snapshot character varying(150) NOT NULL,
    duration_months_snapshot smallint NOT NULL,
    tier_level_snapshot smallint NOT NULL,
    purchase_type character varying(10) DEFAULT 'NEW'::character varying NOT NULL,
    upgrade_from_subscription_id bigint,
    upgrade_credit_amount numeric(12,0) DEFAULT 0 NOT NULL,
    payment_expires_at timestamp with time zone,
    max_classes_per_week_snapshot smallint NOT NULL,
    total_amount numeric(12,0) NOT NULL,
    start_date date,
    end_date date,
    status character varying(20) DEFAULT 'PENDING'::character varying NOT NULL,
    created_by bigint,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT ck_sub_active_dates CHECK ((((status)::text <> 'ACTIVE'::text) OR ((start_date IS NOT NULL) AND (end_date IS NOT NULL)))),
    CONSTRAINT ck_sub_dates CHECK (((end_date IS NULL) OR (start_date IS NULL) OR (end_date > start_date))),
    CONSTRAINT ck_sub_status CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'SCHEDULED'::character varying, 'ACTIVE'::character varying, 'EXPIRED'::character varying, 'UPGRADED'::character varying, 'CANCELED'::character varying])::text[]))),
    CONSTRAINT ck_sub_purchase_type CHECK (((purchase_type)::text = ANY ((ARRAY['NEW'::character varying, 'RENEWAL'::character varying, 'UPGRADE'::character varying])::text[]))),
    CONSTRAINT ck_sub_tier_snapshot CHECK ((tier_level_snapshot >= 1)),
    CONSTRAINT ck_sub_upgrade_credit CHECK ((upgrade_credit_amount >= (0)::numeric)),
    CONSTRAINT member_subscriptions_total_amount_check CHECK ((total_amount >= (0)::numeric))
);


ALTER TABLE public.member_subscriptions OWNER TO postgres;

--
-- TOC entry 240 (class 1259 OID 19096)
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
-- TOC entry 239 (class 1259 OID 19068)
-- Name: membership_packages; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.membership_packages (
    id bigint NOT NULL,
    code character varying(30) NOT NULL,
    name character varying(150) NOT NULL,
    description text,
    duration_months smallint NOT NULL,
    price numeric(12,0) NOT NULL,
    max_classes_per_week smallint DEFAULT 3 NOT NULL,
    tier_level smallint DEFAULT 1 NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT ck_package_duration CHECK ((duration_months >= 1)),
    CONSTRAINT ck_package_price CHECK ((price >= (0)::numeric)),
    CONSTRAINT ck_package_tier CHECK ((tier_level >= 1)),
    CONSTRAINT ck_package_weekly CHECK ((max_classes_per_week >= 0))
);


ALTER TABLE public.membership_packages OWNER TO postgres;

--
-- TOC entry 238 (class 1259 OID 19067)
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
-- TOC entry 255 (class 1259 OID 19344)
-- Name: notifications; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.notifications (
    id bigint NOT NULL,
    recipient_id bigint NOT NULL,
    sender_id bigint,
    type character varying(30) NOT NULL,
    title character varying(200) NOT NULL,
    content text,
    is_read boolean DEFAULT false NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT ck_notification_type CHECK (((type)::text = ANY ((ARRAY['ANNOUNCEMENT'::character varying, 'HOMEWORK'::character varying, 'WORKOUT_PLAN'::character varying, 'MEMBERSHIP'::character varying, 'BOOKING'::character varying, 'SYSTEM'::character varying])::text[])))
);


ALTER TABLE public.notifications OWNER TO postgres;

--
-- TOC entry 254 (class 1259 OID 19343)
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
-- TOC entry 243 (class 1259 OID 19138)
-- Name: payments; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.payments (
    id bigint NOT NULL,
    subscription_id bigint NOT NULL,
    payer_id bigint NOT NULL,
    received_by bigint,
    invoice_number character varying(30) NOT NULL,
    transaction_code character varying(100) NOT NULL,
    amount numeric(12,0) NOT NULL,
    payment_method character varying(20) NOT NULL,
    status character varying(20) DEFAULT 'PENDING'::character varying NOT NULL,
    paid_at timestamp with time zone,
    gateway_response text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    gateway_order_code bigint,
    payment_url text,
    expires_at timestamp with time zone,
    CONSTRAINT ck_payment_method CHECK (((payment_method)::text = ANY ((ARRAY['CASH'::character varying, 'BANK_TRANSFER'::character varying, 'QR_GATEWAY'::character varying])::text[]))),
    CONSTRAINT ck_payment_paid_at CHECK ((((status)::text <> 'SUCCESS'::text) OR (paid_at IS NOT NULL))),
    CONSTRAINT ck_payment_status CHECK (((status)::text = ANY ((ARRAY['CREATING'::character varying, 'PENDING'::character varying, 'SUCCESS'::character varying, 'FAILED'::character varying, 'CANCELED'::character varying, 'REFUNDED'::character varying])::text[]))),
    CONSTRAINT payments_amount_check CHECK ((amount > (0)::numeric))
);


ALTER TABLE public.payments OWNER TO postgres;

--
-- TOC entry 242 (class 1259 OID 19137)
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
-- TOC entry 223 (class 1259 OID 18866)
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
-- TOC entry 222 (class 1259 OID 18865)
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
-- TOC entry 224 (class 1259 OID 18876)
-- Name: role_permissions; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.role_permissions (
    role_id bigint NOT NULL,
    permission_id bigint NOT NULL
);


ALTER TABLE public.role_permissions OWNER TO postgres;

--
-- TOC entry 221 (class 1259 OID 18855)
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
-- TOC entry 220 (class 1259 OID 18854)
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
-- TOC entry 231 (class 1259 OID 18971)
-- Name: sport_categories; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.sport_categories (
    id bigint NOT NULL,
    name character varying(100) NOT NULL,
    description character varying(500)
);


ALTER TABLE public.sport_categories OWNER TO postgres;

--
-- TOC entry 230 (class 1259 OID 18970)
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
-- TOC entry 235 (class 1259 OID 19000)
-- Name: sport_classes; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.sport_classes (
    id bigint NOT NULL,
    category_id bigint NOT NULL,
    coach_id bigint NOT NULL,
    name character varying(150) NOT NULL,
    description text,
    level character varying(20) DEFAULT 'BEGINNER'::character varying NOT NULL,
    max_capacity integer NOT NULL,
    status character varying(20) DEFAULT 'ACTIVE'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT ck_class_level CHECK (((level)::text = ANY ((ARRAY['BEGINNER'::character varying, 'INTERMEDIATE'::character varying, 'ADVANCED'::character varying])::text[]))),
    CONSTRAINT ck_class_status CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'INACTIVE'::character varying, 'CANCELED'::character varying, 'COMPLETED'::character varying])::text[]))),
    CONSTRAINT sport_classes_max_capacity_check CHECK ((max_capacity > 0))
);


ALTER TABLE public.sport_classes OWNER TO postgres;

--
-- TOC entry 234 (class 1259 OID 18999)
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
-- TOC entry 229 (class 1259 OID 18959)
-- Name: staff_profiles; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.staff_profiles (
    user_id bigint NOT NULL,
    job_title character varying(100),
    shift_schedule character varying(255)
);


ALTER TABLE public.staff_profiles OWNER TO postgres;

--
-- TOC entry 257 (class 1259 OID 19372)
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
-- TOC entry 256 (class 1259 OID 19371)
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
-- TOC entry 226 (class 1259 OID 18894)
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
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT ck_users_dob CHECK (((date_of_birth IS NULL) OR (date_of_birth < CURRENT_DATE))),
    CONSTRAINT ck_users_gender CHECK (((gender IS NULL) OR ((gender)::text = ANY ((ARRAY['MALE'::character varying, 'FEMALE'::character varying, 'OTHER'::character varying])::text[])))),
    CONSTRAINT ck_users_phone CHECK (((phone)::text ~ '^(03|05|07|08|09)[0-9]{8}$'::text))
);


ALTER TABLE public.users OWNER TO postgres;

--
-- TOC entry 225 (class 1259 OID 18893)
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
-- TOC entry 251 (class 1259 OID 19277)
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
-- TOC entry 250 (class 1259 OID 19276)
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
-- TOC entry 253 (class 1259 OID 19312)
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
    CONSTRAINT workout_progress_body_fat_pct_check CHECK (((body_fat_pct >= (0)::numeric) AND (body_fat_pct <= (100)::numeric))),
    CONSTRAINT workout_progress_height_cm_check CHECK ((height_cm > (0)::numeric)),
    CONSTRAINT workout_progress_muscle_mass_kg_check CHECK ((muscle_mass_kg > (0)::numeric)),
    CONSTRAINT workout_progress_weight_kg_check CHECK ((weight_kg > (0)::numeric))
);


ALTER TABLE public.workout_progress OWNER TO postgres;

--
-- TOC entry 252 (class 1259 OID 19311)
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
-- TOC entry 5652 (class 0 OID 19399)
-- Dependencies: 259
-- Data for Name: ai_chat_logs; Type: TABLE DATA; Schema: public; Owner: postgres
--

COPY public.ai_chat_logs (id, user_id, question, answer, status, created_at) FROM stdin;
\.


--
-- TOC entry 5654 (class 0 OID 19421)
-- Dependencies: 261
-- Data for Name: audit_logs; Type: TABLE DATA; Schema: public; Owner: postgres
--

COPY public.audit_logs (id, user_id, action, resource, resource_id, payload_diff, created_at) FROM stdin;
\.


--
-- TOC entry 5640 (class 0 OID 19221)
-- Dependencies: 247
-- Data for Name: class_attendances; Type: TABLE DATA; Schema: public; Owner: postgres
--

COPY public.class_attendances (id, class_id, user_id, session_date, status, check_in_time, recorded_by, notes) FROM stdin;
\.


--
-- TOC entry 5638 (class 0 OID 19185)
-- Dependencies: 245
-- Data for Name: class_registrations; Type: TABLE DATA; Schema: public; Owner: postgres
--

COPY public.class_registrations (id, class_id, user_id, registered_at, status, canceled_at, is_penalized, registered_by) FROM stdin;
\.


--
-- TOC entry 5630 (class 0 OID 19037)
-- Dependencies: 237
-- Data for Name: class_schedules; Type: TABLE DATA; Schema: public; Owner: postgres
--

COPY public.class_schedules (id, class_id, room_id, day_of_week, start_time, end_time) FROM stdin;
\.


--
-- TOC entry 5621 (class 0 OID 18944)
-- Dependencies: 228
-- Data for Name: coach_profiles; Type: TABLE DATA; Schema: public; Owner: postgres
--

COPY public.coach_profiles (user_id, specialty, years_experience, certifications, rating) FROM stdin;
8	\N	\N	\N	\N
5	Fitness	6	ACE Certified Personal Trainer	\N
\.


--
-- TOC entry 5642 (class 0 OID 19253)
-- Dependencies: 249
-- Data for Name: facility_attendances; Type: TABLE DATA; Schema: public; Owner: postgres
--

COPY public.facility_attendances (id, user_id, check_in_time, check_out_time, recorded_by, notes) FROM stdin;
\.


--
-- TOC entry 5626 (class 0 OID 18983)
-- Dependencies: 233
-- Data for Name: facility_rooms; Type: TABLE DATA; Schema: public; Owner: postgres
--

COPY public.facility_rooms (id, room_name, floor, capacity, description, maintenance_status) FROM stdin;
\.


--
-- TOC entry 5620 (class 0 OID 18927)
-- Dependencies: 227
-- Data for Name: member_profiles; Type: TABLE DATA; Schema: public; Owner: postgres
--

COPY public.member_profiles (user_id, join_date, height_cm, weight_kg, fitness_goal, membership_tier) FROM stdin;
2	2026-10-11	\N	\N	\N	\N
4	2026-10-13	\N	\N	\N	\N
1	2026-10-11	175.50	70.30	Tang co giam mo	\N
9	2026-10-13	\N	\N	\N	\N
10	2026-10-13	\N	\N	\N	\N
11	2026-10-13	\N	\N	\N	\N
12	2026-10-13	\N	\N	\N	\N
\.


--
-- TOC entry 5634 (class 0 OID 19097)
-- Dependencies: 241
-- Data for Name: member_subscriptions; Type: TABLE DATA; Schema: public; Owner: postgres
--

COPY public.member_subscriptions (id, user_id, package_id, package_name_snapshot, duration_months_snapshot, tier_level_snapshot, max_classes_per_week_snapshot, total_amount, start_date, end_date, status, created_by, created_at, updated_at) FROM stdin;
1	10	1	Basic 1 Month	1	1	3	300000	2026-10-14	2026-11-14	ACTIVE	\N	2026-10-14 19:44:35.715552+07	2026-10-14 19:47:08.988371+07
\.


--
-- TOC entry 5632 (class 0 OID 19068)
-- Dependencies: 239
-- Data for Name: membership_packages; Type: TABLE DATA; Schema: public; Owner: postgres
--

COPY public.membership_packages (id, code, name, description, duration_months, price, max_classes_per_week, tier_level, is_active) FROM stdin;
1	BASIC	Basic	Người muốn tự tập. Có khu tự tập, không bao gồm lớp.	1	250000	0	1	t
2	STARTER	Starter	Người mới muốn thử học cùng Coach. Có khu tự tập, nhóm lớp Tiêu chuẩn.	1	400000	2	2	t
3	STANDARD	Standard	Người tập đều đặn. Có khu tự tập, nhóm lớp Tiêu chuẩn.	1	550000	3	3	t
4	PLUS	Plus	Người muốn thử thêm lớp chuyên biệt. Có khu tự tập, nhóm lớp Tiêu chuẩn + Chuyên biệt.	1	750000	4	4	t
5	PREMIUM	Premium	Người tập thường xuyên, nhiều bộ môn. Có khu tự tập, nhóm lớp Tiêu chuẩn + Chuyên biệt.	1	950000	6	5	t
6	SIGNATURE	Signature	Người muốn tham gia thêm lớp nhóm nhỏ. Có khu tự tập, nhóm lớp Tiêu chuẩn + Chuyên biệt + Nhóm nhỏ.	1	1250000	7	6	t
\.


--
-- TOC entry 5648 (class 0 OID 19344)
-- Dependencies: 255
-- Data for Name: notifications; Type: TABLE DATA; Schema: public; Owner: postgres
--

COPY public.notifications (id, recipient_id, sender_id, type, title, content, is_read, created_at) FROM stdin;
\.


--
-- TOC entry 5636 (class 0 OID 19138)
-- Dependencies: 243
-- Data for Name: payments; Type: TABLE DATA; Schema: public; Owner: postgres
--

COPY public.payments (id, subscription_id, payer_id, received_by, invoice_number, transaction_code, amount, payment_method, status, paid_at, gateway_response, created_at) FROM stdin;
1	1	10	7	INV-b796543748bf41c697b1	CASH-9e034254-ba35-46f6-a8ad-a9f5debd134f	300000	CASH	SUCCESS	2026-10-14 19:47:09.065478+07	\N	2026-10-14 19:47:08.988371+07
\.


--
-- TOC entry 5616 (class 0 OID 18866)
-- Dependencies: 223
-- Data for Name: permissions; Type: TABLE DATA; Schema: public; Owner: postgres
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
-- TOC entry 5617 (class 0 OID 18876)
-- Dependencies: 224
-- Data for Name: role_permissions; Type: TABLE DATA; Schema: public; Owner: postgres
--

COPY public.role_permissions (role_id, permission_id) FROM stdin;
1	1
1	2
1	3
1	4
1	5
1	6
1	7
2	7
1	8
2	8
1	9
2	9
1	10
2	10
1	11
2	11
3	11
1	12
2	12
1	13
2	13
1	14
3	14
1	15
3	15
1	16
3	16
4	16
\.


--
-- TOC entry 5614 (class 0 OID 18855)
-- Dependencies: 221
-- Data for Name: roles; Type: TABLE DATA; Schema: public; Owner: postgres
--

COPY public.roles (id, code, name, description) FROM stdin;
1	MANAGER	Center Manager	Full management access
2	RECEPTIONIST	Receptionist	Front-desk operations
3	COACH	Coach	Teaches classes and tracks students
4	MEMBER	Member	Default role for public registration (BR-AUTH-05)
\.


--
-- TOC entry 5624 (class 0 OID 18971)
-- Dependencies: 231
-- Data for Name: sport_categories; Type: TABLE DATA; Schema: public; Owner: postgres
--

COPY public.sport_categories (id, name, description) FROM stdin;
\.


--
-- TOC entry 5628 (class 0 OID 19000)
-- Dependencies: 235
-- Data for Name: sport_classes; Type: TABLE DATA; Schema: public; Owner: postgres
--

COPY public.sport_classes (id, category_id, coach_id, name, description, level, max_capacity, status, created_at, updated_at) FROM stdin;
\.


--
-- TOC entry 5622 (class 0 OID 18959)
-- Dependencies: 229
-- Data for Name: staff_profiles; Type: TABLE DATA; Schema: public; Owner: postgres
--

COPY public.staff_profiles (user_id, job_title, shift_schedule) FROM stdin;
3	Center Manager	08:00-17:00
6	\N	\N
7	Senior Receptionist	08:00-19:00
\.


--
-- TOC entry 5650 (class 0 OID 19372)
-- Dependencies: 257
-- Data for Name: support_requests; Type: TABLE DATA; Schema: public; Owner: postgres
--

COPY public.support_requests (id, member_id, handled_by, subject, content, status, created_at, resolved_at) FROM stdin;
\.


--
-- TOC entry 5619 (class 0 OID 18894)
-- Dependencies: 226
-- Data for Name: users; Type: TABLE DATA; Schema: public; Owner: postgres
--

COPY public.users (id, role_id, full_name, email, phone, password_hash, avatar_url, gender, date_of_birth, is_active, created_at, updated_at) FROM stdin;
2	4	Nguyen Van B	member002@example.com	0933123321	$2a$10$QPtgjGq5IGR1Jm3HOtRRhes8Fu2tp3UZmpmXVyNPq7JAtCSasKkoG	\N	\N	\N	t	2026-10-11 20:50:25.172167+07	2026-10-11 20:50:25.172167+07
3	1	Center Manager	manager@test.local	0913448760	$2a$10$l3sFTnNN9Don59c35p/JdOkrgGgWF5JSScsLbXRFvb8l9RBAv/i3e	\N	\N	\N	t	2026-10-12 18:48:51.66103+07	2026-10-12 18:48:51.66103+07
5	3	Nguyen Van Coach	coach01@example.com	0987654321	$2a$10$5ID/r4HaF8YV8yFyfNJVFeGDHfGU7IqJUSEs6SSRnlxdx30nkRVha	\N	\N	\N	t	2026-10-13 14:43:18.691629+07	2026-10-13 14:43:18.691629+07
7	2	Nguyen Thi Receptionist	receptionist02@example.com	0934567891	$2a$10$P2QiD/9SzZobUp.gHINDn.M8RylJL2qsmcZ0N5La9DussJTPP3uxy	\N	\N	\N	t	2026-10-13 14:50:49.07969+07	2026-10-13 14:50:49.07969+07
8	3	Nguyen Van Coach1	coach02@example.com	0987654322	$2a$10$cD14ntogyAjDFfkB/Mxng..TqGcjmKya1jAuRBxDfpOjb/plBC8z2	\N	\N	\N	t	2026-10-13 14:56:48.218425+07	2026-10-13 14:56:48.218425+07
6	2	Nguyen Thi Lan	receptionist01@example.com	0934567890	$2a$10$W9hZ13.TIkns663iXHs/kOLdBOecbjzyKhCzpz/DoL39hoRi5uriO	\N	MALE	\N	t	2026-10-13 14:48:22.837416+07	2026-10-13 18:42:33.482023+07
4	4	Nguyen Thi Nal	member003@example.com	0912312312	$2a$10$v26zAvDjB9YP9y7a/nGrKuY2IyNe7s03oV/TZ2pNkVVrV6V7iiuZO	\N	\N	\N	t	2026-10-13 09:43:34.657189+07	2026-10-13 18:43:26.689933+07
9	4	Nguyen Van Test_Receptionist	countermember01@example.com	0913445512	$2a$10$KhU7Ao10.dUg3qu9uWFfUOv9CuifHTBE67.I9L9pVuF1SEu7qqIzC	\N	\N	\N	t	2026-10-13 19:13:06.867017+07	2026-10-13 19:13:06.867017+07
10	4	Nguyen Van Khoa	NguyenVanKhoa@example.com	0323456789	$2a$10$liK3l4Sqgjq3O6nhDEW5X.z4GqwXqGnx4YtbNs.VBm4a0CYC9sKAi	\N	\N	\N	t	2026-10-13 20:33:14.275406+07	2026-10-13 20:33:14.275406+07
11	4	Nguyen Van Khoa1	NguyenVanKhoa1@example.com	0323456782	$2a$10$k0p.hb5qAkp6Fsp5X4vAUemIAUkue4rmgCViadNHUYHcsimNNpU4C	\N	\N	\N	t	2026-10-13 20:36:02.826841+07	2026-10-13 20:36:02.826841+07
12	4	Nguyen Van Khoa1	NguyenVanKhoa12@example.com	0323456723	$2a$10$mIUPszrHxpXBjlk5ohNWrOrxco7Q.1Yoc3h123kEwq2MkOUu/asb6	\N	\N	\N	t	2026-10-13 20:36:34.237128+07	2026-10-13 20:36:34.237128+07
1	4	Luong Ba Hung	member001@example.com	0311231231	$2a$10$NdgsL3L8rvfEssAiMAzINOlSkRKe08SY5kIhBrtxEnBtK3XynCvvy	\N	MALE	\N	t	2026-10-11 20:49:21.447237+07	2026-10-13 21:09:26.633021+07
\.


--
-- TOC entry 5644 (class 0 OID 19277)
-- Dependencies: 251
-- Data for Name: workout_plans; Type: TABLE DATA; Schema: public; Owner: postgres
--

COPY public.workout_plans (id, member_id, coach_id, title, description, content, is_ai_generated, start_date, end_date, status, created_at, updated_at) FROM stdin;
\.


--
-- TOC entry 5646 (class 0 OID 19312)
-- Dependencies: 253
-- Data for Name: workout_progress; Type: TABLE DATA; Schema: public; Owner: postgres
--

COPY public.workout_progress (id, plan_id, member_id, recorded_by, recorded_date, weight_kg, height_cm, body_fat_pct, muscle_mass_kg, notes) FROM stdin;
\.


--
-- TOC entry 5661 (class 0 OID 0)
-- Dependencies: 258
-- Name: ai_chat_logs_id_seq; Type: SEQUENCE SET; Schema: public; Owner: postgres
--

SELECT pg_catalog.setval('public.ai_chat_logs_id_seq', 1, false);


--
-- TOC entry 5662 (class 0 OID 0)
-- Dependencies: 260
-- Name: audit_logs_id_seq; Type: SEQUENCE SET; Schema: public; Owner: postgres
--

SELECT pg_catalog.setval('public.audit_logs_id_seq', 1, false);


--
-- TOC entry 5663 (class 0 OID 0)
-- Dependencies: 246
-- Name: class_attendances_id_seq; Type: SEQUENCE SET; Schema: public; Owner: postgres
--

SELECT pg_catalog.setval('public.class_attendances_id_seq', 1, false);


--
-- TOC entry 5664 (class 0 OID 0)
-- Dependencies: 244
-- Name: class_registrations_id_seq; Type: SEQUENCE SET; Schema: public; Owner: postgres
--

SELECT pg_catalog.setval('public.class_registrations_id_seq', 1, false);


--
-- TOC entry 5665 (class 0 OID 0)
-- Dependencies: 236
-- Name: class_schedules_id_seq; Type: SEQUENCE SET; Schema: public; Owner: postgres
--

SELECT pg_catalog.setval('public.class_schedules_id_seq', 1, false);


--
-- TOC entry 5666 (class 0 OID 0)
-- Dependencies: 248
-- Name: facility_attendances_id_seq; Type: SEQUENCE SET; Schema: public; Owner: postgres
--

SELECT pg_catalog.setval('public.facility_attendances_id_seq', 1, false);


--
-- TOC entry 5667 (class 0 OID 0)
-- Dependencies: 232
-- Name: facility_rooms_id_seq; Type: SEQUENCE SET; Schema: public; Owner: postgres
--

SELECT pg_catalog.setval('public.facility_rooms_id_seq', 1, false);


--
-- TOC entry 5668 (class 0 OID 0)
-- Dependencies: 240
-- Name: member_subscriptions_id_seq; Type: SEQUENCE SET; Schema: public; Owner: postgres
--

SELECT pg_catalog.setval('public.member_subscriptions_id_seq', 1, true);


--
-- TOC entry 5669 (class 0 OID 0)
-- Dependencies: 238
-- Name: membership_packages_id_seq; Type: SEQUENCE SET; Schema: public; Owner: postgres
--

SELECT pg_catalog.setval('public.membership_packages_id_seq', 6, true);


--
-- TOC entry 5670 (class 0 OID 0)
-- Dependencies: 254
-- Name: notifications_id_seq; Type: SEQUENCE SET; Schema: public; Owner: postgres
--

SELECT pg_catalog.setval('public.notifications_id_seq', 1, false);


--
-- TOC entry 5671 (class 0 OID 0)
-- Dependencies: 242
-- Name: payments_id_seq; Type: SEQUENCE SET; Schema: public; Owner: postgres
--

SELECT pg_catalog.setval('public.payments_id_seq', 1, true);


--
-- TOC entry 5672 (class 0 OID 0)
-- Dependencies: 222
-- Name: permissions_id_seq; Type: SEQUENCE SET; Schema: public; Owner: postgres
--

SELECT pg_catalog.setval('public.permissions_id_seq', 16, true);


--
-- TOC entry 5673 (class 0 OID 0)
-- Dependencies: 220
-- Name: roles_id_seq; Type: SEQUENCE SET; Schema: public; Owner: postgres
--

SELECT pg_catalog.setval('public.roles_id_seq', 4, true);


--
-- TOC entry 5674 (class 0 OID 0)
-- Dependencies: 230
-- Name: sport_categories_id_seq; Type: SEQUENCE SET; Schema: public; Owner: postgres
--

SELECT pg_catalog.setval('public.sport_categories_id_seq', 1, false);


--
-- TOC entry 5675 (class 0 OID 0)
-- Dependencies: 234
-- Name: sport_classes_id_seq; Type: SEQUENCE SET; Schema: public; Owner: postgres
--

SELECT pg_catalog.setval('public.sport_classes_id_seq', 1, false);


--
-- TOC entry 5676 (class 0 OID 0)
-- Dependencies: 256
-- Name: support_requests_id_seq; Type: SEQUENCE SET; Schema: public; Owner: postgres
--

SELECT pg_catalog.setval('public.support_requests_id_seq', 1, false);


--
-- TOC entry 5677 (class 0 OID 0)
-- Dependencies: 225
-- Name: users_id_seq; Type: SEQUENCE SET; Schema: public; Owner: postgres
--

SELECT pg_catalog.setval('public.users_id_seq', 12, true);


--
-- TOC entry 5678 (class 0 OID 0)
-- Dependencies: 250
-- Name: workout_plans_id_seq; Type: SEQUENCE SET; Schema: public; Owner: postgres
--

SELECT pg_catalog.setval('public.workout_plans_id_seq', 1, false);


--
-- TOC entry 5679 (class 0 OID 0)
-- Dependencies: 252
-- Name: workout_progress_id_seq; Type: SEQUENCE SET; Schema: public; Owner: postgres
--

SELECT pg_catalog.setval('public.workout_progress_id_seq', 1, false);


--
-- TOC entry 5414 (class 2606 OID 19413)
-- Name: ai_chat_logs ai_chat_logs_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.ai_chat_logs
    ADD CONSTRAINT ai_chat_logs_pkey PRIMARY KEY (id);


--
-- TOC entry 5417 (class 2606 OID 19432)
-- Name: audit_logs audit_logs_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.audit_logs
    ADD CONSTRAINT audit_logs_pkey PRIMARY KEY (id);


--
-- TOC entry 5394 (class 2606 OID 19233)
-- Name: class_attendances class_attendances_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.class_attendances
    ADD CONSTRAINT class_attendances_pkey PRIMARY KEY (id);


--
-- TOC entry 5389 (class 2606 OID 19199)
-- Name: class_registrations class_registrations_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.class_registrations
    ADD CONSTRAINT class_registrations_pkey PRIMARY KEY (id);


--
-- TOC entry 5364 (class 2606 OID 19049)
-- Name: class_schedules class_schedules_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.class_schedules
    ADD CONSTRAINT class_schedules_pkey PRIMARY KEY (id);


--
-- TOC entry 5348 (class 2606 OID 18953)
-- Name: coach_profiles coach_profiles_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.coach_profiles
    ADD CONSTRAINT coach_profiles_pkey PRIMARY KEY (user_id);


--
-- TOC entry 5366 (class 2606 OID 19051)
-- Name: class_schedules ex_schedule_room_overlap; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.class_schedules
    ADD CONSTRAINT ex_schedule_room_overlap EXCLUDE USING gist (room_id WITH =, day_of_week WITH =, tsrange(('2000-01-01 00:00:00'::timestamp without time zone + (start_time)::interval), ('2000-01-01 00:00:00'::timestamp without time zone + (end_time)::interval)) WITH &&);


--
-- TOC entry 5399 (class 2606 OID 19264)
-- Name: facility_attendances facility_attendances_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.facility_attendances
    ADD CONSTRAINT facility_attendances_pkey PRIMARY KEY (id);


--
-- TOC entry 5356 (class 2606 OID 18996)
-- Name: facility_rooms facility_rooms_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.facility_rooms
    ADD CONSTRAINT facility_rooms_pkey PRIMARY KEY (id);


--
-- TOC entry 5358 (class 2606 OID 18998)
-- Name: facility_rooms facility_rooms_room_name_key; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.facility_rooms
    ADD CONSTRAINT facility_rooms_room_name_key UNIQUE (room_name);


--
-- TOC entry 5346 (class 2606 OID 18938)
-- Name: member_profiles member_profiles_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.member_profiles
    ADD CONSTRAINT member_profiles_pkey PRIMARY KEY (user_id);


--
-- TOC entry 5377 (class 2606 OID 19117)
-- Name: member_subscriptions member_subscriptions_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.member_subscriptions
    ADD CONSTRAINT member_subscriptions_pkey PRIMARY KEY (id);


--
-- TOC entry 5369 (class 2606 OID 19090)
-- Name: membership_packages membership_packages_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.membership_packages
    ADD CONSTRAINT membership_packages_pkey PRIMARY KEY (id);


--
-- TOC entry 5410 (class 2606 OID 19359)
-- Name: notifications notifications_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.notifications
    ADD CONSTRAINT notifications_pkey PRIMARY KEY (id);


--
-- TOC entry 5383 (class 2606 OID 19159)
-- Name: payments payments_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.payments
    ADD CONSTRAINT payments_pkey PRIMARY KEY (id);


--
-- TOC entry 5333 (class 2606 OID 18875)
-- Name: permissions permissions_code_key; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.permissions
    ADD CONSTRAINT permissions_code_key UNIQUE (code);


--
-- TOC entry 5335 (class 2606 OID 18873)
-- Name: permissions permissions_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.permissions
    ADD CONSTRAINT permissions_pkey PRIMARY KEY (id);


--
-- TOC entry 5337 (class 2606 OID 18882)
-- Name: role_permissions role_permissions_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.role_permissions
    ADD CONSTRAINT role_permissions_pkey PRIMARY KEY (role_id, permission_id);


--
-- TOC entry 5329 (class 2606 OID 18864)
-- Name: roles roles_code_key; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.roles
    ADD CONSTRAINT roles_code_key UNIQUE (code);


--
-- TOC entry 5331 (class 2606 OID 18862)
-- Name: roles roles_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.roles
    ADD CONSTRAINT roles_pkey PRIMARY KEY (id);


--
-- TOC entry 5352 (class 2606 OID 18981)
-- Name: sport_categories sport_categories_name_key; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.sport_categories
    ADD CONSTRAINT sport_categories_name_key UNIQUE (name);


--
-- TOC entry 5354 (class 2606 OID 18979)
-- Name: sport_categories sport_categories_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.sport_categories
    ADD CONSTRAINT sport_categories_pkey PRIMARY KEY (id);


--
-- TOC entry 5362 (class 2606 OID 19022)
-- Name: sport_classes sport_classes_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.sport_classes
    ADD CONSTRAINT sport_classes_pkey PRIMARY KEY (id);


--
-- TOC entry 5350 (class 2606 OID 18964)
-- Name: staff_profiles staff_profiles_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.staff_profiles
    ADD CONSTRAINT staff_profiles_pkey PRIMARY KEY (user_id);


--
-- TOC entry 5412 (class 2606 OID 19387)
-- Name: support_requests support_requests_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.support_requests
    ADD CONSTRAINT support_requests_pkey PRIMARY KEY (id);


--
-- TOC entry 5397 (class 2606 OID 19235)
-- Name: class_attendances uq_class_attendance; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.class_attendances
    ADD CONSTRAINT uq_class_attendance UNIQUE (class_id, user_id, session_date);


--
-- TOC entry 5392 (class 2606 OID 19201)
-- Name: class_registrations uq_class_member; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.class_registrations
    ADD CONSTRAINT uq_class_member UNIQUE (class_id, user_id);


--
-- TOC entry 5371 (class 2606 OID 19092)
-- Name: membership_packages uq_package_code; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.membership_packages
    ADD CONSTRAINT uq_package_code UNIQUE (code);


--
-- TOC entry 5373 (class 2606 OID 19094)
-- Name: membership_packages uq_package_name; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.membership_packages
    ADD CONSTRAINT uq_package_name UNIQUE (name);


--
-- TOC entry 5385 (class 2606 OID 19163)
-- Name: payments uq_payment_invoice; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.payments
    ADD CONSTRAINT uq_payment_invoice UNIQUE (invoice_number);


--
-- Name: payments uq_payment_gateway_order; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.payments
    ADD CONSTRAINT uq_payment_gateway_order UNIQUE (gateway_order_code);


--
-- TOC entry 5387 (class 2606 OID 19161)
-- Name: payments uq_payment_transaction; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.payments
    ADD CONSTRAINT uq_payment_transaction UNIQUE (transaction_code);


--
-- TOC entry 5340 (class 2606 OID 18917)
-- Name: users uq_users_email; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT uq_users_email UNIQUE (email);


--
-- TOC entry 5342 (class 2606 OID 18919)
-- Name: users uq_users_phone; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT uq_users_phone UNIQUE (phone);


--
-- TOC entry 5344 (class 2606 OID 18915)
-- Name: users users_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_pkey PRIMARY KEY (id);


--
-- TOC entry 5404 (class 2606 OID 19297)
-- Name: workout_plans workout_plans_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.workout_plans
    ADD CONSTRAINT workout_plans_pkey PRIMARY KEY (id);


--
-- TOC entry 5407 (class 2606 OID 19326)
-- Name: workout_progress workout_progress_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.workout_progress
    ADD CONSTRAINT workout_progress_pkey PRIMARY KEY (id);


--
-- TOC entry 5415 (class 1259 OID 19419)
-- Name: idx_ai_chat_user; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_ai_chat_user ON public.ai_chat_logs USING btree (user_id, created_at);


--
-- TOC entry 5418 (class 1259 OID 19439)
-- Name: idx_audit_created; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_audit_created ON public.audit_logs USING btree (created_at);


--
-- TOC entry 5419 (class 1259 OID 19438)
-- Name: idx_audit_user; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_audit_user ON public.audit_logs USING btree (user_id, created_at);


--
-- TOC entry 5395 (class 1259 OID 19251)
-- Name: idx_class_att_user; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_class_att_user ON public.class_attendances USING btree (user_id);


--
-- TOC entry 5359 (class 1259 OID 19035)
-- Name: idx_classes_category; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_classes_category ON public.sport_classes USING btree (category_id);


--
-- TOC entry 5360 (class 1259 OID 19034)
-- Name: idx_classes_coach; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_classes_coach ON public.sport_classes USING btree (coach_id);


--
-- TOC entry 5400 (class 1259 OID 19275)
-- Name: idx_facility_att_user; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_facility_att_user ON public.facility_attendances USING btree (user_id, check_in_time);


--
-- TOC entry 5408 (class 1259 OID 19370)
-- Name: idx_notification_recipient; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_notification_recipient ON public.notifications USING btree (recipient_id, is_read);


--
-- TOC entry 5379 (class 1259 OID 19181)
-- Name: idx_payment_paid_at; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_payment_paid_at ON public.payments USING btree (paid_at);


--
-- TOC entry 5380 (class 1259 OID 19180)
-- Name: idx_payment_payer; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_payment_payer ON public.payments USING btree (payer_id);


--
-- TOC entry 5381 (class 1259 OID 19179)
-- Name: idx_payment_subscription; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_payment_subscription ON public.payments USING btree (subscription_id);


--
-- TOC entry 5401 (class 1259 OID 19310)
-- Name: idx_plan_coach; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_plan_coach ON public.workout_plans USING btree (coach_id);


--
-- TOC entry 5402 (class 1259 OID 19309)
-- Name: idx_plan_member; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_plan_member ON public.workout_plans USING btree (member_id);


--
-- TOC entry 5405 (class 1259 OID 19342)
-- Name: idx_progress_member; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_progress_member ON public.workout_progress USING btree (member_id, recorded_date);


--
-- TOC entry 5390 (class 1259 OID 19217)
-- Name: idx_reg_user; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_reg_user ON public.class_registrations USING btree (user_id);


--
-- TOC entry 5367 (class 1259 OID 19062)
-- Name: idx_schedules_class; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_schedules_class ON public.class_schedules USING btree (class_id);


--
-- TOC entry 5374 (class 1259 OID 19135)
-- Name: idx_sub_package; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_sub_package ON public.member_subscriptions USING btree (package_id);


--
-- TOC entry 5375 (class 1259 OID 19134)
-- Name: idx_sub_user_status; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_sub_user_status ON public.member_subscriptions USING btree (user_id, status);


--
-- TOC entry 5338 (class 1259 OID 18926)
-- Name: idx_users_role; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_users_role ON public.users USING btree (role_id);


--
-- Name: idx_sub_upgrade_from; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX idx_sub_upgrade_from ON public.member_subscriptions USING btree (upgrade_from_subscription_id);


--
-- TOC entry 5378 (class 1259 OID 19136)
-- Name: uq_one_active_subscription; Type: INDEX; Schema: public; Owner: postgres
--

CREATE UNIQUE INDEX uq_one_active_subscription ON public.member_subscriptions USING btree (user_id) WHERE ((status)::text = 'ACTIVE'::text);


--
-- TOC entry 5464 (class 2620 OID 19441)
-- Name: audit_logs trg_audit_logs_immutable; Type: TRIGGER; Schema: public; Owner: postgres
--

CREATE TRIGGER trg_audit_logs_immutable BEFORE DELETE OR UPDATE ON public.audit_logs FOR EACH ROW EXECUTE FUNCTION public.trg_audit_immutable();


--
-- TOC entry 5465 (class 2620 OID 19442)
-- Name: audit_logs trg_audit_logs_no_truncate; Type: TRIGGER; Schema: public; Owner: postgres
--

CREATE TRIGGER trg_audit_logs_no_truncate BEFORE TRUNCATE ON public.audit_logs FOR EACH STATEMENT EXECUTE FUNCTION public.trg_audit_immutable();


--
-- TOC entry 5462 (class 2620 OID 19219)
-- Name: class_registrations trg_class_registrations_check; Type: TRIGGER; Schema: public; Owner: postgres
--

CREATE TRIGGER trg_class_registrations_check BEFORE INSERT OR UPDATE OF status ON public.class_registrations FOR EACH ROW EXECUTE FUNCTION public.trg_check_registration();


--
-- TOC entry 5458 (class 2620 OID 19064)
-- Name: class_schedules trg_class_schedules_check; Type: TRIGGER; Schema: public; Owner: postgres
--

CREATE TRIGGER trg_class_schedules_check BEFORE INSERT OR UPDATE ON public.class_schedules FOR EACH ROW EXECUTE FUNCTION public.trg_check_class_schedule();


--
-- TOC entry 5456 (class 2620 OID 19033)
-- Name: sport_classes trg_classes_updated; Type: TRIGGER; Schema: public; Owner: postgres
--

CREATE TRIGGER trg_classes_updated BEFORE UPDATE ON public.sport_classes FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();


--
-- TOC entry 5459 (class 2620 OID 19095)
-- Name: membership_packages trg_packages_updated; Type: TRIGGER; Schema: public; Owner: postgres
--

CREATE TRIGGER trg_packages_updated BEFORE UPDATE ON public.membership_packages FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();


--
-- TOC entry 5463 (class 2620 OID 19308)
-- Name: workout_plans trg_plans_updated; Type: TRIGGER; Schema: public; Owner: postgres
--

CREATE TRIGGER trg_plans_updated BEFORE UPDATE ON public.workout_plans FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();


--
-- TOC entry 5457 (class 2620 OID 19066)
-- Name: sport_classes trg_sport_classes_check; Type: TRIGGER; Schema: public; Owner: postgres
--

CREATE TRIGGER trg_sport_classes_check BEFORE INSERT OR UPDATE OF coach_id, max_capacity ON public.sport_classes FOR EACH ROW EXECUTE FUNCTION public.trg_check_sport_class();


--
-- TOC entry 5460 (class 2620 OID 19133)
-- Name: member_subscriptions trg_subscriptions_updated; Type: TRIGGER; Schema: public; Owner: postgres
--

CREATE TRIGGER trg_subscriptions_updated BEFORE UPDATE ON public.member_subscriptions FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();


--
-- TOC entry 5455 (class 2620 OID 18925)
-- Name: users trg_users_updated; Type: TRIGGER; Schema: public; Owner: postgres
--

CREATE TRIGGER trg_users_updated BEFORE UPDATE ON public.users FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();


--
-- TOC entry 5453 (class 2606 OID 19414)
-- Name: ai_chat_logs ai_chat_logs_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.ai_chat_logs
    ADD CONSTRAINT ai_chat_logs_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id);


--
-- TOC entry 5454 (class 2606 OID 19433)
-- Name: audit_logs audit_logs_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.audit_logs
    ADD CONSTRAINT audit_logs_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id);


--
-- TOC entry 5439 (class 2606 OID 19236)
-- Name: class_attendances class_attendances_class_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.class_attendances
    ADD CONSTRAINT class_attendances_class_id_fkey FOREIGN KEY (class_id) REFERENCES public.sport_classes(id);


--
-- TOC entry 5440 (class 2606 OID 19246)
-- Name: class_attendances class_attendances_recorded_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.class_attendances
    ADD CONSTRAINT class_attendances_recorded_by_fkey FOREIGN KEY (recorded_by) REFERENCES public.users(id);


--
-- TOC entry 5441 (class 2606 OID 19241)
-- Name: class_attendances class_attendances_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.class_attendances
    ADD CONSTRAINT class_attendances_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id);


--
-- TOC entry 5436 (class 2606 OID 19202)
-- Name: class_registrations class_registrations_class_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.class_registrations
    ADD CONSTRAINT class_registrations_class_id_fkey FOREIGN KEY (class_id) REFERENCES public.sport_classes(id);


--
-- TOC entry 5437 (class 2606 OID 19212)
-- Name: class_registrations class_registrations_registered_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.class_registrations
    ADD CONSTRAINT class_registrations_registered_by_fkey FOREIGN KEY (registered_by) REFERENCES public.users(id);


--
-- TOC entry 5438 (class 2606 OID 19207)
-- Name: class_registrations class_registrations_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.class_registrations
    ADD CONSTRAINT class_registrations_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id);


--
-- TOC entry 5428 (class 2606 OID 19052)
-- Name: class_schedules class_schedules_class_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.class_schedules
    ADD CONSTRAINT class_schedules_class_id_fkey FOREIGN KEY (class_id) REFERENCES public.sport_classes(id) ON DELETE CASCADE;


--
-- TOC entry 5429 (class 2606 OID 19057)
-- Name: class_schedules class_schedules_room_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.class_schedules
    ADD CONSTRAINT class_schedules_room_id_fkey FOREIGN KEY (room_id) REFERENCES public.facility_rooms(id);


--
-- TOC entry 5424 (class 2606 OID 18954)
-- Name: coach_profiles coach_profiles_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.coach_profiles
    ADD CONSTRAINT coach_profiles_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;


--
-- TOC entry 5442 (class 2606 OID 19270)
-- Name: facility_attendances facility_attendances_recorded_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.facility_attendances
    ADD CONSTRAINT facility_attendances_recorded_by_fkey FOREIGN KEY (recorded_by) REFERENCES public.users(id);


--
-- TOC entry 5443 (class 2606 OID 19265)
-- Name: facility_attendances facility_attendances_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.facility_attendances
    ADD CONSTRAINT facility_attendances_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id);


--
-- TOC entry 5423 (class 2606 OID 18939)
-- Name: member_profiles member_profiles_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.member_profiles
    ADD CONSTRAINT member_profiles_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;


--
-- TOC entry 5430 (class 2606 OID 19128)
-- Name: member_subscriptions member_subscriptions_created_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.member_subscriptions
    ADD CONSTRAINT member_subscriptions_created_by_fkey FOREIGN KEY (created_by) REFERENCES public.users(id);


--
-- TOC entry 5431 (class 2606 OID 19123)
-- Name: member_subscriptions member_subscriptions_package_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.member_subscriptions
    ADD CONSTRAINT member_subscriptions_package_id_fkey FOREIGN KEY (package_id) REFERENCES public.membership_packages(id);


--
-- Name: member_subscriptions member_subscriptions_upgrade_from_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.member_subscriptions
    ADD CONSTRAINT member_subscriptions_upgrade_from_fkey FOREIGN KEY (upgrade_from_subscription_id) REFERENCES public.member_subscriptions(id);


--
-- TOC entry 5432 (class 2606 OID 19118)
-- Name: member_subscriptions member_subscriptions_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.member_subscriptions
    ADD CONSTRAINT member_subscriptions_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id);


--
-- TOC entry 5449 (class 2606 OID 19360)
-- Name: notifications notifications_recipient_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.notifications
    ADD CONSTRAINT notifications_recipient_id_fkey FOREIGN KEY (recipient_id) REFERENCES public.users(id) ON DELETE CASCADE;


--
-- TOC entry 5450 (class 2606 OID 19365)
-- Name: notifications notifications_sender_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.notifications
    ADD CONSTRAINT notifications_sender_id_fkey FOREIGN KEY (sender_id) REFERENCES public.users(id);


--
-- TOC entry 5433 (class 2606 OID 19169)
-- Name: payments payments_payer_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.payments
    ADD CONSTRAINT payments_payer_id_fkey FOREIGN KEY (payer_id) REFERENCES public.users(id);


--
-- TOC entry 5434 (class 2606 OID 19174)
-- Name: payments payments_received_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.payments
    ADD CONSTRAINT payments_received_by_fkey FOREIGN KEY (received_by) REFERENCES public.users(id);


--
-- TOC entry 5435 (class 2606 OID 19164)
-- Name: payments payments_subscription_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.payments
    ADD CONSTRAINT payments_subscription_id_fkey FOREIGN KEY (subscription_id) REFERENCES public.member_subscriptions(id);


--
-- TOC entry 5420 (class 2606 OID 18888)
-- Name: role_permissions role_permissions_permission_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.role_permissions
    ADD CONSTRAINT role_permissions_permission_id_fkey FOREIGN KEY (permission_id) REFERENCES public.permissions(id) ON DELETE CASCADE;


--
-- TOC entry 5421 (class 2606 OID 18883)
-- Name: role_permissions role_permissions_role_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.role_permissions
    ADD CONSTRAINT role_permissions_role_id_fkey FOREIGN KEY (role_id) REFERENCES public.roles(id) ON DELETE CASCADE;


--
-- TOC entry 5426 (class 2606 OID 19023)
-- Name: sport_classes sport_classes_category_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.sport_classes
    ADD CONSTRAINT sport_classes_category_id_fkey FOREIGN KEY (category_id) REFERENCES public.sport_categories(id);


--
-- TOC entry 5427 (class 2606 OID 19028)
-- Name: sport_classes sport_classes_coach_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.sport_classes
    ADD CONSTRAINT sport_classes_coach_id_fkey FOREIGN KEY (coach_id) REFERENCES public.users(id);


--
-- TOC entry 5425 (class 2606 OID 18965)
-- Name: staff_profiles staff_profiles_user_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.staff_profiles
    ADD CONSTRAINT staff_profiles_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;


--
-- TOC entry 5451 (class 2606 OID 19393)
-- Name: support_requests support_requests_handled_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.support_requests
    ADD CONSTRAINT support_requests_handled_by_fkey FOREIGN KEY (handled_by) REFERENCES public.users(id);


--
-- TOC entry 5452 (class 2606 OID 19388)
-- Name: support_requests support_requests_member_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.support_requests
    ADD CONSTRAINT support_requests_member_id_fkey FOREIGN KEY (member_id) REFERENCES public.users(id);


--
-- TOC entry 5422 (class 2606 OID 18920)
-- Name: users users_role_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_role_id_fkey FOREIGN KEY (role_id) REFERENCES public.roles(id);


--
-- TOC entry 5444 (class 2606 OID 19303)
-- Name: workout_plans workout_plans_coach_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.workout_plans
    ADD CONSTRAINT workout_plans_coach_id_fkey FOREIGN KEY (coach_id) REFERENCES public.users(id);


--
-- TOC entry 5445 (class 2606 OID 19298)
-- Name: workout_plans workout_plans_member_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.workout_plans
    ADD CONSTRAINT workout_plans_member_id_fkey FOREIGN KEY (member_id) REFERENCES public.users(id);


--
-- TOC entry 5446 (class 2606 OID 19332)
-- Name: workout_progress workout_progress_member_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.workout_progress
    ADD CONSTRAINT workout_progress_member_id_fkey FOREIGN KEY (member_id) REFERENCES public.users(id);


--
-- TOC entry 5447 (class 2606 OID 19327)
-- Name: workout_progress workout_progress_plan_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.workout_progress
    ADD CONSTRAINT workout_progress_plan_id_fkey FOREIGN KEY (plan_id) REFERENCES public.workout_plans(id) ON DELETE SET NULL;


--
-- TOC entry 5448 (class 2606 OID 19337)
-- Name: workout_progress workout_progress_recorded_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.workout_progress
    ADD CONSTRAINT workout_progress_recorded_by_fkey FOREIGN KEY (recorded_by) REFERENCES public.users(id);


-- Completed on 2026-10-16 18:22:32

--
-- PostgreSQL database dump complete
--


