package com.swp391.sportcentermanagementsystem_v2.controller;

import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.UpdateClassSessionRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.ClassSessionResponse;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.CoachSessionResponse;
import com.swp391.sportcentermanagementsystem_v2.service.ClassSessionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
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

    // Coach: các buổi dạy của chính mình, kể cả buổi manager gán riêng. from, to dạng yyyy-MM-dd, mặc định 30 ngày tới
    @GetMapping("/coach/class-sessions/me")
    public List<CoachSessionResponse> getMine(Authentication authentication,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return classSessionService.getMySessions(authentication.getName(), from, to);
    }

    // Manager: đổi phòng hoặc coach của một buổi chưa diễn ra (ngày và giờ không đổi, muốn dời thì hủy rồi xếp bù)
    @PutMapping("/class-sessions/{id}")
    public ClassSessionResponse update(Authentication authentication, @PathVariable Long id,
                                       @Valid @RequestBody UpdateClassSessionRequest request) {
        return classSessionService.updateSession(id, request, authentication.getName());
    }
}
