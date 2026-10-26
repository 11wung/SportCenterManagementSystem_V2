package com.swp391.sportcentermanagementsystem_v2.dto.responseDTO;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalTime;

@Getter
@Setter
@NoArgsConstructor
public class ClassScheduleResponse {

    private Long id;

    // Thông tin lớp học & bộ môn
    private Long classId;
    private String className;
    private String categoryName;
    private String level;
    private Integer classMaxCapacity;

    // Thông tin huấn luyện viên phụ trách
    private Long coachId;
    private String coachName;

    // Thông tin phòng tập
    private Long roomId;
    private String roomName;
    private String floor;
    private Integer roomCapacity;

    // Thông tin thời gian lịch học
    private Short dayOfWeek;
    private String dayOfWeekName;
    private Long timeSlotId;
    private LocalTime startTime;
    private LocalTime endTime;
}