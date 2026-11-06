package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.SubmitSlotRequest;
import com.swp391.sportcentermanagementsystem_v2.entity.CoachSlot;
import com.swp391.sportcentermanagementsystem_v2.entity.CoachSlotRequest;
import com.swp391.sportcentermanagementsystem_v2.entity.Role;
import com.swp391.sportcentermanagementsystem_v2.entity.SportCategory;
import com.swp391.sportcentermanagementsystem_v2.entity.TimeSlot;
import com.swp391.sportcentermanagementsystem_v2.entity.User;
import com.swp391.sportcentermanagementsystem_v2.exception.BusinessRuleException;
import com.swp391.sportcentermanagementsystem_v2.repository.CoachSlotRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.CoachSlotRequestRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.FacilityRoomRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.SportCategoryRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CoachSlotRequestServiceTest {

    @Mock
    CoachSlotRequestRepository requestRepository;
    @Mock
    CoachSlotRepository slotRepository;
    @Mock
    CoachSlotService coachSlotService;
    @Mock
    UserRepository userRepository;
    @Mock
    SportCategoryRepository sportCategoryRepository;
    @Mock
    FacilityRoomRepository facilityRoomRepository;
    @Mock
    TimeSlotService timeSlotService;
    @Mock
    SystemSettingService settings;
    @Mock
    NotificationService notificationService;
    @Mock
    AuditService auditService;
    @Mock
    PricingService pricingService;
    @Mock
    com.swp391.sportcentermanagementsystem_v2.repository.CoachSlotRequestDayRepository dayRepository;

    @InjectMocks
    CoachSlotRequestService service;

    private User member;
    private User coach;
    private User manager;
    private SportCategory sport;
    private TimeSlot timeSlot;

    private static User user(long id, String role) {
        Role r = new Role();
        r.setCode(role);
        User u = new User();
        u.setId(id);
        u.setRole(r);
        u.setIsActive(true);
        u.setEmail("u" + id + "@example.com");
        u.setFullName("User " + id);
        return u;
    }

    @BeforeEach
    void setUp() {
        member = user(5L, "MEMBER");
        coach = user(10L, "COACH");
        manager = user(1L, "MANAGER");
        sport = new SportCategory();
        sport.setId(3L);
        sport.setName("Tennis");
        timeSlot = new TimeSlot();
        timeSlot.setId(2L);
        timeSlot.setStartTime(LocalTime.of(18, 0));
        timeSlot.setEndTime(LocalTime.of(19, 45));

        when(coachSlotService.activeUser("u5@example.com", "MEMBER", "Only active Members can request a 1-1 session"))
                .thenReturn(member);
        when(coachSlotService.activeUser("u1@example.com", "MANAGER", "Only active Managers can approve requests"))
                .thenReturn(manager);
        when(coachSlotService.activeUser("u1@example.com", "MANAGER", "Only active Managers can reject requests"))
                .thenReturn(manager);
        when(userRepository.findById(10L)).thenReturn(Optional.of(coach));
        when(sportCategoryRepository.findById(3L)).thenReturn(Optional.of(sport));
        when(coachSlotService.coachTeaches(10L, 3L)).thenReturn(true);
        when(timeSlotService.requireActive(2L)).thenReturn(timeSlot);
        when(settings.getInt("one_on_one.max_sessions_per_request")).thenReturn(12);
        when(settings.getInt("one_on_one.request_min_lead_hours")).thenReturn(48);
        when(settings.getInt("one_on_one.request_expire_hours")).thenReturn(24);
        when(settings.getInt("one_on_one.request_pay_hours")).thenReturn(3);
        when(coachSlotService.slotMinutes(any(), any())).thenReturn(60L);
        when(coachSlotService.priceFor(60L)).thenReturn(new BigDecimal("300000"));
        when(pricingService.quote(anyLong(), any(), any(), any()))
                .thenReturn(new PricingService.Quote(new BigDecimal("300000"), "NONE", BigDecimal.ZERO));
    }

    private SubmitSlotRequest submission(LocalDate firstDate, int sessions) {
        SubmitSlotRequest in = new SubmitSlotRequest();
        in.setCoachId(10L);
        in.setSportCategoryId(3L);
        in.setTimeSlotId(2L);
        in.setFirstDate(firstDate);
        in.setSessions(sessions);
        return in;
    }

    private CoachSlotRequest pendingRequest(int sessions) {
        CoachSlotRequest r = new CoachSlotRequest();
        r.setId(77L);
        r.setMember(member);
        r.setCoach(coach);
        r.setCategory(sport);
        r.setTimeSlot(timeSlot);
        r.setFirstDate(LocalDate.now().plusDays(10));
        r.setSessions((short) sessions);
        r.setExpiresAt(OffsetDateTime.now().plusHours(5));
        when(requestRepository.findByIdForUpdate(77L)).thenReturn(Optional.of(r));
        return r;
    }

    @Test
    void submitRejectsAFirstSessionCloserThanTheLeadTime() {
        BusinessRuleException e = assertThrows(BusinessRuleException.class,
                () -> service.submit("u5@example.com", submission(LocalDate.now().plusDays(1), 4)));

        assertTrue(e.getMessage().contains("48 hours"));
        verify(requestRepository, never()).save(any());
    }

    @Test
    void submitRejectsMoreSessionsThanTheLimit() {
        BusinessRuleException e = assertThrows(BusinessRuleException.class,
                () -> service.submit("u5@example.com", submission(LocalDate.now().plusDays(10), 13)));

        assertTrue(e.getMessage().contains("at most 12"));
    }

    @Test
    void approveIsAllOrNothingAndNamesTheFailingWeek() {
        CoachSlotRequest request = pendingRequest(3);
        LocalDate badWeek = request.getFirstDate().plusWeeks(1);
        when(coachSlotService.createSlot(any(), any(), any(), any(), any())).thenAnswer(inv -> {
            if (badWeek.equals(inv.getArgument(3))) {
                throw new BusinessRuleException("The slot overlaps the coach's class session");
            }
            return new CoachSlot();
        });

        BusinessRuleException e = assertThrows(BusinessRuleException.class,
                () -> service.approve("u1@example.com", 77L, null));

        assertTrue(e.getMessage().contains(badWeek.toString()));
        // Chưa chuyển trạng thái: giao dịch bị hoàn tác nên các suất đã tạo ở tuần khác cũng biến mất
        assertEquals("PENDING", request.getStatus());
        verify(requestRepository, never()).save(any());
    }

    @Test
    void approveHoldsEverySessionForTheMemberAndSetsThePayDeadline() {
        CoachSlotRequest request = pendingRequest(3);
        when(coachSlotService.createSlot(any(), any(), any(), any(), any())).thenAnswer(inv -> new CoachSlot());

        service.approve("u1@example.com", 77L, null);

        assertEquals("APPROVED", request.getStatus());
        verify(slotRepository, org.mockito.Mockito.times(3)).saveAndFlush(any(CoachSlot.class));
        // Hạn trả tiền 3 giờ kể từ lúc duyệt
        assertTrue(request.getExpiresAt().isBefore(OffsetDateTime.now().plusHours(3).plusMinutes(1)));
        assertTrue(request.getExpiresAt().isAfter(OffsetDateTime.now().plusHours(2)));
    }

    @Test
    void rejectedRequestKeepsTheReason() {
        CoachSlotRequest request = pendingRequest(2);

        service.reject("u1@example.com", 77L, "Coach is on leave");

        assertEquals("REJECTED", request.getStatus());
        assertEquals("Coach is on leave", request.getRejectReason());
    }

    @Test
    void anAlreadyDecidedRequestCannotBeApprovedAgain() {
        CoachSlotRequest request = pendingRequest(2);
        request.setStatus("REJECTED");

        assertThrows(BusinessRuleException.class, () -> service.approve("u1@example.com", 77L, null));
    }

    private TimeSlot slotAt(long id, int hour) {
        TimeSlot t = new TimeSlot();
        t.setId(id);
        t.setStartTime(LocalTime.of(hour, 0));
        t.setEndTime(LocalTime.of(hour + 1, 0));
        return t;
    }

    private SubmitSlotRequest.Day day(int weekday, long timeSlotId) {
        SubmitSlotRequest.Day d = new SubmitSlotRequest.Day();
        d.setWeekday(weekday);
        d.setTimeSlotId(timeSlotId);
        return d;
    }

    @Test
    void sessionPlanGivesEachWeekdayItsOwnTimeSlot() {
        TimeSlot morning = slotAt(1, 10);
        TimeSlot evening = slotAt(2, 17);
        // Thứ 2 02/11/2026: thứ 2 lúc 10:00 và thứ 4 lúc 17:00
        List<CoachSlotRequestService.PlannedSession> plan = CoachSlotRequestService.sessionPlan(
                LocalDate.of(2026, 11, 2), java.util.Map.of(1, morning, 3, evening), 4);

        assertEquals(List.of(
                new CoachSlotRequestService.PlannedSession(LocalDate.of(2026, 11, 2), morning),
                new CoachSlotRequestService.PlannedSession(LocalDate.of(2026, 11, 4), evening),
                new CoachSlotRequestService.PlannedSession(LocalDate.of(2026, 11, 9), morning),
                new CoachSlotRequestService.PlannedSession(LocalDate.of(2026, 11, 11), evening)), plan);
    }

    @Test
    void sessionPlanStartsOnTheFirstMatchingDayAfterTheStartDate() {
        TimeSlot morning = slotAt(1, 10);
        // Bắt đầu tìm từ thứ 3 03/11 không khớp thì nhảy sang thứ 4 04/11
        List<CoachSlotRequestService.PlannedSession> plan = CoachSlotRequestService.sessionPlan(
                LocalDate.of(2026, 11, 3), java.util.Map.of(3, morning, 7, morning), 3);

        assertEquals(List.of(LocalDate.of(2026, 11, 4), LocalDate.of(2026, 11, 8), LocalDate.of(2026, 11, 11)),
                plan.stream().map(CoachSlotRequestService.PlannedSession::date).toList());
    }

    @Test
    void approveCreatesEachSessionAtItsOwnTime() {
        CoachSlotRequest request = pendingRequest(4);
        LocalDate monday = LocalDate.now().plusDays(10).with(java.time.temporal.TemporalAdjusters.nextOrSame(java.time.DayOfWeek.MONDAY));
        request.setFirstDate(monday);
        TimeSlot morning = slotAt(1, 10);
        TimeSlot evening = slotAt(2, 17);
        when(dayRepository.findByRequest_IdInOrderByWeekday(List.of(77L))).thenReturn(List.of(
                new com.swp391.sportcentermanagementsystem_v2.entity.CoachSlotRequestDay(request, 1, morning),
                new com.swp391.sportcentermanagementsystem_v2.entity.CoachSlotRequestDay(request, 3, evening)));
        List<Object[]> created = new java.util.ArrayList<>();
        when(coachSlotService.createSlot(any(), any(), any(), any(), any())).thenAnswer(inv -> {
            created.add(new Object[] { inv.getArgument(3), inv.getArgument(4) });
            return new CoachSlot();
        });

        service.approve("u1@example.com", 77L, null);

        assertEquals(4, created.size());
        assertEquals(monday, created.get(0)[0]);
        assertEquals(morning, created.get(0)[1]);
        assertEquals(monday.plusDays(2), created.get(1)[0]);
        assertEquals(evening, created.get(1)[1]);
        assertEquals(monday.plusWeeks(1), created.get(2)[0]);
        assertEquals(morning, created.get(2)[1]);
    }

    @Test
    void submitStoresOneDayRowPerWeekdayAndStartsOnTheFirstMatchingDay() {
        TimeSlot evening = slotAt(4, 17);
        when(timeSlotService.requireActive(4L)).thenReturn(evening);
        LocalDate start = LocalDate.now().plusDays(10);
        int dow = start.getDayOfWeek().getValue();
        int next = dow % 7 + 1; // ngày kế tiếp trong tuần
        SubmitSlotRequest in = submission(start, 3);
        in.setTimeSlotId(null);
        in.setDays(List.of(day(next, 4), day((dow + 1) % 7 + 1, 2)));

        service.submit("u5@example.com", in);

        org.mockito.ArgumentCaptor<CoachSlotRequest> saved = org.mockito.ArgumentCaptor.forClass(CoachSlotRequest.class);
        verify(requestRepository).save(saved.capture());
        // Buổi đầu là ngày kế tiếp (ngày bắt đầu không khớp thứ nào), giờ của buổi đầu là 17:00
        assertEquals(start.plusDays(1), saved.getValue().getFirstDate());
        assertEquals(evening, saved.getValue().getTimeSlot());
        org.mockito.ArgumentCaptor<Iterable<com.swp391.sportcentermanagementsystem_v2.entity.CoachSlotRequestDay>> rows = org.mockito.ArgumentCaptor.forClass(Iterable.class);
        verify(dayRepository).saveAll(rows.capture());
        assertEquals(2, ((java.util.Collection<?>) rows.getValue()).size());
    }

    @Test
    void submitRejectsTheSameWeekdayTwice() {
        SubmitSlotRequest in = submission(LocalDate.now().plusDays(10), 3);
        in.setTimeSlotId(null);
        in.setDays(List.of(day(1, 2), day(1, 2)));

        BusinessRuleException e = assertThrows(BusinessRuleException.class, () -> service.submit("u5@example.com", in));

        assertTrue(e.getMessage().contains("once"));
    }

    @Test
    void submitNeedsATimeSlotWhenNoDaysAreGiven() {
        SubmitSlotRequest in = submission(LocalDate.now().plusDays(10), 3);
        in.setTimeSlotId(null);

        BusinessRuleException e = assertThrows(BusinessRuleException.class, () -> service.submit("u5@example.com", in));

        assertTrue(e.getMessage().contains("time slot"));
    }
}
