package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.RefundResponse;
import com.swp391.sportcentermanagementsystem_v2.entity.Payment;
import com.swp391.sportcentermanagementsystem_v2.entity.Refund;
import com.swp391.sportcentermanagementsystem_v2.entity.User;
import com.swp391.sportcentermanagementsystem_v2.exception.BusinessRuleException;
import com.swp391.sportcentermanagementsystem_v2.repository.PaymentRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.RefundRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class RefundService {

    private final RefundRepository refundRepository;
    private final PaymentRepository paymentRepository;
    private final UserRepository userRepository;
    private final SystemSettingService systemSettingService;
    private final AuditService auditService;

    // Tạo khoản chờ hoàn tiền: payment SUCCESS (hoặc PENDING_REFUND chưa có dòng hoàn) -> PENDING_REFUND,
    // order -> CANCELED. Mỗi payment chỉ có một khoản hoàn. Chạy trong transaction của người gọi
    @Transactional
    public Refund createRefund(Payment payment, BigDecimal amount, String reason) {
        // Khóa payment: thứ tự khóa luôn là đối tượng được mua (gói, đăng ký) rồi mới tới payment
        Payment locked = paymentRepository.findByIdForUpdate(payment.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found"));

        if (!List.of("SUCCESS", "PENDING_REFUND").contains(locked.getStatus())) {
            throw new BusinessRuleException("Only a successful payment can be refunded (status: " + locked.getStatus() + ")");
        }
        if (amount == null || amount.signum() <= 0 || amount.compareTo(locked.getAmount()) > 0) {
            throw new BusinessRuleException("Refund amount must be between 1 and the paid amount");
        }
        if (refundRepository.existsByPayment_Id(locked.getId())) {
            throw new BusinessRuleException("This payment already has a refund");
        }

        Refund refund = new Refund();
        refund.setPayment(locked);
        refund.setAmount(amount);
        refund.setReason(reason.length() > 255 ? reason.substring(0, 255) : reason);
        refund.setMethod("CASH".equals(locked.getPaymentMethod()) ? "CASH" : "TRANSFER");

        locked.setStatus("PENDING_REFUND");
        locked.getOrder().setStatus("CANCELED");
        paymentRepository.save(locked);
        return refundRepository.save(refund);
    }

    // BR-RFD-03: người xử lý bấm "đã hoàn" sau khi đã trả tiền; hệ thống ghi ai và lúc nào
    @Transactional
    public RefundResponse complete(Long refundId, String staffEmail) {
        User staff = userRepository.findByEmail(staffEmail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        if (!Boolean.TRUE.equals(staff.getIsActive())
                || !List.of("RECEPTIONIST", "MANAGER").contains(staff.getRole().getCode())) {
            throw new BusinessRuleException("Only active Receptionists or Managers can complete a refund");
        }

        Refund refund = refundRepository.findByIdForUpdate(refundId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Refund not found"));
        if (!"PENDING".equals(refund.getStatus())) {
            throw new BusinessRuleException("This refund has already been completed");
        }

        refund.setStatus("DONE");
        refund.setProcessedBy(staff);
        refund.setProcessedAt(OffsetDateTime.now());
        Payment payment = paymentRepository.findByIdForUpdate(refund.getPayment().getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found"));
        payment.setStatus("REFUNDED");
        paymentRepository.save(payment);
        refundRepository.save(refund);

        auditService.log(staff, "REFUND_COMPLETED", "refund", refund.getId(),
                Map.of("paymentId", payment.getId(), "amount", refund.getAmount(), "method", refund.getMethod()));
        return new RefundResponse(refund, false);
    }

    // BR-RPT-01: lễ tân chỉ thấy các khoản chờ hoàn tiền; manager xem được mọi trạng thái (lọc theo status)
    @Transactional(readOnly = true)
    public List<RefundResponse> list(String staffEmail, String status) {
        User staff = userRepository.findByEmail(staffEmail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        boolean manager = "MANAGER".equals(staff.getRole().getCode());

        if (status != null && !List.of("PENDING", "DONE").contains(status)) {
            throw new BusinessRuleException("Status must be PENDING or DONE");
        }
        String effective = manager ? status : "PENDING";
        List<Refund> refunds = effective == null
                ? refundRepository.findAllByOrderByRequestedAtDesc()
                : refundRepository.findByStatusOrderByRequestedAtAsc(effective);

        OffsetDateTime overdueBefore = OffsetDateTime.now()
                .minusDays(systemSettingService.getInt("overview.overdue_refund_days"));
        return refunds.stream()
                .map(r -> new RefundResponse(r, "PENDING".equals(r.getStatus())
                        && r.getRequestedAt() != null && r.getRequestedAt().isBefore(overdueBefore)))
                .toList();
    }
}
