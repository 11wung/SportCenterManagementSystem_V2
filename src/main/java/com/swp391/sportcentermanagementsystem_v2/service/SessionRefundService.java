package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.SessionRefundResponse;
import com.swp391.sportcentermanagementsystem_v2.entity.ClassRegistration;
import com.swp391.sportcentermanagementsystem_v2.entity.ClassSession;
import com.swp391.sportcentermanagementsystem_v2.entity.Payment;
import com.swp391.sportcentermanagementsystem_v2.entity.User;
import com.swp391.sportcentermanagementsystem_v2.exception.BusinessRuleException;
import com.swp391.sportcentermanagementsystem_v2.repository.ClassRegistrationRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.ClassSessionRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.PaymentRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.RefundRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.SportClassRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

// Phần tiền của buổi học bị hủy do trung tâm: học viên không đi được buổi bù (BR-MKP-03) và hoàn thay cho buổi bù
// khi quá hạn bù (BR-MKP-06). Mỗi lần hoàn là một buổi, tức học phí đã trả chia cho số buổi gốc của class
@Service
@RequiredArgsConstructor
public class SessionRefundService {

    private final ClassSessionRepository sessionRepository;
    private final ClassRegistrationRepository registrationRepository;
    private final SportClassRepository sportClassRepository;
    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;
    private final RefundService refundService;
    private final UserRepository userRepository;
    private final ClassDisruptionService disruptionService;
    private final AuditService auditService;

    // BR-MKP-03: member báo không đi được buổi bù. Ghi vắng không phép riêng cho buổi đó (DECLINED_MAKEUP) và hoàn một buổi.
    // Member vẫn giữ đăng ký và học các buổi còn lại
    @Transactional
    public SessionRefundResponse declineMakeup(String memberEmail, Long sessionId) {
        User member = userRepository.findByEmail(memberEmail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        ClassSession session = sessionRepository.findWithDetailsById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Class session not found with ID: " + sessionId));
        if (!session.isMakeup() || !"SCHEDULED".equals(session.getStatus())) {
            throw new BusinessRuleException("Only an upcoming makeup session can be declined");
        }
        if (!LocalDateTime.of(session.getSessionDate(), session.getStartTime()).isAfter(LocalDateTime.now(SportClassService.CENTER_ZONE))) {
            throw new BusinessRuleException("The makeup session has already started");
        }

        Long classId = session.getSportClass().getId();
        sportClassRepository.findByIdForUpdate(classId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Sport class not found"));
        ClassRegistration registration = registrationRepository
                .findFirstBySportClass_IdAndUser_IdAndStatus(classId, member.getId(), "REGISTERED")
                .flatMap(r -> registrationRepository.findByIdForUpdate(r.getId()))
                .filter(r -> "REGISTERED".equals(r.getStatus()))
                .orElseThrow(() -> new BusinessRuleException("You are not registered in this class"));
        if (sessionRepository.existsAttendance(sessionId, member.getId())) {
            throw new BusinessRuleException("You have already declined this makeup session");
        }

        sessionRepository.insertDeclinedMakeup(sessionId, member.getId());
        BigDecimal refunded = refundOneSession(registration, classId,
                "Declined makeup session " + session.getSessionNo() + " of class " + classId);
        return new SessionRefundResponse(sessionId, refunded == null ? 0 : 1, refunded == null ? BigDecimal.ZERO : refunded);
    }

    // BR-MKP-06: không còn ngày bù trong thời hạn thì hoàn một buổi cho mọi học viên đã ghi danh. Làm lại không hoàn trùng
    @Transactional
    public SessionRefundResponse refundInsteadOfMakeup(Long sessionId, String managerEmail) {
        User manager = userRepository.findByEmail(managerEmail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        // Hỏi đề xuất trước: còn ngày bù hợp lệ thì phải bù, hoàn tiền chỉ là phương án cuối
        if (!disruptionService.proposeMakeup(sessionId).isEmpty()) {
            throw new BusinessRuleException("A makeup date is still available, choose one instead of refunding");
        }
        ClassSession session = sessionRepository.findWithDetailsById(sessionId).orElseThrow();
        Long classId = session.getSportClass().getId();
        sportClassRepository.findByIdForUpdate(classId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Sport class not found"));

        String reason = "Session " + session.getSessionNo() + " of class " + classId
                + " (id " + sessionId + ") canceled by the center, refunded instead of a makeup";
        int members = 0;
        BigDecimal total = BigDecimal.ZERO;
        for (ClassRegistration listed : registrationRepository.findBySportClass_IdAndStatusInOrderById(classId, List.of("REGISTERED"))) {
            ClassRegistration registration = registrationRepository.findByIdForUpdate(listed.getId()).orElse(null);
            if (registration == null || !"REGISTERED".equals(registration.getStatus())) {
                continue;
            }
            BigDecimal refunded = refundOneSession(registration, classId, reason);
            if (refunded != null) {
                members++;
                total = total.add(refunded);
            }
        }
        auditService.log(manager, "MAKEUP_REFUNDED_INSTEAD", "class_session", sessionId,
                Map.of("classId", classId, "refundedMembers", members, "refundTotal", total));
        return new SessionRefundResponse(sessionId, members, total);
    }

    // Hoàn giá trị một buổi cho một đăng ký, không vượt phần còn hoàn được. Null nếu không có gì để hoàn
    // (lớp miễn phí, đã hoàn buổi này rồi, hoặc đã hoàn hết)
    private BigDecimal refundOneSession(ClassRegistration registration, Long classId, String reason) {
        Payment paid = paymentRepository
                .findFirstByOrder_Registration_IdAndStatusOrderByIdDesc(registration.getId(), "SUCCESS").orElse(null);
        if (paid == null || refundRepository.existsByPayment_IdAndReason(paid.getId(), truncate(reason))) {
            return null;
        }
        long sessions = sessionRepository.countBySportClassIdAndMakeupFalse(classId);
        BigDecimal unit = paid.getAmount().divide(BigDecimal.valueOf(Math.max(sessions, 1)), 0, RoundingMode.HALF_UP);
        BigDecimal amount = unit.min(refundService.refundable(paid));
        if (amount.signum() <= 0) {
            return null;
        }
        refundService.createPartialRefund(paid, amount, reason);
        return amount;
    }

    private String truncate(String reason) {
        return reason.length() > 255 ? reason.substring(0, 255) : reason;
    }
}
