package com.swp391.sportcentermanagementsystem_v2.controller;

import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.EvaluateStudentRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.ClassEvaluationResponse;
import com.swp391.sportcentermanagementsystem_v2.service.ClassEvaluationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

// BR-CLS-08: đánh giá cuối lớp theo thang A, B, C, D
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ClassEvaluationController {

    private final ClassEvaluationService evaluationService;

    // GET /api/coach/classes/{classId}/evaluations - Coach của class xem học viên và trạng thái đánh giá
    @GetMapping("/coach/classes/{classId}/evaluations")
    public List<ClassEvaluationResponse> listForClass(Authentication authentication, @PathVariable Long classId) {
        return evaluationService.listForClass(classId, authentication.getName());
    }

    // POST /api/coach/classes/{classId}/evaluations/{registrationId} - Chấm một học viên (mỗi người một lần)
    @PostMapping("/coach/classes/{classId}/evaluations/{registrationId}")
    @ResponseStatus(HttpStatus.CREATED)
    public ClassEvaluationResponse evaluate(Authentication authentication, @PathVariable Long classId,
            @PathVariable Long registrationId, @Valid @RequestBody EvaluateStudentRequest request) {
        return evaluationService.evaluate(classId, registrationId, request.getGrade(), request.getComment(),
                authentication.getName());
    }

    // GET /api/member/class-evaluations/me - Member xem đánh giá của mình
    @GetMapping("/member/class-evaluations/me")
    public List<ClassEvaluationResponse> myEvaluations(Authentication authentication) {
        return evaluationService.myEvaluations(authentication.getName());
    }
}
