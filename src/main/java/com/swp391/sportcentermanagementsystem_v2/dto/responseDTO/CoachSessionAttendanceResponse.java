package com.swp391.sportcentermanagementsystem_v2.dto.responseDTO;

import com.swp391.sportcentermanagementsystem_v2.entity.ClassAttendance;
import lombok.Getter;

import java.time.OffsetDateTime;

@Getter
public class CoachSessionAttendanceResponse {
    private final Long attendanceId;
    private final Long memberId;
    private final String memberName;
    private final String status;
    private final OffsetDateTime checkInTime;
    private final String notes;

    public CoachSessionAttendanceResponse(ClassAttendance attendance) {
        this.attendanceId = attendance.getId();
        this.memberId = attendance.getUser().getId();
        this.memberName = attendance.getUser().getFullName();
        this.status = attendance.getStatus();
        this.checkInTime = attendance.getCheckInTime();
        this.notes = attendance.getNotes();
    }
}
