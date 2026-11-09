package com.swp391.sportcentermanagementsystem_v2.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.TrainingRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.CoachDashboardResponse;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.TrainingResponse.*;
import com.swp391.sportcentermanagementsystem_v2.entity.User;
import com.swp391.sportcentermanagementsystem_v2.exception.BusinessRuleException;
import com.swp391.sportcentermanagementsystem_v2.repository.TrainingRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.ClassAttendanceRepository;
import com.swp391.sportcentermanagementsystem_v2.entity.ClassAttendance;
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
    private final ClassAttendanceRepository attendanceRepository;
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
        List<Session> sessions=repository.coachSessions(coach.getId(),range[0],range[1]);
        if (sessions.isEmpty()) return sessions;

        List<ClassAttendance> marked=attendanceRepository.findBySession_IdIn(
                sessions.stream().map(Session::id).toList());
        Map<Long,long[]> totals=new HashMap<>();
        for (ClassAttendance row : marked) {
            long[] count=totals.computeIfAbsent(row.getSession().getId(), id -> new long[6]);
            switch (row.getStatus()) {
                case "PRESENT" -> count[0]++;
                case "ABSENT" -> count[1]++;
                case "LATE" -> count[2]++;
                case "EXCUSED" -> count[3]++;
                case "DECLINED_MAKEUP" -> count[5]++;
                default -> { }
            }
        }
        Map<Long,CoachDashboardResponse.SessionFacts> facts = repository.dashboardSessionFacts(
                sessions.stream().map(Session::id).toList());
        return sessions.stream().map(session -> {
            long[] count=totals.getOrDefault(session.id(), new long[6]);
            CoachDashboardResponse.SessionFacts fact = facts.get(session.id());
            long unmarked = fact == null ? 0 : Math.max(0, fact.members() - fact.marked());
            Counts attendance=new Counts(count[0],count[1],count[2],count[3],unmarked,count[5]);
            return new Session(session.id(),session.classId(),session.className(),session.coachId(),session.coachName(),
                    session.sessionNo(),session.date(),session.startTime(),session.endTime(),session.room(),
                    session.status(),session.classStatus(),session.finalizedAt(),session.version(),attendance);
        }).toList();
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
        require(attendanceEditable(session),"Chỉ sửa điểm danh từ 15 phút trước giờ học đến 48 giờ sau giờ kết thúc; buổi đã hủy hoặc đang đề xuất bị khóa");
        require(!request.finalizeSession() || !now().isBefore(end(session)),"Phải chờ buổi học kết thúc mới chốt điểm danh được");
        Map<Long,Participant> roster=repository.roster(sessionId).stream().collect(Collectors.toMap(Participant::memberId,Function.identity()));
        Set<Long> seen=new HashSet<>();
        // Validate every entry before the first write. A failure anywhere still rolls back the whole transaction.
        for(var entry:request.entries()) {
            require(seen.add(entry.memberId()),"Có hội viên bị trùng trong danh sách điểm danh");
            Participant participant=roster.get(entry.memberId());
            require(participant!=null && (participant.eligible() || participant.attendanceId()!=null),"Hội viên này không thuộc buổi học này");
            require(!participant.withdrawnBeforeSession(),"Hội viên đã rời lớp trước buổi này; điểm danh cũ chỉ để xem");
            require(!"DECLINED_MAKEUP".equals(participant.status()),"Buổi học bù đã từ chối không đổi được bằng điểm danh");
            require(participating(entry.status()) || participant.attendanceId()==null || !repository.hasResults(participant.attendanceId()),
                    "Điểm danh đã có kết quả tập phải là có mặt hoặc đến trễ");
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
        require(!"CANCELED".equals(scope.status()) && !"DRAFT".equals(scope.status()),"Chỉ tạo kế hoạch cho lớp đang mở, đang học hoặc đã xong");
        require(!request.endDate().isBefore(request.startDate()),"Ngày kết thúc kế hoạch không được trước ngày bắt đầu");
        require(request.exercises()!=null && !request.exercises().isEmpty(),"Kế hoạch phải có ít nhất một bài tập");
        Plan old=id==null ? null : repository.plan(id,true).orElseThrow(() -> missing("Plan"));
        if(old!=null) {
            requirePlanOwner(coach,old);
            require(Objects.equals(old.classId(),request.classId()) && Objects.equals(old.memberId(),request.memberId()),
                    "Không đổi được đối tượng của kế hoạch, hãy tạo kế hoạch mới cho lớp hoặc hội viên khác");
            require(Objects.equals(request.version(),old.version()),"Kế hoạch đã bị thay đổi, hãy tải lại trước khi lưu");
        }
        // The immutable, previously authorized recipient can leave without preventing maintenance/archival.
        if(request.memberId()!=null && (old==null || (!"PUBLISHED".equals(old.status()) && "PUBLISHED".equals(request.status()))))
            require(repository.currentlyEnrolled(request.classId(),request.memberId()),"Kế hoạch riêng cần một hội viên đã ghi danh lớp này");
        Set<Long> ids=new HashSet<>();
        for(var exercise:request.exercises()) {
            if(exercise.id()!=null) {
                require(id!=null && ids.add(exercise.id()),"Mã bài tập không hợp lệ hoặc bị trùng");
                var target=repository.exercise(exercise.id()).orElseThrow(() -> missing("Exercise"));
                require(target.planId().equals(id),"Bài tập thuộc kế hoạch khác");
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

    // Coach gửi thông báo hoặc bài tập về nhà cho cả lớp (memberId null) hoặc một học viên; trả về số người nhận
    @Transactional
    public int sendAnnouncement(String email,long classId,TrainingRequest.Announcement request) {
        User coach=actor(email,"COACH");
        AssignedClass scope=requireClassCoach(coach,classId,false);
        require(!"CANCELED".equals(scope.status()) && !"DRAFT".equals(scope.status()),"Chỉ gửi thông báo cho lớp đang mở, đang học hoặc đã xong");
        int recipients;
        if(request.memberId()!=null) {
            require(repository.currentlyEnrolled(classId,request.memberId()),"Hội viên chưa ghi danh lớp này");
            notifications.send(request.memberId(),request.type(),request.title(),request.content());
            recipients=1;
        } else recipients=repository.announceToEnrolled(classId,request.type(),request.title(),request.content());
        audit.log(coach,"COACH_ANNOUNCEMENT","sport_classes",classId,
                Map.of("type",request.type(),"recipients",recipients,"memberId",String.valueOf(request.memberId())));
        return recipients;
    }

    @Transactional
    public Result saveResult(String email,long sessionId,TrainingRequest.Result request) {
        User coach=actor(email,"COACH");
        Session session=ownedSession(coach,sessionId,true);
        requireCurrentVersion(session,request.version());
        require(resultsEditable(session),"Chỉ ghi kết quả cho buổi đã kết thúc và không bị hủy");
        Participant participant=repository.roster(sessionId).stream().filter(p -> p.memberId().equals(request.memberId()))
                .findFirst().orElseThrow(() -> conflict("Hội viên không tham gia buổi này"));
        require(participant.attendanceId()!=null && participating(participant.status()),"Chỉ ghi kết quả cho người có mặt hoặc đến trễ");
        require(!participant.withdrawnBeforeSession(),"Không ghi kết quả cho hội viên đã rời lớp trước buổi này");
        Result previous=repository.sessionResults(sessionId).stream()
                .filter(r -> r.memberId().equals(request.memberId()) && r.activityKey().equals(request.activityKey())).findFirst().orElse(null);
        String snapshot=null;
        TrainingRequest.Result stored=request;
        if(previous!=null) {
            require(Objects.equals(previous.exerciseId(),request.exerciseId()),"Không đổi bài tập của hoạt động đã ghi");
        } else if(request.exerciseId()!=null) {
            var target=repository.exercise(request.exerciseId()).orElseThrow(() -> missing("Exercise"));
            require(Objects.equals(target.classId(),session.classId()) && (target.memberId()==null || target.memberId().equals(request.memberId())),
                    "Bài tập này không được giao cho lớp hoặc hội viên này");
            require(!target.retired() && Set.of("PUBLISHED","COMPLETED").contains(target.planStatus()),"Bài tập phải thuộc kế hoạch đã đăng hoặc đã hoàn thành");
            require(!session.date().isBefore(target.startDate()) && !session.date().isAfter(target.endDate()),"Kế hoạch không bao gồm ngày của buổi này");
            snapshot=serialize(Map.of("planId",target.planId(),"version",target.version(),"exercise",target.exercise()));
            stored=new TrainingRequest.Result(request.memberId(),request.exerciseId(),request.activityKey(),target.exercise().name(),
                    request.actualSets(),request.actualRepetitions(),request.actualDurationSeconds(),request.performance(),request.completionStatus(),request.notes(),request.version());
        }
        if(request.exerciseId()!=null) require(request.activityKey().equals("exercise:"+request.exerciseId()),"Dùng mã bài tập làm khóa hoạt động");
        else require(!request.activityKey().startsWith("exercise:"),"Khóa hoạt động này đã được dành riêng");
        long resultId=repository.upsertResult(participant.attendanceId(),stored,coach.getId(),snapshot);
        repository.advanceTrainingVersion(sessionId);
        audit.log(coach,"TRAINING_RESULT_SAVE","training_results",resultId,Map.of("sessionId",sessionId,"memberId",request.memberId()));
        return repository.sessionResults(sessionId).stream().filter(r -> r.id()==resultId).findFirst().orElseThrow();
    }

    public MemberHistory memberHistory(String email,long classId,long memberId) {
        User coach=actor(email,"COACH");
        requireClassCoach(coach,classId,false);
        require(repository.memberInClass(classId,memberId),"Hội viên không thuộc lớp này");
        var member=repository.memberInfo(memberId).orElseThrow(() -> missing("Member"));
        return new MemberHistory(memberId,member.fullName(),member.fitnessGoal(),repository.attendanceHistory(memberId,classId,null,50),
                repository.memberResults(memberId,classId,null,50),repository.evaluations(memberId,classId,null,50));
    }

    public Page<?> coachHistory(String email,long classId,long memberId,String kind,Long cursor,int limit) {
        User coach=actor(email,"COACH");
        requireClassCoach(coach,classId,false);
        require(repository.memberInClass(classId,memberId),"Hội viên không thuộc lớp này");
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
                if(classId!=null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Loại lịch sử không được hỗ trợ");
                yield repository.memberPlans(memberId,cursor,limit);
            }
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Loại lịch sử không được hỗ trợ");
        };
    }

    @Transactional
    public Evaluation saveEvaluation(String email,Long id,TrainingRequest.Evaluation request) {
        User coach=actor(email,"COACH");
        requireClassCoach(coach,request.classId(),true);
        require(repository.memberInClass(request.classId(),request.memberId()),"Hội viên không thuộc lớp này");
        require(!request.periodEnd().isBefore(request.periodStart()) && !request.evaluationDate().isBefore(request.periodEnd())
                && !request.evaluationDate().isAfter(today()),"Giai đoạn đánh giá phải kết thúc trước hoặc đúng ngày đánh giá, không đánh giá cho tương lai");
        if(id!=null) {
            Evaluation old=repository.evaluation(id,true).orElseThrow(() -> missing("Evaluation"));
            if(!old.coachId().equals(coach.getId())) throw forbidden();
            require(old.classId().equals(request.classId()) && old.memberId().equals(request.memberId()),"Không đổi được đối tượng của đánh giá");
            require(Objects.equals(request.version(),old.version()),"Đánh giá đã bị thay đổi, hãy tải lại trước khi lưu");
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
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Khoảng ngày phải đúng thứ tự và tối đa 366 ngày");
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
    private ResponseStatusException missing(String resource) { return new ResponseStatusException(HttpStatus.NOT_FOUND,resource+" không tìm thấy"); }
    private ResponseStatusException forbidden() { return new ResponseStatusException(HttpStatus.FORBIDDEN,"Bạn không được phân công cho nội dung này"); }
    private String serialize(Object value) {
        try { return json.writeValueAsString(value); } catch(JsonProcessingException e) { throw new IllegalStateException("Cannot serialize prescription",e); }
    }
}
