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
}
