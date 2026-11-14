package com.swp391.sportcentermanagementsystem_v2.dto.responseDTO;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;

// Buổi lớp đã quá 48 giờ sau giờ học mà coach chưa chốt điểm danh. reopenedUntil: hạn manager đã mở lại (null nếu chưa mở)
public record UnmarkedSessionResponse(Long sessionId, Long classId, String className, int sessionNo, LocalDate sessionDate,
                                      LocalTime startTime, String coachName, OffsetDateTime reopenedUntil) {}
