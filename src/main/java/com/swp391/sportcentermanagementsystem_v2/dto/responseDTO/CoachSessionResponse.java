package com.swp391.sportcentermanagementsystem_v2.dto.responseDTO;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.swp391.sportcentermanagementsystem_v2.entity.ClassSession;
import lombok.Getter;

import java.time.LocalDate;
import java.time.LocalTime;

// Một buổi dạy của coach. assignedOverride = true khi buổi được manager gán riêng, coach khác coach phụ trách lớp
@Getter
public class CoachSessionResponse {

    private final Long sessionId;
    private final Long classId;
    private final String className;
    private final Integer sessionNo;
    private final LocalDate sessionDate;
    @JsonFormat(pattern = "HH:mm")
    private final LocalTime startTime;
    @JsonFormat(pattern = "HH:mm")
    private final LocalTime endTime;
    private final Long roomId;
    private final String roomName;
    private final String status;
    private final boolean makeup;
    private final boolean assignedOverride;

    public CoachSessionResponse(ClassSession s) {
        this.sessionId = s.getId();
        this.classId = s.getSportClass().getId();
        this.className = s.getSportClass().getName();
        this.sessionNo = s.getSessionNo();
        this.sessionDate = s.getSessionDate();
        this.startTime = s.getStartTime();
        this.endTime = s.getEndTime();
        this.roomId = s.getRoom().getId();
        this.roomName = s.getRoom().getRoomName();
        this.status = s.getStatus();
        this.makeup = s.isMakeup();
        this.assignedOverride = !s.getCoach().getId().equals(s.getSportClass().getCoach().getId());
    }
}
