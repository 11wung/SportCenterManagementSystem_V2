package com.swp391.sportcentermanagementsystem_v2.controller;

import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.CourseActiveRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.CreateTimeSlotRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.ResourceAvailabilityResponse;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.TimeSlotResponse;
import com.swp391.sportcentermanagementsystem_v2.service.ResourceAvailabilityService;
import com.swp391.sportcentermanagementsystem_v2.service.TimeSlotService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

// Slot giờ cố định dùng chung cho lịch lớp, buổi 1-1 và khung đánh giá thể lực
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class TimeSlotController {

    private final TimeSlotService timeSlotService;
    private final ResourceAvailabilityService resourceAvailabilityService;

    // Mọi user đăng nhập: các slot đang bật
    @GetMapping("/time-slots")
    public List<TimeSlotResponse> listActive() {
        return timeSlotService.listActive();
    }

    // Manager: tất cả slot kể cả slot đã tắt
    @GetMapping("/manager/time-slots")
    public List<TimeSlotResponse> listAll() {
        return timeSlotService.listAll();
    }

    // Manager xếp lịch: với mỗi ngày x slot, phòng và coach nào đang rảnh. Có classId thì chỉ liệt kê phòng đúng môn,
    // đủ sức chứa và coach dạy đúng môn của lớp. Mặc định 7 ngày từ hôm nay, tối đa 14 ngày
    @GetMapping("/manager/availability")
    public List<ResourceAvailabilityResponse> availability(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long classId) {
        return resourceAvailabilityService.availability(from, to, classId);
    }

    // Manager: tạo slot, giờ nằm trong giờ mở cửa và cách các slot khác tối thiểu khoảng nghỉ
    @PostMapping("/manager/time-slots")
    @ResponseStatus(HttpStatus.CREATED)
    public TimeSlotResponse create(@Valid @RequestBody CreateTimeSlotRequest request) {
        return timeSlotService.create(request);
    }

    // Manager: bật / tắt slot. Không có API sửa giờ
    @PatchMapping("/manager/time-slots/{id}/active")
    public TimeSlotResponse setActive(@PathVariable Long id, @Valid @RequestBody CourseActiveRequest request) {
        return timeSlotService.setActive(id, request.getActive());
    }
}
