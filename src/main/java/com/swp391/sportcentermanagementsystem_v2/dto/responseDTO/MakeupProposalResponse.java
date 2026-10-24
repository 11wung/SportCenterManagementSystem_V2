package com.swp391.sportcentermanagementsystem_v2.dto.responseDTO;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalTime;

// Một ngày bù đề xuất (BR-MKP-01), kèm số học viên của lớp bị trùng giờ vào ngày đó
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class MakeupProposalResponse {
    private LocalDate date;
    @JsonFormat(pattern = "HH:mm")
    private LocalTime startTime;
    @JsonFormat(pattern = "HH:mm")
    private LocalTime endTime;
    private Long roomId;
    private String roomName;
    private long conflictedMembers;
}
