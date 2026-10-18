package com.swp391.sportcentermanagementsystem_v2.controller;

import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.ClassScheduleRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.ClassScheduleResponse;
import com.swp391.sportcentermanagementsystem_v2.service.ClassScheduleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/class-schedules")
@RequiredArgsConstructor
public class ClassScheduleController {

    private final ClassScheduleService classScheduleService;

    // 1. Manager: Tạo lịch hoạt động mới cho lớp học
    @PostMapping
    public ResponseEntity<ClassScheduleResponse> createSchedule(
            @Valid @RequestBody ClassScheduleRequest request) {
        ClassScheduleResponse response = classScheduleService.createSchedule(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    // 2. Manager: Cập nhật lịch hoạt động
    @PutMapping("/{id}")
    public ResponseEntity<ClassScheduleResponse> updateSchedule(
            @PathVariable Long id,
            @Valid @RequestBody ClassScheduleRequest request) {
        ClassScheduleResponse response = classScheduleService.updateSchedule(id, request);
        return ResponseEntity.ok(response);
    }

    // 3. Manager: Xóa lịch hoạt động
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, String>> deleteSchedule(@PathVariable Long id) {
        classScheduleService.deleteSchedule(id);
        return ResponseEntity.ok(Map.of("message", "Xóa lịch hoạt động lớp thành công"));
    }

    // 4. All Roles (Manager / Member / Coach / Receptionist): Xem toàn bộ lịch hoạt
    // động
    @GetMapping
    public ResponseEntity<?> getAllSchedules() {
        List<ClassScheduleResponse> schedules = classScheduleService.getAllSchedules();
        if (schedules.isEmpty()) {
            return ResponseEntity.ok(Map.of("message", "Hiện tại chưa có lịch hoạt động lớp nào"));
        }
        return ResponseEntity.ok(schedules);
    }

    // 5. All Roles: Xem chi tiết 1 lịch học theo ID
    @GetMapping("/{id}")
    public ResponseEntity<ClassScheduleResponse> getScheduleById(@PathVariable Long id) {
        return ResponseEntity.ok(classScheduleService.getScheduleById(id));
    }

    // 6. All Roles: Xem lịch hoạt động của 1 lớp học cụ thể
    @GetMapping("/class/{classId}")
    public ResponseEntity<?> getSchedulesByClassId(
            @PathVariable Long classId) {
        List<ClassScheduleResponse> schedules = classScheduleService.getSchedulesByClassId(classId);
        if (schedules.isEmpty()) {
            return ResponseEntity.ok(Map.of("message", "Lớp học này hiện chưa có lịch hoạt động nào"));
        }
        return ResponseEntity.ok(schedules);
    }

    // 7. All Roles: Xem lịch hoạt động theo Thứ trong tuần (1 = Thứ 2 ... 7 = Chủ
    // Nhật)
    @GetMapping("/day/{dayOfWeek}")
    public ResponseEntity<?> getSchedulesByDayOfWeek(
            @PathVariable Short dayOfWeek) {
        List<ClassScheduleResponse> schedules = classScheduleService.getSchedulesByDayOfWeek(dayOfWeek);
        if (schedules.isEmpty()) {
            return ResponseEntity.ok(Map.of("message", "Không có lịch hoạt động nào trong ngày hôm nay"));
        }
        return ResponseEntity.ok(schedules);
    }

    // 8. Coach: Xem lịch dạy cá nhân của chính Huấn luyện viên đang đăng nhập
    @GetMapping("/coach/me")
    public ResponseEntity<?> getMyTeachingSchedules(
            Authentication authentication) {
        String email = authentication.getName();
        List<ClassScheduleResponse> schedules = classScheduleService.getMyCoachSchedules(email);
        if (schedules.isEmpty()) {
            return ResponseEntity.ok(Map.of("message", "Bạn hiện chưa có lịch dạy nào được phân công"));
        }
        return ResponseEntity.ok(schedules);
    }
}