package com.swp391.sportcentermanagementsystem_v2.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.TrainingRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.TrainingResponse.*;
import com.swp391.sportcentermanagementsystem_v2.entity.*;
import com.swp391.sportcentermanagementsystem_v2.exception.BusinessRuleException;
import com.swp391.sportcentermanagementsystem_v2.repository.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TrainingServiceTest {
    @Mock TrainingRepository repository;
    @Mock UserRepository users;
    @Mock AuditService audit;
    @Mock NotificationService notifications;
    TrainingService service;
    User coach;
    final LocalDate date=LocalDate.of(2026,11,6);

    @BeforeEach void setup() {
        coach=new User(); coach.setId(5L); coach.setEmail("coach@test.local");
        Role role=new Role(); role.setCode("COACH"); coach.setRole(role);
        service=new TrainingService(repository,users,audit,notifications,new ObjectMapper().findAndRegisterModules(),
                Clock.fixed(Instant.parse("2026-11-07T03:00:00Z"),ZoneOffset.UTC));
        when(users.findByEmail(coach.getEmail())).thenReturn(Optional.of(coach));
    }
    Session session(String status,LocalDate day) {
        return new Session(1L,1L,"Swimming",5L,"Coach",1,day,LocalTime.of(18,0),LocalTime.of(19,30),"Pool",status,"OPEN",null,0L);
    }
    void lockedSession(Session s) {
        when(repository.sessionClassId(1)).thenReturn(Optional.of(1L));
        when(repository.sportClass(1,true)).thenReturn(Optional.of(new AssignedClass(1L,"Swimming","OPEN",5L)));
        when(repository.session(1,true)).thenReturn(Optional.of(s));
    }
    Participant participant(String status,boolean eligible) { return new Participant(8L,"Member","Endurance",status==null ? null : 10L,status,null,eligible,false); }
    TrainingRequest.Attendance attendance(String status,boolean finalize) {
        return new TrainingRequest.Attendance(List.of(new TrainingRequest.AttendanceEntry(8L,status,null)),finalize,0L);
    }
    @Test void rejectsAnotherCoachSession() {
        when(repository.session(1,false)).thenReturn(Optional.of(new Session(1L,1L,"Class",4L,"Other",1,date,LocalTime.NOON,
                LocalTime.of(13,0),"Room","SCHEDULED","OPEN",null,0L)));
        assertEquals(403,assertThrows(ResponseStatusException.class,() -> service.session(coach.getEmail(),1)).getStatusCode().value());
    }
    @ParameterizedTest @ValueSource(strings={"MEMBER","RECEPTIONIST","MANAGER"})
    void rejectsOtherRoles(String role) { coach.getRole().setCode(role); assertThrows(ResponseStatusException.class,() -> service.classes(coach.getEmail())); }
    @Test void inactiveCoachCannotAccessTraining() { coach.setIsActive(false); assertThrows(ResponseStatusException.class,() -> service.classes(coach.getEmail())); }
    @ParameterizedTest @ValueSource(strings={"PROPOSED","CANCELED_BY_CENTER"})
    void cannotMarkCanceledOrProposed(String status) { lockedSession(session(status,date)); assertThrows(BusinessRuleException.class,() -> service.saveAttendance(coach.getEmail(),1,attendance("PRESENT",false))); }
    @Test void futureSessionLocked() { lockedSession(session("SCHEDULED",date.plusDays(1))); assertThrows(BusinessRuleException.class,() -> service.saveAttendance(coach.getEmail(),1,attendance("PRESENT",false))); }
    @Test void oldAttendanceLocked() { lockedSession(session("DONE",date.minusDays(3))); assertThrows(BusinessRuleException.class,() -> service.saveAttendance(coach.getEmail(),1,attendance("PRESENT",false))); }
    @ParameterizedTest @CsvSource({"2026-11-06T10:44:59Z,false", "2026-11-06T10:45:00Z,true", "2026-11-08T12:30:00Z,true", "2026-11-08T12:30:01Z,false"})
    void attendanceWindowBoundaries(String instant,boolean editable) {
        service=new TrainingService(repository,users,audit,notifications,new ObjectMapper(),Clock.fixed(Instant.parse(instant),ZoneOffset.UTC));
        when(repository.session(1,false)).thenReturn(Optional.of(session("SCHEDULED",date)));
        assertEquals(editable,service.session(coach.getEmail(),1).attendanceEditable());
    }
    @ParameterizedTest @CsvSource({"2026-11-06T10:44:59Z,0", "2026-11-06T10:45:00Z,1", "2026-11-08T12:30:00Z,1", "2026-11-08T12:30:01Z,0"})
    void dashboardPendingUsesTheExistingAttendanceWindow(String instant,long expected) {
        service=new TrainingService(repository,users,audit,notifications,new ObjectMapper(),Clock.fixed(Instant.parse(instant),ZoneOffset.UTC));
        when(repository.coachSessions(eq(5L),any(),any())).thenReturn(List.of(session("SCHEDULED",date)));
        assertEquals(expected,service.coachDashboard(coach.getEmail()).summary().pendingAttendance());
    }

    @Test void cannotFinalizeBeforeSessionEnd() {
        service=new TrainingService(repository,users,audit,notifications,new ObjectMapper(),Clock.fixed(Instant.parse("2026-11-06T11:30:00Z"),ZoneOffset.UTC));
        lockedSession(session("SCHEDULED",date));
        assertThrows(BusinessRuleException.class,() -> service.saveAttendance(coach.getEmail(),1,new TrainingRequest.Attendance(List.of(),true,0L)));
    }
    @Test void finalizationCapabilityIsClosedBeforeEndEvenWhenAttendanceIsOpen() {
        service=new TrainingService(repository,users,audit,notifications,new ObjectMapper(),Clock.fixed(Instant.parse("2026-11-06T11:30:00Z"),ZoneOffset.UTC));
        when(repository.session(1,false)).thenReturn(Optional.of(session("SCHEDULED",date)));
        var detail=service.session(coach.getEmail(),1);
        assertTrue(detail.attendanceEditable());
        assertFalse(detail.finalizationAllowed());
    }
    @Test void enrollmentCanceledBeforeFirstMarkIsIneligible() {
        lockedSession(session("SCHEDULED",date)); when(repository.roster(1)).thenReturn(List.of());
        assertThrows(BusinessRuleException.class,() -> service.saveAttendance(coach.getEmail(),1,attendance("PRESENT",false)));
        verify(repository,never()).attendance(anyLong(),anyLong(),anyString(),any(),anyLong());
    }
    @Test void rejectsDuplicateBulkEntriesBeforeWriting() {
        lockedSession(session("SCHEDULED",date)); when(repository.roster(1)).thenReturn(List.of(participant(null,true)));
        var entry=new TrainingRequest.AttendanceEntry(8L,"PRESENT",null);
        assertThrows(BusinessRuleException.class,() -> service.saveAttendance(coach.getEmail(),1,new TrainingRequest.Attendance(List.of(entry,entry),false,0L)));
        verify(repository,never()).attendance(anyLong(),anyLong(),anyString(),any(),anyLong());
    }
    @Test void preservesDeclinedMakeup() {
        lockedSession(session("SCHEDULED",date)); when(repository.roster(1)).thenReturn(List.of(participant("DECLINED_MAKEUP",true)));
        assertThrows(BusinessRuleException.class,() -> service.saveAttendance(coach.getEmail(),1,attendance("PRESENT",true)));
    }
    @Test void cannotInvalidateParticipationWithResults() {
        lockedSession(session("DONE",date)); when(repository.roster(1)).thenReturn(List.of(participant("PRESENT",false)));
        when(repository.hasResults(10)).thenReturn(true);
        assertThrows(BusinessRuleException.class,() -> service.saveAttendance(coach.getEmail(),1,attendance("ABSENT",false)));
    }
    @Test void historicalAttendanceCanBeCorrectedAfterEnrollmentChanges() {
        Session s=session("DONE",date); lockedSession(s); when(repository.roster(1)).thenReturn(List.of(participant("PRESENT",false)));
        when(repository.session(1,false)).thenReturn(Optional.of(s));
        service.saveAttendance(coach.getEmail(),1,attendance("LATE",false));
        verify(repository).attendance(1,8,"LATE",null,5);
    }
    @Test void unmarkedIsDistinctAndFinalizationMarksAbsentOnly() {
        Session s=session("SCHEDULED",date); lockedSession(s);
        when(repository.roster(1)).thenReturn(List.of(participant(null,true),new Participant(7L,"Other",null,11L,"DECLINED_MAKEUP",null,true,false)));
        when(repository.session(1,false)).thenReturn(Optional.of(s));
        var response=service.saveAttendance(coach.getEmail(),1,new TrainingRequest.Attendance(List.of(),true,0L));
        assertEquals(1,response.counts().unmarked()); // mock still returns the pre-write state
        verify(repository).attendance(1,8,"ABSENT",null,5);
        verify(repository,never()).attendance(eq(1L),eq(7L),anyString(),any(),anyLong());
        verify(repository).finalizeSession(1);
    }
    TrainingRequest.Result result() { return new TrainingRequest.Result(8L,null,"warmup","Warm up",1,10,60,null,"COMPLETED",null,0L); }
    @Test void resultsRequireCompletedSession() { lockedSession(session("SCHEDULED",date)); assertThrows(BusinessRuleException.class,() -> service.saveResult(coach.getEmail(),1,result())); }
    @ParameterizedTest @ValueSource(strings={"ABSENT","EXCUSED","DECLINED_MAKEUP"})
    void resultsRequireParticipation(String status) { lockedSession(session("DONE",date)); when(repository.roster(1)).thenReturn(List.of(participant(status,true))); assertThrows(BusinessRuleException.class,() -> service.saveResult(coach.getEmail(),1,result())); }
    @Test void unrelatedClassPlanForbidden() {
        when(repository.sportClass(2,true)).thenReturn(Optional.of(new AssignedClass(2L,"Other","OPEN",4L)));
        assertThrows(ResponseStatusException.class,() -> service.savePlan(coach.getEmail(),null,plan(2,date,date,List.of(new TrainingRequest.Exercise(null,"Run",null,1,1,60)))));
    }
    @Test void invalidPlanDatesRejected() {
        when(repository.sportClass(1,true)).thenReturn(Optional.of(new AssignedClass(1L,"Class","OPEN",5L)));
        assertThrows(BusinessRuleException.class,() -> service.savePlan(coach.getEmail(),null,plan(1,date,date.minusDays(1),List.of(new TrainingRequest.Exercise(null,"Run",null,1,1,60)))));
    }
    @Test void emptyPlanRejected() {
        when(repository.sportClass(1,true)).thenReturn(Optional.of(new AssignedClass(1L,"Class","OPEN",5L)));
        assertThrows(BusinessRuleException.class,() -> service.savePlan(coach.getEmail(),null,plan(1,date,date,List.of())));
    }
    TrainingRequest.Plan plan(long classId,LocalDate from,LocalDate to,List<TrainingRequest.Exercise> exercises) {
        return new TrainingRequest.Plan(classId,null,"Plan",null,"Goal",from,to,"PUBLISHED",null,exercises,null);
    }
    @Test void invalidEvaluationPeriodRejected() {
        when(repository.sportClass(1,true)).thenReturn(Optional.of(new AssignedClass(1L,"Class","OPEN",5L)));
        when(repository.memberInClass(1,8)).thenReturn(true);
        assertThrows(BusinessRuleException.class,() -> service.saveEvaluation(coach.getEmail(),null,
            new TrainingRequest.Evaluation(1L,8L,date,date,date.plusDays(1),"Summary",3,null,null,null,null,null)));
    }
    @Test void boundsScheduleQuery() { assertThrows(ResponseStatusException.class,() -> service.sessions(coach.getEmail(),date,date.plusDays(367))); }
}
