package com.swp391.sportcentermanagementsystem_v2.dto.responseDTO;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDate;
import java.time.LocalTime;

// Một dòng trong thời khóa biểu của member. type: CLASS (buổi lớp) hoặc ONE_ON_ONE (buổi 1-1).
// declined: member đã báo không đi buổi bù này (BR-MKP-03). refId là id buổi lớp hoặc id suất 1-1. status của buổi lớp: SCHEDULED, DONE, CANCELED_BY_CENTER; của 1-1 luôn là BOOKED
public record TimetableItemResponse(
        String type,
        Long refId,
        Long classId,
        String title,
        LocalDate date,
        @JsonFormat(pattern = "HH:mm") LocalTime startTime,
        @JsonFormat(pattern = "HH:mm") LocalTime endTime,
        String roomName,
        String coachName,
        String status,
        boolean makeup,
        boolean declined) {
}
