package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.SlotAvailabilityResponse;
import com.swp391.sportcentermanagementsystem_v2.entity.ClassSession;
import com.swp391.sportcentermanagementsystem_v2.entity.CoachSlot;
import com.swp391.sportcentermanagementsystem_v2.entity.Role;
import com.swp391.sportcentermanagementsystem_v2.entity.TimeSlot;
import com.swp391.sportcentermanagementsystem_v2.entity.User;
import com.swp391.sportcentermanagementsystem_v2.repository.ClassSessionRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.CoachSlotRepository;
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
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

// Lịch rảnh của coach: ô trùng buổi lớp hoặc suất khác bị đánh dấu, ô cách đúng khoảng nghỉ thì vẫn rảnh
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CoachAvailabilityTest {

    private static final LocalDate DAY = LocalDate.now().plusDays(10);

    @Mock
    CoachSlotRepository slotRepository;
    @Mock
    ClassSessionRepository classSessionRepository;
    @Mock
    UserRepository userRepository;
    @Mock
    SystemSettingService settings;
    @Mock
    TimeSlotService timeSlotService;

    @InjectMocks
    CoachSlotService service;

    private User coach;

    private static TimeSlot slot(long id, int no, LocalTime start, LocalTime end) {
        TimeSlot t = new TimeSlot();
        t.setId(id);
        t.setSlotNo((short) no);
        t.setStartTime(start);
        t.setEndTime(end);
        return t;
    }

    @BeforeEach
    void setUp() {
        Role role = new Role();
        role.setCode("COACH");
        coach = new User();
        coach.setId(10L);
        coach.setRole(role);
        coach.setIsActive(true);
        when(userRepository.findByEmail("coach@example.com")).thenReturn(Optional.of(coach));
        when(settings.getInt("schedule.buffer_minutes")).thenReturn(15);
        when(timeSlotService.activeSlots()).thenReturn(List.of(
                slot(1, 1, LocalTime.of(8, 0), LocalTime.of(9, 45)),
                slot(2, 2, LocalTime.of(10, 0), LocalTime.of(11, 45)),
                slot(3, 3, LocalTime.of(12, 0), LocalTime.of(13, 45))));
    }

    private Map<Integer, String> statuses() {
        return service.availability("coach@example.com", DAY, DAY).stream()
                .collect(Collectors.toMap(r -> (int) r.slotNo(), SlotAvailabilityResponse::status));
    }

    @Test
    void classSessionMarksOnlyItsOwnSlotAndNeighboursStayFree() {
        ClassSession session = new ClassSession();
        session.setSessionDate(DAY);
        session.setStartTime(LocalTime.of(10, 0));
        session.setEndTime(LocalTime.of(11, 45));
        when(classSessionRepository.findCoachBusySessions(anyLong(), any(), any())).thenReturn(List.of(session));

        // slot 1 kết thúc 09:45, buổi lớp bắt đầu 10:00: đúng 15 phút nghỉ nên vẫn rảnh
        assertEquals(Map.of(1, "AVAILABLE", 2, "CLASS", 3, "AVAILABLE"), statuses());
    }

    @Test
    void ownOneOnOneIsMarked() {
        CoachSlot oneOnOne = new CoachSlot();
        oneOnOne.setKind("ONE_ON_ONE");
        oneOnOne.setStartAt(DAY.atTime(8, 0).atZone(SportClassService.CENTER_ZONE).toOffsetDateTime());
        oneOnOne.setEndAt(DAY.atTime(9, 45).atZone(SportClassService.CENTER_ZONE).toOffsetDateTime());
        when(slotRepository.findCoachOverlapping(anyLong(), any(), any(), any())).thenReturn(List.of(oneOnOne));

        assertEquals(Map.of(1, "ONE_ON_ONE", 2, "AVAILABLE", 3, "AVAILABLE"), statuses());
    }

    @Test
    void slotsInThePastAreMarkedPast() {
        LocalDate yesterday = LocalDate.now().minusDays(1);

        var rows = service.availability("coach@example.com", yesterday, yesterday);

        assertEquals(List.of("PAST", "PAST", "PAST"), rows.stream().map(SlotAvailabilityResponse::status).toList());
    }
}
