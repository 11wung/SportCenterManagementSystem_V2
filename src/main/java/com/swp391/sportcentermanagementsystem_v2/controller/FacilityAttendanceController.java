package com.swp391.sportcentermanagementsystem_v2.controller;

import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.CheckInRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.FacilityAttendanceResponse;
import com.swp391.sportcentermanagementsystem_v2.service.FacilityAttendanceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/receptionist/facility-attendances")
@RequiredArgsConstructor
public class FacilityAttendanceController {

    private final FacilityAttendanceService attendanceService;

    // Check-in: chỉ khi hội viên còn gói hợp lệ và chưa ở trong trung tâm
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public FacilityAttendanceResponse checkIn(Authentication authentication,
                                              @Valid @RequestBody CheckInRequest request) {
        return attendanceService.checkIn(authentication.getName(), request);
    }

    @PatchMapping("/{id}/check-out")
    public FacilityAttendanceResponse checkOut(@PathVariable Long id) {
        return attendanceService.checkOut(id);
    }

    // Lượt ra vào theo ngày (mặc định hôm nay), lọc theo tên hội viên
    @GetMapping
    public List<FacilityAttendanceResponse> list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) String name) {
        return attendanceService.list(date, name);
    }
}
