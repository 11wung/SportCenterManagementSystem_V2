package com.swp391.sportcentermanagementsystem_v2.controller;

import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.OnlinePaymentLinkResponse;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.PaymentResponse;
import com.swp391.sportcentermanagementsystem_v2.repository.ClassRegistrationRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.CoachSlotRequestRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.MemberSubscriptionRepository;
import com.swp391.sportcentermanagementsystem_v2.service.CoachSlotRequestService;
import com.swp391.sportcentermanagementsystem_v2.service.OnlinePaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Thu bằng QR tại quầy: khách không có tiền mặt thì lễ tân tạo mã QR PayOS cho đơn của họ, khách quét bằng app ngân hàng.
 * Dùng đúng luồng thanh toán online của hội viên (cùng giữ chỗ, cùng webhook), chỉ khác người bấm là lễ tân.
 * /check hỏi PayOS ngay để màn hình quầy biết khách đã trả chưa.
 */
@RestController
@RequestMapping("/api/receptionist")
@RequiredArgsConstructor
public class CounterQrController {

    private final OnlinePaymentService onlinePaymentService;
    private final CoachSlotRequestService requestService;
    private final MemberSubscriptionRepository subscriptionRepository;
    private final ClassRegistrationRepository registrationRepository;
    private final CoachSlotRequestRepository requestRepository;

    @PostMapping("/subscriptions/{id}/qr-payment")
    public OnlinePaymentLinkResponse subscriptionQr(@PathVariable Long id) {
        return onlinePaymentService.startOrReusePayment(id, subscriptionOwner(id));
    }

    @PostMapping("/subscriptions/{id}/qr-payment/check")
    public PaymentResponse subscriptionCheck(@PathVariable Long id) {
        return onlinePaymentService.checkPayment(id, subscriptionOwner(id));
    }

    @PostMapping("/class-registrations/{id}/qr-payment")
    public OnlinePaymentLinkResponse registrationQr(@PathVariable Long id) {
        return onlinePaymentService.startOrReuseRegistrationPayment(id, registrationOwner(id));
    }

    @PostMapping("/class-registrations/{id}/qr-payment/check")
    public PaymentResponse registrationCheck(@PathVariable Long id) {
        return onlinePaymentService.checkRegistrationPayment(id, registrationOwner(id));
    }

    @PostMapping("/coach-requests/{id}/qr-payment")
    public OnlinePaymentLinkResponse requestQr(@PathVariable Long id) {
        return onlinePaymentService.startOrReuseSlotPayment(requestService.anchorSlotId(id), requestOwner(id));
    }

    @PostMapping("/coach-requests/{id}/qr-payment/check")
    public PaymentResponse requestCheck(@PathVariable Long id) {
        return onlinePaymentService.checkSlotPayment(requestService.anchorSlotId(id), requestOwner(id));
    }

    private String subscriptionOwner(Long id) {
        return subscriptionRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy gói thành viên"))
                .getUser().getEmail();
    }

    private String registrationOwner(Long id) {
        return registrationRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy đăng ký"))
                .getUser().getEmail();
    }

    private String requestOwner(Long id) {
        return requestRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy yêu cầu có mã " + id))
                .getMember().getEmail();
    }
}
