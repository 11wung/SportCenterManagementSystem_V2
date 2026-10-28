package com.swp391.sportcentermanagementsystem_v2.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.TrainingRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.CoachDashboardResponse;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.TrainingResponse.*;
import com.swp391.sportcentermanagementsystem_v2.entity.User;
import com.swp391.sportcentermanagementsystem_v2.exception.BusinessRuleException;
import com.swp391.sportcentermanagementsystem_v2.repository.TrainingRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TrainingService {
    private static final ZoneId CENTER_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private final TrainingRepository repository;
    private final UserRepository users;
    private final AuditService audit;
    private final NotificationService notifications;
    private final ObjectMapper json;
    private final Clock trainingClock;

    public List<AssignedClass> classes(String email) { return repository.classes(actor(email,"COACH").getId()); }

    public CoachDashboardResponse coachDashboard(String email) {
        User coach=actor(email,"COACH");
        ZonedDateTime refreshed=now();
        LocalDate day=refreshed.toLocalDate();
        // Includes next-day sessions whose -15 minute window has already opened near midnight.
        List<Session> nearby=repository.coachSessions(coach.getId(),day.minusDays(3),day.plusDays(1));
        List<Session> today=nearby.stream().filter(s -> s.date().equals(day)).toList();
        List<Session> pending=nearby.stream().filter(s -> attendanceEditable(s) && s.finalizedAt()==null).toList();
        var ids=java.util.stream.Stream.concat(today.stream(),pending.stream()).map(Session::id).distinct().toList();
        var facts=repository.dashboardSessionFacts(ids);
        Function<Session,CoachDashboardResponse.SessionItem> item=s -> {
            var f=facts.getOrDefault(s.id(),new CoachDashboardResponse.SessionFacts(null,0,0));
            return new CoachDashboardResponse.SessionItem(s,f.sport(),f.members(),
                Set.of("SCHEDULED","DONE").contains(s.status()) ? f.marked() : 0,
                attendanceEditable(s),attendanceEditable(s) && !refreshed.isBefore(end(s)) && s.finalizedAt()==null,resultsEditable(s));
        };
        var classes=repository.dashboardClasses(coach.getId(),refreshed.toLocalDateTime());
        long todayScheduled=today.stream().filter(s -> "SCHEDULED".equals(s.status()) && !Set.of("DRAFT","CANCELED").contains(s.classStatus())).count();
        return new CoachDashboardResponse(day,refreshed.toOffsetDateTime(),
            new CoachDashboardResponse.Summary(classes.size(),todayScheduled,
                repository.upcomingSessionCount(coach.getId(),refreshed.toLocalDateTime()),pending.size()),
            today.stream().map(item).toList(),pending.stream().map(item).toList(),classes,repository.recentTrainingActivities(coach.getId()));
    }

    public List<Session> sessions(String email,LocalDate from,LocalDate to) {
        User coach=actor(email,"COACH");
        LocalDate[] range=dateRange(from,to);
        return repository.coachSessions(coach.getId(),range[0],range[1]);
    }
    public SessionDetail session(String email,long sessionId) {
        User coach=actor(email,"COACH");
        Session session=ownedSession(coach,sessionId,false);
        return detail(session);
    }
    public List<Participant> classRoster(String email,long classId) {
        User coach=actor(email,"COACH");
        requireClassCoach(coach,classId,false);
        return repository.classRoster(classId);
    }

    @Transactional
    public SessionDetail saveAttendance(String email,long sessionId,TrainingRequest.Attendance request) {
        User coach=actor(email,"COACH");
        Session session=ownedSession(coach,sessionId,true);
        requireCurrentVersion(session,request.version());
        require(attendanceEditable(session),"Attendance may be edited from 15 minutes before the session until 48 hours after its end; canceled/proposed sessions are locked");
        require(!request.finalizeSession() || !now().isBefore(end(session)),"The session must end before attendance is finalized");
        Map<Long,Participant> roster=repository.roster(sessionId).stream().collect(Collectors.toMap(Participant::memberId,Function.identity()));
        Set<Long> seen=new HashSet<>();
        // Validate every entry before the first write. A failure anywhere still rolls back the whole transaction.
        for(var entry:request.entries()) {
            require(seen.add(entry.memberId()),"Duplicate member in attendance submission");
            Participant participant=roster.get(entry.memberId());
            require(participant!=null && (participant.eligible() || participant.attendanceId()!=null),"Member is not eligible for this session");
            require(!participant.withdrawnBeforeSession(),"Member left the class before this session; the early attendance record is read-only");
            require(!"DECLINED_MAKEUP".equals(participant.status()),"A declined makeup session cannot be changed by attendance management");
            require(participating(entry.status()) || participant.attendanceId()==null || !repository.hasResults(participant.attendanceId()),
                    "Attendance with training results must remain PRESENT or LATE");
        }
        for(var entry:request.entries()) repository.attendance(sessionId,entry.memberId(),entry.status(),entry.notes(),coach.getId());
        if(request.finalizeSession()) {
            for(var participant:roster.values()) {
                if(participant.status()==null && !seen.contains(participant.memberId()))
                    repository.attendance(sessionId,participant.memberId(),"ABSENT",null,coach.getId());
            }
            repository.finalizeSession(sessionId);
        }
        repository.advanceTrainingVersion(sessionId);
        audit.log(coach,"TRAINING_ATTENDANCE_SAVE","class_sessions",sessionId,
                Map.of("members",request.entries().size(),"finalized",request.finalizeSession()));
        return detail(repository.session(sessionId,false).orElseThrow(() -> missing("Session")));
    }

    public Page<Plan> plans(String email,Long cursor,int limit) {
        pageParameters(cursor,limit);
        return repository.coachPlans(actor(email,"COACH").getId(),cursor,limit);
    }
    public Plan plan(String email,long id) {
        User coach=actor(email,"COACH");
        Plan plan=repository.plan(id,false).orElseThrow(() -> missing("Plan"));
        // An author can still read their own plan after reassignment. Writes also require the current class assignment.
        if(!plan.coachId().equals(coach.getId())) throw forbidden();
        return repository.hydratePlans(List.of(plan)).getFirst();
    }

    @Transactional
    public Plan savePlan(String email,Long id,TrainingRequest.Plan request) {
        User coach=actor(email,"COACH");
        AssignedClass scope=requireClassCoach(coach,request.classId(),true);
        require(!"CANCELED".equals(scope.status()) && !"DRAFT".equals(scope.status()),"Plans require an open, active or completed class");
        require(!request.endDate().isBefore(request.startDate()),"Plan end date must not precede start date");
        require(request.exercises()!=null && !request.exercises().isEmpty(),"A plan must contain at least one exercise");
        Plan old=id==null ? null : repository.plan(id,true).orElseThrow(() -> missing("Plan"));
        if(old!=null) {
            requirePlanOwner(coach,old);
            require(Objects.equals(old.classId(),request.classId()) && Objects.equals(old.memberId(),request.memberId()),
                    "Plan assignment is immutable; create a new plan for a different class or member");
            require(Objects.equals(request.version(),old.version()),"Plan changed; reload before saving");
        }
        // The immutable, previously authorized recipient can leave without preventing maintenance/archival.
        if(request.memberId()!=null && (old==null || (!"PUBLISHED".equals(old.status()) && "PUBLISHED".equals(request.status()))))
            require(repository.currentlyEnrolled(request.classId(),request.memberId()),"Individual plans require an enrolled member of this class");
        Set<Long> ids=new HashSet<>();
        for(var exercise:request.exercises()) {
            if(exercise.id()!=null) {
                require(id!=null && ids.add(exercise.id()),"Invalid or duplicate exercise ID");
                var target=repository.exercise(exercise.id()).orElseThrow(() -> missing("Exercise"));
                require(target.planId().equals(id),"Exercise belongs to a different plan");
            }
        }
        long planId=id==null ? repository.insertPlan(request,coach.getId()) : id;
        if(id!=null) repository.updatePlan(id,request);
        repository.replaceExercises(planId,request.exercises());
        if("PUBLISHED".equals(request.status()) && (old==null || !"PUBLISHED".equals(old.status()))) {
            if(request.memberId()!=null) notifications.send(request.memberId(),"WORKOUT_PLAN","Kế hoạch tập luyện mới",request.title());
            else repository.notifyEnrolled(request.classId(),request.title());
        }
        audit.log(coach,id==null ? "TRAINING_PLAN_CREATE" : "TRAINING_PLAN_UPDATE","workout_plans",planId,
                Map.of("classId",request.classId(),"exerciseCount",request.exercises().size()));
        return repository.hydratePlans(List.of(repository.plan(planId,false).orElseThrow())).getFirst();
    }

    @Transactional
    public Result saveResult(String email,long sessionId,TrainingRequest.Result request) {
        User coach=actor(email,"COACH");
        Session session=ownedSession(coach,sessionId,true);
        requireCurrentVersion(session,request.version());
        require(resultsEditable(session),"Results require a completed, non-canceled session after its end");
        Participant participant=repository.roster(sessionId).stream().filter(p -> p.memberId().equals(request.memberId()))
                .findFirst().orElseThrow(() -> conflict("Member did not participate in this session"));
        require(participant.attendanceId()!=null && participating(participant.status()),"Results require PRESENT or LATE attendance");
        require(!participant.withdrawnBeforeSession(),"Results cannot be recorded for a member who left before the session");
        Result previous=repository.sessionResults(sessionId).stream()
                .filter(r -> r.memberId().equals(request.memberId()) && r.activityKey().equals(request.activityKey())).findFirst().orElse(null);
        String snapshot=null;
        TrainingRequest.Result stored=request;
        if(previous!=null) {
            require(Objects.equals(previous.exerciseId(),request.exerciseId()),"An existing activity cannot be reassigned to another exercise");
        } else if(request.exerciseId()!=null) {
            var target=repository.exercise(request.exerciseId()).orElseThrow(() -> missing("Exercise"));
            require(Objects.equals(target.classId(),session.classId()) && (target.memberId()==null || target.memberId().equals(request.memberId())),
                    "Exercise is not assigned to this class/member");
            require(!target.retired() && Set.of("PUBLISHED","COMPLETED").contains(target.planStatus()),"Exercise must belong to a published or completed plan");
            require(!session.date().isBefore(target.startDate()) && !session.date().isAfter(target.endDate()),"Plan does not cover this session date");
            snapshot=serialize(Map.of("planId",target.planId(),"version",target.version(),"exercise",target.exercise()));
            stored=new TrainingRequest.Result(request.memberId(),request.exerciseId(),request.activityKey(),target.exercise().name(),
                    request.actualSets(),request.actualRepetitions(),request.actualDurationSeconds(),request.performance(),request.completionStatus(),request.notes(),request.version());
        }
        if(request.exerciseId()!=null) require(request.activityKey().equals("exercise:"+request.exerciseId()),"Use the exercise ID as the activity key");
        else require(!request.activityKey().startsWith("exercise:"),"Reserved activity key");
        long resultId=repository.upsertResult(participant.attendanceId(),stored,coach.getId(),snapshot);
        repository.advanceTrainingVersion(sessionId);
        audit.log(coach,"TRAINING_RESULT_SAVE","training_results",resultId,Map.of("sessionId",sessionId,"memberId",request.memberId()));
        return repository.sessionResults(sessionId).stream().filter(r -> r.id()==resultId).findFirst().orElseThrow();
    }

    public MemberHistory memberHistory(String email,long classId,long memberId) {
        User coach=actor(email,"COACH");
        requireClassCoach(coach,classId,false);
        require(repository.memberInClass(classId,memberId),"Member does not belong to this class");
        var member=repository.memberInfo(memberId).orElseThrow(() -> missing("Member"));
        return new MemberHistory(memberId,member.fullName(),member.fitnessGoal(),repository.attendanceHistory(memberId,classId,null,50),
                repository.memberResults(memberId,classId,null,50),repository.evaluations(memberId,classId,null,50));
    }

    public Page<?> coachHistory(String email,long classId,long memberId,String kind,Long cursor,int limit) {
        User coach=actor(email,"COACH");
        requireClassCoach(coach,classId,false);
        require(repository.memberInClass(classId,memberId),"Member does not belong to this class");
        return historyPage(memberId,classId,kind,cursor,limit);
    }

    public Page<?> memberHistoryPage(String email,String kind,Long cursor,int limit) {
        return historyPage(actor(email,"MEMBER").getId(),null,kind,cursor,limit);
    }

    private Page<?> historyPage(long memberId,Long classId,String kind,Long cursor,int limit) {
        pageParameters(cursor,limit);
        return switch(kind) {
            case "attendance" -> repository.attendanceHistory(memberId,classId,cursor,limit);
            case "results" -> repository.memberResults(memberId,classId,cursor,limit);
            case "evaluations" -> repository.evaluations(memberId,classId,cursor,limit);
            case "plans" -> {
                if(classId!=null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Unsupported history type");
                yield repository.memberPlans(memberId,cursor,limit);
            }
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Unsupported history type");
        };
    }

    @Transactional
    public Evaluation saveEvaluation(String email,Long id,TrainingRequest.Evaluation request) {
        User coach=actor(email,"COACH");
        requireClassCoach(coach,request.classId(),true);
        require(repository.memberInClass(request.classId(),request.memberId()),"Member does not belong to this class");
        require(!request.periodEnd().isBefore(request.periodStart()) && !request.evaluationDate().isBefore(request.periodEnd())
                && !request.evaluationDate().isAfter(today()),"Evaluation period must end before or on the evaluation date; future evaluations are not allowed");
        if(id!=null) {
            Evaluation old=repository.evaluation(id,true).orElseThrow(() -> missing("Evaluation"));
            if(!old.coachId().equals(coach.getId())) throw forbidden();
            require(old.classId().equals(request.classId()) && old.memberId().equals(request.memberId()),"Evaluation assignment cannot change");
            require(Objects.equals(request.version(),old.version()),"Evaluation changed; reload before saving");
            repository.updateEvaluation(id,request);
        } else id=repository.insertEvaluation(request,coach.getId());
        audit.log(coach,"TRAINING_EVALUATION_SAVE","training_progress_evaluations",id,Map.of("memberId",request.memberId()));
        return repository.evaluation(id,false).orElseThrow();
    }

    public Dashboard dashboard(String email,LocalDate from,LocalDate to) {
        User member=actor(email,"MEMBER");
        LocalDate[] range=dateRange(from,to);
        long id=member.getId();
        return new Dashboard(repository.memberSessions(id,range[0],range[1]),repository.memberPlans(id,null,50),repository.attendanceHistory(id,null,null,50),
                repository.memberResults(id,null,null,50),repository.evaluations(id,null,null,50),repository.statistics(id));
    }

    private SessionDetail detail(Session session) {
        List<Participant> roster=repository.roster(session.id());
        Map<String,Long> counts=roster.stream().filter(p -> Set.of("SCHEDULED","DONE").contains(session.status()) && !p.withdrawnBeforeSession())
                .collect(Collectors.groupingBy(p -> p.status()==null ? "UNMARKED" : p.status(),Collectors.counting()));
        return new SessionDetail(session,roster,new Counts(counts.getOrDefault("PRESENT",0L),counts.getOrDefault("ABSENT",0L),
                counts.getOrDefault("LATE",0L),counts.getOrDefault("EXCUSED",0L),counts.getOrDefault("UNMARKED",0L),
                counts.getOrDefault("DECLINED_MAKEUP",0L)),attendanceEditable(session),
                attendanceEditable(session) && !now().isBefore(end(session)) && session.finalizedAt()==null,
                resultsEditable(session),repository.sessionResults(session.id()),
                repository.sessionPlans(session.id()));
    }
    private Session ownedSession(User coach,long id,boolean lock) {
        if(lock) {
            long classId=repository.sessionClassId(id).orElseThrow(() -> missing("Session"));
            repository.sportClass(classId,true).orElseThrow(() -> missing("Class"));
        }
        Session session=repository.session(id,lock).orElseThrow(() -> missing("Session"));
        if(!session.coachId().equals(coach.getId())) throw forbidden();
        return session;
    }
    private AssignedClass requireClassCoach(User coach,long id,boolean lock) {
        var scope=repository.sportClass(id,lock).orElseThrow(() -> missing("Class"));
        if(!scope.coachId().equals(coach.getId())) throw forbidden();
        return scope;
    }
    private void requirePlanOwner(User coach,Plan plan) {
        if(!plan.coachId().equals(coach.getId())) throw forbidden();
        if(plan.classId()!=null) requireClassCoach(coach,plan.classId(),false);
    }
    private User actor(String email,String role) {
        User user=users.findByEmail(email).orElseThrow(() -> missing("User"));
        if(!Boolean.TRUE.equals(user.getIsActive()) || user.getRole()==null || !role.equals(user.getRole().getCode())) throw forbidden();
        return user;
    }
    private boolean attendanceEditable(Session s) {
        return Set.of("SCHEDULED","DONE").contains(s.status()) && !Set.of("CANCELED","DRAFT").contains(s.classStatus())
                && !now().isBefore(start(s).minusMinutes(15)) && !now().isAfter(end(s).plusHours(48));
    }
    private boolean resultsEditable(Session s) {
        return "DONE".equals(s.status()) && !"CANCELED".equals(s.classStatus()) && !now().isBefore(end(s));
    }
    private ZonedDateTime start(Session s) { return s.date().atTime(s.startTime()).atZone(CENTER_ZONE); }
    private ZonedDateTime end(Session s) { return s.date().atTime(s.endTime()).atZone(CENTER_ZONE); }
    private ZonedDateTime now() { return ZonedDateTime.now(trainingClock).withZoneSameInstant(CENTER_ZONE); }
    private LocalDate today() { return now().toLocalDate(); }
    private LocalDate[] dateRange(LocalDate from,LocalDate to) {
        if(from==null) from=today().minusDays(30);
        if(to==null) to=today().plusDays(30);
        if(to.isBefore(from) || ChronoUnit.DAYS.between(from,to)>366)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Date range must be ordered and at most 366 days");
        return new LocalDate[]{from,to};
    }
    private boolean participating(String status) { return "PRESENT".equals(status) || "LATE".equals(status); }
    private void requireCurrentVersion(Session session,Long version) {
        require(version!=null && version==session.version(),"Dữ liệu buổi tập đã thay đổi. Hãy tải lại buổi tập trước khi lưu.");
    }
    private void pageParameters(Long cursor,int limit) {
        if((cursor!=null && cursor<=0) || limit<1 || limit>100)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Limit must be 1–100 and cursor must be positive");
    }
    private void require(boolean ok,String message) { if(!ok) throw conflict(message); }
    private BusinessRuleException conflict(String message) { return new BusinessRuleException(message); }
    private ResponseStatusException missing(String resource) { return new ResponseStatusException(HttpStatus.NOT_FOUND,resource+" not found"); }
    private ResponseStatusException forbidden() { return new ResponseStatusException(HttpStatus.FORBIDDEN,"You are not assigned to this training resource"); }
    private String serialize(Object value) {
        try { return json.writeValueAsString(value); } catch(JsonProcessingException e) { throw new IllegalStateException("Cannot serialize prescription",e); }
    }
}
