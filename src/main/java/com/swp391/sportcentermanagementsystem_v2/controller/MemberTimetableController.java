package com.swp391.sportcentermanagementsystem_v2.controller;

import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.TimetableItemResponse;
import com.swp391.sportcentermanagementsystem_v2.service.MemberTimetableService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/member")
@RequiredArgsConstructor
public class MemberTimetableController {

    private final MemberTimetableService timetableService;

    // Member: thời khóa biểu gộp buổi lớp và buổi 1-1. Mặc định 7 ngày từ hôm nay, tối đa 31 ngày
    @GetMapping("/timetable")
    public List<TimetableItemResponse> timetable(Authentication authentication,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return timetableService.myTimetable(authentication.getName(), from, to);
    }
}
