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

    // Hoàn tiền kết thúc việc mua: payment SUCCESS (hoặc PENDING_REFUND chưa có dòng hoàn) -> PENDING_REFUND,
    // order -> CANCELED, nên chỉ làm được một lần. Chạy trong transaction của người gọi
    @Transactional
    public Refund createRefund(Payment payment, BigDecimal amount, String reason) {
        return create(payment, amount, reason, true);
    }

    // Hoàn một phần mà người mua vẫn giữ đăng ký (vd. buổi bù không đi được): payment vẫn SUCCESS, order giữ nguyên.
    // Làm được nhiều lần, miễn tổng các khoản hoàn không vượt số đã trả
    @Transactional
    public Refund createPartialRefund(Payment payment, BigDecimal amount, String reason) {
        return create(payment, amount, reason, false);
    }

    // Số tiền còn hoàn được của payment = đã trả trừ tổng các khoản đã yêu cầu hoàn
    @Transactional(readOnly = true)
    public BigDecimal refundable(Payment payment) {
        return payment.getAmount().subtract(refundRepository.sumAmountByPaymentId(payment.getId()));
    }

    private Refund create(Payment payment, BigDecimal amount, String reason, boolean closesPurchase) {
        // Khóa payment: thứ tự khóa luôn là đối tượng được mua (gói, đăng ký) rồi mới tới payment
        Payment locked = paymentRepository.findByIdForUpdate(payment.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found"));

        boolean refundable = "SUCCESS".equals(locked.getStatus())
                || ("PENDING_REFUND".equals(locked.getStatus()) && !refundRepository.existsByPayment_Id(locked.getId()));
        if (!refundable) {
            throw new BusinessRuleException("Only a successful payment can be refunded (status: " + locked.getStatus() + ")");
        }
        BigDecimal left = refundable(locked);
        if (amount == null || amount.signum() <= 0 || amount.compareTo(left) > 0) {
            throw new BusinessRuleException("Refund amount must be between 1 and the amount still refundable (" + left + ")");
        }

        Refund refund = new Refund();
        refund.setPayment(locked);
        refund.setAmount(amount);
        refund.setReason(reason.length() > 255 ? reason.substring(0, 255) : reason);
        refund.setMethod("CASH".equals(locked.getPaymentMethod()) ? "CASH" : "TRANSFER");

        if (closesPurchase) {
            locked.setStatus("PENDING_REFUND");
            locked.getOrder().setStatus("CANCELED");
            paymentRepository.save(locked);
        }
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
        // Payment chỉ thành REFUNDED khi việc mua đã kết thúc (PENDING_REFUND) và không còn khoản hoàn nào đang chờ
        if ("PENDING_REFUND".equals(payment.getStatus())
                && !refundRepository.existsByPayment_IdAndStatusAndIdNot(payment.getId(), "PENDING", refund.getId())) {
            payment.setStatus("REFUNDED");
            paymentRepository.save(payment);
        }
        refundRepository.save(refund);

        auditService.log(staff, "REFUND_COMPLETED", "refund", refund.getId(),
                Map.of("paymentId", payment.getId(), "amount", refund.getAmount(), "method", refund.getMethod()));
        return new RefundResponse(refund, false);
    }

    // Member theo dõi các khoản hoàn tiền của chính mình: PENDING là chờ quầy hoàn, DONE là đã hoàn
    @Transactional(readOnly = true)
    public List<RefundResponse> myRefunds(String memberEmail) {
        User member = userRepository.findByEmail(memberEmail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        return refundRepository.findByPayment_Payer_IdOrderByRequestedAtDesc(member.getId()).stream()
                .map(r -> new RefundResponse(r, false))
                .toList();
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
