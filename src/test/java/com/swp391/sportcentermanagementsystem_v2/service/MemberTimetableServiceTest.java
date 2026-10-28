package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.TimetableItemResponse;
import com.swp391.sportcentermanagementsystem_v2.entity.ClassSession;
import com.swp391.sportcentermanagementsystem_v2.entity.CoachSlot;
import com.swp391.sportcentermanagementsystem_v2.entity.FacilityRoom;
import com.swp391.sportcentermanagementsystem_v2.entity.SportClass;
import com.swp391.sportcentermanagementsystem_v2.entity.User;
import com.swp391.sportcentermanagementsystem_v2.exception.BusinessRuleException;
import com.swp391.sportcentermanagementsystem_v2.repository.ClassSessionRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.CoachSlotRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.UserRepository;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

// Thời khóa biểu member: buổi lớp và buổi 1-1 gộp chung, sắp theo ngày giờ
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MemberTimetableServiceTest {

    private static final LocalDate DAY = LocalDate.now().plusDays(2);

    @Mock
    ClassSessionRepository sessionRepository;
    @Mock
    CoachSlotRepository slotRepository;
    @Mock
    UserRepository userRepository;

    @InjectMocks
    MemberTimetableService service;

    private User member() {
        User u = new User();
        u.setId(1L);
        when(userRepository.findByEmail("m@example.com")).thenReturn(Optional.of(u));
        return u;
    }

    @Test
    void classAndOneOnOneAreMergedAndSortedByTime() {
        member();
        User coach = new User();
        coach.setId(9L);
        coach.setFullName("Coach A");
        SportClass sportClass = new SportClass();
        sportClass.setId(5L);
        sportClass.setName("Yoga");
        FacilityRoom room = new FacilityRoom();
        room.setRoomName("Room 1");
        ClassSession session = new ClassSession();
        session.setId(7L);
        session.setSportClass(sportClass);
        session.setSessionDate(DAY);
        session.setStartTime(LocalTime.of(10, 0));
        session.setEndTime(LocalTime.of(11, 45));
        session.setRoom(room);
        session.setCoach(coach);
        when(sessionRepository.findMemberSessions(any(), any(), any())).thenReturn(List.of(session));

        CoachSlot slot = new CoachSlot();
        slot.setId(3L);
        slot.setCoach(coach);
        slot.setStatus("BOOKED");
        slot.setStartAt(DAY.atTime(8, 0).atZone(SportClassService.CENTER_ZONE).toOffsetDateTime());
        slot.setEndAt(DAY.atTime(9, 45).atZone(SportClassService.CENTER_ZONE).toOffsetDateTime());
        when(slotRepository.findMemberBooked(any(), any(), any())).thenReturn(List.of(slot));

        List<TimetableItemResponse> items = service.myTimetable("m@example.com", DAY, DAY);

        assertEquals(List.of("ONE_ON_ONE", "CLASS"), items.stream().map(TimetableItemResponse::type).toList());
        assertEquals(LocalTime.of(8, 0), items.get(0).startTime());
        assertEquals("Room 1", items.get(1).roomName());
    }

    @Test
    void rangeLongerThan31DaysIsRejected() {
        member();

        assertThrows(BusinessRuleException.class, () -> service.myTimetable("m@example.com", DAY, DAY.plusDays(31)));
    }
}
