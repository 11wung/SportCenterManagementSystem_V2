
package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.CashPaymentRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.PaymentResponse;
import com.swp391.sportcentermanagementsystem_v2.entity.MemberSubscription;
import com.swp391.sportcentermanagementsystem_v2.entity.Payment;
import com.swp391.sportcentermanagementsystem_v2.entity.User;
import com.swp391.sportcentermanagementsystem_v2.repository.PaymentRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.UserRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final UserRepository userRepository;
    private final EntityManager entityManager;

    @Transactional
    public PaymentResponse payCash(
            CashPaymentRequest request,
            String receptionistEmail
    ) {

        // 1. Tìm Subscription
        MemberSubscription subscription = entityManager.find(
                MemberSubscription.class,
                request.getSubscriptionId()
        );

        if (subscription == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Subscription not found"
            );
        }

        // 2. Khóa User trước để phối hợp kiểm soát
        // nhiều giao dịch của cùng một Member
        User member = entityManager.find(
                User.class,
                subscription.getUser().getId(),
                LockModeType.PESSIMISTIC_WRITE
        );

        // 3. Khóa và đọc lại Subscription
        entityManager.refresh(
                subscription,
                LockModeType.PESSIMISTIC_WRITE
        );

        // 4. Kiểm tra Subscription đang PENDING
        if (!"PENDING".equals(subscription.getStatus())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Subscription is not PENDING"
            );
        }

        // 5. Kiểm tra Payment SUCCESS trùng
        boolean alreadyPaid =
                paymentRepository.existsBySubscription_IdAndStatus(
                        subscription.getId(),
                        "SUCCESS"
                );

        if (alreadyPaid) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Subscription already paid"
            );
        }

        // 6. Phiên bản này chưa hỗ trợ gia hạn/nâng cấp
        Long activeCount = entityManager.createQuery("""
                        SELECT COUNT(s)
                        FROM MemberSubscription s
                        WHERE s.user.id = :userId
                          AND s.status = 'ACTIVE'
                        """, Long.class)
                .setParameter("userId", member.getId())
                .getSingleResult();

        if (activeCount > 0) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Member already has an ACTIVE subscription"
            );
        }

        // 7. Kiểm tra số tiền
        if (subscription.getTotalAmount() == null
                || subscription.getTotalAmount().signum() <= 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid payment amount"
            );
        }

        // 8. Xác định Receptionist đang đăng nhập
        User receptionist = userRepository
                .findByEmail(receptionistEmail)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Receptionist not found"
                ));

        // 9. Tạo Payment
        Payment payment = new Payment();

        payment.setSubscription(subscription);
        payment.setPayer(member);
        payment.setReceivedBy(receptionist);

        payment.setAmount(subscription.getTotalAmount());
        payment.setPaymentMethod("CASH");
        payment.setStatus("SUCCESS");
        payment.setPaidAt(OffsetDateTime.now(ZoneOffset.UTC));

        payment.setTransactionCode(
                "CASH-" + UUID.randomUUID()
        );

        payment.setInvoiceNumber(
                "INV-" + UUID.randomUUID()
                        .toString()
                        .replace("-", "")
                        .substring(0, 20)
        );

        // 10. Lưu Payment vào PostgreSQL
        Payment savedPayment =
                paymentRepository.saveAndFlush(payment);

        // Đọc lại các giá trị DB tự sinh, như createdAt
        entityManager.refresh(savedPayment);

        // 11. Chuyển Entity thành Response DTO
        PaymentResponse response = new PaymentResponse();

        response.setId(savedPayment.getId());
        response.setSubscriptionId(subscription.getId());
        response.setPayerId(member.getId());

        response.setInvoiceNumber(
                savedPayment.getInvoiceNumber()
        );
        response.setTransactionCode(
                savedPayment.getTransactionCode()
        );

        response.setAmount(savedPayment.getAmount());
        response.setPaymentMethod(savedPayment.getPaymentMethod());
        response.setStatus(savedPayment.getStatus());
        response.setPaidAt(savedPayment.getPaidAt());
        response.setCreatedAt(savedPayment.getCreatedAt());

        return response;
    }
}
