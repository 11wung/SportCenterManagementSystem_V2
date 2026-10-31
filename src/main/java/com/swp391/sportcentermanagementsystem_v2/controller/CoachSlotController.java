package com.swp391.sportcentermanagementsystem_v2.controller;

import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.CancelSlotRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.CreateCoachSlotRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.SetCoachRankRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.CoachSlotResponse;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.FacilityRoomResponse;
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

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

// Buổi 1-1 (BR-ONE): manager mở suất, member đặt và trả tiền, hủy và hoàn tiền
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class CoachSlotController {

    private final CoachSlotService coachSlotService;
    private final OnlinePaymentService onlinePaymentService;

    // ====================== MANAGER (trung tâm quyết định lịch 1-1) ======================

    // Mở suất hàng loạt cho một coach: khoảng ngày x thứ trong tuần x slot giờ. Trả số suất tạo được và các ô bị bỏ qua kèm lý do
    @PostMapping("/manager/coach-slots")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> publish(Authentication authentication,
                                       @Valid @RequestBody CreateCoachSlotRequest request) {
        return coachSlotService.publish(authentication.getName(), request);
    }

    @GetMapping("/manager/coach-slots")
    public List<CoachSlotResponse> managerList(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(required = false) Long coachId,
            @RequestParam(required = false) String status) {
        return coachSlotService.managerList(from, to, coachId, status);
    }

    // Phòng còn trống cho một suất: theo ngày, slot giờ và môn
    @GetMapping("/manager/coach-slots/free-rooms")
    public List<FacilityRoomResponse> freeRooms(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam Long timeSlotId,
            @RequestParam Long sportCategoryId) {
        return coachSlotService.freeRooms(date, timeSlotId, sportCategoryId);
    }

    // Chỉ xóa được suất chưa có người đặt
    @DeleteMapping("/manager/coach-slots/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSlot(@PathVariable Long id) {
        coachSlotService.deleteSlot(id);
    }

    // Hủy buổi đã có người đặt: member được hoàn đủ kèm voucher đền bù (BR-ONE-05)
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

    @GetMapping("/receptionist/members/{memberId}/coach-slots")
    public List<CoachSlotResponse> memberBookings(@PathVariable Long memberId) {
        return coachSlotService.memberBookings(memberId);
    }

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
