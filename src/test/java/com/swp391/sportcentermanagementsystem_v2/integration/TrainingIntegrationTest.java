package com.swp391.sportcentermanagementsystem_v2.integration;

import com.fasterxml.jackson.databind.*;
import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.TrainingRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.TrainingResponse.*;
import com.swp391.sportcentermanagementsystem_v2.repository.*;
import com.swp391.sportcentermanagementsystem_v2.security.JwtService;
import com.swp391.sportcentermanagementsystem_v2.service.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Opt-in: requires an isolated PostgreSQL database loaded with schema_v3 and migrations 04–07. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@EnabledIfEnvironmentVariable(named="FLOW4_TEST_DB_URL",matches="jdbc:postgresql:.*")
@Import(TrainingIntegrationTest.TimeConfig.class)
class TrainingIntegrationTest {
    @TestConfiguration static class TimeConfig {
        @Bean @Primary Clock fixedTrainingClock() { return Clock.fixed(Instant.parse("2026-11-07T03:00:00Z"),ZoneOffset.UTC); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",() -> System.getenv("FLOW4_TEST_DB_URL"));
        r.add("spring.datasource.username",() -> System.getenv().getOrDefault("FLOW4_TEST_DB_USER","flow4_test"));
        r.add("spring.datasource.password",() -> System.getenv().getOrDefault("FLOW4_TEST_DB_PASSWORD",""));
        r.add("spring.jpa.hibernate.ddl-auto",() -> "none");
        r.add("app.jwt.secret",() -> Base64.getEncoder().encodeToString(new byte[32]));
    }
    @MockitoBean ClassLifecycleScheduler lifecycleScheduler;
    @MockitoBean SubscriptionScheduler subscriptionScheduler;
    @MockitoBean VoucherScheduler voucherScheduler;
    @Autowired TrainingService service;
    @Autowired TrainingRepository repository;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JwtService jwt;
    @Autowired UserRepository users;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired ClassDisruptionService classDisruption;
    private static final String COACH="coach01@test.local", MEMBER="member001@test.local";
    private final LocalDate day=LocalDate.of(2026,11,6);

    private TrainingRequest.Attendance mark(String status,boolean finalize) {
        return new TrainingRequest.Attendance(List.of(new TrainingRequest.AttendanceEntry(8L,status,"Test attendance")),finalize,version());
    }
    private TrainingRequest.Plan plan(Long memberId,List<TrainingRequest.Exercise> exercises,Integer version) {
        return new TrainingRequest.Plan(1L,memberId,"Endurance", "Weekly plan","Improve endurance",day,day.plusDays(14),"PUBLISHED","Coach advice",exercises,version);
    }
    private TrainingRequest.Exercise exercise(String name) { return new TrainingRequest.Exercise(null,name,"Controlled movement",3,10,60); }
    private TrainingRequest.Result result(Long exerciseId,String key,String name,int reps) {
        return new TrainingRequest.Result(8L,exerciseId,key,name,3,reps,60,"Good form","COMPLETED","Steady progress",version());
    }
    private long version() { return repository.session(1,false).orElseThrow().version(); }
    private String token(String email) { return "Bearer "+jwt.generateToken(users.findByEmail(email).orElseThrow()); }

    @Test void coachAndMemberJourneyPersistsEverythingAndPreservesHistory() throws Exception {
        assertEquals(1,service.session(COACH,1).counts().unmarked());
        mvc.perform(put("/api/coach/training/sessions/1/attendance").header("Authorization",token(COACH))
            .contentType("application/json").content(json.writeValueAsString(mark("PRESENT",true))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.session.status").value("DONE"));
        Plan saved=service.savePlan(COACH,null,plan(null,List.of(exercise("Swim 100m")),null));
        Long exerciseId=saved.exercises().getFirst().id();
        Result result=service.saveResult(COACH,1,result(exerciseId,"exercise:"+exerciseId,"Swim 100m",10));
        Result updated=service.saveResult(COACH,1,result(exerciseId,"exercise:"+exerciseId,"Swim 100m",12));
        assertEquals(result.id(),updated.id()); assertEquals(12,updated.actualRepetitions());
        var e=saved.exercises().getFirst();
        service.savePlan(COACH,saved.id(),plan(null,List.of(new TrainingRequest.Exercise(e.id(),"Swim 200m","New instructions",4,20,120)),saved.version()));
        assertEquals("Swim 100m",repository.sessionResults(1).getFirst().prescription().get("exercise").get("name").asText());
        service.savePlan(COACH,saved.id(),plan(null,List.of(exercise("Warm up")),saved.version()+1));
        assertTrue(repository.exercise(exerciseId).orElseThrow().retired());
        service.saveResult(COACH,1,result(exerciseId,"exercise:"+exerciseId,"Swim 100m",14));
        service.saveEvaluation(COACH,null,new TrainingRequest.Evaluation(1L,8L,day.plusDays(1),day,day,"Consistent progress",4,"Endurance","Technique","Practice breathing","Keep going",null));
        jdbc.update("UPDATE class_registrations SET status='LEFT',canceled_at='2026-11-07T03:00Z' WHERE class_id=1 AND user_id=8");
        mvc.perform(get("/api/member/training").header("Authorization",token(MEMBER)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.statistics.attendedSessions").value(1))
            .andExpect(jsonPath("$.results.items[0].actualRepetitions").value(14))
            .andExpect(jsonPath("$.evaluations.items[0].rating").value(4)).andExpect(jsonPath("$.plans.items[0].title").value("Endurance"));
        assertFalse(service.session(COACH,1).roster().getFirst().eligible());
        service.saveAttendance(COACH,1,mark("LATE",false));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM class_attendances WHERE session_id=1 AND user_id=8",Integer.class));
    }
    @Test void bulkAttendanceIsUpsertAndFinalizesOnlyUnmarked() {
        // An existing declined makeup record is owned by Flow 2 and must not be overwritten.
        jdbc.update("INSERT INTO class_attendances(session_id,user_id,status,recorded_by) VALUES(1,7,'DECLINED_MAKEUP',7)");
        service.saveAttendance(COACH,1,mark("PRESENT",false));
        service.saveAttendance(COACH,1,mark("LATE",false));
        var detail=service.saveAttendance(COACH,1,new TrainingRequest.Attendance(List.of(),true,version()));
        assertEquals(1,detail.counts().late()); assertEquals(1,detail.counts().declined()); assertEquals(0,detail.counts().unmarked());
        assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM class_attendances WHERE session_id=1",Integer.class));
    }
    @Test void finalizeMarksAbsenceAndResultsRejectAbsence() {
        var detail=service.saveAttendance(COACH,1,new TrainingRequest.Attendance(List.of(),true,version()));
        assertEquals(1,detail.counts().absent());
        assertThrows(RuntimeException.class,() -> service.saveResult(COACH,1,result(null,"warmup","Warm up",10)));
    }
    @Test void canceledRegistrationCannotBeMarkedAndUnmarkedRemainsUnmarked() {
        assertEquals(1,service.session(COACH,1).counts().unmarked());
        service.saveAttendance(COACH,1,new TrainingRequest.Attendance(List.of(),false,version()));
        assertEquals(1,service.session(COACH,1).counts().unmarked());
        jdbc.update("UPDATE class_registrations SET status='CANCELED',canceled_at=now() WHERE class_id=1 AND user_id=8");
        assertThrows(RuntimeException.class,() -> service.saveAttendance(COACH,1,mark("PRESENT",false)));
    }
    @Test void personalPlansAndMemberDataArePrivate() throws Exception {
        service.savePlan(COACH,null,plan(8L,List.of(exercise("Private exercise")),null));
        var other=service.dashboard("member002@test.local",null,null);
        assertTrue(other.plans().items().isEmpty()); assertTrue(other.results().items().isEmpty()); assertNull(other.statistics().attendanceRate());
        mvc.perform(get("/api/member/training").param("memberId","8").header("Authorization",token("member002@test.local")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.plans.items").isEmpty());
    }
    @Test void formerMembersCannotSeeClassPlansForFutureSessions() {
        service.saveAttendance(COACH,1,mark("PRESENT",true));
        jdbc.update("UPDATE class_registrations SET status='LEFT',canceled_at='2026-11-07T03:00Z' WHERE class_id=1 AND user_id=8");
        service.savePlan(COACH,null,new TrainingRequest.Plan(1L,null,"Future class plan",null,"Future objective",
            day.plusDays(2),day.plusDays(14),"PUBLISHED",null,List.of(exercise("Future exercise")),null));
        assertTrue(service.dashboard(MEMBER,null,null).plans().items().isEmpty());
        assertEquals(1,service.dashboard(MEMBER,null,null).attendance().items().size());
    }
    @Test void endpointRoleAndOwnershipChecks() throws Exception {
        mvc.perform(get("/api/coach/training/classes")).andExpect(status().isUnauthorized());
        for(String email:List.of(MEMBER,"manager@test.local","receptionist01@test.local"))
            mvc.perform(get("/api/coach/training/classes").header("Authorization",token(email))).andExpect(status().isForbidden());
        mvc.perform(get("/api/coach/training/sessions/1").header("Authorization",token("coach02@test.local"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/member/training").header("Authorization",token(COACH))).andExpect(status().isForbidden());
        mvc.perform(post("/api/member/training/results").header("Authorization",token(MEMBER))).andExpect(status().isForbidden());
        mvc.perform(get("/api/member/training").header("Authorization","Bearer invalid-token")).andExpect(status().isUnauthorized());
    }
    @Test void beanValidationRejectsEmptyPlansNegativeResultsAndUnknownStatuses() throws Exception {
        mvc.perform(post("/api/coach/training/plans").header("Authorization",token(COACH)).contentType("application/json")
            .content(json.writeValueAsString(plan(null,List.of(),null)))).andExpect(status().isBadRequest());
        mvc.perform(put("/api/coach/training/sessions/1/results").header("Authorization",token(COACH)).contentType("application/json")
            .content(json.writeValueAsString(result(null,"warmup","Warm up",-1)))).andExpect(status().isBadRequest());
        mvc.perform(put("/api/coach/training/sessions/1/attendance").header("Authorization",token(COACH)).contentType("application/json")
            .content(json.writeValueAsString(mark("UNKNOWN",false)))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/coach/training/sessions?from=2026-11-07&to=2026-11-01").header("Authorization",token(COACH)))
            .andExpect(status().isBadRequest());
    }
    @Test void stalePlanEditsAndCrossPlanExerciseReferencesAreRejected() {
        Plan p=service.savePlan(COACH,null,plan(null,List.of(exercise("Run")),null));
        Plan other=service.savePlan(COACH,null,plan(null,List.of(exercise("Swim")),null));
        assertThrows(RuntimeException.class,() -> service.savePlan(COACH,p.id(),plan(null,List.of(exercise("Change")),99)));
        var e=other.exercises().getFirst();
        assertThrows(RuntimeException.class,() -> service.savePlan(COACH,p.id(),plan(null,
            List.of(new TrainingRequest.Exercise(e.id(),e.name(),e.instructions(),e.sets(),e.repetitions(),e.durationSeconds())),p.version())));
    }
    @Test void evaluationsUpdateWithVersionAndHistoryIsClassScoped() {
        Evaluation e=service.saveEvaluation(COACH,null,new TrainingRequest.Evaluation(1L,8L,day,day,day,"Progress",3,null,null,null,null,null));
        var changed=service.saveEvaluation(COACH,e.id(),new TrainingRequest.Evaluation(1L,8L,day,day,day,"Improved",4,null,null,null,"Well done",e.version()));
        assertEquals(2,changed.version());
        assertEquals("Improved",service.memberHistory(COACH,1,8).evaluations().items().getFirst().summary());
        assertThrows(RuntimeException.class,() -> service.memberHistory(COACH,2,7));
    }
    @Test void evaluationDateFollowsCenterDayInsteadOfValidatorServerDay() throws Exception {
        LocalDate centerDay=day.plusDays(1);
        var request=new TrainingRequest.Evaluation(1L,8L,centerDay,day,centerDay,"Center day evaluation",4,null,null,null,null,null);
        // At 01:30 in Vietnam the UTC server still has the previous calendar date.
        try(var factory=jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            var validator=factory.usingContext().clockProvider(() ->
                Clock.fixed(Instant.parse("2026-11-06T18:30:00Z"),ZoneOffset.UTC)).getValidator();
            assertTrue(validator.validate(request).isEmpty(),"Calendar business validation must use the center clock in the service");
        }
        mvc.perform(post("/api/coach/training/evaluations").header("Authorization",token(COACH))
            .contentType("application/json").content(json.writeValueAsString(request)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.evaluationDate").value(centerDay.toString()));
        assertThrows(RuntimeException.class,() -> service.saveEvaluation(COACH,null,
            new TrainingRequest.Evaluation(1L,8L,centerDay.plusDays(1),day,centerDay,"Future day",4,null,null,null,null,null)));
    }
    @Test void withdrawalBeforeSessionLocksEarlyAttendanceAndExcludesItFromStatistics() {
        service.savePlan(COACH,null,plan(null,List.of(exercise("Class exercise")),null));
        // An early mark can exist when a member leaves during the 15-minute pre-session window.
        repository.attendance(1,8,"PRESENT","Early arrival",5);
        jdbc.update("UPDATE class_registrations SET status='LEFT',canceled_at='2026-11-06T10:55Z' WHERE class_id=1 AND user_id=8");
        var detail=service.session(COACH,1);
        assertTrue(detail.roster().getFirst().withdrawnBeforeSession());
        assertEquals(0,detail.counts().present());
        service.saveAttendance(COACH,1,new TrainingRequest.Attendance(List.of(),true,version()));
        var dashboard=service.dashboard(MEMBER,null,null);
        assertEquals(0,dashboard.statistics().markedSessions());
        assertEquals(0,dashboard.statistics().completedSessions());
        assertNull(dashboard.statistics().attendanceRate());
        assertTrue(dashboard.plans().items().isEmpty());
        assertTrue(dashboard.attendance().items().getFirst().withdrawnBeforeSession());
        assertEquals("PRESENT",dashboard.attendance().items().getFirst().status()); // the original record survives
        assertThrows(RuntimeException.class,() -> service.saveAttendance(COACH,1,mark("LATE",false)));
        assertThrows(RuntimeException.class,() -> service.saveResult(COACH,1,result(null,"warmup","Warm up",10)));
    }
    @Test void departedRecipientDoesNotPreventArchivingAnIndividualPlan() {
        Plan p=service.savePlan(COACH,null,plan(8L,List.of(exercise("Private exercise")),null));
        jdbc.update("UPDATE class_registrations SET status='LEFT',canceled_at='2026-11-06T10:55Z' WHERE class_id=1 AND user_id=8");
        assertEquals(8L,repository.classRoster(1).getFirst().memberId());
        var e=p.exercises().getFirst();
        Plan archived=service.savePlan(COACH,p.id(),new TrainingRequest.Plan(1L,8L,p.title(),p.description(),p.objective(),
            p.startDate(),p.endDate(),"ARCHIVED",p.coachNotes(),List.of(new TrainingRequest.Exercise(e.id(),e.name(),e.instructions(),e.sets(),e.repetitions(),e.durationSeconds())),p.version()));
        assertEquals("ARCHIVED",archived.status());
        assertTrue(service.dashboard(MEMBER,null,null).plans().items().isEmpty());
        assertThrows(RuntimeException.class,() -> service.savePlan(COACH,p.id(),plan(8L,List.of(exercise("Reassigned")),archived.version())));
    }
    @Test void coachReassignmentPreservesAuthorReadAccessButRevokesEdits() throws Exception {
        Plan p=service.savePlan(COACH,null,plan(null,List.of(exercise("Run")),null));
        Evaluation e=service.saveEvaluation(COACH,null,new TrainingRequest.Evaluation(1L,8L,day,day,day,"Progress",3,null,null,null,null,null));
        long replacement=users.findByEmail("coach02@test.local").orElseThrow().getId();
        jdbc.update("""
            INSERT INTO coach_sports(coach_id,category_id)
            SELECT ?,co.category_id FROM sport_classes c JOIN courses co ON co.id=c.course_id WHERE c.id=1
            ON CONFLICT DO NOTHING
            """,replacement);
        jdbc.update("UPDATE sport_classes SET coach_id=? WHERE id=1",replacement);
        assertEquals(p.id(),service.plan(COACH,p.id()).id());
        mvc.perform(put("/api/coach/training/plans/"+p.id()).header("Authorization",token(COACH)).contentType("application/json")
            .content(json.writeValueAsString(plan(null,List.of(exercise("Change")),p.version())))).andExpect(status().isForbidden());
        mvc.perform(put("/api/coach/training/evaluations/"+e.id()).header("Authorization",token("coach02@test.local")).contentType("application/json")
            .content(json.writeValueAsString(new TrainingRequest.Evaluation(1L,8L,LocalDate.now(),LocalDate.now(),LocalDate.now(),"Changed",4,null,null,null,null,e.version()))))
            .andExpect(status().isForbidden());
        assertEquals(1,service.memberHistory("coach02@test.local",1,8).evaluations().items().size());
        // The original coach still owns the past session even though the class has a new coach.
        assertEquals(5L,service.session(COACH,1).session().coachId());
    }
    @Test void canceledClassKeepsReadableProgressAndLocksSessionWrites() throws Exception {
        service.saveAttendance(COACH,1,mark("PRESENT",true));
        service.saveResult(COACH,1,result(null,"warmup","Warm up",10));
        jdbc.update("UPDATE sport_classes SET status='CANCELED' WHERE id=1");
        assertEquals(1,service.memberHistory(COACH,1,8).results().items().size());
        assertEquals(1,service.dashboard(MEMBER,null,null).results().items().size());
        assertFalse(service.session(COACH,1).attendanceEditable());
        assertFalse(service.session(COACH,1).resultsEditable());
        mvc.perform(put("/api/coach/training/sessions/1/results").header("Authorization",token(COACH)).contentType("application/json")
            .content(json.writeValueAsString(result(null,"warmup","Warm up",12)))).andExpect(status().isConflict());
    }
    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void databaseFailureRollsBackEarlierRowsAndAuditAtomically() {
        int before=jdbc.queryForObject("SELECT count(*) FROM class_attendances",Integer.class);
        var tx=new TransactionTemplate(transactionManager);
        assertThrows(RuntimeException.class,() -> tx.executeWithoutResult(status -> {
            // Simulate a storage failure on the second row after the first insert has succeeded.
            repository.attendance(1,7,"EXCUSED",null,5);
            service.saveAttendance(COACH,1,new TrainingRequest.Attendance(List.of(
                new TrainingRequest.AttendanceEntry(8L,"PRESENT",null),
                new TrainingRequest.AttendanceEntry(7L,"EXCUSED","x".repeat(501))),true,version()));
        }));
        assertEquals(before,jdbc.queryForObject("SELECT count(*) FROM class_attendances",Integer.class));
        assertEquals("SCHEDULED",repository.session(1,false).orElseThrow().status());
    }
    @Test void databaseRejectsDuplicateAttendanceAndProtectsHistoricalSessionDeletion() {
        service.saveAttendance(COACH,1,mark("PRESENT",true));
        assertThrows(RuntimeException.class,() -> jdbc.update("DELETE FROM class_sessions WHERE id=1"));
    }
    @Test void databaseEnforcesAttendanceUniqueness() {
        service.saveAttendance(COACH,1,mark("PRESENT",false));
        assertThrows(RuntimeException.class,() -> jdbc.update("INSERT INTO class_attendances(session_id,user_id,status) VALUES(1,8,'LATE')"));
    }

    @Test void canceledRegistrationDoesNotInvalidateAValidReregistration() {
        jdbc.update("INSERT INTO class_registrations(class_id,user_id,status,registered_at,canceled_at) VALUES(1,8,'CANCELED','2026-10-20T03:00Z','2026-10-21T03:00Z')");
        service.savePlan(COACH,null,plan(null,List.of(exercise("Run")),null));
        assertFalse(service.session(COACH,1).roster().getFirst().withdrawnBeforeSession());
        service.saveAttendance(COACH,1,mark("PRESENT",true));
        assertFalse(service.session(COACH,1).roster().getFirst().withdrawnBeforeSession());
        service.saveAttendance(COACH,1,mark("LATE",false));
        service.saveResult(COACH,1,result(null,"reregister","Run",10));
        assertEquals(1,repository.statistics(8).attendedSessions());
        assertEquals(100.0,repository.statistics(8).attendanceRate());
        jdbc.update("UPDATE class_registrations SET status='LEFT',canceled_at='2026-11-07T02:00Z' WHERE id=1");
        assertFalse(service.session(COACH,1).roster().getFirst().withdrawnBeforeSession());
        assertEquals(1,service.dashboard(MEMBER,null,null).plans().items().size());
    }

    @Test void reregistrationAfterSessionDoesNotRestoreInvalidEarlyAttendance() {
        repository.attendance(1,8,"PRESENT","Early",5);
        jdbc.update("UPDATE class_registrations SET status='CANCELED',canceled_at='2026-11-06T10:55Z' WHERE id=1");
        jdbc.update("INSERT INTO class_registrations(class_id,user_id,status,registered_at) VALUES(1,8,'REGISTERED','2026-11-07T02:00Z')");
        assertTrue(service.session(COACH,1).roster().getFirst().withdrawnBeforeSession());
        assertThrows(RuntimeException.class,() -> service.saveAttendance(COACH,1,mark("LATE",false)));
        assertEquals(0,repository.statistics(8).markedSessions());
    }

    @Test void centerCancellationExcludesEarlyMarksButKeepsReadableHistory() {
        repository.attendance(1,8,"ABSENT","Early mark",5);
        // Invoke the real Flow 2 transition, while this seeded session is still in the future for its clock.
        classDisruption.cancelSession(1L,"Pool maintenance","manager@test.local");
        var dashboard=service.dashboard(MEMBER,null,null);
        assertEquals(0,dashboard.statistics().markedSessions());
        assertNull(dashboard.statistics().attendanceRate());
        var history=dashboard.attendance().items().getFirst();
        assertEquals("ABSENT",history.status());
        assertEquals("CANCELED_BY_CENTER",history.sessionStatus());
        assertEquals("Pool maintenance",history.cancelReason());
        assertEquals(0,service.session(COACH,1).counts().absent());
    }

    @Test void canceledSessionAlsoExcludesPresentAndCompletedActivitiesFromStatistics() {
        service.saveAttendance(COACH,1,mark("PRESENT",true));
        service.saveResult(COACH,1,result(null,"warmup","Warm up",10));
        jdbc.update("UPDATE class_sessions SET status='CANCELED_BY_CENTER',cancel_reason='Audit historical correction' WHERE id=1");
        var statistics=repository.statistics(8);
        assertEquals(0,statistics.attendedSessions());
        assertEquals(0,statistics.completedSessions());
        assertEquals(0,statistics.completedActivities());
        assertEquals(1,service.dashboard(MEMBER,null,null).results().items().size());
    }

    @Test void evaluationRemainsAccessibleToCoachAfterMemberLeavesWithoutAttendance() {
        var e=service.saveEvaluation(COACH,null,new TrainingRequest.Evaluation(1L,8L,day,day,day,"Initial assessment",3,null,null,null,"Feedback",null));
        jdbc.update("UPDATE class_registrations SET status='LEFT',canceled_at='2026-11-07T02:00Z' WHERE id=1");
        var former=service.classRoster(COACH,1).getFirst();
        assertEquals(8L,former.memberId()); assertFalse(former.eligible());
        assertEquals(e.id(),service.memberHistory(COACH,1,8).evaluations().items().getFirst().id());
        service.saveEvaluation(COACH,e.id(),new TrainingRequest.Evaluation(1L,8L,day,day,day,"Corrected assessment",4,null,null,null,"Updated",e.version()));
        assertEquals("Corrected assessment",service.dashboard(MEMBER,null,null).evaluations().items().getFirst().summary());
        assertThrows(RuntimeException.class,() -> service.memberHistory("coach02@test.local",1,8));
    }

    @Test void staleAttendanceAndFinalizationCannotOverwriteAnotherTab() throws Exception {
        var stale=mark("PRESENT",false);
        service.saveAttendance(COACH,1,mark("LATE",false));
        mvc.perform(put("/api/coach/training/sessions/1/attendance").header("Authorization",token(COACH))
            .contentType("application/json").content(json.writeValueAsString(stale))).andExpect(status().isConflict());
        assertThrows(RuntimeException.class,() -> service.saveAttendance(COACH,1,new TrainingRequest.Attendance(List.of(),true,stale.version())));
        assertEquals("LATE",service.session(COACH,1).roster().getFirst().status());
        assertEquals("SCHEDULED",repository.session(1,false).orElseThrow().status());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM class_attendances WHERE session_id=1 AND user_id=8",Integer.class));
    }

    @Test void staleResultCannotOverwriteNewerValuesOrAttendance() {
        service.saveAttendance(COACH,1,mark("PRESENT",true));
        var stale=result(null,"warmup","Warm up",5);
        var saved=service.saveResult(COACH,1,result(null,"warmup","Warm up",10));
        assertThrows(RuntimeException.class,() -> service.saveResult(COACH,1,stale));
        assertEquals(10,repository.sessionResults(1).getFirst().actualRepetitions());
        var updated=service.saveResult(COACH,1,result(null,"warmup","Warm up",12));
        assertEquals(saved.id(),updated.id());
        assertEquals(12,updated.actualRepetitions());
    }

    @Test void fractionalNumbersAndMissingRevisionAreRejectedAtHttpBoundary() throws Exception {
        String valid=json.writeValueAsString(plan(null,List.of(exercise("Run")),null));
        int before=jdbc.queryForObject("SELECT count(*) FROM workout_plans",Integer.class);
        for(String value:List.of("-0.4","1.7","10000.1","\"3\"")) {
            mvc.perform(post("/api/coach/training/plans").header("Authorization",token(COACH)).contentType("application/json")
                .content(valid.replace("\"sets\":3","\"sets\":"+value))).andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/coach/training/plans").header("Authorization",token(COACH)).contentType("application/json")
            .content(valid.replace("\"classId\":1","\"classId\":1.7"))).andExpect(status().isBadRequest());
        assertEquals(before,jdbc.queryForObject("SELECT count(*) FROM workout_plans",Integer.class));
        service.saveAttendance(COACH,1,mark("PRESENT",true));
        String result=json.writeValueAsString(result(null,"warmup","Warm up",10));
        mvc.perform(put("/api/coach/training/sessions/1/results").header("Authorization",token(COACH)).contentType("application/json")
            .content(result.replace("\"actualSets\":3","\"actualSets\":-0.4"))).andExpect(status().isBadRequest());
        mvc.perform(put("/api/coach/training/sessions/1/attendance").header("Authorization",token(COACH)).contentType("application/json")
            .content("{\"entries\":[],\"finalizeSession\":false}")).andExpect(status().isBadRequest());
        assertTrue(repository.sessionResults(1).isEmpty());
    }

    @Test void cursorPagesReachBeyondOneThousandResultsAndPreserveMemberScope() throws Exception {
        service.saveAttendance(COACH,1,mark("PRESENT",true));
        long attendance=service.session(COACH,1).roster().getFirst().attendanceId();
        jdbc.update("""
            INSERT INTO training_results(attendance_id,activity_key,activity_name,completion_status,recorded_by)
            SELECT ?,'history-'||n,'Historical activity '||n,'COMPLETED',5 FROM generate_series(1,1001) n
            """,attendance);
        Set<Long> ids=new HashSet<>(); Long cursor=null;
        do {
            var page=repository.memberResults(8,null,cursor,100);
            for(var item:page.items()) assertTrue(ids.add(item.id()),"Duplicate across cursor pages");
            cursor=page.nextCursor();
        } while(cursor!=null);
        assertEquals(1001,ids.size());
        assertEquals(1001,repository.statistics(8).completedActivities());
        mvc.perform(get("/api/member/training/history/results?limit=100").header("Authorization",token(MEMBER)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(100)).andExpect(jsonPath("$.nextCursor").isNumber());
        mvc.perform(get("/api/member/training/history/results?memberId=8&limit=100").header("Authorization",token("member002@test.local")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
        mvc.perform(get("/api/coach/training/classes/1/members/8/history/results").header("Authorization",token("coach02@test.local")))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/member/training/history/results?limit=101").header("Authorization",token(MEMBER))).andExpect(status().isBadRequest());
    }

    @Test void dashboardUsesRealScopedCountsAndDoesNotCapUpcomingAtThirtyDays() throws Exception {
        jdbc.update("UPDATE class_sessions SET session_date='2026-11-07',start_time='10:15',end_time='11:30' WHERE id=1");
        jdbc.update("UPDATE class_sessions SET session_date='2027-02-01' WHERE id=2");
        var dashboard=service.coachDashboard(COACH);
        assertEquals(LocalDate.of(2026,11,7),dashboard.today());
        assertEquals(1,dashboard.summary().myClasses());
        assertEquals(1,dashboard.summary().todaySessions());
        assertEquals(12,dashboard.summary().upcomingSessions());
        assertEquals(1,dashboard.summary().pendingAttendance());
        assertTrue(dashboard.todaySessions().getFirst().attendanceEditable());
        assertFalse(dashboard.todaySessions().getFirst().finalizationAllowed());
        assertEquals(1,dashboard.todaySessions().getFirst().members());
        assertNotNull(dashboard.todaySessions().getFirst().sport());
        assertNotNull(dashboard.classes().getFirst().schedule());
        assertEquals(1,dashboard.classes().getFirst().nextSession().id());
        mvc.perform(get("/api/coach/training/dashboard").header("Authorization",token(COACH)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.summary.upcomingSessions").value(12))
            .andExpect(jsonPath("$.todaySessions[0].session.id").value(1));
        jdbc.update("UPDATE class_sessions SET status='CANCELED_BY_CENTER',cancel_reason='Weather' WHERE id=1");
        var canceled=service.coachDashboard(COACH);
        assertEquals(0,canceled.summary().todaySessions());
        assertEquals(11,canceled.summary().upcomingSessions());
        assertEquals(0,canceled.summary().pendingAttendance());
        assertFalse(canceled.todaySessions().getFirst().attendanceEditable());
    }

    @Test void dashboardRejectsOtherRolesAndNeverTrustsCoachIdFromClient() throws Exception {
        mvc.perform(get("/api/coach/training/dashboard")).andExpect(status().isUnauthorized());
        for(String email:List.of(MEMBER,"manager@test.local","receptionist01@test.local"))
            mvc.perform(get("/api/coach/training/dashboard").header("Authorization",token(email))).andExpect(status().isForbidden());
        mvc.perform(get("/api/coach/training/dashboard?coachId=5").header("Authorization",token("coach02@test.local")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.classes[?(@.id == 1)]").isEmpty());
    }

    @Test void dashboardRefreshReflectsSavedAttendanceAndFinalization() {
        jdbc.update("UPDATE class_sessions SET session_date='2026-11-07',start_time='08:00',end_time='09:30' WHERE id=1");
        assertEquals(1,service.coachDashboard(COACH).summary().pendingAttendance());
        service.saveAttendance(COACH,1,mark("PRESENT",false));
        var saved=service.coachDashboard(COACH);
        assertEquals(1,saved.todaySessions().getFirst().marked());
        assertEquals(1,saved.summary().pendingAttendance(),"Saving all rows does not finalize attendance");
        service.saveAttendance(COACH,1,mark("PRESENT",true));
        var finalized=service.coachDashboard(COACH);
        assertEquals(0,finalized.summary().pendingAttendance());
        assertNotNull(finalized.todaySessions().getFirst().session().finalizedAt());
        assertTrue(finalized.recentActivities().stream().anyMatch(a -> a.finalized()));
    }

    @Test void dashboardReturnsHonestEmptyStateForAnUnassignedCoach() {
        jdbc.update("""
            INSERT INTO users(role_id,full_name,email,phone,password_hash)
            SELECT role_id,'Unassigned Coach','empty-coach@test.local','0999999999',password_hash FROM users WHERE id=5
            """);
        var empty=service.coachDashboard("empty-coach@test.local");
        assertEquals(0,empty.summary().myClasses()); assertEquals(0,empty.summary().todaySessions());
        assertEquals(0,empty.summary().upcomingSessions()); assertEquals(0,empty.summary().pendingAttendance());
        assertTrue(empty.classes().isEmpty()); assertTrue(empty.todaySessions().isEmpty());
        assertTrue(empty.pendingSessions().isEmpty()); assertTrue(empty.recentActivities().isEmpty());
    }

    @Test void dashboardIncludesSubstituteAssignmentsWithoutGrantingClassOwnership() {
        long replacement=users.findByEmail("coach02@test.local").orElseThrow().getId();
        jdbc.update("""
            INSERT INTO coach_sports(coach_id,category_id)
            SELECT ?,co.category_id FROM sport_classes c JOIN courses co ON co.id=c.course_id WHERE c.id=1
            ON CONFLICT DO NOTHING
            """,replacement);
        jdbc.update("UPDATE class_sessions SET coach_id=? WHERE id=1",replacement);
        var substitute=service.coachDashboard("coach02@test.local");
        assertTrue(substitute.classes().stream().anyMatch(c -> c.id()==1 && c.coachId()==5));
        assertTrue(substitute.pendingSessions().stream().anyMatch(s -> s.session().id()==1));
        assertThrows(RuntimeException.class,() -> service.classRoster("coach02@test.local",1));
        assertTrue(service.coachDashboard(COACH).pendingSessions().stream().noneMatch(s -> s.session().id()==1));
    }

    @Test void dashboardRecentActivitiesUseOnlyTheAuthenticatedCoachsRealWrites() {
        service.saveAttendance(COACH,1,mark("PRESENT",true));
        service.savePlan(COACH,null,plan(null,List.of(exercise("Swim")),null));
        service.saveResult(COACH,1,result(null,"dashboard-warmup","Warm up",10));
        service.saveEvaluation(COACH,null,new TrainingRequest.Evaluation(1L,8L,day,day,day,"Dashboard feedback",4,null,null,null,null,null));
        var actions=service.coachDashboard(COACH).recentActivities().stream().map(a -> a.action()).collect(java.util.stream.Collectors.toSet());
        assertTrue(actions.containsAll(Set.of("TRAINING_ATTENDANCE_SAVE","TRAINING_PLAN_CREATE","TRAINING_RESULT_SAVE","TRAINING_EVALUATION_SAVE")));
        assertTrue(service.coachDashboard("coach02@test.local").recentActivities().isEmpty());
    }

    @Test void dashboardRejectsExpiredSignedAuthentication() throws Exception {
        var expiredJwt=new JwtService(Base64.getEncoder().encodeToString(new byte[32]),-1000);
        mvc.perform(get("/api/coach/training/dashboard").header("Authorization","Bearer "+expiredJwt.generateToken(users.findByEmail(COACH).orElseThrow())))
            .andExpect(status().isUnauthorized());
    }

    @Test void plansEvaluationsAndAttendanceAllExposeStableContinuation() {
        for(int i=0;i<3;i++) {
            service.savePlan(COACH,null,plan(null,List.of(exercise("Exercise "+i)),null));
            service.saveEvaluation(COACH,null,new TrainingRequest.Evaluation(1L,8L,day,day,day,"Review "+i,3,null,null,null,null,null));
        }
        var p1=repository.memberPlans(8,null,2);
        var p2=repository.memberPlans(8,p1.nextCursor(),2);
        assertEquals(2,p1.items().size()); assertEquals(1,p2.items().size()); assertNull(p2.nextCursor());
        var coach=service.plans(COACH,null,2);
        assertEquals(p1.items().getFirst().id(),coach.items().getFirst().id());
        var e1=repository.evaluations(8,1L,null,2);
        assertEquals(1,repository.evaluations(8,1L,e1.nextCursor(),2).items().size());
        repository.attendance(1,8,"PRESENT",null,5);
        repository.attendance(2,8,"LATE",null,5);
        var a1=repository.attendanceHistory(8,null,null,1);
        var a2=repository.attendanceHistory(8,null,a1.nextCursor(),1);
        assertNotEquals(a1.items().getFirst().sessionId(),a2.items().getFirst().sessionId());
        assertNull(a2.nextCursor());
    }
}
