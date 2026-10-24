package com.swp391.sportcentermanagementsystem_v2.controller;

import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.ChooseMakeupRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.CoachLeaveRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.ReasonRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.ReplaceCoachRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.RoomMaintenanceRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.ClassSessionResponse;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.MakeupProposalResponse;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.RoomMaintenanceResponse;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.SportClassResponse;
import com.swp391.sportcentermanagementsystem_v2.service.ClassDisruptionService;
import com.swp391.sportcentermanagementsystem_v2.service.SportClassService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

// L8: buổi học bị hủy do trung tâm, buổi bù, đổi phòng khi bảo trì, đổi coach và coach nghỉ
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ClassDisruptionController {

    private final ClassDisruptionService disruptionService;
    private final SportClassService sportClassService;

    // ====================== MANAGER: HỦY BUỔI VÀ BUỔI BÙ ======================

    // PATCH /api/class-sessions/{id}/cancel - Manager hủy một buổi do lỗi trung tâm (BR-MKP-01)
    @PatchMapping("/class-sessions/{id}/cancel")
    public ClassSessionResponse cancelSession(Authentication authentication, @PathVariable Long id,
            @Valid @RequestBody ReasonRequest request) {
        return disruptionService.cancelSession(id, request.getReason(), authentication.getName());
    }

    // GET /api/class-sessions/{id}/makeup-proposals - Tối đa 3 ngày bù đề xuất kèm số học viên bị trùng giờ (BR-MKP-02)
    @GetMapping("/class-sessions/{id}/makeup-proposals")
    public List<MakeupProposalResponse> proposeMakeup(@PathVariable Long id) {
        return disruptionService.proposeMakeup(id);
    }

    // POST /api/class-sessions/{id}/makeup - Manager chọn ngày thì hệ thống tạo buổi bù (BR-MKP-02)
    @PostMapping("/class-sessions/{id}/makeup")
    @ResponseStatus(HttpStatus.CREATED)
    public ClassSessionResponse chooseMakeup(Authentication authentication, @PathVariable Long id,
            @Valid @RequestBody ChooseMakeupRequest request) {
        return disruptionService.chooseMakeup(id, request.getDate(), authentication.getName());
    }

    // ====================== MANAGER: PHÒNG VÀ COACH ======================

    // PATCH /api/rooms/{id}/maintenance - Đổi trạng thái phòng; phòng bảo trì thì đổi phòng cho các buổi sắp tới (BR-SCH-05)
    @PatchMapping("/rooms/{id}/maintenance")
    public RoomMaintenanceResponse setRoomStatus(Authentication authentication, @PathVariable Long id,
            @Valid @RequestBody RoomMaintenanceRequest request) {
        return disruptionService.setRoomStatus(id, request.getStatus(), authentication.getName());
    }

    // POST /api/sport-classes/{id}/replace-coach - Thay coach giữa lớp khi coach nghỉ việc hẳn (BR-MKP-05)
    @PostMapping("/sport-classes/{id}/replace-coach")
    public SportClassResponse replaceCoach(Authentication authentication, @PathVariable Long id,
            @Valid @RequestBody ReplaceCoachRequest request) {
        return sportClassService.replaceCoach(id, request.getCoachId(), request.getReason(), authentication.getName());
    }

    // POST /api/coaches/{id}/leave - Coach nghỉ tạm: hủy các buổi học trong khoảng ngày (BR-MKP-05)
    @PostMapping("/coaches/{id}/leave")
    public List<ClassSessionResponse> coachLeave(Authentication authentication, @PathVariable Long id,
            @Valid @RequestBody CoachLeaveRequest request) {
        return disruptionService.coachLeave(id, request.getFrom(), request.getTo(), request.getReason(),
                authentication.getName());
    }
}
