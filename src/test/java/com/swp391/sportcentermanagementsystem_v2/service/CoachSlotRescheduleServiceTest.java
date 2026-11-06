package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.RescheduleSlotRequest;
import com.swp391.sportcentermanagementsystem_v2.entity.CoachSlot;
import com.swp391.sportcentermanagementsystem_v2.entity.CoachSlotRequest;
import com.swp391.sportcentermanagementsystem_v2.entity.FacilityRoom;
import com.swp391.sportcentermanagementsystem_v2.entity.Order;
import com.swp391.sportcentermanagementsystem_v2.entity.Role;
import com.swp391.sportcentermanagementsystem_v2.entity.SportCategory;
import com.swp391.sportcentermanagementsystem_v2.entity.TimeSlot;
import com.swp391.sportcentermanagementsystem_v2.entity.User;
import com.swp391.sportcentermanagementsystem_v2.exception.BusinessRuleException;
import com.swp391.sportcentermanagementsystem_v2.repository.CoachSlotRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.FacilityRoomRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.OrderRepository;
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
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Dời lịch của member (báo trước 24 giờ) và xử lý sự cố của trung tâm: giữ buổi, hoàn tiền chỉ là bước cuối
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CoachSlotRescheduleServiceTest {

    @Mock
    CoachSlotRepository slotRepository;
    @Mock
    CoachSlotService coachSlotService;
    @Mock
    UserRepository userRepository;
    @Mock
    TimeSlotService timeSlotService;
    @Mock
    FacilityRoomRepository facilityRoomRepository;
    @Mock
    OrderRepository orderRepository;
    @Mock
    SystemSettingService settings;
    @Mock
    NotificationService notificationService;
    @Mock
    AuditService auditService;

    @InjectMocks
    CoachSlotRescheduleService service;

    private User member;
    private User coach;
    private CoachSlot old;
    private CoachSlot moved;
    private Order order;
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

    private CoachSlot slotAt(long id, OffsetDateTime start) {
        CoachSlot s = new CoachSlot();
        s.setId(id);
        s.setCoach(coach);
        s.setMember(member);
        s.setStatus("BOOKED");
        s.setStartAt(start);
        s.setEndAt(start.plusHours(1));
        s.setPrice(new BigDecimal("300000"));
        SportCategory sport = new SportCategory();
        sport.setId(3L);
        sport.setName("Tennis");
        s.setCategory(sport);
        CoachSlotRequest request = new CoachSlotRequest();
        request.setId(77L);
        s.setRequest(request);
        return s;
    }

    @BeforeEach
    void setUp() {
        member = user(5L, "MEMBER");
        coach = user(10L, "COACH");
        old = slotAt(200L, OffsetDateTime.now().plusDays(5));
        moved = new CoachSlot();
        moved.setId(201L);
        moved.setCoach(coach);
        moved.setStartAt(OffsetDateTime.now().plusDays(8));
        moved.setEndAt(moved.getStartAt().plusHours(1));
        timeSlot = new TimeSlot();
        timeSlot.setId(2L);
        order = new Order();
        order.setCoachSlot(old);

        when(coachSlotService.activeUser(any(), any(), any())).thenReturn(member);
        when(coachSlotService.lockSlot(200L)).thenReturn(old);
        when(coachSlotService.coachTeaches(any(), any())).thenReturn(true);
        when(coachSlotService.createSlot(any(), any(), any(), any(), any())).thenReturn(moved);
        when(coachSlotService.toResponse(any())).thenReturn(null);
        when(timeSlotService.requireActive(2L)).thenReturn(timeSlot);
        when(settings.getInt("one_on_one.reschedule_before_hours")).thenReturn(24);
        when(orderRepository.findByCoachSlot_Id(200L)).thenReturn(Optional.of(order));
        when(slotRepository.saveAndFlush(any(CoachSlot.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private RescheduleSlotRequest to(LocalDate date, Long coachId) {
        RescheduleSlotRequest in = new RescheduleSlotRequest();
        in.setDate(date);
        in.setTimeSlotId(2L);
        in.setCoachId(coachId);
        return in;
    }

    @Test
    void rescheduleMovesTheSessionAndKeepsPriceRequestAndOrder() {
        service.reschedule("u5@example.com", 200L, to(LocalDate.now().plusDays(8), null));

        assertEquals("CANCELED", old.getStatus());
        assertEquals("BOOKED", moved.getStatus());
        assertEquals(new BigDecimal("300000"), moved.getPrice());
        assertEquals(77L, moved.getRequest().getId());
        assertEquals(member, moved.getMember());
        // Hóa đơn đi theo buổi mới
        assertEquals(moved, order.getCoachSlot());
    }

    @Test
    void rescheduleIsRefusedLessThanTheNoticeBeforeTheSession() {
        old.setStartAt(OffsetDateTime.now().plusHours(5));
        old.setEndAt(old.getStartAt().plusHours(1));

        BusinessRuleException e = assertThrows(BusinessRuleException.class,
                () -> service.reschedule("u5@example.com", 200L, to(LocalDate.now().plusDays(8), null)));

        assertTrue(e.getMessage().contains("24 hours"));
        assertEquals("BOOKED", old.getStatus());
    }

    @Test
    void aSessionTheCenterCannotRunCanBeMovedAtAnyTimeAndWithAnotherCoach() {
        old.setStartAt(OffsetDateTime.now().plusHours(5));
        old.setEndAt(old.getStartAt().plusHours(1));
        old.setNeedsReschedule(true);
        User other = user(11L, "COACH");
        when(userRepository.findById(11L)).thenReturn(Optional.of(other));

        service.reschedule("u5@example.com", 200L, to(LocalDate.now().plusDays(8), 11L));

        assertEquals("CANCELED", old.getStatus());
        verify(coachSlotService).createSlot(any(), any(), any(), any(), any());
    }

    @Test
    void theCoachCannotBeChangedByTheMemberUnlessTheCenterAsked() {
        User other = user(11L, "COACH");
        when(userRepository.findById(11L)).thenReturn(Optional.of(other));

        assertThrows(BusinessRuleException.class,
                () -> service.reschedule("u5@example.com", 200L, to(LocalDate.now().plusDays(8), 11L)));

        verify(coachSlotService, never()).createSlot(any(), any(), any(), any(), any());
    }

    @Test
    void anUnpaidSessionCannotBeRescheduled() {
        old.setStatus("HELD");

        assertThrows(BusinessRuleException.class,
                () -> service.reschedule("u5@example.com", 200L, to(LocalDate.now().plusDays(8), null)));
    }

    @Test
    void managerCanAskForARescheduleOnlyOnce() {
        when(coachSlotService.activeUser(any(), any(), any())).thenReturn(user(1L, "MANAGER"));

        service.markNeedsReschedule("u1@example.com", 200L, "room under repair");
        assertTrue(old.isNeedsReschedule());

        assertThrows(BusinessRuleException.class, () -> service.markNeedsReschedule("u1@example.com", 200L, "again"));
    }

    @Test
    void changeCoachFailsWhenNobodyElseIsFree() {
        when(coachSlotService.activeUser(any(), any(), any())).thenReturn(user(1L, "MANAGER"));
        when(userRepository.findByRole_CodeAndIsActiveTrue("COACH")).thenReturn(List.of(coach));
        when(coachSlotService.findReplacement(any(), any())).thenReturn(null);

        BusinessRuleException e = assertThrows(BusinessRuleException.class,
                () -> service.changeCoach("u1@example.com", 200L, null));

        assertTrue(e.getMessage().contains("No other coach"));
    }

    @Test
    void changingTheRoomMovesTheSessionToANewRowBecauseTheDatabaseForbidsEditingIt() {
        when(coachSlotService.activeUser(any(), any(), any())).thenReturn(user(1L, "MANAGER"));
        FacilityRoom oldRoom = new FacilityRoom();
        oldRoom.setId(1L);
        oldRoom.setRoomName("A1");
        FacilityRoom newRoom = new FacilityRoom();
        newRoom.setId(2L);
        newRoom.setRoomName("B2");
        old.setRoom(oldRoom);
        when(facilityRoomRepository.findById(2L)).thenReturn(Optional.of(newRoom));
        when(coachSlotService.roomUsable(eq(newRoom), any(), any(), any(), any(), any(), any())).thenReturn(true);
        when(coachSlotService.rebook(eq(old), eq(coach), eq(newRoom), any())).thenReturn(moved);

        service.changeRoom("u1@example.com", 200L, 2L);

        verify(coachSlotService).rebook(eq(old), eq(coach), eq(newRoom), any());
    }

    @Test
    void changingToTheRoomAlreadyInUseIsRefused() {
        when(coachSlotService.activeUser(any(), any(), any())).thenReturn(user(1L, "MANAGER"));
        FacilityRoom room = new FacilityRoom();
        room.setId(1L);
        old.setRoom(room);
        when(facilityRoomRepository.findById(1L)).thenReturn(Optional.of(room));

        assertThrows(BusinessRuleException.class, () -> service.changeRoom("u1@example.com", 200L, 1L));
        verify(coachSlotService, never()).rebook(any(), any(), any(), any());
    }
}
