package com.swp391.sportcentermanagementsystem_v2.dto.responseDTO;

import java.time.OffsetDateTime;

// Một học viên của lớp cho manager xem: trạng thái đăng ký và số buổi có mặt trên số buổi đã điểm danh
public record ClassRosterResponse(Long registrationId, String memberName, String phone, String status,
                                  OffsetDateTime registeredAt, long present, long marked) {}
