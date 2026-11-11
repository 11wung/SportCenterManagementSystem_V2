package com.swp391.sportcentermanagementsystem_v2.service;
import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.CreateSubscriptionRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.MemberSubscriptionResponse;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.UpgradeQuoteResponse;
import com.swp391.sportcentermanagementsystem_v2.entity.MemberSubscription;
import com.swp391.sportcentermanagementsystem_v2.entity.MembershipPackage;
import com.swp391.sportcentermanagementsystem_v2.entity.Order;
import com.swp391.sportcentermanagementsystem_v2.entity.Payment;
import com.swp391.sportcentermanagementsystem_v2.entity.User;
import com.swp391.sportcentermanagementsystem_v2.exception.BusinessRuleException;
import com.swp391.sportcentermanagementsystem_v2.repository.MemberSubscriptionRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.ClassAttendanceRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.ClassRegistrationRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.FacilityAttendanceRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.CoachSlotRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.MembershipPackageRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.OrderRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.PaymentRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
@Service
@RequiredArgsConstructor
public class MemberSubscriptionService {
        private final MemberSubscriptionRepository subscriptionRepository;
        private final UserRepository userRepository;
        private final MembershipPackageRepository packageRepository;
        private final PaymentRepository paymentRepository;
        private final OrderRepository orderRepository;
        private final MembershipPackageService packageService;
        private final RefundService refundService;
        private final NotificationService notificationService;
        // Repository kiểm tra hội viên đã sử dụng quyền lợi của gói hiện tại hay chưa.
        private final FacilityAttendanceRepository facilityAttendanceRepository;
        private final ClassAttendanceRepository classAttendanceRepository;
        private final ClassRegistrationRepository classRegistrationRepository;
        private final CoachSlotRepository coachSlotRepository;
        private static final ZoneId VIETNAM_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
        // =========================================================
        // 1. UPGRADE PRICE DIFFERENCE
        // =========================================================
        private static final int UPGRADE_DURATION_DAYS = 30;

        /**
         * Nâng cấp chỉ được phép khi hội viên chưa sử dụng quyền lợi của gói ACTIVE hiện tại.
         * Mốc kiểm tra bắt đầu từ startDate của gói hiện tại, không lấy ngày của gói đầu tiên.
         */
@Transactional(readOnly = true)
        public boolean hasUsedCurrentSubscription(MemberSubscription currentSubscription,
                        java.time.OffsetDateTime checkedAt) {
                if (currentSubscription == null || currentSubscription.getStartDate() == null
                                || currentSubscription.getUser() == null) {
                        return true;
                }

                Long memberId = currentSubscription.getUser().getId();
                java.time.OffsetDateTime startOfStartDate = currentSubscription.getStartDate()
                                .atStartOfDay(VIETNAM_ZONE).toOffsetDateTime();
                // Nếu gói hiện tại được tạo/kích hoạt sau 00:00 của ngày startDate (ví dụ nâng cấp trong ngày),
                // lấy mốc từ thời điểm gói hiện tại bắt đầu có hiệu lực thực tế.
                java.time.OffsetDateTime from = (currentSubscription.getUpdatedAt() != null
                                && currentSubscription.getUpdatedAt().isAfter(startOfStartDate))
                                ? currentSubscription.getUpdatedAt()
                                : startOfStartDate;

                java.time.OffsetDateTime until = checkedAt == null
                                ? java.time.OffsetDateTime.now(VIETNAM_ZONE)
                                : checkedAt.atZoneSameInstant(VIETNAM_ZONE).toOffsetDateTime();

                if (until.isBefore(from)) {
                        return false;
                }

                boolean usedFacility = facilityAttendanceRepository
                                .existsByUser_IdAndCheckInTimeGreaterThanEqualAndCheckInTimeLessThanEqual(
                                                memberId, from, until);
                if (usedFacility) {
                        return true;
                }

                boolean registeredClass = classRegistrationRepository
                                .existsByUser_IdAndStatusInAndRegisteredAtGreaterThanEqualAndRegisteredAtLessThanEqual(
                                                memberId, List.of("REGISTERED", "PENDING_PAYMENT"), from, until);
                if (registeredClass) {
                        return true;
                }

                LocalDate checkedDate = until.toLocalDate();
                boolean attendedClass = classAttendanceRepository
                                .existsByUser_IdAndStatusInAndSession_SessionDateGreaterThanEqualAndSession_SessionDateLessThanEqual(
                                                memberId, List.of("PRESENT", "LATE"),
                                                currentSubscription.getStartDate(), checkedDate);
                if (attendedClass) {
                        return true;
                }

                return coachSlotRepository
                                .existsByMember_IdAndStatusAndCreatedAtGreaterThanEqualAndCreatedAtLessThanEqual(
                                                memberId, "BOOKED", from, until);
        }

        /**
         * Tính số tiền nâng cấp = giá tháng của gói mới - giá tháng của gói đang ACTIVE.
         * Không sử dụng credit và không đổi tiền thành ngày.
         */
        static BigDecimal calculateUpgradeDifference(BigDecimal currentPackagePrice,
                        BigDecimal newPackagePrice) {
                if (currentPackagePrice == null || currentPackagePrice.signum() <= 0
                                || newPackagePrice == null || newPackagePrice.signum() <= 0) {
                        throw new BusinessRuleException("Giá của hai gói Membership phải lớn hơn 0");
                }

                BigDecimal difference = newPackagePrice.subtract(currentPackagePrice);
                if (difference.signum() <= 0) {
                        throw new BusinessRuleException("Giá gói mới phải cao hơn giá gói hiện tại");
                }
                return difference;
        }

        private BigDecimal getCurrentPackagePrice(MemberSubscription currentSubscription) {
                if (currentSubscription.getMembershipPackage() == null
                                || currentSubscription.getMembershipPackage().getMonthlyPrice() == null) {
                        throw new BusinessRuleException("Không xác định được giá gói Membership hiện tại");
                }
                return currentSubscription.getMembershipPackage().getMonthlyPrice();
        }

        // Báo giá chỉ có một lựa chọn: mỗi lần nâng cấp nhận trọn 30 ngày của gói mới.
        @Transactional(readOnly = true)
        public List<UpgradeQuoteResponse> quoteUpgrade(Long memberId, Long packageId) {
                MembershipPackage target = packageRepository.findByIdAndIsActiveTrue(packageId)
                                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                                                "Không tìm thấy gói hoặc gói đã ngừng bán"));

                LocalDate today = LocalDate.now(VIETNAM_ZONE);
                java.time.OffsetDateTime now = java.time.OffsetDateTime.now(VIETNAM_ZONE);
                Optional<MemberSubscription> active = subscriptionRepository
                                .findValidActiveSubscription(memberId, today);
                boolean hasScheduled = subscriptionRepository
                                .findFirstByUserIdAndStatusOrderByEndDateDesc(memberId, "SCHEDULED").isPresent();
                boolean hasPending = subscriptionRepository.existsByUserIdAndStatus(memberId, "PENDING");

                String blocked = null;
                String currentName = null;
                BigDecimal currentPrice = BigDecimal.ZERO;
                BigDecimal amountDue = BigDecimal.ZERO;

                if (active.isEmpty()) {
                        blocked = "NO_ACTIVE";
                } else if (hasPending) {
                        blocked = "PENDING_EXISTS";
                } else if (hasScheduled) {
                        blocked = "SCHEDULED_EXISTS";
                } else {
                        MemberSubscription current = active.get();
                        currentName = current.getPackageNameSnapshot();
                        currentPrice = getCurrentPackagePrice(current);

                        if (target.getTierLevel() <= current.getTierLevelSnapshot()) {
                                blocked = "NOT_HIGHER_TIER";
                        } else if (hasUsedCurrentSubscription(current, now)) {
                                blocked = "CURRENT_PACKAGE_ALREADY_USED";
                        } else {
                                try {
                                        amountDue = calculateUpgradeDifference(currentPrice, target.getMonthlyPrice());
                                } catch (BusinessRuleException ex) {
                                        blocked = "PRICE_NOT_HIGHER";
                                }
                        }
                }

                UpgradeQuoteResponse quote = new UpgradeQuoteResponse();
                quote.setPackageId(packageId);
                quote.setCurrentPackageName(currentName);
                quote.setCurrentPackagePrice(currentPrice);
                quote.setNewPackagePrice(target.getMonthlyPrice());
                quote.setAmountDue(amountDue);
                quote.setDurationDays(UPGRADE_DURATION_DAYS);
                quote.setCanUpgrade(blocked == null);
                quote.setReason(blocked);
                // Field cũ: không còn credit; khoản giảm là giá tháng của gói hiện tại.
                quote.setMonths(1);
                quote.setNewTotal(target.getMonthlyPrice());
                quote.setCredit(BigDecimal.ZERO);
                quote.setDiscountAmount(currentPrice);
                quote.setCreditDays(null);
                quote.setUpgradeMode("PRICE_DIFFERENCE_30_DAYS");
                return List.of(quote);
        }

        // =========================================================
        // 2. CREATE SUBSCRIPTION ORDER
        // =========================================================
        // Tạo đơn đăng ký mới, gia hạn hoặc nâng cấp.
        //
        // Đơn chỉ ở trạng thái PENDING.
        // Không tính ngày bắt đầu/kết thúc khi chưa thanh toán.
        //
        // purchaseType nhận:
        // NEW, RENEWAL hoặc UPGRADE.
        @Transactional
        public MemberSubscriptionResponse createSubscriptionOrder(
                        Long memberId,
                        CreateSubscriptionRequest request,
                        Long staffId,
                        String purchaseType) {
                // 1. Khóa Member để tránh hai request tạo đơn đồng thời.
                User member = userRepository.findByIdForUpdate(memberId)
                                .orElseThrow(() -> new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Không tìm thấy hội viên"));
                if (!"MEMBER".equals(member.getRole().getCode())) {
                        throw new BusinessRuleException(
                                        "Người dùng này không phải hội viên");
                }
                // Chưa xác thực email thì chưa có dữ liệu nào: hội viên tạo tại quầy phải đọc mã xác thực trước.
                if (member.getEmailVerifiedAt() == null) {
                        throw new BusinessRuleException(
                                        "Hội viên chưa xác thực email, hãy xác thực trước khi đăng ký gói");
                }
                // 2. Mỗi Member chỉ được có một đơn PENDING.
                if (subscriptionRepository.existsByUserIdAndStatus(
                                memberId, "PENDING")) {
                        throw new BusinessRuleException(
                                        "Bạn đang có một đơn chờ thanh toán");
                }
                // 3. Chỉ cho phép mua Package đang mở bán.
                MembershipPackage selectedPackage = packageRepository
                                .findByIdAndIsActiveTrue(request.getPackageId())
                                .orElseThrow(() -> new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Không tìm thấy gói hoặc gói đã ngừng bán"));
                LocalDate today = LocalDate.now(VIETNAM_ZONE);
                // 4. Tìm Subscription ACTIVE còn hiệu lực.
                Optional<MemberSubscription> active = subscriptionRepository.findValidActiveSubscription(
                                memberId,
                                today);
                // 5. Tìm Subscription đã thanh toán và chờ sử dụng.
                Optional<MemberSubscription> scheduled = subscriptionRepository
                                .findFirstByUserIdAndStatusOrderByEndDateDesc(
                                                memberId,
                                                "SCHEDULED");
                BigDecimal currentPackagePriceDeduction = BigDecimal.ZERO;
                Long upgradeFromId = null;
                int months = request.getMonths();
                // 6. Xử lý nghiệp vụ theo loại đơn.
                if ("NEW".equals(purchaseType)) {
                        // Không đăng ký mới khi vẫn có gói đang sử dụng
                        // hoặc gói đã thanh toán đang chờ kích hoạt.
                        if (active.isPresent() || scheduled.isPresent()) {
                                throw new BusinessRuleException(
                                                "Hội viên đã có gói thành viên. "
                                                                + "Vui lòng chọn gia hạn hoặc nâng cấp");
                        }
                } else if ("RENEWAL".equals(purchaseType)) {
                        // Kiểm tra Member có lịch sử Membership
                        // thực sự đã được thanh toán.
                        //
                        // Không tính đơn PENDING hoặc CANCELED.
                        List<MemberSubscription> paidHistory = subscriptionRepository.findByUserIdAndStatusIn(
                                        memberId,
                                        List.of(
                                                        "ACTIVE",
                                                        "SCHEDULED",
                                                        "EXPIRED",
                                                        "UPGRADED"));
                        if (paidHistory.isEmpty()) {
                                throw new BusinessRuleException(
                                                "Chưa có gói nào đã thanh toán trước đó. "
                                                                + "Vui lòng đăng ký gói mới");
                        }
                } else if ("UPGRADE".equals(purchaseType)) {
                        // 7. UPGRADE phải có gói ACTIVE còn hiệu lực.
                        if (active.isEmpty()) {
                                throw new BusinessRuleException(
                                                "Không có gói đang dùng để nâng cấp. "
                                                                + "Vui lòng đăng ký hoặc gia hạn thay vì nâng cấp");
                        }
                        // Chưa hỗ trợ Upgrade khi có gói chờ.
                        if (scheduled.isPresent()) {
                                throw new BusinessRuleException(
                                                "Không thể nâng cấp khi đang có gói chờ kích hoạt");
                        }
                        MemberSubscription oldSub = active.get();
                        // 8. Xác định cấp quyền lợi bằng tierLevel.
                        // Không dùng tổng giá Package để phân biệt cấp.
                        if (selectedPackage.getTierLevel() <= oldSub.getTierLevelSnapshot()) {
                                throw new BusinessRuleException(
                                                "Gói mới phải có hạng cao hơn");
                        }
                        // Mỗi lần nâng cấp chỉ áp dụng 30 ngày, không chọn 3/6/12 tháng.
                        if (months != 1) {
                                throw new BusinessRuleException(
                                                "Mỗi lần nâng cấp có thời hạn 30 ngày; vui lòng chọn 1 tháng");
                        }
                        // Chỉ cho nâng cấp nếu chưa dùng quyền lợi của gói ACTIVE hiện tại.
                        if (hasUsedCurrentSubscription(oldSub, java.time.OffsetDateTime.now(VIETNAM_ZONE))) {
                                throw new BusinessRuleException(
                                                "Bạn đã sử dụng gói Membership hiện tại nên không thể nâng cấp theo chính sách này");
                        }
                        // Tính phần chênh lệch giữa giá gói mới và giá gói hiện tại.
                        currentPackagePriceDeduction = getCurrentPackagePrice(oldSub);
                        // Lưu giá gói cũ vào cột legacy upgrade_credit_amount để biết khoản được trừ.
                        // Không phải credit/ví và không được quy đổi thành ngày.
                        upgradeFromId = oldSub.getId();
                } else {
                        throw new ResponseStatusException(
                                        HttpStatus.BAD_REQUEST,
                                        "Loại mua gói không hợp lệ");
                }
                // 10. Tính tiền:
                // NEW/RENEWAL dùng giá theo số tháng; UPGRADE chỉ trả phần chênh lệch giá tháng.
                BigDecimal amount;
                if ("UPGRADE".equals(purchaseType)) {
                        amount = calculateUpgradeDifference(
                                        currentPackagePriceDeduction, selectedPackage.getMonthlyPrice());
                } else {
                        amount = packageService.calculateTotal(selectedPackage, months);
                }
                // Phiên bản Cash hiện tại chỉ hỗ trợ số tiền > 0.
                // Không tự tạo giao dịch miễn phí hoặc hoàn tiền.
                if (amount.compareTo(BigDecimal.ZERO) <= 0) {
                        throw new BusinessRuleException(
                                        "Số tiền thanh toán phải lớn hơn 0");
                }
                // 11. Lấy thông tin Staff nếu Receptionist tạo đơn hộ.
                User staff = null;
                if (staffId != null) {
                        staff = userRepository.findById(staffId)
                                        .orElseThrow(() -> new ResponseStatusException(
                                                        HttpStatus.NOT_FOUND,
                                                        "Không tìm thấy nhân viên"));
                }
                // 12. Tạo Subscription mới.
                MemberSubscription newSub = new MemberSubscription();
                newSub.setUser(member);
                newSub.setMembershipPackage(selectedPackage);
                // 13. Lưu snapshot khi tạo đơn.
                // Những giá trị này không thay đổi theo Package gốc.
                newSub.setPackageNameSnapshot(
                                selectedPackage.getName());
                newSub.setMonths((short) months);
                newSub.setBenefitsSnapshot(buildBenefitsSnapshot(selectedPackage));
                newSub.setTierLevelSnapshot(
                                selectedPackage.getTierLevel());
                // 14. Thông tin loại giao dịch.
                newSub.setPurchaseType(purchaseType);
                newSub.setTotalAmount(amount);
                // Cột DB cũ upgrade_credit_amount lưu giá gói hiện tại được trừ khỏi gói mới.
                // Với NEW/RENEWAL giá trị này bằng 0; không còn cơ chế credit.
                newSub.setUpgradeCreditAmount(currentPackagePriceDeduction);
                newSub.setUpgradeFromSubscriptionId(upgradeFromId);
                // 15. Chưa thanh toán nên chưa có thời gian sử dụng.
                newSub.setStartDate(null);
                newSub.setEndDate(null);
                newSub.setStatus("PENDING");
                // Hạn thanh toán chung của đơn (cả tiền mặt tại quầy và online): 24 giờ từ lúc tạo.
                newSub.setPaymentExpiresAt(java.time.OffsetDateTime.now(VIETNAM_ZONE).plusHours(24));
                // Staff có thể null nếu Member tự đăng ký.
                newSub.setCreatedBy(staff);
                // 16. Lưu đơn PENDING.
                MemberSubscription saved = subscriptionRepository.save(newSub);
                // 17. Đơn mua gắn với gói; payment sau này trỏ vào đơn này.
                Order order = new Order();
                order.setMember(member);
                order.setCreatedBy(staff);
                order.setChannel(staff != null ? "COUNTER" : "ONLINE");
                order.setSubscription(saved);
                // Order lưu số tiền thực tế cần thu. Với UPGRADE đây là phần chênh lệch,
                // không ghi toàn bộ giá gói vào list_price để tránh vi phạm total = list_price - discount_amount.
                order.setListPrice(amount);
                order.setTotalAmount(amount);
                orderRepository.save(order);
                return mapToResponse(saved);
        }

        // =========================================================
        // 3. CANCEL PENDING ORDER
        // =========================================================
        // Gói SCHEDULED (đã trả tiền, chưa tới ngày bắt đầu): hủy và hoàn đủ số tiền đã trả qua danh sách refund.
        // Gói đã bắt đầu (ACTIVE) thì không hủy được.
        // Gọi trong transaction của cancelPendingOrder, member đã được khóa
        private void cancelScheduledWithRefund(Long memberId, Long subscriptionId) {
                MemberSubscription sub = subscriptionRepository
                                .findByIdForUpdate(subscriptionId)
                                .orElseThrow(() -> new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Không tìm thấy gói thành viên"));
                if (!"SCHEDULED".equals(sub.getStatus())
                                || sub.getStartDate() == null
                                || !sub.getStartDate().isAfter(LocalDate.now(VIETNAM_ZONE))) {
                        throw new BusinessRuleException(
                                        "Chỉ hủy kèm hoàn tiền được gói chưa bắt đầu");
                }
                Payment paid = paymentRepository
                                .findFirstByOrder_Subscription_IdAndStatusOrderByIdDesc(subscriptionId, "SUCCESS")
                                .orElseThrow(() -> new BusinessRuleException(
                                                "Gói này chưa có giao dịch thành công"));
                sub.setStatus("CANCELED");
                subscriptionRepository.save(sub);
                refundService.createRefund(paid, paid.getAmount(),
                                "Package canceled before it started");
                notificationService.send(memberId, "MEMBERSHIP", "Package canceled",
                                "Your " + sub.getPackageNameSnapshot() + " bắt đầu ngày " + sub.getStartDate()
                                                + " đã bị hủy. Khoản hoàn đủ " + paid.getAmount()
                                                + " đang được xử lý.");
        }
        // Hủy đơn chưa thanh toán của Member.
        // Không cho hủy khi đang có Payment xử lý.
        @Transactional
        public void cancelPendingOrder(
                        Long memberId,
                        Long subscriptionId) {
                // 1. Khóa Member để xử lý tuần tự.
                userRepository.findByIdForUpdate(memberId)
                                .orElseThrow(() -> new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Không tìm thấy hội viên"));
                // 2. Tìm Subscription cần hủy.
                MemberSubscription sub = subscriptionRepository
                                .findById(subscriptionId)
                                .orElseThrow(() -> new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Không tìm thấy gói thành viên"));
                // 3. Không cho hủy đơn của Member khác.
                if (!sub.getUser().getId().equals(memberId)) {
                        throw new BusinessRuleException(
                                        "Gói này không thuộc về bạn");
                }
                // 4. Gói đã trả tiền nhưng chưa bắt đầu thì hủy và hoàn đủ tiền.
                if ("SCHEDULED".equals(sub.getStatus())) {
                        cancelScheduledWithRefund(memberId, subscriptionId);
                        return;
                }
                // Chỉ còn cho phép hủy đơn PENDING.
                if (!"PENDING".equals(sub.getStatus())) {
                        throw new BusinessRuleException(
                                        "Chỉ hủy được gói đang chờ thanh toán hoặc chưa bắt đầu");
                }
                // 5. Kiểm tra bằng PaymentRepository,
                // không đặt logic Payment trong SubscriptionRepository.
                boolean paymentProcessing = paymentRepository.existsBySubscription_IdAndStatusIn(
                                subscriptionId,
                                List.of("CREATING", "PENDING", "SUCCESS"));
                if (paymentProcessing) {
                        throw new BusinessRuleException(
                                        "Giao dịch đang được xử lý. "
                                                        + "Vui lòng chờ hệ thống xác nhận thanh toán");
                }
                // 6. Chuyển đơn sang CANCELED.
                sub.setStatus("CANCELED");
                subscriptionRepository.save(sub);
                orderRepository.findBySubscription_Id(subscriptionId)
                                .ifPresent(o -> o.setStatus("CANCELED"));
        }
        // PayOS xác nhận khách đã hủy thanh toán: hủy luôn đơn gói chưa trả tiền.
        // Chỉ đơn PENDING; gói đã trả (SCHEDULED/ACTIVE) giữ nguyên, không đi vào nhánh hoàn tiền.
        @Transactional
        public void cancelPendingOrderAfterGatewayCancel(Long subscriptionId) {
                subscriptionRepository.findById(subscriptionId)
                                .filter(s -> "PENDING".equals(s.getStatus()))
                                .ifPresent(s -> cancelPendingOrder(s.getUser().getId(), s.getId()));
        }
        // =========================================================
        // 4. GET MY SUBSCRIPTIONS
        // =========================================================
        // Lấy toàn bộ lịch sử đơn Membership của Member.
        @Transactional(readOnly = true)
        public List<MemberSubscriptionResponse> getMySubscriptions(
                        String email) {
                User member = userRepository.findByEmail(email)
                                .orElseThrow(() -> new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Không tìm thấy hội viên"));
                List<MemberSubscription> subscriptions = subscriptionRepository.findByUserIdOrderByCreatedAtDesc(
                                member.getId());
                List<MemberSubscriptionResponse> responses = new ArrayList<>();
                for (MemberSubscription sub : subscriptions) {
                        responses.add(mapToResponse(sub));
                }
                return responses;
        }
        // =========================================================
        // 5. GET MY ACTIVE SUBSCRIPTION
        // =========================================================
        // Lấy gói Membership đang ACTIVE và còn thời hạn.
        @Transactional(readOnly = true)
        public MemberSubscriptionResponse getMyActiveSubscription(
                        String email) {
                User member = userRepository.findByEmail(email)
                                .orElseThrow(() -> new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Không tìm thấy hội viên"));
                LocalDate today = LocalDate.now(VIETNAM_ZONE);
                MemberSubscription subscription = subscriptionRepository.findValidActiveSubscription(
                                member.getId(),
                                today).orElseThrow(
                                                () -> new BusinessRuleException(
                                                                "Bạn chưa có gói thành viên đang dùng"));
                return mapToResponse(subscription);
        }
        // =========================================================
        // 6. RECEPTIONIST GET MEMBER SUBSCRIPTIONS
        // =========================================================
        // Lễ tân xem lịch sử gói của Member theo memberId.
        @Transactional(readOnly = true)
        public List<MemberSubscriptionResponse> getSubscriptionsByMemberId(
                        Long memberId) {
                if (!userRepository.existsById(memberId)) {
                        throw new ResponseStatusException(
                                        HttpStatus.NOT_FOUND,
                                        "Không tìm thấy hội viên có mã " + memberId);
                }
                List<MemberSubscription> subscriptions = subscriptionRepository.findByUserIdOrderByCreatedAtDesc(
                                memberId);
                List<MemberSubscriptionResponse> responses = new ArrayList<>();
                for (MemberSubscription sub : subscriptions) {
                        responses.add(mapToResponse(sub));
                }
                return responses;
        }
        // Chụp quyền lợi của tier lúc mua. Key trùng tên cột của membership_packages.
        private static Map<String, Object> buildBenefitsSnapshot(MembershipPackage p) {
                Map<String, Object> b = new HashMap<>();
                b.put("tuition_discount_pct", p.getTuitionDiscountPct());
                b.put("one_on_one_discount_pct", p.getOneOnOneDiscountPct());
                b.put("has_personal_plan", p.getHasPersonalPlan());
                b.put("early_leave_refund_sessions", p.getEarlyLeaveRefundSessions());
                b.put("waitlist_priority", p.getWaitlistPriority());
                return b;
        }
        // =========================================================
        // 8. MAP ENTITY TO RESPONSE DTO
        // =========================================================
        // Chuyển MemberSubscription Entity sang Response DTO.
        // Mapping thủ công theo cách viết code hiện tại của nhóm.
        private MemberSubscriptionResponse mapToResponse(
                        MemberSubscription sub) {
                MemberSubscriptionResponse res = new MemberSubscriptionResponse();
                // 1. Subscription ID.
                res.setId(sub.getId());
                // 2. Thông tin Member.
                res.setMemberId(sub.getUser().getId());
                res.setMemberName(sub.getUser().getFullName());
                // 3. Snapshot thông tin Package.
                res.setPackageId(sub.getMembershipPackage().getId());
                res.setPackageName(sub.getPackageNameSnapshot());
                res.setMonths(sub.getMonths());
                res.setBenefits(sub.getBenefitsSnapshot());
                // Tier tại thời điểm tạo đơn.
                res.setTierLevel(
                                sub.getTierLevelSnapshot());
                // 4. Thông tin giao dịch.
                res.setPurchaseType(sub.getPurchaseType());
                // Subscription gốc nếu đây là đơn UPGRADE.
                res.setUpgradeFromSubscriptionId(
                                sub.getUpgradeFromSubscriptionId());
                res.setUpgradeCreditAmount(BigDecimal.ZERO); // Không còn credit nâng cấp.
                res.setUpgradePriceDifference("UPGRADE".equals(sub.getPurchaseType())
                                ? sub.getTotalAmount() : BigDecimal.ZERO);
                res.setPaymentExpiresAt(sub.getPaymentExpiresAt());
                res.setCashPaymentDeadline(sub.getPaymentExpiresAt());
                res.setAvailablePaymentMethods("PENDING".equals(sub.getStatus())
                                ? List.of("CASH", "QR_GATEWAY")
                                : List.of());
                // Khoản giảm ở đây là giá tháng của gói hiện tại được trừ khi nâng cấp,
                // không phải credit tích lũy.
                res.setUpgradeDiscountAmount("UPGRADE".equals(sub.getPurchaseType())
                                && sub.getUpgradeCreditAmount() != null
                                                ? sub.getUpgradeCreditAmount() : BigDecimal.ZERO);
                res.setTotalAmount(
                                sub.getTotalAmount());
                // 5. Ngày hiệu lực và trạng thái.
                res.setStartDate(sub.getStartDate());
                res.setEndDate(sub.getEndDate());
                res.setStatus(sub.getStatus());
                // 6. Người tạo đơn hộ.
                if (sub.getCreatedBy() != null) {
                        res.setCreatedByStaffName(sub.getCreatedBy().getFullName());
                }
                // 7. Thời điểm tạo đơn.
                res.setCreatedAt(sub.getCreatedAt());
                return res;
        }
}
