package com.swp391.sportcentermanagementsystem_v2.controller;

import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.CancelSlotRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.ChangeCoachRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.ChangeRoomRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.RescheduleSlotRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.CoachSlotResponse;
import com.swp391.sportcentermanagementsystem_v2.service.CoachSlotRescheduleService;
import com.swp391.sportcentermanagementsystem_v2.service.CoachSlotService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.List;

// Buổi 1-1 (BR-ONE): lịch các buổi đã tạo từ yêu cầu, dời lịch của member và xử lý sự cố của trung tâm
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class CoachSlotController {

    private final CoachSlotService coachSlotService;
    private final CoachSlotRescheduleService rescheduleService;

    // ====================== MANAGER ======================

    @GetMapping("/manager/coach-slots")
    public List<CoachSlotResponse> managerList(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(required = false) Long coachId,
            @RequestParam(required = false) String status) {
        return coachSlotService.managerList(from, to, coachId, status);
    }

    // Sự cố phòng: đổi sang phòng khác cùng giờ (không gửi roomId thì hệ thống tự chọn phòng trống)
    @PostMapping("/manager/coach-slots/{id}/change-room")
    public CoachSlotResponse changeRoom(Authentication authentication, @PathVariable Long id,
                                        @RequestBody(required = false) ChangeRoomRequest request) {
        return rescheduleService.changeRoom(authentication.getName(), id, request == null ? null : request.getRoomId());
    }

    // Sự cố coach: đổi sang coach khác rảnh đúng giờ (không gửi coachId thì hệ thống tự chọn)
    @PostMapping("/manager/coach-slots/{id}/change-coach")
    public CoachSlotResponse changeCoach(Authentication authentication, @PathVariable Long id,
                                         @RequestBody(required = false) ChangeCoachRequest request) {
        return rescheduleService.changeCoach(authentication.getName(), id, request == null ? null : request.getCoachId());
    }

    // Không đổi được phòng hay coach: giữ buổi và để hội viên chọn giờ mới (BR-ONE-05)
    @PostMapping("/manager/coach-slots/{id}/needs-reschedule")
    public CoachSlotResponse needsReschedule(Authentication authentication, @PathVariable Long id,
                                             @Valid @RequestBody CancelSlotRequest request) {
        return rescheduleService.markNeedsReschedule(authentication.getName(), id, request.getReason().trim());
    }

    // Bước cuối khi hoàn toàn bất khả thi: hủy buổi và hoàn phần tiền của buổi đó (BR-ONE-05)
    @PostMapping("/manager/coach-slots/{id}/cancel")
    public CoachSlotResponse managerCancel(Authentication authentication, @PathVariable Long id,
                                           @Valid @RequestBody CancelSlotRequest request) {
        return coachSlotService.managerCancel(authentication.getName(), id, request.getReason().trim());
    }

    // ====================== COACH (chỉ xem lịch của mình) ======================

    @GetMapping("/coach/slots/me")
    public List<CoachSlotResponse> mySlots(Authentication authentication) {
        return coachSlotService.mySlots(authentication.getName());
    }

    // ====================== MEMBER ======================

    @GetMapping("/member/coach-slots/me")
    public List<CoachSlotResponse> myBookings(Authentication authentication) {
        return coachSlotService.myBookings(authentication.getName());
    }

    // Dời một buổi đã trả tiền sang ngày và khung giờ khác (BR-ONE-04)
    @PostMapping("/member/coach-slots/{id}/reschedule")
    public CoachSlotResponse reschedule(Authentication authentication, @PathVariable Long id,
                                        @Valid @RequestBody RescheduleSlotRequest request) {
        return rescheduleService.reschedule(authentication.getName(), id, request);
    }
}
