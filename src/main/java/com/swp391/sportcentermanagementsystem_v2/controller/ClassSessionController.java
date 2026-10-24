package com.swp391.sportcentermanagementsystem_v2.controller;

import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.UpdateClassSessionRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.ClassSessionResponse;
import com.swp391.sportcentermanagementsystem_v2.service.ClassSessionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ClassSessionController {

    private final ClassSessionService classSessionService;

    // Manager: sinh các buổi học từ lịch tuần (sinh lại nếu đã có, chỉ khi class còn nháp)
    @PostMapping("/sport-classes/{classId}/sessions/generate")
    @ResponseStatus(HttpStatus.CREATED)
    public List<ClassSessionResponse> generate(Authentication authentication, @PathVariable Long classId) {
        return classSessionService.generateSessions(classId, authentication.getName());
    }

    // Mọi user đăng nhập: xem các buổi học của một class
    @GetMapping("/sport-classes/{classId}/sessions")
    public List<ClassSessionResponse> getByClass(@PathVariable Long classId) {
        return classSessionService.getSessionsByClass(classId);
    }

    // Manager: sửa tay một buổi (ngày, giờ, phòng) khi class còn nháp
    @PutMapping("/class-sessions/{id}")
    public ClassSessionResponse update(Authentication authentication, @PathVariable Long id,
                                       @Valid @RequestBody UpdateClassSessionRequest request) {
        return classSessionService.updateSession(id, request, authentication.getName());
    }
}
