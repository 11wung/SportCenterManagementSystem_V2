package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.entity.TimeSlot;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CoachSlotRequestScheduleTest {

    @Test
    void oneSelectedWeeklySlotCreatesSixSessions() {
        TimeSlot slot = slot(1L);
        LocalDate firstDate = LocalDate.of(2026, 11, 11); // Wednesday

        List<CoachSlotRequestService.PlannedSession> plan =
                CoachSlotRequestService.sessionPlanForSixWeeks(
                        firstDate, Map.of(3, List.of(slot)));

        assertEquals(6, plan.size());
        assertEquals(firstDate, plan.get(0).date());
        assertEquals(firstDate.plusWeeks(5), plan.get(5).date());
    }

    @Test
    void twoSelectedWeeklySlotsCreateTwelveSessions() {
        TimeSlot monday = slot(1L);
        TimeSlot thursday = slot(2L);
        LocalDate firstDate = LocalDate.of(2026, 11, 11); // Wednesday

        List<CoachSlotRequestService.PlannedSession> plan =
                CoachSlotRequestService.sessionPlanForSixWeeks(
                        firstDate,
                        Map.of(1, List.of(monday), 4, List.of(thursday)));

        assertEquals(12, plan.size());
        assertEquals(LocalDate.of(2026, 11, 16), plan.get(0).date());
        assertEquals(LocalDate.of(2026, 11, 19), plan.get(1).date());
        assertEquals(LocalDate.of(2026, 12, 21), plan.get(10).date());
        assertEquals(LocalDate.of(2026, 12, 24), plan.get(11).date());
    }

    private TimeSlot slot(Long id) {
        TimeSlot slot = new TimeSlot();
        slot.setId(id);
        slot.setStartTime(LocalTime.of(18, 0));
        slot.setEndTime(LocalTime.of(19, 0));
        return slot;
    }
}
