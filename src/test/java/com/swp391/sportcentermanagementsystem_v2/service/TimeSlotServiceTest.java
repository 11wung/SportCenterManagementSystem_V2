package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.CreateTimeSlotRequest;
import com.swp391.sportcentermanagementsystem_v2.entity.TimeSlot;
import com.swp391.sportcentermanagementsystem_v2.exception.BusinessRuleException;
import com.swp391.sportcentermanagementsystem_v2.repository.TimeSlotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TimeSlotServiceTest {

    @Mock
    TimeSlotRepository timeSlotRepository;
    @Mock
    CenterHoursService centerHours;
    @Mock
    SystemSettingService settings;

    @InjectMocks
    TimeSlotService timeSlotService;

    private TimeSlot slot1;

    @BeforeEach
    void setUp() {
        slot1 = new TimeSlot();
        slot1.setId(1L);
        slot1.setSlotNo((short) 1);
        slot1.setStartTime(LocalTime.of(7, 30));
        slot1.setEndTime(LocalTime.of(9, 15));
    }

    private CreateTimeSlotRequest request(int slotNo, LocalTime start, LocalTime end) {
        CreateTimeSlotRequest request = new CreateTimeSlotRequest();
        request.setSlotNo((short) slotNo);
        request.setStartTime(start);
        request.setEndTime(end);
        return request;
    }

    private void stubExistingSlot() {
        when(settings.getInt("schedule.buffer_minutes")).thenReturn(15);
        when(timeSlotRepository.findAllByOrderByStartTime()).thenReturn(List.of(slot1));
        when(timeSlotRepository.saveAndFlush(any(TimeSlot.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void slotStartingExactlyOneBufferAfterAnotherIsAllowed() {
        stubExistingSlot();

        var response = timeSlotService.create(request(2, LocalTime.of(9, 30), LocalTime.of(11, 45)));

        assertEquals(LocalTime.of(9, 30), response.getStartTime());
    }

    @Test
    void slotCloserThanBufferIsRejected() {
        when(settings.getInt("schedule.buffer_minutes")).thenReturn(15);
        when(timeSlotRepository.findAllByOrderByStartTime()).thenReturn(List.of(slot1));

        assertThrows(BusinessRuleException.class,
                () -> timeSlotService.create(request(2, LocalTime.of(9, 29), LocalTime.of(11, 45))));
        verify(timeSlotRepository, never()).saveAndFlush(any());
    }

    @Test
    void endBeforeStartIsRejected() {
        assertThrows(BusinessRuleException.class,
                () -> timeSlotService.create(request(2, LocalTime.of(11, 0), LocalTime.of(10, 0))));
    }

    @Test
    void duplicateSlotNumberIsRejected() {
        when(timeSlotRepository.existsBySlotNo((short) 1)).thenReturn(true);

        assertThrows(BusinessRuleException.class,
                () -> timeSlotService.create(request(1, LocalTime.of(13, 0), LocalTime.of(14, 0))));
    }

    @Test
    void timesOfSlotAreOnTheGivenDateInCenterZone() {
        var start = TimeSlotService.startAt(slot1, LocalDate.of(2026, 11, 10));

        assertEquals("2026-11-10T07:30+07:00", start.toString());
    }
}
