package com.swp391.sportcentermanagementsystem_v2.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.TrainingRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.CoachDashboardResponse;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.TrainingResponse.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import java.util.function.Function;

/** JDBC follows the existing assessment, notification and audit modules; all values are bound parameters. */
@Repository
@RequiredArgsConstructor
public class TrainingRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    // SQL expressions passed here are internal aliases, never request values.
    private static String withdrawnBeforeSession(String member) {
        String start = "(s.session_date+s.start_time) AT TIME ZONE 'Asia/Ho_Chi_Minh'";
        return "EXISTS (SELECT 1 FROM class_registrations cr WHERE cr.class_id=s.class_id AND cr.user_id=" + member
            + " AND cr.status IN ('LEFT','CANCELED') AND cr.canceled_at <= " + start + ")"
            + " AND NOT EXISTS (SELECT 1 FROM class_registrations cr WHERE cr.class_id=s.class_id AND cr.user_id=" + member
            + " AND cr.registered_at <= " + start + " AND (cr.status='REGISTERED' OR "
            + "(cr.status IN ('LEFT','CANCELED') AND cr.canceled_at > " + start + ")))";
    }
    private static final String WITHDRAWN_BEFORE_SESSION = withdrawnBeforeSession("a.user_id");

    private static final String SESSION_SELECT = """
        SELECT s.id, s.class_id, c.name class_name, s.coach_id, u.full_name coach_name,
        s.session_no, s.session_date, s.start_time, s.end_time, r.room_name,
        s.status, c.status class_status, s.attendance_finalized_at, s.training_version
        FROM class_sessions s JOIN sport_classes c ON c.id=s.class_id
        JOIN users u ON u.id=s.coach_id JOIN facility_rooms r ON r.id=s.room_id
        """;
    private final RowMapper<Session> sessionMapper = (rs, n) -> new Session(rs.getLong("id"),
            rs.getLong("class_id"), rs.getString("class_name"), rs.getLong("coach_id"), rs.getString("coach_name"),
            rs.getInt("session_no"), rs.getObject("session_date", LocalDate.class),
            rs.getObject("start_time", LocalTime.class), rs.getObject("end_time", LocalTime.class),
            rs.getString("room_name"), rs.getString("status"), rs.getString("class_status"),
            rs.getObject("attendance_finalized_at", OffsetDateTime.class), rs.getLong("training_version"));

    public List<AssignedClass> classes(long coachId) {
        return jdbc.query("""
            SELECT id,name,status,coach_id FROM sport_classes c WHERE coach_id=? OR EXISTS
            (SELECT 1 FROM class_sessions s WHERE s.class_id=c.id AND s.coach_id=?) ORDER BY id DESC
            """, (r, i) -> new AssignedClass(r.getLong("id"), r.getString("name"), r.getString("status"),
                r.getLong("coach_id")), coachId, coachId);
    }

    public long upcomingSessionCount(long coachId, LocalDateTime now) {
        return jdbc.queryForObject("""
            SELECT count(*) FROM class_sessions s JOIN sport_classes c ON c.id=s.class_id
            WHERE s.coach_id=? AND s.status='SCHEDULED' AND c.status NOT IN ('DRAFT','CANCELED')
            AND (s.session_date+s.start_time)>?
            """,Long.class,coachId,now);
    }

    /** Fixed query count; no per-session HTTP/JDBC requests and no member profile data. */
    public Map<Long,CoachDashboardResponse.SessionFacts> dashboardSessionFacts(List<Long> sessionIds) {
        if(sessionIds.isEmpty()) return Map.of();
        Map<Long,CoachDashboardResponse.SessionFacts> facts=new HashMap<>();
        new NamedParameterJdbcTemplate(jdbc).query("""
            SELECT s.id,sc.name sport,
            (SELECT count(*) FROM users u WHERE (EXISTS
                (SELECT 1 FROM class_registrations cr WHERE cr.class_id=s.class_id AND cr.user_id=u.id
                 AND cr.status='REGISTERED' AND cr.registered_at <= (s.session_date+s.start_time) AT TIME ZONE 'Asia/Ho_Chi_Minh')
                OR EXISTS(SELECT 1 FROM class_attendances a0 WHERE a0.session_id=s.id AND a0.user_id=u.id))
                AND NOT EXISTS(SELECT 1 FROM class_attendances a0 WHERE a0.session_id=s.id AND a0.user_id=u.id AND a0.status='DECLINED_MAKEUP')
                AND NOT (
            """ + withdrawnBeforeSession("u.id") + """
                )) members,
            (SELECT count(*) FROM class_attendances a WHERE a.session_id=s.id
                AND a.status IN ('PRESENT','LATE','ABSENT','EXCUSED') AND NOT (
            """ + WITHDRAWN_BEFORE_SESSION + """
                )) marked
            FROM class_sessions s JOIN sport_classes c ON c.id=s.class_id
            JOIN courses co ON co.id=c.course_id JOIN sport_categories sc ON sc.id=co.category_id
            WHERE s.id IN (:ids)
            """,Map.of("ids",sessionIds),(org.springframework.jdbc.core.RowCallbackHandler) r ->
                facts.put(r.getLong("id"),new CoachDashboardResponse.SessionFacts(r.getString("sport"),r.getLong("members"),r.getLong("marked"))));
        return facts;
    }

    public List<CoachDashboardResponse.AssignedClass> dashboardClasses(long coachId,LocalDateTime now) {
        return jdbc.query("""
            SELECT c.id,c.coach_id,c.name,c.description,c.status,c.max_capacity,co.name course,sc.name sport,
            (SELECT count(DISTINCT cr.user_id) FROM class_registrations cr WHERE cr.class_id=c.id AND cr.status='REGISTERED') members,
            (SELECT string_agg((CASE WHEN sch.day_of_week=7 THEN 'CN' ELSE 'Thứ '||(sch.day_of_week+1) END)
                ||' '||to_char(sch.start_time,'HH24:MI')||'–'||to_char(sch.end_time,'HH24:MI'),' · ' ORDER BY sch.day_of_week,sch.start_time)
                FROM class_schedules sch WHERE sch.class_id=c.id) schedule,
            ns.id next_id,ns.session_date next_date,ns.start_time next_start,ns.end_time next_end,room.room_name,
            (SELECT s.id FROM class_sessions s WHERE s.class_id=c.id AND s.coach_id=? ORDER BY s.session_date DESC,s.id DESC LIMIT 1) representative_id
            FROM sport_classes c JOIN courses co ON co.id=c.course_id JOIN sport_categories sc ON sc.id=co.category_id
            LEFT JOIN LATERAL (SELECT s.* FROM class_sessions s WHERE s.class_id=c.id AND s.coach_id=?
                AND s.status='SCHEDULED' AND c.status NOT IN ('DRAFT','CANCELED') AND (s.session_date+s.start_time)>?
                ORDER BY s.session_date,s.start_time,s.id LIMIT 1) ns ON true
            LEFT JOIN facility_rooms room ON room.id=ns.room_id
            WHERE c.coach_id=? OR EXISTS(SELECT 1 FROM class_sessions s WHERE s.class_id=c.id AND s.coach_id=?)
            ORDER BY CASE WHEN c.status IN ('OPEN','IN_PROGRESS') THEN 0 ELSE 1 END,c.name,c.id
            """,(r,i) -> new CoachDashboardResponse.AssignedClass(r.getLong("id"),r.getLong("coach_id"),r.getString("name"),
                r.getString("sport"),r.getString("course"),r.getString("description"),r.getString("status"),r.getInt("max_capacity"),
                r.getLong("members"),r.getString("schedule"),r.getObject("next_id")==null ? null : new CoachDashboardResponse.NextSession(
                    r.getLong("next_id"),r.getObject("next_date",LocalDate.class),r.getObject("next_start",LocalTime.class),
                    r.getObject("next_end",LocalTime.class),r.getString("room_name")),r.getObject("representative_id",Long.class)),
                coachId,coachId,now,coachId,coachId);
    }

    public List<CoachDashboardResponse.Activity> recentTrainingActivities(long coachId) {
        return jdbc.query("""
            SELECT id,action,resource_id,created_at,coalesce(payload_diff->>'finalized','false')='true' finalized
            FROM audit_logs WHERE user_id=? AND action IN ('TRAINING_ATTENDANCE_SAVE','TRAINING_PLAN_CREATE',
                'TRAINING_PLAN_UPDATE','TRAINING_RESULT_SAVE','TRAINING_EVALUATION_SAVE')
            ORDER BY created_at DESC,id DESC LIMIT 8
            """,(r,i) -> new CoachDashboardResponse.Activity(r.getLong("id"),r.getString("action"),r.getString("resource_id"),
                r.getObject("created_at",OffsetDateTime.class),r.getBoolean("finalized")),coachId);
    }

    public Optional<AssignedClass> sportClass(long id, boolean lock) {
        return jdbc.query("SELECT id,name,status,coach_id FROM sport_classes WHERE id=?" + (lock ? " FOR UPDATE" : ""),
                (r, i) -> new AssignedClass(r.getLong("id"), r.getString("name"), r.getString("status"),
                        r.getLong("coach_id")), id).stream().findFirst();
    }

    public Optional<Long> sessionClassId(long id) {
        return jdbc.query("SELECT class_id FROM class_sessions WHERE id=?", (r, i) -> r.getLong(1), id)
                .stream().findFirst();
    }
    public Optional<Session> session(long id, boolean lock) {
        return jdbc.query(SESSION_SELECT + " WHERE s.id=?" + (lock ? " FOR UPDATE OF s" : ""), sessionMapper, id)
                .stream().findFirst();
    }
    public List<Session> coachSessions(long coachId, LocalDate from, LocalDate to) {
        return jdbc.query(SESSION_SELECT + " WHERE s.coach_id=? AND s.session_date BETWEEN ? AND ? ORDER BY s.session_date,s.start_time,s.id",
                sessionMapper, coachId, from, to);
    }
    public List<Session> memberSessions(long memberId, LocalDate from, LocalDate to) {
        return jdbc.query(SESSION_SELECT + """
            WHERE s.session_date BETWEEN ? AND ? AND (EXISTS
            (SELECT 1 FROM class_registrations cr WHERE cr.class_id=s.class_id AND cr.user_id=? AND cr.status='REGISTERED'
                AND cr.registered_at <= (s.session_date+s.start_time) AT TIME ZONE 'Asia/Ho_Chi_Minh')
            OR EXISTS (SELECT 1 FROM class_attendances a WHERE a.session_id=s.id AND a.user_id=?))
            ORDER BY s.session_date,s.start_time,s.id
            """, sessionMapper, from, to, memberId, memberId);
    }

    // Include existing attendance even after enrollment cancellation, without exposing the rest of a member's profile.
    public List<Participant> roster(long sessionId) {
        return jdbc.query("""
            SELECT u.id member_id,u.full_name,mp.fitness_goal,a.id attendance_id,a.status,a.notes,
            EXISTS (SELECT 1 FROM class_registrations cr WHERE cr.class_id=s.class_id AND cr.user_id=u.id
                AND cr.status='REGISTERED' AND cr.registered_at <=
                    (s.session_date+s.start_time) AT TIME ZONE 'Asia/Ho_Chi_Minh') eligible,
            """ + withdrawnBeforeSession("u.id") + " withdrawn_before_session " + """
            FROM class_sessions s JOIN users u ON EXISTS
                (SELECT 1 FROM class_registrations cr WHERE cr.class_id=s.class_id AND cr.user_id=u.id
                AND cr.status='REGISTERED' AND cr.registered_at <=
                    (s.session_date+s.start_time) AT TIME ZONE 'Asia/Ho_Chi_Minh')
                OR EXISTS (SELECT 1 FROM class_attendances a0 WHERE a0.session_id=s.id AND a0.user_id=u.id)
            LEFT JOIN member_profiles mp ON mp.user_id=u.id
            LEFT JOIN class_attendances a ON a.session_id=s.id AND a.user_id=u.id
            WHERE s.id=? ORDER BY u.full_name,u.id
            """, (r, i) -> new Participant(r.getLong("member_id"), r.getString("full_name"), r.getString("fitness_goal"),
                r.getObject("attendance_id", Long.class), r.getString("status"), r.getString("notes"), r.getBoolean("eligible"),
                r.getBoolean("withdrawn_before_session")), sessionId);
    }

    public boolean memberInClass(long classId, long memberId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM class_registrations WHERE class_id=? AND user_id=? AND status='REGISTERED')
            OR EXISTS(SELECT 1 FROM class_attendances a JOIN class_sessions s ON s.id=a.session_id
                WHERE s.class_id=? AND a.user_id=? AND a.status <> 'DECLINED_MAKEUP')
            OR EXISTS(SELECT 1 FROM training_progress_evaluations WHERE class_id=? AND member_id=?)
            OR EXISTS(SELECT 1 FROM workout_plans WHERE class_id=? AND member_id=?)
            """, Boolean.class, classId, memberId, classId, memberId, classId, memberId, classId, memberId));
    }
    public boolean currentlyEnrolled(long classId, long memberId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM class_registrations WHERE class_id=? AND user_id=? AND status='REGISTERED')
            """, Boolean.class, classId, memberId));
    }
    public List<Participant> classRoster(long classId) {
        return jdbc.query("""
            SELECT u.id,u.full_name,mp.fitness_goal,
                EXISTS(SELECT 1 FROM class_registrations cr WHERE cr.class_id=? AND cr.user_id=u.id AND cr.status='REGISTERED') eligible
            FROM users u LEFT JOIN member_profiles mp ON mp.user_id=u.id
            WHERE EXISTS(SELECT 1 FROM class_registrations cr WHERE cr.class_id=? AND cr.user_id=u.id AND cr.status='REGISTERED')
            OR EXISTS(SELECT 1 FROM class_attendances a JOIN class_sessions s ON s.id=a.session_id
                WHERE s.class_id=? AND a.user_id=u.id AND a.status<>'DECLINED_MAKEUP')
            OR EXISTS(SELECT 1 FROM training_progress_evaluations e WHERE e.class_id=? AND e.member_id=u.id)
            OR EXISTS(SELECT 1 FROM workout_plans p WHERE p.class_id=? AND p.member_id=u.id)
            ORDER BY u.full_name,u.id
            """,(r,i) -> new Participant(r.getLong("id"),r.getString("full_name"),r.getString("fitness_goal"),null,null,null,
                r.getBoolean("eligible"),false),classId,classId,classId,classId,classId);
    }
    public void notifyEnrolled(long classId,String title) {
        jdbc.update("""
            INSERT INTO notifications(recipient_id,type,channel,title,content)
            SELECT DISTINCT user_id,'WORKOUT_PLAN','APP','Kế hoạch tập luyện mới',? FROM class_registrations
            WHERE class_id=? AND status='REGISTERED'
            """,title,classId);
    }
    public void attendance(long sessionId, long memberId, String status, String notes, long actorId) {
        jdbc.update("""
            INSERT INTO class_attendances(session_id,user_id,status,notes,recorded_by,check_in_time)
            VALUES(?,?,?,?,?,CASE WHEN ? IN ('PRESENT','LATE') THEN now() ELSE NULL END)
            ON CONFLICT(session_id,user_id) DO UPDATE SET status=excluded.status,notes=excluded.notes,
            recorded_by=excluded.recorded_by,updated_at=now(),check_in_time=CASE
                WHEN excluded.status IN ('PRESENT','LATE') THEN coalesce(class_attendances.check_in_time,now()) ELSE NULL END
            """, sessionId, memberId, status, notes, actorId, status);
    }
    public boolean hasResults(long attendanceId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM training_results WHERE attendance_id=?)",
                Boolean.class, attendanceId));
    }
    public void finalizeSession(long id) {
        jdbc.update("UPDATE class_sessions SET status='DONE',attendance_finalized_at=coalesce(attendance_finalized_at,now()) WHERE id=?", id);
    }
    public void advanceTrainingVersion(long id) {
        jdbc.update("UPDATE class_sessions SET training_version=training_version+1 WHERE id=?", id);
    }

    private static final String PLAN_SELECT = """
        SELECT p.*,c.name class_name,m.full_name member_name,u.full_name coach_name FROM workout_plans p
        LEFT JOIN sport_classes c ON c.id=p.class_id LEFT JOIN users m ON m.id=p.member_id JOIN users u ON u.id=p.coach_id
        """;
    private final RowMapper<Plan> planMapper = (r, i) -> new Plan(r.getLong("id"), r.getObject("class_id", Long.class),
            r.getString("class_name"), r.getObject("member_id", Long.class), r.getString("member_name"), r.getLong("coach_id"),
            r.getString("coach_name"), r.getString("title"), r.getString("description"), r.getString("objective"),
            r.getObject("start_date", LocalDate.class), r.getObject("end_date", LocalDate.class), r.getString("status"),
            r.getString("coach_notes"), r.getInt("version"), List.of());

    public Optional<Plan> plan(long id, boolean lock) {
        return jdbc.query(PLAN_SELECT + " WHERE p.id=?" + (lock ? " FOR UPDATE OF p" : ""), planMapper, id).stream().findFirst();
    }
    public Page<Plan> coachPlans(long coachId, Long cursor, int limit) {
        return hydratePage(page(PLAN_SELECT + " WHERE p.coach_id=?", new Object[]{coachId},
            "p.id", cursor, limit, planMapper, Plan::id));
    }
    public Page<Plan> memberPlans(long memberId, Long cursor, int limit) {
        return hydratePage(page(PLAN_SELECT + """
            WHERE p.status IN ('PUBLISHED','COMPLETED') AND (p.member_id=? OR (p.member_id IS NULL AND
            (EXISTS (SELECT 1 FROM class_registrations cr WHERE cr.class_id=p.class_id AND cr.user_id=? AND cr.status='REGISTERED')
            OR EXISTS (SELECT 1 FROM class_attendances a JOIN class_sessions s ON s.id=a.session_id
                WHERE s.class_id=p.class_id AND a.user_id=? AND a.status<>'DECLINED_MAKEUP'
                AND s.status IN ('SCHEDULED','DONE')
                AND s.session_date BETWEEN p.start_date AND p.end_date AND NOT (
            """ + WITHDRAWN_BEFORE_SESSION + """
                )))))
            """, new Object[]{memberId, memberId, memberId}, "p.id", cursor, limit, planMapper, Plan::id));
    }
    public List<Plan> sessionPlans(long sessionId) {
        return hydratePlans(jdbc.query(PLAN_SELECT + """
            JOIN class_sessions s ON s.class_id=p.class_id
            WHERE s.id=? AND p.status IN ('PUBLISHED','COMPLETED') AND s.session_date BETWEEN p.start_date AND p.end_date
            AND (p.member_id IS NULL OR EXISTS(SELECT 1 FROM class_attendances a WHERE a.session_id=s.id AND a.user_id=p.member_id)
                OR EXISTS(SELECT 1 FROM class_registrations cr WHERE cr.class_id=s.class_id AND cr.user_id=p.member_id
                    AND cr.status='REGISTERED' AND cr.registered_at <= (s.session_date+s.start_time) AT TIME ZONE 'Asia/Ho_Chi_Minh'))
            ORDER BY p.id DESC
            """,planMapper,sessionId));
    }
    private Page<Plan> hydratePage(Page<Plan> page) {
        return new Page<>(hydratePlans(page.items()), page.nextCursor());
    }

    // Stable ID cursor: inserts between pages cannot shift or duplicate already loaded records.
    private <T> Page<T> page(String query, Object[] parameters, String idColumn, Long cursor, int limit,
            RowMapper<T> mapper, Function<T,Long> id) {
        List<Object> args = new ArrayList<>(Arrays.asList(parameters));
        if (cursor != null) { query += " AND " + idColumn + " < ?"; args.add(cursor); }
        args.add(limit + 1);
        List<T> rows = jdbc.query(query + " ORDER BY " + idColumn + " DESC LIMIT ?", mapper, args.toArray());
        boolean more = rows.size() > limit;
        List<T> items = List.copyOf(more ? rows.subList(0, limit) : rows);
        return new Page<>(items, more ? id.apply(items.getLast()) : null);
    }
    public List<Plan> hydratePlans(List<Plan> plans) {
        if (plans.isEmpty()) return plans;
        Map<Long,List<Exercise>> grouped = new HashMap<>();
        new NamedParameterJdbcTemplate(jdbc).query("""
            SELECT * FROM training_plan_exercises WHERE plan_id IN (:ids) AND NOT retired ORDER BY position,id
            """, Map.of("ids", plans.stream().map(Plan::id).toList()), (org.springframework.jdbc.core.RowCallbackHandler) r ->
                grouped.computeIfAbsent(r.getLong("plan_id"), k -> new ArrayList<>()).add(exerciseMapper.mapRow(r, 0)));
        return plans.stream().map(p -> new Plan(p.id(),p.classId(),p.className(),p.memberId(),p.memberName(),p.coachId(),
                p.coachName(),p.title(),p.description(),p.objective(),p.startDate(),p.endDate(),p.status(),p.coachNotes(),p.version(),
                grouped.getOrDefault(p.id(),List.of()))).toList();
    }
    private final RowMapper<Exercise> exerciseMapper = (r, i) -> new Exercise(r.getLong("id"),r.getInt("position"),
            r.getString("name"),r.getString("instructions"),r.getObject("sets",Integer.class),
            r.getObject("repetitions",Integer.class),r.getObject("duration_seconds",Integer.class));
    public record ExerciseTarget(Exercise exercise, Long planId, Long classId, Long memberId, String planStatus,
            LocalDate startDate, LocalDate endDate, int version, boolean retired) {}
    public Optional<ExerciseTarget> exercise(long id) {
        return jdbc.query("""
            SELECT e.*,p.id plan_id,p.class_id,p.member_id,p.status plan_status,p.start_date,p.end_date,p.version
            FROM training_plan_exercises e JOIN workout_plans p ON p.id=e.plan_id WHERE e.id=?
            """, (r,i) -> new ExerciseTarget(exerciseMapper.mapRow(r,i),r.getLong("plan_id"),r.getObject("class_id",Long.class),
                r.getObject("member_id",Long.class),r.getString("plan_status"),r.getObject("start_date",LocalDate.class),
                r.getObject("end_date",LocalDate.class),r.getInt("version"),r.getBoolean("retired")),id).stream().findFirst();
    }
    public long insertPlan(TrainingRequest.Plan p, long coachId) {
        return Objects.requireNonNull(jdbc.queryForObject("""
            INSERT INTO workout_plans(class_id,member_id,coach_id,title,description,objective,start_date,end_date,status,coach_notes)
            VALUES(?,?,?,?,?,?,?,?,?,?) RETURNING id
            """, Long.class,p.classId(),p.memberId(),coachId,p.title().trim(),p.description(),p.objective(),p.startDate(),p.endDate(),p.status(),p.coachNotes()));
    }
    public void updatePlan(long id, TrainingRequest.Plan p) {
        jdbc.update("""
            UPDATE workout_plans SET title=?,description=?,objective=?,start_date=?,end_date=?,status=?,coach_notes=?,version=version+1 WHERE id=?
            """,p.title().trim(),p.description(),p.objective(),p.startDate(),p.endDate(),p.status(),p.coachNotes(),id);
    }
    public void replaceExercises(long planId, List<TrainingRequest.Exercise> exercises) {
        jdbc.update("UPDATE training_plan_exercises SET retired=true WHERE plan_id=?",planId);
        for (int i=0;i<exercises.size();i++) {
            var e=exercises.get(i);
            if (e.id()==null) jdbc.update("""
                INSERT INTO training_plan_exercises(plan_id,position,name,instructions,sets,repetitions,duration_seconds)
                VALUES(?,?,?,?,?,?,?)
                """,planId,i,e.name().trim(),e.instructions(),e.sets(),e.repetitions(),e.durationSeconds());
            else jdbc.update("""
                UPDATE training_plan_exercises SET position=?,name=?,instructions=?,sets=?,repetitions=?,duration_seconds=?,retired=false
                WHERE id=? AND plan_id=?
                """,i,e.name().trim(),e.instructions(),e.sets(),e.repetitions(),e.durationSeconds(),e.id(),planId);
        }
    }

    private static final String RESULT_SELECT = """
        SELECT t.*,a.session_id,a.user_id member_id,m.full_name member_name,c.name class_name,s.session_date,u.full_name coach_name
        FROM training_results t JOIN class_attendances a ON a.id=t.attendance_id JOIN class_sessions s ON s.id=a.session_id
        JOIN sport_classes c ON c.id=s.class_id JOIN users m ON m.id=a.user_id JOIN users u ON u.id=t.recorded_by
        """;
    private Result result(ResultSet r,int i) throws SQLException {
        try {
            return new Result(r.getLong("id"),r.getLong("session_id"),r.getString("class_name"),r.getObject("session_date",LocalDate.class),
                r.getLong("member_id"),r.getString("member_name"),r.getObject("exercise_id",Long.class),r.getString("activity_key"),
                r.getString("activity_name"),r.getString("prescription")==null ? null : json.readTree(r.getString("prescription")),
                r.getObject("actual_sets",Integer.class),r.getObject("actual_repetitions",Integer.class),r.getObject("actual_duration_seconds",Integer.class),
                r.getString("performance"),r.getString("completion_status"),r.getString("notes"),r.getString("coach_name"),
                r.getObject("recorded_at",OffsetDateTime.class),r.getObject("updated_at",OffsetDateTime.class));
        } catch (JsonProcessingException ex) { throw new SQLException("Invalid stored prescription",ex); }
    }
    public List<Result> sessionResults(long id) { return jdbc.query(RESULT_SELECT+" WHERE a.session_id=? ORDER BY t.id",this::result,id); }
    public Page<Result> memberResults(long memberId, Long classId, Long cursor, int limit) {
        return page(RESULT_SELECT+" WHERE a.user_id=?"+(classId==null ? "" : " AND s.class_id=?"),
            classId==null ? new Object[]{memberId} : new Object[]{memberId,classId}, "t.id", cursor, limit, this::result, Result::id);
    }
    public long upsertResult(long attendanceId,TrainingRequest.Result r,long actorId,String snapshot) {
        return Objects.requireNonNull(jdbc.queryForObject("""
            INSERT INTO training_results(attendance_id,exercise_id,activity_key,activity_name,prescription,
                actual_sets,actual_repetitions,actual_duration_seconds,performance,completion_status,notes,recorded_by)
            VALUES(?,?,?,?,cast(? as jsonb),?,?,?,?,?,?,?)
            ON CONFLICT(attendance_id,activity_key) DO UPDATE SET actual_sets=excluded.actual_sets,
                actual_repetitions=excluded.actual_repetitions,actual_duration_seconds=excluded.actual_duration_seconds,
                performance=excluded.performance,completion_status=excluded.completion_status,notes=excluded.notes,
                recorded_by=excluded.recorded_by,updated_at=now()
            RETURNING id
            """,Long.class,attendanceId,r.exerciseId(),r.activityKey(),r.activityName().trim(),snapshot,r.actualSets(),
                r.actualRepetitions(),r.actualDurationSeconds(),r.performance(),r.completionStatus(),r.notes(),actorId));
    }

    private static final String EVALUATION_SELECT = """
        SELECT e.*,c.name class_name,m.full_name member_name,u.full_name coach_name FROM training_progress_evaluations e
        JOIN sport_classes c ON c.id=e.class_id JOIN users m ON m.id=e.member_id JOIN users u ON u.id=e.coach_id
        """;
    private final RowMapper<Evaluation> evaluationMapper = (r,i) -> new Evaluation(r.getLong("id"),r.getLong("class_id"),
            r.getString("class_name"),r.getLong("member_id"),r.getString("member_name"),r.getLong("coach_id"),r.getString("coach_name"),
            r.getObject("evaluation_date",LocalDate.class),r.getObject("period_start",LocalDate.class),r.getObject("period_end",LocalDate.class),
            r.getString("summary"),r.getInt("rating"),r.getString("strengths"),r.getString("improvements"),
            r.getString("recommendations"),r.getString("feedback"),r.getInt("version"));
    public Optional<Evaluation> evaluation(long id, boolean lock) {
        return jdbc.query(EVALUATION_SELECT+" WHERE e.id=?"+(lock ? " FOR UPDATE OF e" : ""),evaluationMapper,id).stream().findFirst();
    }
    public Page<Evaluation> evaluations(long memberId,Long classId,Long cursor,int limit) {
        return page(EVALUATION_SELECT+" WHERE e.member_id=?"+(classId==null ? "" : " AND e.class_id=?"),
            classId==null ? new Object[]{memberId} : new Object[]{memberId,classId}, "e.id", cursor, limit, evaluationMapper, Evaluation::id);
    }
    public long insertEvaluation(TrainingRequest.Evaluation e,long coachId) {
        return Objects.requireNonNull(jdbc.queryForObject("""
            INSERT INTO training_progress_evaluations(class_id,member_id,coach_id,evaluation_date,period_start,period_end,
                summary,rating,strengths,improvements,recommendations,feedback) VALUES(?,?,?,?,?,?,?,?,?,?,?,?) RETURNING id
            """,Long.class,e.classId(),e.memberId(),coachId,e.evaluationDate(),e.periodStart(),e.periodEnd(),e.summary(),
                e.rating(),e.strengths(),e.improvements(),e.recommendations(),e.feedback()));
    }
    public void updateEvaluation(long id,TrainingRequest.Evaluation e) {
        jdbc.update("""
            UPDATE training_progress_evaluations SET evaluation_date=?,period_start=?,period_end=?,summary=?,rating=?,
                strengths=?,improvements=?,recommendations=?,feedback=?,updated_at=now(),version=version+1 WHERE id=?
            """,e.evaluationDate(),e.periodStart(),e.periodEnd(),e.summary(),e.rating(),e.strengths(),e.improvements(),e.recommendations(),e.feedback(),id);
    }
    public Page<Attendance> attendanceHistory(long memberId,Long classId,Long cursor,int limit) {
        return page("""
            SELECT a.id attendance_id,s.id,c.name,s.session_date,s.start_time,a.status,a.notes,s.attendance_finalized_at,
                s.status session_status,s.cancel_reason,
            """ + WITHDRAWN_BEFORE_SESSION + " withdrawn_before_session " + """
            FROM class_attendances a JOIN class_sessions s ON s.id=a.session_id JOIN sport_classes c ON c.id=s.class_id
            WHERE a.user_id=?
            """+(classId==null ? "" : " AND s.class_id=?"),
                classId==null ? new Object[]{memberId} : new Object[]{memberId,classId}, "a.id", cursor, limit,
                (r,i) -> new Attendance(r.getLong("attendance_id"),r.getLong("id"),r.getString("name"),r.getObject("session_date",LocalDate.class),
                    r.getObject("start_time",LocalTime.class),r.getString("status"),r.getString("notes"),
                     r.getObject("attendance_finalized_at",OffsetDateTime.class),r.getBoolean("withdrawn_before_session"),
                     r.getString("session_status"),r.getString("cancel_reason")), Attendance::id);
    }
    public record MemberInfo(String fullName,String fitnessGoal) {}
    public Optional<MemberInfo> memberInfo(long id) {
        return jdbc.query("SELECT u.full_name,m.fitness_goal FROM users u JOIN member_profiles m ON m.user_id=u.id WHERE u.id=?",
                (r,i) -> new MemberInfo(r.getString(1),r.getString(2)),id).stream().findFirst();
    }
    public Statistics statistics(long memberId) {
        return jdbc.queryForObject("""
            WITH participation AS (
                SELECT a.id,a.status,s.status session_status
                FROM class_attendances a JOIN class_sessions s ON s.id=a.session_id
                WHERE a.user_id=? AND s.status IN ('SCHEDULED','DONE') AND NOT (
            """ + WITHDRAWN_BEFORE_SESSION + """
                )
            )
            SELECT count(*) FILTER(WHERE a.status IN ('PRESENT','LATE')) attended,
                count(*) FILTER(WHERE a.status IN ('PRESENT','LATE','ABSENT','EXCUSED')) marked,
                count(*) FILTER(WHERE a.session_status='DONE' AND a.status<>'DECLINED_MAKEUP') completed,
                (SELECT count(*) FROM training_results t JOIN participation p ON p.id=t.attendance_id
                    WHERE t.completion_status='COMPLETED') activities
            FROM participation a
            """,(r,i) -> {long attended=r.getLong("attended"),marked=r.getLong("marked");
                return new Statistics(attended,marked,marked==0 ? null : Math.round(1000.0*attended/marked)/10.0,
                    r.getLong("completed"),r.getLong("activities"));},memberId);
    }
}
