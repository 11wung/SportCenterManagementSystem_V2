package com.swp391.sportcentermanagementsystem_v2.dto.responseDTO;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

// Hiệu quả từng lớp: lấp đầy chỗ và chuyên cần. attendanceRate là null khi lớp chưa có buổi nào được điểm danh.
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ClassPerformanceResponse {

    private Long classId;
    private String className;
    private String sportName;
    private String coachName;
    private String status;
    private LocalDate startDate;
    private int maxCapacity;
    private long reservedSeats;
    private int fillPercent;
    private long markedRecords;
    private Integer attendanceRate;
}
