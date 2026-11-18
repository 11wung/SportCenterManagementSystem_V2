package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.MakeupProposalResponse;
import com.swp391.sportcentermanagementsystem_v2.entity.ClassSession;
import com.swp391.sportcentermanagementsystem_v2.entity.Course;
import com.swp391.sportcentermanagementsystem_v2.entity.FacilityRoom;
import com.swp391.sportcentermanagementsystem_v2.entity.SportCategory;
import com.swp391.sportcentermanagementsystem_v2.entity.SportClass;
import com.swp391.sportcentermanagementsystem_v2.entity.TimeSlot;
import com.swp391.sportcentermanagementsystem_v2.entity.User;
import com.swp391.sportcentermanagementsystem_v2.exception.BusinessRuleException;
import com.swp391.sportcentermanagementsystem_v2.repository.ClassRegistrationRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.ClassScheduleRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.ClassSessionRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.FacilityRoomRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.SportClassRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.TimeSlotRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

// Buổi bù ưu tiên trong khóa: báo trước, bỏ ngày lớp đã có buổi, loại chỗ làm quá nửa lớp bị trùng lịch, kèm một chỗ
// sau khóa khi chỗ tốt nhất trong khóa vẫn có người bị trùng
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ClassMakeupInCourseTest {

    private static final LocalDate TODAY = LocalDate.now(SportClassService.CENTER_ZONE);
    private static final LocalDate COURSE_END = TODAY.plusDays(10);

    @Mock
    ClassSessionRepository sessionRepository;
    @Mock
    ClassScheduleRepository scheduleRepository;
    @Mock
    SportClassRepository sportClassRepository;
    @Mock
    ClassRegistrationRepository registrationRepository;
    @Mock
    FacilityRoomRepository facilityRoomRepository;
    @Mock
    TimeSlotRepository timeSlotRepository;
    @Mock
    UserRepository userRepository;
    @Mock
    ClassSessionService classSessionService;
    @Mock
    CoachSlotService coachSlotService;
    @Mock
    SystemSettingService settings;
    @Mock
    AuditService auditService;
    @Mock
    NotificationService notificationService;

    @InjectMocks
    ClassDisruptionService service;

    private ClassSession canceled;

    @BeforeEach
    void setUp() {
        SportCategory category = new SportCategory();
        category.setId(1L);
        Course course = new Course();
        course.setCategory(category);
        User coach = new User();
        coach.setId(5L);
        SportClass sportClass = new SportClass();
        sportClass.setId(7L);
        sportClass.setName("Yoga");
        sportClass.setCourse(course);
        sportClass.setCoach(coach);
        sportClass.setMaxCapacity(10);
        sportClass.setStatus("IN_PROGRESS");

        FacilityRoom room = new FacilityRoom();
        room.setId(3L);
        room.setRoomName("A1");
        room.setCapacity(20);
        room.setSports(Set.of(category));

        canceled = new ClassSession();
        canceled.setId(1L);
        canceled.setSportClass(sportClass);
        canceled.setSessionNo(4);
        canceled.setSessionDate(TODAY.plusDays(3));
        canceled.setStartTime(LocalTime.of(18, 0));
        canceled.setEndTime(LocalTime.of(19, 45));
        canceled.setRoom(room);
        canceled.setStatus("CANCELED_BY_CENTER");

        TimeSlot evening = new TimeSlot();
        evening.setStartTime(LocalTime.of(18, 0));
        evening.setEndTime(LocalTime.of(19, 45));
        TimeSlot shortSlot = new TimeSlot();
        shortSlot.setStartTime(LocalTime.of(20, 0));
        shortSlot.setEndTime(LocalTime.of(21, 0));

        when(sessionRepository.findWithDetailsById(1L)).thenReturn(Optional.of(canceled));
        when(sportClassRepository.findById(7L)).thenReturn(Optional.of(sportClass));
        when(sportClassRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(sportClass));
        when(userRepository.findByEmail("m@x")).thenReturn(Optional.of(new User()));
        when(sessionRepository.findLastActiveDate(7L)).thenReturn(Optional.of(COURSE_END));
        when(sessionRepository.findActiveDates(7L)).thenReturn(List.of(COURSE_END));
        TimeSlot morning = new TimeSlot();
        morning.setStartTime(LocalTime.of(10, 0));
        morning.setEndTime(LocalTime.of(11, 45));
        when(timeSlotRepository.findByActiveTrueOrderByStartTime()).thenReturn(List.of(morning, evening, shortSlot));
        when(facilityRoomRepository.findAll()).thenReturn(List.of(room));
        when(registrationRepository.countBySportClass_IdAndStatusIn(anyLong(), any())).thenReturn(10L);
        when(settings.getInt("makeup.proposal_count")).thenReturn(3);
        when(settings.getInt("makeup.max_days")).thenReturn(30);
        when(settings.getInt("schedule.buffer_minutes")).thenReturn(15);
        when(settings.getInt("makeup.min_notice_days", 2)).thenReturn(2);
        when(settings.getInt("makeup.max_busy_percent", 50)).thenReturn(50);
    }

    private void busy(long inCourse, long afterCourse) {
        // doAnswer: đặt lại stub nhiều lần trong một test mà không gọi lại câu trả lời cũ
        doAnswer(inv -> ((LocalDate) inv.getArgument(1)).isAfter(COURSE_END) ? afterCourse : inCourse)
                .when(sessionRepository).countMembersBusy(anyLong(), any(), any(), any(), anyInt());
    }

    private static List<MakeupProposalResponse> recommended(List<MakeupProposalResponse> all) {
        return all.stream().filter(MakeupProposalResponse::isRecommended).toList();
    }

    @Test
    void listsEveryFreeSpotInsideTheCourseAndRecommendsOneSpotOnEachOfTheBestDays() {
        busy(0, 0);

        List<MakeupProposalResponse> all = service.proposeMakeup(1L);
        List<MakeupProposalResponse> inCourse = all.stream().filter(p -> !p.isAfterClassEnd()).toList();

        assertEquals(TODAY.plusDays(2), inCourse.get(0).getDate());
        assertTrue(inCourse.stream().anyMatch(p -> p.getStartTime().equals(LocalTime.of(10, 0))), "mọi khung giờ trống đều được liệt kê");
        assertTrue(all.stream().noneMatch(p -> p.getStartTime().equals(LocalTime.of(20, 0))),
                "khung giờ ngắn hơn buổi bị hủy không được dùng");
        List<MakeupProposalResponse> picks = recommended(all);
        assertEquals(3, picks.size());
        assertEquals(3, picks.stream().map(MakeupProposalResponse::getDate).distinct().count(), "mỗi ngày gợi ý một giờ");
        assertTrue(picks.stream().allMatch(p -> p.getStartTime().equals(LocalTime.of(18, 0))),
                "gợi ý giờ gần giờ học cũ của lớp nhất");
        assertEquals(TODAY.plusDays(2), picks.get(0).getDate());
    }

    @Test
    void skipsADayTheClassAlreadyHasASession() {
        busy(0, 0);
        when(sessionRepository.findActiveDates(7L)).thenReturn(List.of(TODAY.plusDays(2), COURSE_END));

        List<MakeupProposalResponse> all = service.proposeMakeup(1L);

        assertEquals(TODAY.plusDays(3), all.get(0).getDate());
        assertTrue(all.stream().noneMatch(p -> p.getDate().equals(TODAY.plusDays(2))));
    }

    @Test
    void halfTheClassBusyIsStillAllowedButMoreThanHalfIsNot() {
        busy(5, 0);
        List<MakeupProposalResponse> atLimit = service.proposeMakeup(1L);
        assertFalse(atLimit.isEmpty());
        assertEquals(5, atLimit.get(0).getConflictedMembers());

        busy(6, 6);
        assertTrue(service.proposeMakeup(1L).isEmpty());
    }

    @Test
    void recommendsOneAfterClassSpotWhenTheBestInCourseSpotStillHasBusyMembers() {
        busy(3, 0);

        List<MakeupProposalResponse> picks = recommended(service.proposeMakeup(1L));

        assertEquals(4, picks.size());
        MakeupProposalResponse last = picks.stream().filter(MakeupProposalResponse::isAfterClassEnd).findFirst().orElseThrow();
        assertTrue(last.getDate().isAfter(COURSE_END));
        assertEquals(0, last.getConflictedMembers());
    }

    @Test
    void doesNotRecommendAnAfterClassSpotThatIsNoBetter() {
        busy(3, 3);

        List<MakeupProposalResponse> all = service.proposeMakeup(1L);

        assertTrue(recommended(all).stream().noneMatch(MakeupProposalResponse::isAfterClassEnd));
        assertTrue(all.stream().anyMatch(MakeupProposalResponse::isAfterClassEnd), "vẫn liệt kê để manager tự chọn");
    }

    @Test
    void recommendsOnlyAfterClassSpotsWhenNothingFitsInsideTheCourse() {
        when(sessionRepository.findLastActiveDate(7L)).thenReturn(Optional.of(TODAY.plusDays(1)));
        when(sessionRepository.findActiveDates(7L)).thenReturn(List.of(TODAY.plusDays(1)));
        busy(0, 0);

        List<MakeupProposalResponse> all = service.proposeMakeup(1L);

        assertTrue(all.stream().allMatch(MakeupProposalResponse::isAfterClassEnd));
        assertEquals(3, recommended(all).size());
    }

    @Test
    void choosingADateInsideTheNoticePeriodIsRejected() {
        busy(0, 0);

        assertThrows(BusinessRuleException.class,
                () -> service.chooseMakeup(1L, TODAY.plusDays(1), LocalTime.of(18, 0), "m@x"));
    }

    @Test
    void choosingASlotThatIsNotACenterSlotIsRejected() {
        busy(0, 0);

        assertThrows(BusinessRuleException.class,
                () -> service.chooseMakeup(1L, TODAY.plusDays(4), LocalTime.of(18, 30), "m@x"));
    }

    @Test
    void choosingASpotWithTooManyBusyMembersIsRejected() {
        busy(6, 6);

        assertThrows(BusinessRuleException.class,
                () -> service.chooseMakeup(1L, TODAY.plusDays(4), LocalTime.of(18, 0), "m@x"));
    }
}
