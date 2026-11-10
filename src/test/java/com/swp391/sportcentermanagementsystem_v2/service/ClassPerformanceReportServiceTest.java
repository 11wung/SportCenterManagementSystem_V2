package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.ClassPerformanceResponse;
import com.swp391.sportcentermanagementsystem_v2.entity.Course;
import com.swp391.sportcentermanagementsystem_v2.entity.ClassAttendance;
import com.swp391.sportcentermanagementsystem_v2.entity.ClassSession;
import com.swp391.sportcentermanagementsystem_v2.entity.SportCategory;
import com.swp391.sportcentermanagementsystem_v2.entity.SportClass;
import com.swp391.sportcentermanagementsystem_v2.entity.User;
import com.swp391.sportcentermanagementsystem_v2.repository.ClassAttendanceRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.ClassRegistrationRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.ClassSessionRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.SportClassRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClassPerformanceReportServiceTest {

    @Mock SportClassRepository sportClassRepository;
    @Mock ClassRegistrationRepository registrationRepository;
    @Mock ClassSessionRepository sessionRepository;
    @Mock ClassAttendanceRepository attendanceRepository;

    @InjectMocks ClassPerformanceReportService service;

    @Test
    void classWithoutStartedSessionsShowsExplicitNotStartedAttendanceStatus() {
        SportCategory category = new SportCategory();
        category.setName("Tennis");
        Course course = new Course();
        course.setCategory(category);
        User coach = new User();
        coach.setFullName("Coach A");

        SportClass sportClass = new SportClass();
        sportClass.setId(10L);
        sportClass.setName("Tennis Beginner");
        sportClass.setCourse(course);
        sportClass.setCoach(coach);
        sportClass.setMaxCapacity(10);
        sportClass.setStartDate(LocalDate.now().plusDays(5));
        sportClass.setStatus("OPEN");

        when(sportClassRepository.findAllByOrderByStartDateDescIdDesc()).thenReturn(List.of(sportClass));
        when(registrationRepository.countTakenByClassIds(List.of(10L))).thenReturn(Collections.emptyList());
        when(registrationRepository.countRegisteredByClassIds(List.of(10L))).thenReturn(Collections.emptyList());
        when(sessionRepository.findBySportClass_IdIn(List.of(10L))).thenReturn(Collections.emptyList());

        List<ClassPerformanceResponse> result = service.getClassPerformance();

        assertEquals(1, result.size());
        assertEquals("NOT_STARTED", result.get(0).getAttendanceStatus());
        assertEquals("Chưa bắt đầu", result.get(0).getAttendanceStatusLabel());
    }
    @Test
    void attendanceIsSummarizedPerSessionRatherThanForTheWholeClass() {
        SportClass sportClass = new SportClass();
        sportClass.setId(11L);
        sportClass.setName("Tennis Basic");
        sportClass.setMaxCapacity(12);
        sportClass.setStartDate(LocalDate.now().minusDays(10));
        sportClass.setStatus("IN_PROGRESS");

        ClassSession completed = new ClassSession();
        completed.setId(101L);
        completed.setSportClass(sportClass);
        completed.setSessionNo(1);
        completed.setSessionDate(LocalDate.now().minusDays(2));
        completed.setStartTime(java.time.LocalTime.of(9, 0));
        completed.setEndTime(java.time.LocalTime.of(10, 0));
        completed.setStatus("DONE");

        ClassSession upcoming = new ClassSession();
        upcoming.setId(102L);
        upcoming.setSportClass(sportClass);
        upcoming.setSessionNo(2);
        upcoming.setSessionDate(LocalDate.now().plusDays(2));
        upcoming.setStartTime(java.time.LocalTime.of(9, 0));
        upcoming.setEndTime(java.time.LocalTime.of(10, 0));
        upcoming.setStatus("SCHEDULED");

        User member = new User();
        member.setId(55L);
        member.setFullName("Member A");
        ClassAttendance attendance = new ClassAttendance();
        attendance.setId(900L);
        attendance.setSession(completed);
        attendance.setUser(member);
        attendance.setStatus("PRESENT");

        when(sportClassRepository.findAllByOrderByStartDateDescIdDesc()).thenReturn(List.of(sportClass));
        when(registrationRepository.countTakenByClassIds(List.of(11L)))
                .thenReturn(List.<Object[]>of(new Object[] { 11L, 1L }));
        when(registrationRepository.countRegisteredByClassIds(List.of(11L)))
                .thenReturn(List.<Object[]>of(new Object[] { 11L, 1L }));
        when(sessionRepository.findBySportClass_IdIn(List.of(11L))).thenReturn(List.of(completed, upcoming));
        when(attendanceRepository.findBySession_IdIn(List.of(101L, 102L))).thenReturn(List.of(attendance));

        ClassPerformanceResponse result = service.getClassPerformance().get(0);

        assertEquals(2, result.getSessions().size());
        assertEquals("RECORDED", result.getSessions().get(0).attendanceStatus());
        assertEquals("NOT_STARTED", result.getSessions().get(1).attendanceStatus());
        assertEquals("PARTIAL", result.getAttendanceStatus());
        assertEquals("Chưa hoàn tất điểm danh", result.getAttendanceStatusLabel());
        assertEquals(1, result.getSessions().get(1).unmarkedCount());
    }

}
