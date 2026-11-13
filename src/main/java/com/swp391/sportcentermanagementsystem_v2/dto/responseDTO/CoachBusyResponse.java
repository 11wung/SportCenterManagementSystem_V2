package com.swp391.sportcentermanagementsystem_v2.dto.responseDTO;

import java.time.LocalDate;
import java.time.LocalTime;

// Một khoảng coach đã bận (giờ trung tâm), để hội viên thấy ô nào không chọn được khi đặt 1-1.
// kind: CLASS (đang dạy lớp) hoặc ONE_ON_ONE (đã có buổi 1-1). Không kèm tên lớp hay học viên khác
public record CoachBusyResponse(LocalDate date, LocalTime startTime, LocalTime endTime, String kind) {}
