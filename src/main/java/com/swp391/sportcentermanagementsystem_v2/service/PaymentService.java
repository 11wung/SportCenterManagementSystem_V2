
package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.InvoiceResponse;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.PaymentResponse;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.OnlinePaymentLinkResponse;
import com.swp391.sportcentermanagementsystem_v2.entity.ClassRegistration;
import com.swp391.sportcentermanagementsystem_v2.entity.CoachSlot;
import com.swp391.sportcentermanagementsystem_v2.entity.MemberSubscription;
import com.swp391.sportcentermanagementsystem_v2.entity.Payment;
import com.swp391.sportcentermanagementsystem_v2.entity.Order;
import com.swp391.sportcentermanagementsystem_v2.entity.User;
import com.swp391.sportcentermanagementsystem_v2.exception.BusinessRuleException;
import com.swp391.sportcentermanagementsystem_v2.repository.MemberSubscriptionRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.ClassRegistrationRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.CoachSlotRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.PaymentRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.UserRepository;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import lombok.extern.slf4j.Slf4j;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {

        private final PaymentRepository paymentRepository;
        private final MemberSubscriptionRepository subscriptionRepository;
        private final UserRepository userRepository;
        private final PlatformTransactionManager transactionManager;
        private final OrderService orderService;
        private final ClassRegistrationRepository classRegistrationRepository;
        private final ClassRegistrationService classRegistrationService;
        private final RefundService refundService;
        private final NotificationService notificationService;
        private final CoachSlotRepository coachSlotRepository;
        private final CoachSlotService coachSlotService;
        private final MemberSubscriptionService memberSubscriptionService;

        private static final ZoneId VIETNAM_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

        // Đánh dấu Payment đã nhận tiền, đang chờ kích hoạt Membership.
        private static final String ACTIVATION_PENDING = "REQUIRES_REVIEW: Membership activation pending";

        // ========================================================
        // 1. CONFIRM CASH PAYMENT
        // ========================================================

        @Transactional
        public PaymentResponse payCash(
                        Long subscriptionId,
                        String receptionistEmail) {

                User receptionist = userRepository
                                .findByEmail(receptionistEmail)
                                .orElseThrow(() -> new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Không tìm thấy lễ tân"));

                String cashierRole = receptionist.getRole().getCode();

                if ((!"RECEPTIONIST".equals(cashierRole) && !"MANAGER".equals(cashierRole))
                                || !Boolean.TRUE.equals(receptionist.getIsActive())) {

                        throw new BusinessRuleException(
                                        "Chỉ lễ tân hoặc quản lý đang hoạt động mới xác nhận được thu tiền mặt");
                }

                MemberSubscription summary = subscriptionRepository
                                .findById(subscriptionId)
                                .orElseThrow(() -> new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Không tìm thấy gói thành viên"));

                Long memberId = summary.getUser().getId();

                // Lock order: Member -> Subscription.
                User member = userRepository.findByIdForUpdate(memberId)
                                .orElseThrow(() -> new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Không tìm thấy hội viên"));

                if (!"MEMBER".equals(member.getRole().getCode())) {
                        throw new BusinessRuleException(
                                        "Người trả tiền gói phải là hội viên");
                }

                MemberSubscription subscription = subscriptionRepository
                                .findByIdForUpdate(subscriptionId)
                                .orElseThrow(() -> new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Không tìm thấy gói thành viên"));

                if (!subscription.getUser().getId().equals(memberId)) {
                        throw new BusinessRuleException(
                                        "Chủ sở hữu gói không hợp lệ");
                }

                if (!"PENDING".equals(subscription.getStatus())) {
                        throw new BusinessRuleException(
                                        "Chỉ thanh toán được gói đang chờ thanh toán");
                }

                // Nếu đơn do Member tự tạo online mà đến quầy nộp tiền mặt,
                // ghi nhận Lễ tân tiếp nhận và xử lý thanh toán tại quầy.
                if (subscription.getCreatedBy() == null) {
                        subscription.setCreatedBy(receptionist);
                }

                if (paymentRepository.existsBySubscription_IdAndStatus(subscriptionId, "SUCCESS")) {
                        throw new BusinessRuleException(
                                        "Giao dịch đã hoàn tất");
                }

                // QR online chưa được đóng (OnlinePaymentService.payCash đã hủy trên PayOS
                // trước khi vào đây) thì không thu tiền mặt, tránh thu hai lần.
                if (paymentRepository.existsBySubscription_IdAndStatusIn(
                                subscriptionId,
                                List.of("CREATING", "PENDING"))) {
                        throw new BusinessRuleException(
                                        "Thanh toán online đang được xử lý, vui lòng thử lại sau ít phút");
                }

                BigDecimal amount = subscription.getTotalAmount();

                if (amount == null
                                || amount.compareTo(BigDecimal.ZERO) <= 0) {
                        throw new BusinessRuleException(
                                        "Số tiền mặt phải lớn hơn 0");
                }

                OffsetDateTime paidAt = OffsetDateTime.now(VIETNAM_ZONE);

                LocalDate today = paidAt.toLocalDate();

                if (subscription.getPaymentExpiresAt() != null
                                && !paidAt.isBefore(
                                                subscription.getPaymentExpiresAt())) {

                        throw new BusinessRuleException(
                                        "Đã quá hạn thanh toán");
                }

                // Cash không dùng ngoại lệ UPGRADE qua ngày.
                activateAfterPayment(
                                subscription,
                                memberId,
                                today,
                                false,
                                false);

                subscriptionRepository.saveAndFlush(subscription);

                String uniqueCode = UUID.randomUUID()
                                .toString()
                                .replace("-", "");

                // Đơn có thể đã tạo lúc khách mở QR online, nay khách ra quầy trả tiền mặt
                Order order = orderService.getOrCreateForSubscription(
                                subscription, member, receptionist, "COUNTER");
                order.setChannel("COUNTER");
                order.setStatus("PAID");

                Payment payment = new Payment();

                payment.setOrder(order);
                payment.setPayer(member);
                payment.setReceivedBy(receptionist);

                payment.setInvoiceNumber(
                                "INV-" + uniqueCode.substring(0, 20));

                payment.setTransactionCode(
                                "CASH-" + uniqueCode);

                payment.setAmount(amount);
                payment.setPaymentMethod("CASH");
                payment.setStatus("SUCCESS");
                payment.getOrder().setStatus("PAID");
                payment.setPaidAt(paidAt);
                payment.setGatewayResponse(null);

                Payment saved = tellCashReceived(paymentRepository.saveAndFlush(payment));

                return mapToResponse(saved);
        }

        // Hội viên được báo ngay khi quầy đã nhận tiền mặt, để biết khoản nào đã được ghi nhận
        private Payment tellCashReceived(Payment saved) {
                String text = "Quầy đã nhận " + saved.getAmount().toPlainString() + " đ tiền mặt của bạn. Hóa đơn "
                                + saved.getInvoiceNumber() + ".";
                notificationService.sendWithEmail(saved.getPayer().getId(), "PAYMENT", "Đã nhận thanh toán", text,
                                "Đã nhận thanh toán", text);
                return saved;
        }

        // ========================================================
        // 2. RESERVE ONLINE PAYMENT
        // ========================================================

        // Dữ liệu trả về sau khi reservation đã được COMMIT.
        public record OnlineReservation(
                        Long paymentId,
                        Long subscriptionId,
                        Long registrationId,
                        Long coachSlotId,
                        Long orderCode,
                        BigDecimal amount,
                        OffsetDateTime expiresAt,
                        String status,
                        String checkoutUrl,
                        boolean newReservation) {
        }

        @Transactional
        public OnlineReservation reserveOnlinePayment(
                        Long subscriptionId,
                        String memberEmail) {

                User currentMember = userRepository
                                .findByEmail(memberEmail)
                                .orElseThrow(() -> new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Không tìm thấy hội viên"));

                if (!"MEMBER".equals(currentMember.getRole().getCode())
                                || !Boolean.TRUE.equals(currentMember.getIsActive())) {

                        throw new BusinessRuleException(
                                        "Chỉ hội viên đang hoạt động mới thanh toán online được");
                }

                MemberSubscription summary = subscriptionRepository
                                .findById(subscriptionId)
                                .orElseThrow(() -> new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Không tìm thấy gói thành viên"));

                if (!summary.getUser().getId()
                                .equals(currentMember.getId())) {

                        throw new BusinessRuleException(
                                        "Gói này không thuộc hội viên");
                }

                // Lock Member trước, giống Cash Payment.
                User member = userRepository
                                .findByIdForUpdate(currentMember.getId())
                                .orElseThrow(() -> new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Không tìm thấy hội viên"));

                MemberSubscription subscription = subscriptionRepository
                                .findByIdForUpdate(subscriptionId)
                                .orElseThrow(() -> new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Không tìm thấy gói thành viên"));

                if (!subscription.getUser().getId()
                                .equals(member.getId())
                                || !"PENDING".equals(subscription.getStatus())) {

                        throw new BusinessRuleException(
                                        "Gói này không thanh toán được");
                }

                if (paymentRepository.existsBySubscription_IdAndStatus(
                                subscriptionId, "SUCCESS")) {

                        throw new BusinessRuleException(
                                        "Gói này đã có giao dịch thành công");
                }

                // Nếu đang có QR thì trả giao dịch đó.
                Optional<Payment> existing = paymentRepository
                                .findFirstByOrder_Subscription_IdAndPaymentMethodAndStatusInOrderByIdDesc(
                                                subscriptionId,
                                                "QR_GATEWAY",
                                                List.of("CREATING", "PENDING", "SUCCESS"));

                if (existing.isPresent()) {

                        Payment previous = existing.get();

                        if ("SUCCESS".equals(previous.getStatus())) {
                                throw new BusinessRuleException(
                                                "Gói này đã được thanh toán");
                        }

                        if (previous.getGatewayOrderCode() == null) {
                                throw new BusinessRuleException(
                                                "Thiếu mã đơn của cổng thanh toán, cần đối soát thủ công");
                        }

                        return new OnlineReservation(
                                        previous.getId(),
                                        subscriptionId,
                                        null,
                                        null,
                                        previous.getGatewayOrderCode(),
                                        previous.getAmount(),
                                        previous.getExpiresAt(),
                                        previous.getStatus(),
                                        previous.getPaymentUrl(),
                                        false);
                }

                BigDecimal amount = subscription.getTotalAmount();

                if (amount == null
                                || amount.compareTo(BigDecimal.ZERO) <= 0) {

                        throw new BusinessRuleException(
                                        "Số tiền thanh toán online phải lớn hơn 0");
                }

                OffsetDateTime expiresAt = OffsetDateTime.now(VIETNAM_ZONE)
                                .plusMinutes(15);

                String uniqueCode = UUID.randomUUID()
                                .toString()
                                .replace("-", "");

                Order order = orderService.getOrCreateForSubscription(
                                subscription, member, null, "ONLINE");

                Payment payment = new Payment();

                payment.setOrder(order);
                payment.setPayer(member);
                payment.setReceivedBy(null);

                payment.setInvoiceNumber(
                                "INV-" + uniqueCode.substring(0, 20));

                payment.setTransactionCode(
                                "PAYOS-" + uniqueCode);

                payment.setAmount(amount);
                payment.setPaymentMethod("QR_GATEWAY");
                payment.setStatus("CREATING");
                payment.setPaidAt(null);
                payment.setGatewayResponse(null);
                payment.setPaymentUrl(null);
                payment.setExpiresAt(expiresAt);

                // ID của Payment làm PayOS orderCode dạng số.
                Payment saved = paymentRepository.saveAndFlush(payment);

                saved.setGatewayOrderCode(saved.getId());
                paymentRepository.save(saved);

                // Giữ để các API Subscription hiện tại hiển thị.
                // Payment.expiresAt là hạn QR 15 phút; không ghi đè hạn chung của đơn membership (24 giờ).
                subscriptionRepository.save(subscription);

                return new OnlineReservation(
                                saved.getId(),
                                subscriptionId,
                                null,
                                null,
                                saved.getGatewayOrderCode(),
                                amount,
                                expiresAt,
                                "CREATING",
                                null,
                                true);
        }

        // ========================================================
        // 2B. ONLINE AND CASH PAYMENT FOR A CLASS REGISTRATION
        // ========================================================

        // Giống reserveOnlinePayment nhưng cho đăng ký class: QR hết hạn cùng lúc hạn giữ chỗ (BR-QR-02).
        @Transactional
        public OnlineReservation reserveOnlinePaymentForRegistration(
                        Long registrationId,
                        String memberEmail) {

                User currentMember = userRepository
                                .findByEmail(memberEmail)
                                .orElseThrow(() -> new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Không tìm thấy hội viên"));

                if (!"MEMBER".equals(currentMember.getRole().getCode())
                                || !Boolean.TRUE.equals(currentMember.getIsActive())) {

                        throw new BusinessRuleException(
                                        "Chỉ hội viên đang hoạt động mới thanh toán online được");
                }

                ClassRegistration summary = classRegistrationRepository
                                .findById(registrationId)
                                .orElseThrow(() -> new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Không tìm thấy đăng ký"));

                if (!summary.getUser().getId().equals(currentMember.getId())) {
                        throw new BusinessRuleException(
                                        "Đăng ký này không thuộc hội viên");
                }

                // Lock Member trước, rồi đăng ký, giống luồng của gói.
                User member = userRepository
                                .findByIdForUpdate(currentMember.getId())
                                .orElseThrow(() -> new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Không tìm thấy hội viên"));

                ClassRegistration registration = classRegistrationRepository
                                .findByIdForUpdate(registrationId)
                                .orElseThrow(() -> new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Không tìm thấy đăng ký"));

                if (!"PENDING_PAYMENT".equals(registration.getStatus())) {
                        throw new BusinessRuleException(
                                        "Đăng ký này không thanh toán được");
                }

                if (registration.getHoldExpiresAt() == null
                                || !OffsetDateTime.now(VIETNAM_ZONE)
                                                .isBefore(registration.getHoldExpiresAt())) {

                        throw new BusinessRuleException(
                                        "Đã hết hạn giữ chỗ, vui lòng đăng ký lại");
                }

                if (paymentRepository.existsByRegistration_IdAndStatus(
                                registrationId, "SUCCESS")) {

                        throw new BusinessRuleException(
                                        "Đăng ký này đã có giao dịch thành công");
                }

                // Nếu đang có QR thì trả giao dịch đó.
                Optional<Payment> existing = paymentRepository
                                .findFirstByOrder_Registration_IdAndPaymentMethodAndStatusInOrderByIdDesc(
                                                registrationId,
                                                "QR_GATEWAY",
                                                List.of("CREATING", "PENDING", "SUCCESS"));

                if (existing.isPresent()) {

                        Payment previous = existing.get();

                        if ("SUCCESS".equals(previous.getStatus())) {
                                throw new BusinessRuleException(
                                                "Đăng ký này đã được thanh toán");
                        }

                        if (previous.getGatewayOrderCode() == null) {
                                throw new BusinessRuleException(
                                                "Thiếu mã đơn của cổng thanh toán, cần đối soát thủ công");
                        }

                        return new OnlineReservation(
                                        previous.getId(),
                                        null,
                                        registrationId,
                                        null,
                                        previous.getGatewayOrderCode(),
                                        previous.getAmount(),
                                        previous.getExpiresAt(),
                                        previous.getStatus(),
                                        previous.getPaymentUrl(),
                                        false);
                }

                Order order = orderService.getOrCreateForRegistration(
                                registration, member, null, "ONLINE");
                BigDecimal amount = orderService.applyPricing(order, member).getTotalAmount();

                // Sau ưu đãi không còn gì phải trả: bỏ qua thanh toán, đơn coi như đã trả
                if (amount.signum() == 0) {
                        settleFreeOrder(order);
                        classRegistrationService.confirmPaid(registrationId);
                        return freeReservation(registrationId, null);
                }

                // Mỗi QR sống tối đa 15 phút (như gói đăng ký). Chỗ mời từ danh sách chờ giữ lâu hơn (BR-WL-03),
                // hết QR thì member lấy QR mới trong lúc còn giữ chỗ
                OffsetDateTime qrLimit = OffsetDateTime.now(VIETNAM_ZONE).plusMinutes(15);
                OffsetDateTime expiresAt = registration.getHoldExpiresAt().isBefore(qrLimit)
                                ? registration.getHoldExpiresAt()
                                : qrLimit;

                String uniqueCode = UUID.randomUUID()
                                .toString()
                                .replace("-", "");

                Payment payment = new Payment();

                payment.setOrder(order);
                payment.setPayer(member);
                payment.setReceivedBy(null);
                payment.setInvoiceNumber("INV-" + uniqueCode.substring(0, 20));
                payment.setTransactionCode("PAYOS-" + uniqueCode);
                payment.setAmount(amount);
                payment.setPaymentMethod("QR_GATEWAY");
                payment.setStatus("CREATING");
                payment.setPaidAt(null);
                payment.setGatewayResponse(null);
                payment.setPaymentUrl(null);
                payment.setExpiresAt(expiresAt);

                // ID của Payment làm PayOS orderCode dạng số.
                Payment saved = paymentRepository.saveAndFlush(payment);

                saved.setGatewayOrderCode(saved.getId());
                paymentRepository.save(saved);

                return new OnlineReservation(
                                saved.getId(),
                                null,
                                registrationId,
                                null,
                                saved.getGatewayOrderCode(),
                                amount,
                                expiresAt,
                                "CREATING",
                                null,
                                true);
        }

        // Thu tiền mặt cho đăng ký class tại quầy (BR-PAY-04). QR còn mở phải được đóng trước
        // (OnlinePaymentService.payCashForRegistration), tránh thu hai lần.
        @Transactional
        public PaymentResponse payCashForRegistration(
                        Long registrationId,
                        String cashierEmail) {

                User cashier = userRepository
                                .findByEmail(cashierEmail)
                                .orElseThrow(() -> new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Không tìm thấy lễ tân"));

                String cashierRole = cashier.getRole().getCode();

                if ((!"RECEPTIONIST".equals(cashierRole) && !"MANAGER".equals(cashierRole))
                                || !Boolean.TRUE.equals(cashier.getIsActive())) {

                        throw new BusinessRuleException(
                                        "Chỉ lễ tân hoặc quản lý đang hoạt động mới xác nhận được thu tiền mặt");
                }

                ClassRegistration summary = classRegistrationRepository
                                .findById(registrationId)
                                .orElseThrow(() -> new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Không tìm thấy đăng ký"));

                // Lock order: Member -> Registration.
                User member = userRepository
                                .findByIdForUpdate(summary.getUser().getId())
                                .orElseThrow(() -> new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Không tìm thấy hội viên"));

                if (!"MEMBER".equals(member.getRole().getCode())) {
                        throw new BusinessRuleException(
                                        "Người trả tiền đăng ký phải là hội viên");
                }

                ClassRegistration registration = classRegistrationRepository
                                .findByIdForUpdate(registrationId)
                                .orElseThrow(() -> new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Không tìm thấy đăng ký"));

                if (!"PENDING_PAYMENT".equals(registration.getStatus())) {
                        throw new BusinessRuleException(
                                        "Chỉ thanh toán được đăng ký đang chờ thanh toán");
                }

                OffsetDateTime paidAt = OffsetDateTime.now(VIETNAM_ZONE);

                if (registration.getHoldExpiresAt() == null
                                || !paidAt.isBefore(registration.getHoldExpiresAt())) {

                        throw new BusinessRuleException(
                                        "Đã hết hạn giữ chỗ, vui lòng đăng ký lại");
                }

                if (paymentRepository.existsByRegistration_IdAndStatus(registrationId, "SUCCESS")) {
                        throw new BusinessRuleException(
                                        "Giao dịch đã hoàn tất");
                }

                if (paymentRepository.existsByRegistration_IdAndStatusIn(
                                registrationId,
                                List.of("CREATING", "PENDING"))) {

                        throw new BusinessRuleException(
                                        "Thanh toán online đang được xử lý, vui lòng thử lại sau ít phút");
                }

                Order order = orderService.getOrCreateForRegistration(
                                registration, member, cashier, "COUNTER");
                BigDecimal amount = orderService.applyPricing(order, member).getTotalAmount();

                classRegistrationService.confirmPaid(registrationId);

                order.setChannel("COUNTER");
                order.setStatus("PAID");

                // Sau ưu đãi không còn gì phải thu: không tạo payment
                if (amount.signum() == 0) {
                        return freeResponse(order, paidAt);
                }

                String uniqueCode = UUID.randomUUID()
                                .toString()
                                .replace("-", "");

                Payment payment = new Payment();

                payment.setOrder(order);
                payment.setPayer(member);
                payment.setReceivedBy(cashier);
                payment.setInvoiceNumber("INV-" + uniqueCode.substring(0, 20));
                payment.setTransactionCode("CASH-" + uniqueCode);
                payment.setAmount(amount);
                payment.setPaymentMethod("CASH");
                payment.setStatus("SUCCESS");
                payment.setPaidAt(paidAt);
                payment.setGatewayResponse(null);

                return mapToResponse(tellCashReceived(paymentRepository.saveAndFlush(payment)));
        }

        // ========================================================
        // 2C. ONLINE AND CASH PAYMENT FOR A 1-1 SESSION
        // ========================================================

        // Giống reserveOnlinePaymentForRegistration nhưng cho suất 1-1: QR hết hạn cùng lúc hạn giữ chỗ (BR-QR-02).
        @Transactional
        public OnlineReservation reserveOnlinePaymentForSlot(
                        Long slotId,
                        String memberEmail) {

                User currentMember = userRepository
                                .findByEmail(memberEmail)
                                .orElseThrow(() -> new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Không tìm thấy hội viên"));

                if (!"MEMBER".equals(currentMember.getRole().getCode())
                                || !Boolean.TRUE.equals(currentMember.getIsActive())) {

                        throw new BusinessRuleException(
                                        "Chỉ hội viên đang hoạt động mới thanh toán online được");
                }

                CoachSlot summary = coachSlotRepository
                                .findById(slotId)
                                .orElseThrow(() -> new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Không tìm thấy khung giờ"));

                if (summary.getMember() == null
                                || !summary.getMember().getId().equals(currentMember.getId())) {
                        throw new BusinessRuleException(
                                        "Buổi này không thuộc hội viên");
                }

                // Lock Member trước, rồi suất, giống luồng của gói.
                User member = userRepository
                                .findByIdForUpdate(currentMember.getId())
                                .orElseThrow(() -> new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Không tìm thấy hội viên"));

                CoachSlot slot = coachSlotRepository
                                .findByIdForUpdate(slotId)
                                .orElseThrow(() -> new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Không tìm thấy khung giờ"));

                if (!"HELD".equals(slot.getStatus())) {
                        throw new BusinessRuleException(
                                        "Buổi này không thanh toán được");
                }

                if (slot.getHeldUntil() == null
                                || !OffsetDateTime.now(VIETNAM_ZONE).isBefore(slot.getHeldUntil())) {

                        throw new BusinessRuleException(
                                        "Đã hết hạn giữ chỗ, vui lòng đặt lại");
                }

                if (paymentRepository.existsByCoachSlot_IdAndStatus(slotId, "SUCCESS")) {
                        throw new BusinessRuleException(
                                        "Buổi này đã có giao dịch thành công");
                }

                // Nếu đang có QR thì trả giao dịch đó.
                Optional<Payment> existing = paymentRepository
                                .findFirstByOrder_CoachSlot_IdAndPaymentMethodAndStatusInOrderByIdDesc(
                                                slotId,
                                                "QR_GATEWAY",
                                                List.of("CREATING", "PENDING", "SUCCESS"));

                if (existing.isPresent()) {

                        Payment previous = existing.get();

                        if ("SUCCESS".equals(previous.getStatus())) {
                                throw new BusinessRuleException(
                                                "Buổi này đã được thanh toán");
                        }

                        if (previous.getGatewayOrderCode() == null) {
                                throw new BusinessRuleException(
                                                "Thiếu mã đơn của cổng thanh toán, cần đối soát thủ công");
                        }

                        return new OnlineReservation(
                                        previous.getId(),
                                        null,
                                        null,
                                        slotId,
                                        previous.getGatewayOrderCode(),
                                        previous.getAmount(),
                                        previous.getExpiresAt(),
                                        previous.getStatus(),
                                        previous.getPaymentUrl(),
                                        false);
                }

                // Yêu cầu nhiều buổi chỉ có một khoản thanh toán: đang có QR ở buổi khác của yêu cầu thì không tạo QR thứ hai
                if (slot.getRequest() != null
                                && paymentRepository.existsByCoachSlot_IdAndStatusIn(slotId, List.of("CREATING", "PENDING"))) {
                        throw new BusinessRuleException(
                                        "Yêu cầu này đang có một giao dịch, hãy dùng link thanh toán của yêu cầu");
                }

                Order order = orderService.getOrCreateForSlot(
                                slot, member, null, "ONLINE");
                BigDecimal amount = orderService.applyPricing(order, member).getTotalAmount();

                // Sau ưu đãi không còn gì phải trả (giá gốc bằng 0): bỏ qua thanh toán
                if (amount.signum() == 0) {
                        settleFreeOrder(order);
                        coachSlotService.confirmPaid(slotId);
                        return freeReservation(null, slotId);
                }

                OffsetDateTime expiresAt = slot.getHeldUntil();

                String uniqueCode = UUID.randomUUID()
                                .toString()
                                .replace("-", "");

                Payment payment = new Payment();

                payment.setOrder(order);
                payment.setPayer(member);
                payment.setReceivedBy(null);
                payment.setInvoiceNumber("INV-" + uniqueCode.substring(0, 20));
                payment.setTransactionCode("PAYOS-" + uniqueCode);
                payment.setAmount(amount);
                payment.setPaymentMethod("QR_GATEWAY");
                payment.setStatus("CREATING");
                payment.setPaidAt(null);
                payment.setGatewayResponse(null);
                payment.setPaymentUrl(null);
                payment.setExpiresAt(expiresAt);

                // ID của Payment làm PayOS orderCode dạng số.
                Payment saved = paymentRepository.saveAndFlush(payment);

                saved.setGatewayOrderCode(saved.getId());
                paymentRepository.save(saved);

                return new OnlineReservation(
                                saved.getId(),
                                null,
                                null,
                                slotId,
                                saved.getGatewayOrderCode(),
                                amount,
                                expiresAt,
                                "CREATING",
                                null,
                                true);
        }

        // Thu tiền mặt cho buổi 1-1 tại quầy (BR-PAY-04). QR còn mở phải được đóng trước
        // (OnlinePaymentService.payCashForSlot), tránh thu hai lần.
        @Transactional
        public PaymentResponse payCashForSlot(
                        Long slotId,
                        String cashierEmail) {

                User cashier = userRepository
                                .findByEmail(cashierEmail)
                                .orElseThrow(() -> new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Không tìm thấy lễ tân"));

                String cashierRole = cashier.getRole().getCode();

                if ((!"RECEPTIONIST".equals(cashierRole) && !"MANAGER".equals(cashierRole))
                                || !Boolean.TRUE.equals(cashier.getIsActive())) {

                        throw new BusinessRuleException(
                                        "Chỉ lễ tân hoặc quản lý đang hoạt động mới xác nhận được thu tiền mặt");
                }

                CoachSlot summary = coachSlotRepository
                                .findById(slotId)
                                .orElseThrow(() -> new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Không tìm thấy khung giờ"));

                if (summary.getMember() == null) {
                        throw new BusinessRuleException(
                                        "Buổi này chưa có hội viên đặt");
                }

                // Lock order: Member -> Slot.
                User member = userRepository
                                .findByIdForUpdate(summary.getMember().getId())
                                .orElseThrow(() -> new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Không tìm thấy hội viên"));

                CoachSlot slot = coachSlotRepository
                                .findByIdForUpdate(slotId)
                                .orElseThrow(() -> new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Không tìm thấy khung giờ"));

                if (!"HELD".equals(slot.getStatus())) {
                        throw new BusinessRuleException(
                                        "Chỉ thanh toán được buổi đang chờ thanh toán");
                }

                OffsetDateTime paidAt = OffsetDateTime.now(VIETNAM_ZONE);

                if (slot.getHeldUntil() == null
                                || !paidAt.isBefore(slot.getHeldUntil())) {

                        throw new BusinessRuleException(
                                        "Đã hết hạn giữ chỗ, vui lòng đặt lại");
                }

                if (paymentRepository.existsByCoachSlot_IdAndStatus(slotId, "SUCCESS")) {
                        throw new BusinessRuleException(
                                        "Giao dịch đã hoàn tất");
                }

                if (paymentRepository.existsByCoachSlot_IdAndStatusIn(
                                slotId,
                                List.of("CREATING", "PENDING"))) {

                        throw new BusinessRuleException(
                                        "Thanh toán online đang được xử lý, vui lòng thử lại sau ít phút");
                }

                Order order = orderService.getOrCreateForSlot(
                                slot, member, cashier, "COUNTER");
                BigDecimal amount = orderService.applyPricing(order, member).getTotalAmount();

                coachSlotService.confirmPaid(slotId);

                order.setChannel("COUNTER");
                order.setStatus("PAID");

                // Sau ưu đãi không còn gì phải thu: không tạo payment
                if (amount.signum() == 0) {
                        return freeResponse(order, paidAt);
                }

                String uniqueCode = UUID.randomUUID()
                                .toString()
                                .replace("-", "");

                Payment payment = new Payment();

                payment.setOrder(order);
                payment.setPayer(member);
                payment.setReceivedBy(cashier);
                payment.setInvoiceNumber("INV-" + uniqueCode.substring(0, 20));
                payment.setTransactionCode("CASH-" + uniqueCode);
                payment.setAmount(amount);
                payment.setPaymentMethod("CASH");
                payment.setStatus("SUCCESS");
                payment.setPaidAt(paidAt);
                payment.setGatewayResponse(null);

                return mapToResponse(tellCashReceived(paymentRepository.saveAndFlush(payment)));
        }

        // ========================================================
        // 3. CONFIRM PAYMENT LINK CREATED
        // ========================================================

        @Transactional
        public OnlinePaymentLinkResponse confirmPaymentLinkCreated(
                        Long paymentId,
                        Long orderCode,
                        String checkoutUrl) {

                Payment summary = paymentRepository.findById(paymentId)
                                .orElseThrow(() -> new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Không tìm thấy giao dịch"));

                userRepository.findByIdForUpdate(summary.getPayer().getId())
                                .orElseThrow(() -> new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Không tìm thấy hội viên"));

                // Lock gói hoặc đăng ký trước Payment để giữ thứ tự nhất quán.
                boolean targetPayable = lockTargetAndCheckPayable(summary);

                Payment payment = paymentRepository
                                .findByIdForUpdate(paymentId)
                                .orElseThrow(() -> new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Không tìm thấy giao dịch"));

                if (!"QR_GATEWAY".equals(payment.getPaymentMethod())
                                || orderCode == null
                                || !orderCode.equals(payment.getGatewayOrderCode())) {

                        throw new BusinessRuleException(
                                        "Mã đơn của cổng thanh toán không khớp");
                }

                if (!targetPayable) {
                        throw new BusinessRuleException(
                                        "Đơn này không còn thanh toán được");
                }

                if (!isValidCheckoutUrl(checkoutUrl)) {
                        throw new BusinessRuleException(
                                        "Link thanh toán không hợp lệ");
                }

                if (!"CREATING".equals(payment.getStatus())
                                && !"PENDING".equals(payment.getStatus())) {

                        throw new BusinessRuleException(
                                        "Giao dịch không còn chờ link thanh toán");
                }

                // Không được hồi sinh QR đã hết hạn.
                if (payment.getExpiresAt() == null
                                || !OffsetDateTime.now(VIETNAM_ZONE)
                                                .isBefore(payment.getExpiresAt())) {

                        throw new BusinessRuleException(
                                        "Link thanh toán đã hết hạn, cần đối soát thủ công");
                }

                payment.setPaymentUrl(checkoutUrl);
                payment.setStatus("PENDING");

                paymentRepository.saveAndFlush(payment);

                return mapToOnlineLinkResponse(payment);
        }

        // ========================================================
        // 4. MARK DEFINITIVE LINK REJECTION
        // ========================================================

        @Transactional
        public void markLinkRejected(
                        Long paymentId,
                        Long orderCode) {

                Payment summary = paymentRepository.findById(paymentId)
                                .orElseThrow(() -> new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Không tìm thấy giao dịch"));

                userRepository.findByIdForUpdate(summary.getPayer().getId())
                                .orElseThrow(() -> new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Không tìm thấy hội viên"));

                lockTargetAndCheckPayable(summary);

                Payment payment = paymentRepository
                                .findByIdForUpdate(paymentId)
                                .orElseThrow(() -> new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Không tìm thấy giao dịch"));

                if (orderCode == null
                                || !orderCode.equals(payment.getGatewayOrderCode())
                                || !"QR_GATEWAY".equals(payment.getPaymentMethod())) {

                        throw new BusinessRuleException(
                                        "Mã đơn của cổng thanh toán không khớp");
                }

                if ("CREATING".equals(payment.getStatus())) {

                        payment.setStatus("FAILED");
                        payment.setGatewayResponse(
                                        "Cổng thanh toán từ chối tạo link");

                        if (payment.getSubscription() != null) {
                                payment.getSubscription().setPaymentExpiresAt(null);
                        }

                        // Không xóa payment.expiresAt.
                        paymentRepository.saveAndFlush(payment);
                }
        }

        // ========================================================
        // 5. MARK CONFIRMED LINK CANCELLATION
        // ========================================================

        @Transactional
        public void markLinkCanceled(
                        Long paymentId,
                        Long orderCode) {

                Payment summary = paymentRepository.findById(paymentId)
                                .orElseThrow(() -> new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Không tìm thấy giao dịch"));

                userRepository.findByIdForUpdate(summary.getPayer().getId())
                                .orElseThrow(() -> new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Không tìm thấy hội viên"));

                lockTargetAndCheckPayable(summary);

                Payment payment = paymentRepository
                                .findByIdForUpdate(paymentId)
                                .orElseThrow(() -> new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Không tìm thấy giao dịch"));

                if (orderCode == null
                                || !orderCode.equals(payment.getGatewayOrderCode())
                                || !"QR_GATEWAY".equals(payment.getPaymentMethod())) {

                        throw new BusinessRuleException(
                                        "Mã đơn của cổng thanh toán không khớp");
                }

                if ("CREATING".equals(payment.getStatus())
                                || "PENDING".equals(payment.getStatus())) {

                        payment.setStatus("CANCELED");
                        payment.setGatewayResponse(
                                        "Cổng thanh toán xác nhận link đã hủy hoặc hết hạn");

                        if (payment.getSubscription() != null) {
                                payment.getSubscription().setPaymentExpiresAt(null);
                        }

                        paymentRepository.saveAndFlush(payment);
                }
        }

        // ========================================================
        // 6. CONFIRM VERIFIED ONLINE SUCCESS
        // ========================================================

        // Chỉ gọi sau khi dữ liệu thanh toán được backend
        // xác minh từ PayOS. Không cho Member gọi trực tiếp.
        //
        // Transaction 1: Ghi nhận tiền đã nhận và COMMIT.
        // Transaction 2: Kích hoạt Membership.
        //
        // Nếu Transaction 2 thất bại, Payment SUCCESS vẫn tồn tại.
        public PaymentResponse confirmVerifiedOnlinePayment(
                        Long paymentId,
                        Long verifiedOrderCode,
                        BigDecimal receivedAmount,
                        OffsetDateTime verifiedPaidAt) {

                // TRANSACTION 1: Ghi nhận thanh toán.
                TransactionTemplate recordTransaction = new TransactionTemplate(transactionManager);

                recordTransaction.setPropagationBehavior(
                                TransactionDefinition.PROPAGATION_REQUIRES_NEW);

                OnlinePaymentRecordingResult recordingResult = recordTransaction
                                .execute(status -> recordVerifiedOnlinePayment(
                                                paymentId,
                                                verifiedOrderCode,
                                                receivedAmount,
                                                verifiedPaidAt));

                if (recordingResult == null) {
                        throw new IllegalStateException(
                                        "Chưa ghi nhận được thanh toán online đã xác minh");
                }

                // Payment đã SUCCESS từ trước, hoặc cần đối soát.
                // Không tự động kích hoạt lần nữa khi webhook lặp.
                if (!recordingResult.shouldActivate()) {
                        return recordingResult.response();
                }

                // TRANSACTION 2: Kích hoạt Membership.
                try {

                        return activateInNewTransaction(paymentId);

                } catch (RuntimeException ex) {

                        // Transaction 2 đã rollback.
                        // Transaction 1 vẫn giữ Payment SUCCESS và
                        // ACTIVATION_PENDING; retryPendingActivations sẽ thử lại.
                        log.error(
                                        "Membership activation failed for paymentId={}",
                                        paymentId,
                                        ex);

                        return recordingResult.response();
                }
        }

        private PaymentResponse activateInNewTransaction(Long paymentId) {

                TransactionTemplate activationTransaction = new TransactionTemplate(transactionManager);

                activationTransaction.setPropagationBehavior(
                                TransactionDefinition.PROPAGATION_REQUIRES_NEW);

                PaymentResponse response = activationTransaction
                                .execute(status -> activateVerifiedOnlineMembership(paymentId));

                if (response == null) {
                        throw new IllegalStateException(
                                        "Kích hoạt gói không trả về kết quả");
                }

                return response;
        }

        // Thử lại các Payment đã nhận tiền nhưng chưa kích hoạt xong
        // (lỗi tạm hoặc server tắt giữa hai transaction). Gọi từ scheduler.
        //
        // shortcut: chỉ thử các giao dịch trả trong 24 giờ qua để một lỗi
        // vĩnh viễn không lặp mãi; quá hạn thì xử lý tay.
        public void retryPendingActivations() {

                OffsetDateTime now = OffsetDateTime.now(VIETNAM_ZONE);

                // Chừa 1 phút cho lần kích hoạt đang chạy ngay sau webhook.
                List<Long> paymentIds = paymentRepository.findIdsAwaitingActivation(
                                ACTIVATION_PENDING,
                                now.minusHours(24),
                                now.minusMinutes(1));

                for (Long paymentId : paymentIds) {

                        try {
                                activateInNewTransaction(paymentId);
                        } catch (RuntimeException ex) {
                                log.error(
                                                "Retry activation failed for paymentId={}",
                                                paymentId,
                                                ex);
                        }
                }
        }

        // ========================================================
        // 6.1. RECORD VERIFIED ONLINE PAYMENT
        // ========================================================

        private record OnlinePaymentRecordingResult(
                        boolean shouldActivate,
                        PaymentResponse response) {
        }

        private OnlinePaymentRecordingResult recordVerifiedOnlinePayment(
                        Long paymentId,
                        Long verifiedOrderCode,
                        BigDecimal receivedAmount,
                        OffsetDateTime verifiedPaidAt) {

                Payment summary = paymentRepository.findById(paymentId)
                                .orElseThrow(() -> new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Không tìm thấy giao dịch"));

                Long memberId = summary.getPayer().getId();

                // Giữ thứ tự khóa: Member -> Subscription -> Payment.
                userRepository.findByIdForUpdate(memberId)
                                .orElseThrow(() -> new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Không tìm thấy hội viên"));

                Long targetOwnerId = lockTargetOwner(summary);

                Payment payment = paymentRepository.findByIdForUpdate(paymentId)
                                .orElseThrow(() -> new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Không tìm thấy giao dịch"));

                if (!"QR_GATEWAY".equals(payment.getPaymentMethod())
                                || verifiedOrderCode == null
                                || !verifiedOrderCode.equals(payment.getGatewayOrderCode())) {

                        throw new BusinessRuleException(
                                        "Định danh thanh toán PayOS không hợp lệ");
                }

                if (!payment.getPayer().getId().equals(memberId)
                                || !targetOwnerId.equals(memberId)) {

                        throw new BusinessRuleException(
                                        "Chủ giao dịch không hợp lệ");
                }

                // Webhook lặp: không ghi nhận hoặc kích hoạt lại.
                if ("SUCCESS".equals(payment.getStatus())) {

                        return new OnlinePaymentRecordingResult(
                                        false,
                                        mapToResponse(payment));
                }

                String problem = null;

                if (!"CREATING".equals(payment.getStatus())
                                && !"PENDING".equals(payment.getStatus())) {

                        problem = "Giao dịch trước đó ở trạng thái " + payment.getStatus();
                }

                if (receivedAmount == null
                                || payment.getAmount() == null
                                || payment.getOrder().getTotalAmount() == null
                                || receivedAmount.compareTo(payment.getAmount()) != 0
                                || payment.getAmount().compareTo(
                                                payment.getOrder().getTotalAmount()) != 0) {

                        problem = "Số tiền nhận được không khớp đơn hàng";
                }

                // PayOS đã xác nhận nhận tiền:
                // lưu SUCCESS bất kể Membership có kích hoạt được không.
                payment.setStatus("SUCCESS");
                payment.getOrder().setStatus("PAID");
                // DB bắt buộc SUCCESS phải có paid_at; thiếu giờ PayOS thì ghi giờ hiện tại và để REQUIRES_REVIEW xử lý.
                payment.setPaidAt(verifiedPaidAt != null
                                ? verifiedPaidAt
                                : OffsetDateTime.now(VIETNAM_ZONE));

                if (problem != null) {

                        payment.setGatewayResponse(
                                        "REQUIRES_REVIEW: " + problem);

                } else if (verifiedPaidAt == null) {

                        payment.setGatewayResponse(
                                        "REQUIRES_REVIEW: Verified payment time is missing");

                } else {

                        // Đặt REVIEW trước khi thử kích hoạt.
                        // Nếu server dừng giữa hai transaction,
                        // Payment vẫn được ghi nhận an toàn.
                        payment.setGatewayResponse(ACTIVATION_PENDING);
                }

                Payment saved = paymentRepository.saveAndFlush(payment);

                boolean shouldActivate = problem == null && verifiedPaidAt != null;

                return new OnlinePaymentRecordingResult(
                                shouldActivate,
                                mapToResponse(saved));
        }

        // ========================================================
        // 6.2. ACTIVATE VERIFIED ONLINE MEMBERSHIP
        // ========================================================

        private PaymentResponse activateVerifiedOnlineMembership(
                        Long paymentId) {

                Payment summary = paymentRepository.findById(paymentId)
                                .orElseThrow(() -> new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Không tìm thấy giao dịch"));

                if (summary.getOrder().getCoachSlot() != null) {
                        return activateVerifiedSlot(paymentId);
                }

                if (summary.getOrder().getRegistration() != null) {
                        return activateVerifiedRegistration(paymentId);
                }

                Long memberId = summary.getPayer().getId();

                userRepository.findByIdForUpdate(memberId)
                                .orElseThrow(() -> new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Không tìm thấy hội viên"));

                MemberSubscription subscription = subscriptionRepository
                                .findByIdForUpdate(summary.getSubscription().getId())
                                .orElseThrow(() -> new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Không tìm thấy gói thành viên"));

                Payment payment = paymentRepository.findByIdForUpdate(paymentId)
                                .orElseThrow(() -> new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Không tìm thấy giao dịch"));

                if (!"SUCCESS".equals(payment.getStatus())) {
                        throw new BusinessRuleException(
                                        "Thanh toán chưa được xác nhận");
                }

                // Chỉ xử lý Payment đang chờ kích hoạt.
                if (!ACTIVATION_PENDING.equals(payment.getGatewayResponse())) {

                        return mapToResponse(payment);
                }

                OffsetDateTime verifiedPaidAt = payment.getPaidAt();

                String problem = getOnlineActivationProblem(
                                payment,
                                subscription,
                                memberId,
                                verifiedPaidAt);

                if (problem == null) {

                        LocalDate paymentDate = verifiedPaidAt
                                        .atZoneSameInstant(VIETNAM_ZONE)
                                        .toLocalDate();

                        boolean allowExpiredOnlineUpgrade = "UPGRADE".equals(subscription.getPurchaseType());

                        // Ngày bắt đầu tính theo ngày member trả tiền,
                        // dù PayOS xác nhận trễ sang ngày khác.
                        activateAfterPayment(
                                        subscription,
                                        memberId,
                                        paymentDate,
                                        true,
                                        allowExpiredOnlineUpgrade);

                        subscriptionRepository.saveAndFlush(subscription);
                }

                if (problem != null) {

                        payment.setGatewayResponse(
                                        "REQUIRES_REVIEW: " + problem);

                } else {

                        payment.setGatewayResponse(
                                        "VERIFIED: PayOS payment confirmed");
                }

                Payment saved = paymentRepository.saveAndFlush(payment);

                return mapToResponse(saved);
        }

        // ========================================================
        // 7. CHECK ONLINE ACTIVATION CONDITIONS
        // ========================================================

        // null = có thể kích hoạt.
        // String = cần đối soát.
        private String getOnlineActivationProblem(
                        Payment payment,
                        MemberSubscription subscription,
                        Long memberId,
                        OffsetDateTime verifiedPaidAt) {

                if (!"PENDING".equals(subscription.getStatus())) {
                        return "Gói không còn ở trạng thái chờ thanh toán";
                }

                if (verifiedPaidAt == null) {
                        return "Thiếu thời điểm thanh toán đã xác minh";
                }

                if (!wasPaidWithinDeadline(payment, verifiedPaidAt)) {
                        return "Tiền về sau khi mã QR đã hết hạn";
                }

                LocalDate paymentDate = verifiedPaidAt
                                .atZoneSameInstant(VIETNAM_ZONE)
                                .toLocalDate();

                String purchaseType = subscription.getPurchaseType();

                if ("NEW".equals(purchaseType)) {

                        Optional<MemberSubscription> lastPaid = subscriptionRepository
                                        .findFirstByUserIdAndStatusInOrderByEndDateDesc(
                                                        memberId,
                                                        List.of("ACTIVE", "SCHEDULED"));

                        if (lastPaid.isPresent()
                                        && lastPaid.get().getEndDate() != null
                                        && lastPaid.get().getEndDate().isAfter(paymentDate)) {

                                return "Hội viên đã có gói còn hạn";
                        }

                        return null;
                }

                if ("RENEWAL".equals(purchaseType)) {
                        return null;
                }

                if (!"UPGRADE".equals(purchaseType)) {
                        return "Loại mua gói không hợp lệ";
                }

                Long oldId = subscription.getUpgradeFromSubscriptionId();

                if (oldId == null) {
                        return "Thiếu gói gốc";
                }

                MemberSubscription oldSub = subscriptionRepository
                                .findByIdForUpdate(oldId)
                                .orElse(null);

                if (oldSub == null) {
                        return "Không tìm thấy gói gốc";
                }

                if (!oldSub.getUser().getId().equals(memberId)) {
                        return "Gói gốc thuộc hội viên khác";
                }

                if (!"ACTIVE".equals(oldSub.getStatus())
                                && !"EXPIRED".equals(oldSub.getStatus())) {

                        return "Gói gốc đã được thay thế";
                }

                if (!wasUpgradeValidWhenOrdered(
                                subscription, oldSub)) {

                        return "Đơn nâng cấp không hợp lệ ngay từ lúc tạo";
                }

                if (oldSub.getStartDate() == null
                                || paymentDate.isBefore(oldSub.getStartDate())) {

                        return "Thời hạn gói gốc không hợp lệ";
                }

                Optional<MemberSubscription> scheduled = subscriptionRepository
                                .findFirstByUserIdAndStatusOrderByEndDateDesc(
                                                memberId,
                                                "SCHEDULED");

                if (scheduled.isPresent()) {
                        return "Hội viên đang có gói khác chờ kích hoạt";
                }

                if (subscription.getTierLevelSnapshot() == null
                                || oldSub.getTierLevelSnapshot() == null
                                || subscription.getTierLevelSnapshot() <= oldSub.getTierLevelSnapshot()) {

                        return "Hạng nâng cấp không hợp lệ";
                }

                // Gói hết hạn trước ngày thanh toán vẫn được xét
                // nếu đơn đã tạo hợp lệ và QR thanh toán đúng hạn.
                return null;
        }

        // ========================================================
        // 8. ACTIVATE AFTER PAYMENT
        // ========================================================

        private void activateAfterPayment(
                        MemberSubscription subscription,
                        Long memberId,
                        LocalDate today,
                        boolean keepUpgradeQuote,
                        boolean allowExpiredOnlineUpgrade) {

                expireOldSubscriptions(memberId, today);

                String purchaseType = subscription.getPurchaseType();

                if ("NEW".equals(purchaseType)
                                || "RENEWAL".equals(purchaseType)) {

                        activateNewOrRenewal(
                                        subscription,
                                        memberId,
                                        today);

                } else if ("UPGRADE".equals(purchaseType)) {

                        activateUpgrade(
                                        subscription,
                                        memberId,
                                        today,
                                        keepUpgradeQuote,
                                        allowExpiredOnlineUpgrade);

                } else {

                        throw new BusinessRuleException(
                                        "Loại mua gói không hợp lệ");
                }
        }

        // ========================================================
        // 9. EXPIRE OLD SUBSCRIPTIONS
        // ========================================================

        private void expireOldSubscriptions(
                        Long memberId,
                        LocalDate today) {

                List<MemberSubscription> subscriptions = subscriptionRepository.findByUserIdAndStatusIn(
                                memberId,
                                List.of("ACTIVE", "SCHEDULED"));

                for (MemberSubscription oldSub : subscriptions) {

                        if (oldSub.getEndDate() != null
                                        && !oldSub.getEndDate().isAfter(today)) {

                                oldSub.setStatus("EXPIRED");
                                subscriptionRepository.saveAndFlush(oldSub);
                        }
                }
        }

        // ========================================================
        // 10. ACTIVATE NEW / RENEWAL
        // ========================================================

        private void activateNewOrRenewal(
                        MemberSubscription newSubscription,
                        Long memberId,
                        LocalDate today) {

                Optional<MemberSubscription> lastPaid = subscriptionRepository
                                .findFirstByUserIdAndStatusInOrderByEndDateDesc(
                                                memberId,
                                                List.of("ACTIVE", "SCHEDULED"));

                if ("NEW".equals(newSubscription.getPurchaseType())
                                && lastPaid.isPresent()) {

                        throw new BusinessRuleException(
                                        "Hội viên đã có gói đã thanh toán, hãy chọn gia hạn");
                }

                LocalDate startDate = today;

                if (lastPaid.isPresent()) {

                        LocalDate lastEndDate = lastPaid.get().getEndDate();

                        if (lastEndDate != null
                                        && lastEndDate.isAfter(today)) {

                                startDate = lastEndDate;
                        }
                }

                LocalDate endDate = startDate.plusMonths(
                                newSubscription.getMonths());

                newSubscription.setStartDate(startDate);
                newSubscription.setEndDate(endDate);

                if (startDate.isAfter(today)) {
                        newSubscription.setStatus("SCHEDULED");
                } else {
                        newSubscription.setStatus("ACTIVE");
                }
        }

        // ========================================================
        // 11. ACTIVATE UPGRADE
        // ========================================================

        private void activateUpgrade(
                        MemberSubscription newSubscription,
                        Long memberId,
                        LocalDate today,
                        boolean keepUpgradeQuote,
                        boolean allowExpiredOnlineUpgrade) {

                Long oldId = newSubscription.getUpgradeFromSubscriptionId();

                if (oldId == null) {
                        throw new BusinessRuleException(
                                        "Nâng cấp cần có gói gốc");
                }

                MemberSubscription oldSub = subscriptionRepository
                                .findByIdForUpdate(oldId)
                                .orElseThrow(() -> new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Không tìm thấy gói gốc"));

                if (!oldSub.getUser().getId().equals(memberId)) {
                        throw new BusinessRuleException(
                                        "Gói gốc không thuộc hội viên");
                }

                if (oldSub.getStartDate() == null
                                || oldSub.getEndDate() == null) {

                        throw new BusinessRuleException(
                                        "Ngày của gói gốc không hợp lệ");
                }

                boolean activeToday = "ACTIVE".equals(oldSub.getStatus())
                                && !today.isBefore(oldSub.getStartDate())
                                && today.isBefore(oldSub.getEndDate());

                // Phương án C:
                // Cho phép xử lý gói vừa EXPIRED nếu đơn và
                // thời điểm thanh toán đã được kiểm tra.
                boolean validExpiredOnlineUpgrade = allowExpiredOnlineUpgrade
                                && "EXPIRED".equals(oldSub.getStatus());

                if (!activeToday && !validExpiredOnlineUpgrade) {
                        throw new BusinessRuleException(
                                        "Gói gốc không đủ điều kiện nâng cấp");
                }

                Optional<MemberSubscription> scheduled = subscriptionRepository
                                .findFirstByUserIdAndStatusOrderByEndDateDesc(
                                                memberId,
                                                "SCHEDULED");

                if (scheduled.isPresent()) {
                        throw new BusinessRuleException(
                                        "Không thể nâng cấp khi đang có gói khác chờ kích hoạt");
                }

                if (newSubscription.getTierLevelSnapshot() == null
                                || oldSub.getTierLevelSnapshot() == null
                                || newSubscription.getTierLevelSnapshot() <= oldSub.getTierLevelSnapshot()) {

                        throw new BusinessRuleException(
                                        "Gói mới phải có hạng cao hơn");
                }

                // Cash: kiểm tra lại Credit.
                // Online: giữ báo giá đã chốt.
                if (!keepUpgradeQuote) {

                        BigDecimal currentCredit = memberSubscriptionService.calculateUpgradeCredit(oldSub, today);

                        if (currentCredit.compareTo(
                                        newSubscription.getUpgradeCreditAmount()) != 0) {

                                throw new BusinessRuleException(
                                                "Báo giá nâng cấp đã đổi, hãy tạo đơn mới");
                        }
                }

                oldSub.setStatus("UPGRADED");

                // Giải phóng unique index ACTIVE trước khi
                // gói mới chuyển thành ACTIVE.
                subscriptionRepository.saveAndFlush(oldSub);

                newSubscription.setStartDate(today);
                int extraDays = newSubscription.getUpgradeCreditAmount() == null
                                || newSubscription.getUpgradeCreditAmount().signum() <= 0
                                ? 0
                                : MemberSubscriptionService.creditDays(
                                                newSubscription.getUpgradeCreditAmount(),
                                                newSubscription.getMembershipPackage().getMonthlyPrice());
                newSubscription.setEndDate(
                                today.plusMonths(newSubscription.getMonths()).plusDays(extraDays));
                newSubscription.setStatus("ACTIVE");
        }

        // ========================================================
        // 13. CHECK UPGRADE AT ORDER TIME
        // ========================================================

        private boolean wasUpgradeValidWhenOrdered(
                        MemberSubscription subscription,
                        MemberSubscription oldSub) {

                if (subscription.getCreatedAt() == null
                                || oldSub.getStartDate() == null
                                || oldSub.getEndDate() == null) {
                        return false;
                }

                LocalDate orderDate = subscription.getCreatedAt()
                                .atZoneSameInstant(VIETNAM_ZONE)
                                .toLocalDate();

                return !orderDate.isBefore(oldSub.getStartDate())
                                && orderDate.isBefore(oldSub.getEndDate());
        }

        // ========================================================
        // 14. CHECK PAYMENT DEADLINE
        // ========================================================

        private boolean wasPaidWithinDeadline(
                        Payment payment,
                        OffsetDateTime verifiedPaidAt) {

                if (verifiedPaidAt == null
                                || payment.getExpiresAt() == null) {
                        return false;
                }

                return verifiedPaidAt.isBefore(
                                payment.getExpiresAt());
        }

        // ========================================================
        // 15. VALIDATE CHECKOUT URL
        // ========================================================

        private boolean isValidCheckoutUrl(String url) {
                return url != null
                                && !url.isBlank()
                                && url.startsWith("https://");
        }

        // ========================================================
        // 15.1. GET PAYMENT RESPONSE
        // ========================================================

        @Transactional(readOnly = true)
        public PaymentResponse getPaymentResponse(Long paymentId) {

                Payment payment = paymentRepository.findById(paymentId)
                                .orElseThrow(() -> new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Không tìm thấy giao dịch"));

                return mapToResponse(payment);
        }

        // ========================================================
        // 15.2. PAYMENT HISTORY AND INVOICE (READ ONLY)
        // ========================================================

        // Lễ tân: lịch sử payment của một Member (mọi trạng thái).
        @Transactional(readOnly = true)
        public List<PaymentResponse> getMemberPayments(Long memberId) {

                User member = userRepository.findById(memberId)
                                .orElseThrow(() -> new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Không tìm thấy hội viên"));

                if (!"MEMBER".equals(member.getRole().getCode())) {
                        throw new ResponseStatusException(
                                        HttpStatus.NOT_FOUND,
                                        "Không tìm thấy hội viên");
                }

                return paymentRepository
                                .findByPayer_IdOrderByCreatedAtDesc(memberId)
                                .stream()
                                .map(this::mapToResponse)
                                .toList();
        }

        // Member: các payment đã thanh toán của chính mình.
        @Transactional(readOnly = true)
        public List<PaymentResponse> getMyPayments(String memberEmail) {

                User member = userRepository.findByEmail(memberEmail)
                                .orElseThrow(() -> new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Không tìm thấy hội viên"));

                return paymentRepository
                                .findByPayer_IdAndStatusOrderByPaidAtDesc(member.getId(), "SUCCESS")
                                .stream()
                                .map(this::mapToResponse)
                                .toList();
        }

        // Lễ tân: hóa đơn của bất kỳ payment đã thanh toán.
        @Transactional(readOnly = true)
        public InvoiceResponse getInvoice(Long paymentId) {

                return mapToInvoice(loadInvoicePayment(paymentId));
        }

        // Member: hóa đơn của chính mình. Payment của người khác trả 404
        // (giống "không tồn tại") để không lộ số hóa đơn nào đang có.
        @Transactional(readOnly = true)
        public InvoiceResponse getMyInvoice(Long paymentId, String memberEmail) {

                Payment payment = paymentRepository.findWithDetailsById(paymentId)
                                .orElseThrow(() -> new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Không tìm thấy giao dịch"));

                if (!memberEmail.equals(payment.getPayer().getEmail())) {
                        throw new ResponseStatusException(
                                        HttpStatus.NOT_FOUND,
                                        "Không tìm thấy giao dịch");
                }

                return mapToInvoice(requirePaid(payment));
        }

        private Payment loadInvoicePayment(Long paymentId) {

                Payment payment = paymentRepository.findWithDetailsById(paymentId)
                                .orElseThrow(() -> new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Không tìm thấy giao dịch"));

                return requirePaid(payment);
        }

        // Chưa thanh toán thành công thì chưa có hóa đơn.
        private Payment requirePaid(Payment payment) {

                if (!"SUCCESS".equals(payment.getStatus())) {
                        throw new BusinessRuleException(
                                        "Chỉ giao dịch thành công mới có hóa đơn");
                }

                return payment;
        }

        private InvoiceResponse mapToInvoice(Payment payment) {

                MemberSubscription subscription = payment.getSubscription();
                User payer = payment.getPayer();

                if (subscription == null) {
                        return mapToClassInvoice(payment);
                }

                BigDecimal credit = subscription.getUpgradeCreditAmount();

                if (credit == null) {
                        credit = BigDecimal.ZERO;
                }

                InvoiceResponse invoice = new InvoiceResponse();

                invoice.setInvoiceNumber(payment.getInvoiceNumber());
                invoice.setTransactionCode(payment.getTransactionCode());
                invoice.setPaidAt(payment.getPaidAt());

                invoice.setMemberName(payer.getFullName());
                invoice.setMemberEmail(payer.getEmail());
                invoice.setMemberPhone(payer.getPhone());

                invoice.setPackageName(subscription.getPackageNameSnapshot());
                invoice.setDurationMonths(subscription.getMonths());
                invoice.setPurchaseType(subscription.getPurchaseType());
                invoice.setStartDate(subscription.getStartDate());
                invoice.setEndDate(subscription.getEndDate());

                // Số tiền trả = giá gói - tiền được trừ khi nâng cấp.
                invoice.setAmount(payment.getAmount());
                invoice.setUpgradeCreditAmount(credit);
                invoice.setPackagePrice(payment.getAmount().add(credit));

                invoice.setPaymentMethod(payment.getPaymentMethod());

                if (payment.getReceivedBy() != null) {
                        invoice.setReceivedByName(payment.getReceivedBy().getFullName());
                }

                return invoice;
        }

        // Hóa đơn ghi danh class: tên class thay cho tên gói, không có tiền nâng gói.
        private InvoiceResponse mapToClassInvoice(Payment payment) {

                if (payment.getOrder().getCoachSlot() != null) {
                        return mapToSlotInvoice(payment);
                }

                ClassRegistration registration = payment.getOrder().getRegistration();
                User payer = payment.getPayer();

                InvoiceResponse invoice = new InvoiceResponse();

                invoice.setInvoiceNumber(payment.getInvoiceNumber());
                invoice.setTransactionCode(payment.getTransactionCode());
                invoice.setPaidAt(payment.getPaidAt());

                invoice.setMemberName(payer.getFullName());
                invoice.setMemberEmail(payer.getEmail());
                invoice.setMemberPhone(payer.getPhone());

                invoice.setPackageName(registration.getSportClass().getName());
                invoice.setPurchaseType("CLASS_ENROLLMENT");
                invoice.setStartDate(registration.getSportClass().getStartDate());

                invoice.setPackagePrice(payment.getOrder().getListPrice());
                invoice.setUpgradeCreditAmount(BigDecimal.ZERO);
                invoice.setAmount(payment.getAmount());

                invoice.setPaymentMethod(payment.getPaymentMethod());

                if (payment.getReceivedBy() != null) {
                        invoice.setReceivedByName(payment.getReceivedBy().getFullName());
                }

                return invoice;
        }

        // ========================================================
        // 16. MAP PAYMENT RESPONSE
        // ========================================================

        // Đơn có tổng bằng 0 sau ưu đãi: không có payment, đơn tự coi là đã trả
        private void settleFreeOrder(Order order) {
                order.setStatus("PAID");
        }

        private OnlineReservation freeReservation(Long registrationId, Long slotId) {
                return new OnlineReservation(
                                null,
                                null,
                                registrationId,
                                slotId,
                                null,
                                BigDecimal.ZERO,
                                null,
                                "PAID",
                                null,
                                false);
        }

        private PaymentResponse freeResponse(Order order, OffsetDateTime paidAt) {
                PaymentResponse response = new PaymentResponse();
                response.setRegistrationId(
                                order.getRegistration() == null ? null : order.getRegistration().getId());
                response.setCoachSlotId(
                                order.getCoachSlot() == null ? null : order.getCoachSlot().getId());
                response.setAmount(BigDecimal.ZERO);
                response.setPaymentMethod("FREE");
                response.setPaymentStatus("PAID");
                response.setPaidAt(paidAt);
                return response;
        }

        private PaymentResponse mapToResponse(Payment payment) {

                PaymentResponse response = new PaymentResponse();

                response.setId(payment.getId());
                response.setSubscriptionId(
                                payment.getSubscription() == null ? null : payment.getSubscription().getId());
                response.setRegistrationId(
                                payment.getOrder().getRegistration() == null ? null : payment.getOrder().getRegistration().getId());
                response.setCoachSlotId(
                                payment.getOrder().getCoachSlot() == null ? null : payment.getOrder().getCoachSlot().getId());
                response.setInvoiceNumber(
                                payment.getInvoiceNumber());
                response.setTransactionCode(
                                payment.getTransactionCode());
                response.setAmount(payment.getAmount());
                response.setPaymentMethod(
                                payment.getPaymentMethod());
                response.setPaymentStatus(
                                payment.getStatus());
                if (payment.getSubscription() != null) {
                        response.setSubscriptionStatus(
                                        payment.getSubscription().getStatus());
                        response.setStartDate(
                                        payment.getSubscription().getStartDate());
                        response.setEndDate(
                                        payment.getSubscription().getEndDate());
                }
                response.setPaidAt(payment.getPaidAt());

                String gatewayResponse = payment.getGatewayResponse();

                response.setRequiresReview(
                                gatewayResponse != null
                                                && gatewayResponse.startsWith("REQUIRES_REVIEW:"));

                return response;
        }

        // Kích hoạt buổi 1-1 sau khi PayOS xác nhận đã nhận tiền (cùng khuôn với đăng ký class).
        // BR-QR-04: tiền về muộn mà suất không còn thì payment chuyển sang chờ hoàn tiền.
        private PaymentResponse activateVerifiedSlot(Long paymentId) {

                Payment summary = paymentRepository.findById(paymentId)
                                .orElseThrow(() -> new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Không tìm thấy giao dịch"));

                userRepository.findByIdForUpdate(summary.getPayer().getId())
                                .orElseThrow(() -> new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Không tìm thấy hội viên"));

                CoachSlot slot = lockSlot(summary);

                Payment payment = paymentRepository.findByIdForUpdate(paymentId)
                                .orElseThrow(() -> new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Không tìm thấy giao dịch"));

                if (!"SUCCESS".equals(payment.getStatus())) {
                        throw new BusinessRuleException(
                                        "Thanh toán chưa được xác nhận");
                }

                // Chỉ xử lý Payment đang chờ kích hoạt.
                if (!ACTIVATION_PENDING.equals(payment.getGatewayResponse())) {
                        return mapToResponse(payment);
                }

                if (coachSlotService.activatePaidOnline(slot.getId(), payment.getPayer().getId())) {

                        payment.setGatewayResponse(
                                        "VERIFIED: PayOS payment confirmed");

                } else {

                        refundService.createRefund(payment, payment.getAmount(),
                                        "1-1 session no longer available when the payment arrived");
                        payment.setGatewayResponse(
                                        "VERIFIED: Session is no longer available, refund required");
                }

                return mapToResponse(paymentRepository.saveAndFlush(payment));
        }

        private CoachSlot lockSlot(Payment summary) {

                return coachSlotRepository
                                .findByIdForUpdate(summary.getOrder().getCoachSlot().getId())
                                .orElseThrow(() -> new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Không tìm thấy khung giờ"));
        }

        // Hóa đơn buổi 1-1: tên buổi thay cho tên gói.
        private InvoiceResponse mapToSlotInvoice(Payment payment) {

                CoachSlot slot = payment.getOrder().getCoachSlot();
                User payer = payment.getPayer();

                InvoiceResponse invoice = new InvoiceResponse();

                invoice.setInvoiceNumber(payment.getInvoiceNumber());
                invoice.setTransactionCode(payment.getTransactionCode());
                invoice.setPaidAt(payment.getPaidAt());

                invoice.setMemberName(payer.getFullName());
                invoice.setMemberEmail(payer.getEmail());
                invoice.setMemberPhone(payer.getPhone());

                invoice.setPackageName("Buổi 1-1 với " + slot.getCoach().getFullName());
                invoice.setPurchaseType("ONE_ON_ONE_SESSION");
                invoice.setStartDate(slot.getStartAt().atZoneSameInstant(VIETNAM_ZONE).toLocalDate());

                invoice.setPackagePrice(payment.getOrder().getListPrice());
                invoice.setUpgradeCreditAmount(BigDecimal.ZERO);
                invoice.setAmount(payment.getAmount());

                invoice.setPaymentMethod(payment.getPaymentMethod());

                if (payment.getReceivedBy() != null) {
                        invoice.setReceivedByName(payment.getReceivedBy().getFullName());
                }

                return invoice;
        }

        // ========================================================
        // 16B. PAYMENT TARGET (SUBSCRIPTION OR CLASS REGISTRATION)
        // ========================================================

        // Khóa đối tượng đang được thanh toán (gói hoặc đăng ký class) trước Payment để giữ thứ tự khóa
        // Member -> đối tượng -> Payment. Trả về true nếu đối tượng còn có thể trả tiền.
        private boolean lockTargetAndCheckPayable(Payment summary) {

                if (summary.getOrder().getCoachSlot() != null) {
                        return "HELD".equals(lockSlot(summary).getStatus());
                }

                if (summary.getOrder().getRegistration() != null) {
                        return "PENDING_PAYMENT".equals(lockRegistration(summary).getStatus());
                }

                MemberSubscription subscription = subscriptionRepository
                                .findByIdForUpdate(summary.getSubscription().getId())
                                .orElseThrow(() -> new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Không tìm thấy gói thành viên"));

                return "PENDING".equals(subscription.getStatus());
        }

        // Như trên nhưng trả về id chủ sở hữu của gói hoặc đăng ký.
        private Long lockTargetOwner(Payment summary) {

                if (summary.getOrder().getCoachSlot() != null) {
                        lockSlot(summary);
                        return summary.getOrder().getMember().getId();
                }

                if (summary.getOrder().getRegistration() != null) {
                        return lockRegistration(summary).getUser().getId();
                }

                return subscriptionRepository
                                .findByIdForUpdate(summary.getSubscription().getId())
                                .orElseThrow(() -> new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Không tìm thấy gói thành viên"))
                                .getUser().getId();
        }

        private ClassRegistration lockRegistration(Payment summary) {

                return classRegistrationRepository
                                .findByIdForUpdate(summary.getOrder().getRegistration().getId())
                                .orElseThrow(() -> new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Không tìm thấy đăng ký"));
        }

        // Kích hoạt đăng ký class sau khi PayOS xác nhận đã nhận tiền (cùng khuôn với kích hoạt gói).
        // BR-QR-04: tiền về muộn mà không còn chỗ thì payment chuyển sang chờ hoàn tiền.
        private PaymentResponse activateVerifiedRegistration(Long paymentId) {

                Payment summary = paymentRepository.findById(paymentId)
                                .orElseThrow(() -> new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Không tìm thấy giao dịch"));

                userRepository.findByIdForUpdate(summary.getPayer().getId())
                                .orElseThrow(() -> new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Không tìm thấy hội viên"));

                ClassRegistration registration = lockRegistration(summary);

                Payment payment = paymentRepository.findByIdForUpdate(paymentId)
                                .orElseThrow(() -> new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Không tìm thấy giao dịch"));

                if (!"SUCCESS".equals(payment.getStatus())) {
                        throw new BusinessRuleException(
                                        "Thanh toán chưa được xác nhận");
                }

                // Chỉ xử lý Payment đang chờ kích hoạt.
                if (!ACTIVATION_PENDING.equals(payment.getGatewayResponse())) {
                        return mapToResponse(payment);
                }

                if (classRegistrationService.activatePaidOnline(registration.getId())) {

                        payment.setGatewayResponse(
                                        "VERIFIED: PayOS payment confirmed");

                } else {

                        // Tạo khoản chờ hoàn tiền: payment sang PENDING_REFUND, order sang CANCELED
                        refundService.createRefund(payment, payment.getAmount(),
                                        "Seat no longer available when the payment arrived");
                        payment.setGatewayResponse(
                                        "VERIFIED: Seat is no longer available, refund required");
                }

                return mapToResponse(paymentRepository.saveAndFlush(payment));
        }

        // ========================================================
        // 17. MAP ONLINE LINK RESPONSE
        // ========================================================

        private OnlinePaymentLinkResponse mapToOnlineLinkResponse(
                        Payment payment) {

                OnlinePaymentLinkResponse response = new OnlinePaymentLinkResponse();

                response.setPaymentId(payment.getId());
                response.setSubscriptionId(
                                payment.getSubscription() == null ? null : payment.getSubscription().getId());
                response.setRegistrationId(
                                payment.getOrder().getRegistration() == null ? null : payment.getOrder().getRegistration().getId());
                response.setCoachSlotId(
                                payment.getOrder().getCoachSlot() == null ? null : payment.getOrder().getCoachSlot().getId());
                response.setOrderCode(
                                payment.getGatewayOrderCode());
                response.setCheckoutUrl(
                                payment.getPaymentUrl());
                response.setExpiresAt(
                                payment.getExpiresAt());
                response.setPaymentStatus(
                                payment.getStatus());

                return response;
        }
}
