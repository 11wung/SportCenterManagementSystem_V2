package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.ResourceAvailabilityResponse;
import com.swp391.sportcentermanagementsystem_v2.entity.ClassSession;
import com.swp391.sportcentermanagementsystem_v2.entity.CoachSlot;
import com.swp391.sportcentermanagementsystem_v2.entity.FacilityRoom;
import com.swp391.sportcentermanagementsystem_v2.entity.TimeSlot;
import com.swp391.sportcentermanagementsystem_v2.entity.User;
import com.swp391.sportcentermanagementsystem_v2.repository.ClassSessionRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.CoachProfileRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.CoachSlotRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.FacilityRoomRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.SportClassRepository;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

// Lịch rảnh khi xếp lịch: phòng và coach đang bận ở slot nào thì không còn trong danh sách rảnh của slot đó
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ResourceAvailabilityServiceTest {

    private static final LocalDate DAY = LocalDate.now().plusDays(10);

    @Mock
    FacilityRoomRepository facilityRoomRepository;
    @Mock
    UserRepository userRepository;
    @Mock
    CoachProfileRepository coachProfileRepository;
    @Mock
    ClassSessionRepository classSessionRepository;
    @Mock
    CoachSlotRepository coachSlotRepository;
    @Mock
    SportClassRepository sportClassRepository;
    @Mock
    TimeSlotService timeSlotService;
    @Mock
    SystemSettingService settings;

    @InjectMocks
    ResourceAvailabilityService service;

    private FacilityRoom roomA;
    private FacilityRoom roomB;
    private User coachX;
    private User coachY;

    private static TimeSlot slot(long id, int no, LocalTime start, LocalTime end) {
        TimeSlot t = new TimeSlot();
        t.setId(id);
        t.setSlotNo((short) no);
        t.setStartTime(start);
        t.setEndTime(end);
        return t;
    }

    private static FacilityRoom room(long id, String name) {
        FacilityRoom r = new FacilityRoom();
        r.setId(id);
        r.setRoomName(name);
        return r;
    }

    private static User coach(long id, String name) {
        User u = new User();
        u.setId(id);
        u.setFullName(name);
        return u;
    }

    @BeforeEach
    void setUp() {
        roomA = room(1, "Room A");
        roomB = room(2, "Room B");
        coachX = coach(10, "Coach X");
        coachY = coach(11, "Coach Y");
        when(settings.getInt("schedule.buffer_minutes")).thenReturn(15);
        when(timeSlotService.activeSlots()).thenReturn(List.of(
                slot(1, 1, LocalTime.of(8, 0), LocalTime.of(9, 45)),
                slot(2, 2, LocalTime.of(10, 0), LocalTime.of(11, 45))));
        when(facilityRoomRepository.findAvailableWithSports()).thenReturn(List.of(roomA, roomB));
        when(userRepository.findByRole_CodeAndIsActiveTrue("COACH")).thenReturn(List.of(coachX, coachY));
    }

    private List<String> names(List<ResourceAvailabilityResponse.Ref> refs) {
        return refs.stream().map(ResourceAvailabilityResponse.Ref::name).toList();
    }

    @Test
    void busyRoomAndCoachAreLeftOutOfThatSlotOnly() {
        ClassSession session = new ClassSession();
        session.setSessionDate(DAY);
        session.setStartTime(LocalTime.of(10, 0));
        session.setEndTime(LocalTime.of(11, 45));
        session.setRoom(roomA);
        session.setCoach(coachX);
        when(classSessionRepository.findBusySessionsBetween(any(), any())).thenReturn(List.of(session));

        var rows = service.availability(DAY, DAY, null);

        // slot 1 kết thúc 09:45, buổi lớp bắt đầu 10:00: đúng 15 phút nghỉ nên slot 1 vẫn rảnh hết
        assertEquals(List.of("Room A", "Room B"), names(rows.get(0).freeRooms()));
        assertEquals(List.of("Coach X", "Coach Y"), names(rows.get(0).freeCoaches()));
        assertEquals(List.of("Room B"), names(rows.get(1).freeRooms()));
        assertEquals(List.of("Coach Y"), names(rows.get(1).freeCoaches()));
    }

    @Test
    void coachWithAOneOnOneSlotIsNotFreeThere() {
        CoachSlot oneOnOne = new CoachSlot();
        oneOnOne.setCoach(coachY);
        oneOnOne.setStartAt(DAY.atTime(8, 0).atZone(SportClassService.CENTER_ZONE).toOffsetDateTime());
        oneOnOne.setEndAt(DAY.atTime(9, 45).atZone(SportClassService.CENTER_ZONE).toOffsetDateTime());
        when(coachSlotRepository.findActiveBetween(any(), any())).thenReturn(List.of(oneOnOne));

        var rows = service.availability(DAY, DAY, null);

        assertEquals(List.of("Coach X"), names(rows.get(0).freeCoaches()));
        assertEquals(List.of("Coach X", "Coach Y"), names(rows.get(1).freeCoaches()));
    }
}
