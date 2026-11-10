package com.swp391.sportcentermanagementsystem_v2.dto.responseDTO;

import java.time.LocalDate;
import java.time.LocalTime;

public record ClassSessionAttendanceSummary(
        Long sessionId, Integer sessionNo, LocalDate sessionDate, LocalTime startTime, LocalTime endTime,
        String sessionStatus, String teachingStatus, String attendanceStatus, String attendanceStatusLabel,
        long markedRecords, long presentCount, long absentCount, long lateCount, long excusedCount, long unmarkedCount) {
}
