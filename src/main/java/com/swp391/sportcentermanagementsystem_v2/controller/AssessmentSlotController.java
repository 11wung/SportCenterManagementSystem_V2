package com.swp391.sportcentermanagementsystem_v2.controller;

import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.AssessmentResultRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.BulkAssessmentSlotsRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.CreateAssessmentSlotRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.AssessmentCapacityResponse;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.AssessmentSlotResponse;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.FitnessProgressResponse;
import com.swp391.sportcentermanagementsystem_v2.service.AssessmentSlotService;
import com.swp391.sportcentermanagementsystem_v2.service.FitnessAssessmentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

// BR-BEN-03: đánh giá thể lực. Manager tạo khung và gán coach, member đặt miễn phí theo lượt của gói, coach ghi kết quả
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class AssessmentSlotController {

    private final AssessmentSlotService assessmentSlotService;
    private final FitnessAssessmentService fitnessAssessmentService;

    // ====================== MANAGER ======================

    // POST /api/manager/assessment-slots - Tạo một khung và gán coach
    @PostMapping("/manager/assessment-slots")
    @ResponseStatus(HttpStatus.CREATED)
    public AssessmentSlotResponse createSlot(Authentication authentication,
            @Valid @RequestBody CreateAssessmentSlotRequest request) {
        return assessmentSlotService.createSlot(authentication.getName(), request.getCoachId(),
                request.getStartAt(), request.getEndAt());
    }

    // POST /api/manager/assessment-slots/bulk - Tạo nhiều khung cho một coach (tối đa 4 tuần, 200 khung)
    @PostMapping("/manager/assessment-slots/bulk")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createBulk(Authentication authentication,
            @Valid @RequestBody BulkAssessmentSlotsRequest request) {
        return assessmentSlotService.createBulk(authentication.getName(), request);
    }

    // DELETE /api/manager/assessment-slots?from=...&to=...&coachId=... - Xóa các khung còn trống trong khoảng ngày
    @DeleteMapping("/manager/assessment-slots")
    public Map<String, Object> deleteOpen(Authentication authentication,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long coachId) {
        return assessmentSlotService.deleteOpen(authentication.getName(), from, to, coachId);
    }

    // GET /api/manager/assessment-capacity?days=30 - Năng lực so với nhu cầu ước tính
    @GetMapping("/manager/assessment-capacity")
    public AssessmentCapacityResponse capacity(@RequestParam(defaultValue = "30") int days) {
        return assessmentSlotService.capacity(days);
    }

    // ====================== MEMBER ======================

    // GET /api/assessment-slots - Khung đánh giá còn trống (mặc định 30 ngày tới)
    @GetMapping("/assessment-slots")
    public List<AssessmentSlotResponse> openSlots(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to) {
        return assessmentSlotService.openSlots(from, to);
    }

    // POST /api/member/assessment-slots/{id}/book - Đặt khung, miễn phí khi gói có quyền lợi và còn lượt
    @PostMapping("/member/assessment-slots/{id}/book")
    public AssessmentSlotResponse book(Authentication authentication, @PathVariable Long id) {
        return assessmentSlotService.book(authentication.getName(), id);
    }

    // PATCH /api/member/assessment-slots/{id}/cancel - Hủy trước giờ hẹn, lượt được trả lại
    @PatchMapping("/member/assessment-slots/{id}/cancel")
    public AssessmentSlotResponse cancel(Authentication authentication, @PathVariable Long id) {
        return assessmentSlotService.cancel(authentication.getName(), id);
    }

    // GET /api/member/assessment-slots/me - Các lần đánh giá sắp tới đã đặt
    @GetMapping("/member/assessment-slots/me")
    public List<AssessmentSlotResponse> myAssessments(Authentication authentication) {
        return assessmentSlotService.myAssessments(authentication.getName());
    }

    // GET /api/member/fitness-progress/me - Lịch sử số đo, kèm BMI và chênh lệch so với lần trước
    @GetMapping("/member/fitness-progress/me")
    public List<FitnessProgressResponse> myProgress(Authentication authentication) {
        return fitnessAssessmentService.myProgress(authentication.getName());
    }

    // ====================== COACH ======================

    // POST /api/coach/assessment-slots/{id}/result - Coach được gán ghi kết quả đo và nhận xét
    @PostMapping("/coach/assessment-slots/{id}/result")
    @ResponseStatus(HttpStatus.CREATED)
    public FitnessProgressResponse recordResult(Authentication authentication, @PathVariable Long id,
            @Valid @RequestBody AssessmentResultRequest request) {
        return fitnessAssessmentService.record(authentication.getName(), id, request);
    }
}
