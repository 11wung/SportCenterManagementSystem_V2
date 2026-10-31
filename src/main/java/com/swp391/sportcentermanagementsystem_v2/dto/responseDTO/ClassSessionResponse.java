package com.swp391.sportcentermanagementsystem_v2.dto.responseDTO;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.swp391.sportcentermanagementsystem_v2.entity.ClassSession;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalTime;

@Getter
@Setter
@NoArgsConstructor
public class ClassSessionResponse {
    private Long id;
    private Long classId;
    private String className;
    private Integer sessionNo;
    private LocalDate sessionDate;
    @JsonFormat(pattern = "HH:mm")
    private LocalTime startTime;
    @JsonFormat(pattern = "HH:mm")
    private LocalTime endTime;
    private Long roomId;
    private String roomName;
    private Long coachId;
    private String coachName;
    private String status;
    private boolean makeup;
    private String cancelReason;

    public ClassSessionResponse(ClassSession s) {
        this.id = s.getId();
        this.classId = s.getSportClass().getId();
        this.className = s.getSportClass().getName();
        this.sessionNo = s.getSessionNo();
        this.sessionDate = s.getSessionDate();
        this.startTime = s.getStartTime();
        this.endTime = s.getEndTime();
        this.roomId = s.getRoom().getId();
        this.roomName = s.getRoom().getRoomName();
        this.coachId = s.getCoach().getId();
        this.coachName = s.getCoach().getFullName();
        this.status = s.getStatus();
        this.makeup = s.isMakeup();
        this.cancelReason = s.getCancelReason();
    }
}
