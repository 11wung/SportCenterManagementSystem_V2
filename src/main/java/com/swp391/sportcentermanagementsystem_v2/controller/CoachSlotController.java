package com.swp391.sportcentermanagementsystem_v2.controller;

import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.CancelSlotRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.CreateCoachSlotRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.SetCoachRankRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.CoachSlotResponse;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.OnlinePaymentLinkResponse;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.PaymentResponse;
import com.swp391.sportcentermanagementsystem_v2.service.CoachSlotService;
import com.swp391.sportcentermanagementsystem_v2.service.OnlinePaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

// Buổi 1-1 (BR-ONE): coach đăng suất, member đặt và trả tiền, hủy và hoàn tiền
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class CoachSlotController {

    private final CoachSlotService coachSlotService;
    private final OnlinePaymentService onlinePaymentService;

    // ====================== COACH ======================

    // Đăng suất rảnh: dài 60 đến 180 phút, bước 30 phút, không trùng buổi học hay suất khác của coach
    @PostMapping("/coach/slots")
    @ResponseStatus(HttpStatus.CREATED)
    public CoachSlotResponse publish(Authentication authentication,
                                     @Valid @RequestBody CreateCoachSlotRequest request) {
        return coachSlotService.publish(authentication.getName(), request);
    }

    @GetMapping("/coach/slots/me")
    public List<CoachSlotResponse> mySlots(Authentication authentication) {
        return coachSlotService.mySlots(authentication.getName());
    }

    // Chỉ xóa được suất chưa có người đặt
    @DeleteMapping("/coach/slots/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSlot(Authentication authentication, @PathVariable Long id) {
        coachSlotService.deleteSlot(authentication.getName(), id);
    }

    // Coach bận hủy buổi đã đặt: member được hoàn đủ (BR-ONE-05)
    @PostMapping("/coach/slots/{id}/cancel")
    public CoachSlotResponse coachCancel(Authentication authentication, @PathVariable Long id,
                                         @Valid @RequestBody CancelSlotRequest request) {
        return coachSlotService.coachCancel(authentication.getName(), id, request.getReason().trim());
    }

    // ====================== CÔNG KHAI ======================

    // Xem suất 1-1 còn trống (không cần đăng nhập). from/to dạng ISO có múi giờ, mặc định 90 ngày tới
    @GetMapping("/coach-slots")
    public List<CoachSlotResponse> openSlots(
            @RequestParam(required = false) Long coachId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to) {
        return coachSlotService.openSlots(coachId, from, to);
    }

    // ====================== MANAGER ======================

    // Gán hạng coach, quyết định giá 1-1 (BR-ONE-02)
    @PutMapping("/coaches/{id}/rank")
    public Map<String, Object> setRank(Authentication authentication, @PathVariable Long id,
                                       @Valid @RequestBody SetCoachRankRequest request) {
        return coachSlotService.setRank(authentication.getName(), id, request.getRank());
    }

    // ====================== MEMBER ======================

    // Đặt suất: giữ chỗ 15 phút, sau đó trả tiền bằng QR hoặc tiền mặt tại quầy
    @PostMapping("/member/coach-slots/{id}/book")
    @ResponseStatus(HttpStatus.CREATED)
    public CoachSlotResponse book(Authentication authentication, @PathVariable Long id) {
        return coachSlotService.book(authentication.getName(), id);
    }

    @GetMapping("/member/coach-slots/me")
    public List<CoachSlotResponse> myBookings(Authentication authentication) {
        return coachSlotService.myBookings(authentication.getName());
    }

    @PostMapping("/member/coach-slots/{id}/online-payment")
    public OnlinePaymentLinkResponse startOnlinePayment(Authentication authentication, @PathVariable Long id,
            @RequestParam(required = false) Long voucherId) {
        return onlinePaymentService.startOrReuseSlotPayment(id, authentication.getName(), voucherId);
    }

    @PostMapping("/member/coach-slots/{id}/online-payment/check")
    public PaymentResponse checkOnlinePayment(Authentication authentication, @PathVariable Long id) {
        return onlinePaymentService.checkSlotPayment(id, authentication.getName());
    }

    // Hủy: còn từ 24 giờ trở lên trước giờ học thì hoàn đủ, muộn hơn không hoàn (BR-ONE-04). Suất mở lại cho người khác
    @PatchMapping("/member/coach-slots/{id}/cancel")
    public CoachSlotResponse cancelMyBooking(Authentication authentication, @PathVariable Long id) {
        return onlinePaymentService.cancelCoachSlotBooking(authentication.getName(), id, false);
    }

    // ====================== RECEPTIONIST ======================

    @PostMapping("/receptionist/coach-slots/{id}/cash-payment")
    public PaymentResponse payCash(Authentication authentication, @PathVariable Long id,
            @RequestParam(required = false) Long voucherId) {
        return onlinePaymentService.payCashForSlot(id, authentication.getName(), voucherId);
    }

    @PatchMapping("/receptionist/coach-slots/{id}/cancel")
    public CoachSlotResponse cancelByReceptionist(Authentication authentication, @PathVariable Long id) {
        return onlinePaymentService.cancelCoachSlotBooking(authentication.getName(), id, true);
    }
}
