package com.swp391.sportcentermanagementsystem_v2.controller;

import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.ApproveSlotRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.CancelSlotRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.SubmitSlotRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.CoachOptionResponse;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.CoachSlotRequestResponse;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.OnlinePaymentLinkResponse;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.PaymentResponse;
import com.swp391.sportcentermanagementsystem_v2.service.CoachSlotRequestService;
import com.swp391.sportcentermanagementsystem_v2.service.OnlinePaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

// Yêu cầu buổi 1-1 nhiều buổi: member gửi, manager duyệt hoặc từ chối
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class CoachSlotRequestController {

    private final CoachSlotRequestService requestService;
    private final OnlinePaymentService onlinePaymentService;

    // ====================== MEMBER ======================

    @GetMapping("/member/coach-options")
    public List<CoachOptionResponse> coachOptions() {
        return requestService.coachOptions();
    }

    // Lịch bận của một coach để hội viên chọn giờ 1-1 (ngày theo yyyy-MM-dd)
    @GetMapping("/member/coach-options/{coachId}/busy")
    public List<com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.CoachBusyResponse> coachBusy(
            @PathVariable Long coachId,
            @RequestParam @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate from,
            @RequestParam @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate to) {
        return requestService.coachBusy(coachId, from, to);
    }

    @PostMapping("/member/coach-requests")
    @ResponseStatus(HttpStatus.CREATED)
    public CoachSlotRequestResponse submit(Authentication authentication, @Valid @RequestBody SubmitSlotRequest request) {
        return requestService.submit(authentication.getName(), request);
    }

    @GetMapping("/member/coach-requests")
    public List<CoachSlotRequestResponse> myRequests(Authentication authentication) {
        return requestService.myRequests(authentication.getName());
    }

    @PostMapping("/member/coach-requests/{id}/cancel")
    public CoachSlotRequestResponse cancel(Authentication authentication, @PathVariable Long id) {
        return requestService.cancel(authentication.getName(), id);
    }

    // Trả một lần cho cả chuỗi bằng QR: tạo (hoặc lấy lại) mã QR của đơn tổng, rồi hỏi PayOS xem đã trả chưa
    @PostMapping("/member/coach-requests/{id}/online-payment")
    public OnlinePaymentLinkResponse startOnlinePayment(Authentication authentication, @PathVariable Long id) {
        return onlinePaymentService.startOrReuseSlotPayment(requestService.anchorSlotId(id), authentication.getName());
    }

    @PostMapping("/member/coach-requests/{id}/online-payment/check")
    public PaymentResponse checkOnlinePayment(Authentication authentication, @PathVariable Long id) {
        return onlinePaymentService.checkSlotPayment(requestService.anchorSlotId(id), authentication.getName());
    }

    // Lễ tân xem yêu cầu của hội viên để thu tiền mặt
    @GetMapping("/receptionist/members/{memberId}/coach-requests")
    public List<CoachSlotRequestResponse> memberRequests(@PathVariable Long memberId) {
        return requestService.requestsOf(memberId);
    }

    // Lễ tân thu tiền mặt cho cả chuỗi
    @PostMapping("/receptionist/coach-requests/{id}/cash-payment")
    public PaymentResponse payCash(Authentication authentication, @PathVariable Long id) {
        return onlinePaymentService.payCashForSlot(requestService.anchorSlotId(id), authentication.getName());
    }

    // ====================== MANAGER ======================

    @GetMapping("/manager/coach-requests")
    public List<CoachSlotRequestResponse> managerList(@RequestParam(required = false) String status) {
        return requestService.managerList(status);
    }

    @PostMapping("/manager/coach-requests/{id}/approve")
    public CoachSlotRequestResponse approve(Authentication authentication, @PathVariable Long id,
                                            @RequestBody(required = false) ApproveSlotRequest request) {
        return requestService.approve(authentication.getName(), id, request == null ? null : request.getRoomId(),
                request == null ? null : request.getCoachId());
    }

    @PostMapping("/manager/coach-requests/{id}/reject")
    public CoachSlotRequestResponse reject(Authentication authentication, @PathVariable Long id,
                                           @Valid @RequestBody CancelSlotRequest request) {
        return requestService.reject(authentication.getName(), id, request.getReason().trim());
    }
}
