
package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.CreateSubscriptionRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.MemberSubscriptionResponse;
import com.swp391.sportcentermanagementsystem_v2.entity.MemberSubscription;
import com.swp391.sportcentermanagementsystem_v2.entity.MembershipPackage;
import com.swp391.sportcentermanagementsystem_v2.entity.User;
import com.swp391.sportcentermanagementsystem_v2.exception.BusinessRuleException;
import com.swp391.sportcentermanagementsystem_v2.repository.MemberSubscriptionRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.MembershipPackageRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.PaymentRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.UserRepository;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class MemberSubscriptionService {

        private final MemberSubscriptionRepository subscriptionRepository;
        private final UserRepository userRepository;
        private final MembershipPackageRepository packageRepository;
        private final PaymentRepository paymentRepository;

        private static final ZoneId VIETNAM_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

        // =========================================================
        // 1. CALCULATE UPGRADE CREDIT
        // =========================================================

        // Tính giá trị còn lại của Subscription cũ.
        // Giá trị này được dùng để khấu trừ khi nâng cấp.
        static BigDecimal calculateUpgradeCredit(
                        MemberSubscription oldSub,
                        LocalDate upgradeDate) {

                LocalDate startDate = oldSub.getStartDate();
                LocalDate endDate = oldSub.getEndDate();

                // Tổng số ngày của Subscription cũ.
                long totalDays = ChronoUnit.DAYS.between(
                                startDate,
                                endDate);

                // Số ngày còn lại tại thời điểm tạo đơn Upgrade.
                long remainingDays = ChronoUnit.DAYS.between(
                                upgradeDate,
                                endDate);

                // Không thể Upgrade một gói đã hết hạn.
                if (totalDays <= 0 || remainingDays <= 0) {
                        throw new BusinessRuleException(
                                        "Current subscription has no remaining value!");
                }

                BigDecimal previousCredit = oldSub.getUpgradeCreditAmount();

                if (previousCredit == null) {
                        previousCredit = BigDecimal.ZERO;
                }

                // Giá trị của gói hiện tại bao gồm:
                // Tiền đã thanh toán + credit từ lần Upgrade trước.
                BigDecimal oldValue = oldSub.getTotalAmount()
                                .add(previousCredit);

                // Credit = Giá trị gói cũ * Số ngày còn lại / Tổng số ngày.
                return oldValue
                                .multiply(BigDecimal.valueOf(remainingDays))
                                .divide(
                                                BigDecimal.valueOf(totalDays),
                                                0,
                                                RoundingMode.DOWN);
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
                                                "Member not found!"));

                if (!"MEMBER".equals(member.getRole().getCode())) {
                        throw new BusinessRuleException(
                                        "User is not MEMBER!");
                }

                // 2. Mỗi Member chỉ được có một đơn PENDING.
                if (subscriptionRepository.existsByUserIdAndStatus(
                                memberId, "PENDING")) {

                        throw new BusinessRuleException(
                                        "You already have a pending order!");
                }

                // 3. Chỉ cho phép mua Package đang mở bán.
                MembershipPackage selectedPackage = packageRepository
                                .findByIdAndIsActiveTrue(request.getPackageId())
                                .orElseThrow(() -> new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Package not found or unavailable!"));

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

                BigDecimal credit = BigDecimal.ZERO;
                Long upgradeFromId = null;

                // 6. Xử lý nghiệp vụ theo loại đơn.
                if ("NEW".equals(purchaseType)) {

                        // Không đăng ký mới khi vẫn có gói đang sử dụng
                        // hoặc gói đã thanh toán đang chờ kích hoạt.
                        if (active.isPresent() || scheduled.isPresent()) {
                                throw new BusinessRuleException(
                                                "Member already has a membership. "
                                                                + "Please choose RENEWAL or UPGRADE!");
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
                                                "No previous paid membership found. "
                                                                + "Please use NEW registration!");
                        }

                } else if ("UPGRADE".equals(purchaseType)) {

                        // 7. UPGRADE phải có gói ACTIVE còn hiệu lực.
                        if (active.isEmpty()) {
                                throw new BusinessRuleException(
                                                "No active membership to upgrade. "
                                                                + "Please register or renew instead!");
                        }

                        // Chưa hỗ trợ Upgrade khi có gói chờ.
                        if (scheduled.isPresent()) {
                                throw new BusinessRuleException(
                                                "Cannot upgrade while another package is scheduled!");
                        }

                        MemberSubscription oldSub = active.get();

                        // 8. Xác định cấp quyền lợi bằng tierLevel.
                        // Không dùng tổng giá Package để phân biệt cấp.
                        if (selectedPackage.getTierLevel() <= oldSub.getTierLevelSnapshot()) {

                                throw new BusinessRuleException(
                                                "New package must have a higher tier!");
                        }

                        // 9. Tính khoản khấu trừ từ gói cũ.
                        credit = calculateUpgradeCredit(oldSub, today);

                        // Ghi nhớ gói được nâng cấp để PaymentService
                        // có thể xử lý sau khi thanh toán thành công.
                        upgradeFromId = oldSub.getId();

                } else {

                        throw new ResponseStatusException(
                                        HttpStatus.BAD_REQUEST,
                                        "Invalid purchase type!");
                }

                // Tổng số tháng của đơn = thời hạn gói x số kỳ mua.
                long totalMonths = (long) selectedPackage.getDurationMonths() * request.getQuantity();

                if (totalMonths > Short.MAX_VALUE) {
                        throw new BusinessRuleException(
                                        "Total duration is too long!");
                }

                // 10. Tính số tiền Member cần thanh toán.
                BigDecimal amount = selectedPackage.getPrice()
                                .multiply(BigDecimal.valueOf(request.getQuantity()))
                                .subtract(credit);

                // Phiên bản Cash hiện tại chỉ hỗ trợ số tiền > 0.
                // Không tự tạo giao dịch miễn phí hoặc hoàn tiền.
                if (amount.compareTo(BigDecimal.ZERO) <= 0) {
                        throw new BusinessRuleException(
                                        "Final payment amount must be greater than zero!");
                }

                // 11. Lấy thông tin Staff nếu Receptionist tạo đơn hộ.
                User staff = null;

                if (staffId != null) {

                        staff = userRepository.findById(staffId)
                                        .orElseThrow(() -> new ResponseStatusException(
                                                        HttpStatus.NOT_FOUND,
                                                        "Staff not found!"));
                }

                // 12. Tạo Subscription mới.
                MemberSubscription newSub = new MemberSubscription();

                newSub.setUser(member);
                newSub.setMembershipPackage(selectedPackage);

                // 13. Lưu snapshot khi tạo đơn.
                // Những giá trị này không thay đổi theo Package gốc.
                newSub.setPackageNameSnapshot(
                                selectedPackage.getName());

                // Tổng số tháng của đơn (đã nhân số kỳ mua), không phải thời hạn một kỳ.
                newSub.setDurationMonthsSnapshot((short) totalMonths);

                newSub.setMaxClassesPerWeekSnapshot(
                                selectedPackage.getMaxClassesPerWeek());

                newSub.setTierLevelSnapshot(
                                selectedPackage.getTierLevel());

                // 14. Thông tin loại giao dịch.
                newSub.setPurchaseType(purchaseType);
                newSub.setTotalAmount(amount);

                // Với NEW/RENEWAL, credit = 0 và upgradeFromId = null.
                newSub.setUpgradeCreditAmount(credit);
                newSub.setUpgradeFromSubscriptionId(upgradeFromId);

                // 15. Chưa thanh toán nên chưa có thời gian sử dụng.
                newSub.setStartDate(null);
                newSub.setEndDate(null);
                newSub.setStatus("PENDING");

                // Hạn thanh toán chỉ được đặt khi tạo QR PayOS.
                newSub.setPaymentExpiresAt(null);

                // Staff có thể null nếu Member tự đăng ký.
                newSub.setCreatedBy(staff);

                // 16. Lưu đơn PENDING.
                MemberSubscription saved = subscriptionRepository.save(newSub);

                return mapToResponse(saved);
        }

        // =========================================================
        // 3. CANCEL PENDING ORDER
        // =========================================================

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
                                                "Member not found!"));

                // 2. Tìm Subscription cần hủy.
                MemberSubscription sub = subscriptionRepository
                                .findById(subscriptionId)
                                .orElseThrow(() -> new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Subscription not found!"));

                // 3. Không cho hủy đơn của Member khác.
                if (!sub.getUser().getId().equals(memberId)) {
                        throw new BusinessRuleException(
                                        "This subscription does not belong to you!");
                }

                // 4. Chỉ cho phép hủy đơn PENDING.
                if (!"PENDING".equals(sub.getStatus())) {
                        throw new BusinessRuleException(
                                        "Only pending subscriptions can be canceled!");
                }

                // 5. Kiểm tra bằng PaymentRepository,
                // không đặt logic Payment trong SubscriptionRepository.
                boolean paymentProcessing = paymentRepository.existsBySubscription_IdAndStatusIn(
                                subscriptionId,
                                List.of("CREATING", "PENDING", "SUCCESS"));

                if (paymentProcessing) {
                        throw new BusinessRuleException(
                                        "Payment is being processed. "
                                                        + "Please wait for payment verification!");
                }

                // 6. Chuyển đơn sang CANCELED.
                sub.setStatus("CANCELED");
                subscriptionRepository.save(sub);
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
                                                "No member found!"));

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
                                                "No member found!"));

                LocalDate today = LocalDate.now(VIETNAM_ZONE);

                MemberSubscription subscription = subscriptionRepository.findValidActiveSubscription(
                                member.getId(),
                                today).orElseThrow(
                                                () -> new BusinessRuleException(
                                                                "You currently have no active package!"));

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
                                        "No member found with ID: " + memberId);
                }

                List<MemberSubscription> subscriptions = subscriptionRepository.findByUserIdOrderByCreatedAtDesc(
                                memberId);

                List<MemberSubscriptionResponse> responses = new ArrayList<>();

                for (MemberSubscription sub : subscriptions) {
                        responses.add(mapToResponse(sub));
                }

                return responses;
        }

        // =========================================================
        // 7. CHECK ACTIVE SUBSCRIPTION FOR BOOKING
        // =========================================================

        // Cung cấp cho Flow 2:
        // Kiểm tra Member có Subscription ACTIVE hay không.
        //
        // Chỉ kiểm tra quyền Membership.
        // Chưa kiểm tra số lớp đã đặt trong tuần.
        @Transactional(readOnly = true)
        public Optional<MemberSubscription> checkActiveSubscriptionForBooking(
                        Long memberId) {

                LocalDate today = LocalDate.now(VIETNAM_ZONE);

                return subscriptionRepository.findValidActiveSubscription(
                                memberId,
                                today);
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

                res.setMaxClassesPerWeek(
                                sub.getMaxClassesPerWeekSnapshot());

                res.setDurationMonths(
                                sub.getDurationMonthsSnapshot());

                // Tier tại thời điểm tạo đơn.
                res.setTierLevel(
                                sub.getTierLevelSnapshot());

                // 4. Thông tin giao dịch.
                res.setPurchaseType(sub.getPurchaseType());

                // Subscription gốc nếu đây là đơn UPGRADE.
                res.setUpgradeFromSubscriptionId(
                                sub.getUpgradeFromSubscriptionId());

                res.setUpgradeCreditAmount(
                                sub.getUpgradeCreditAmount());

                res.setPaymentExpiresAt(
                                sub.getPaymentExpiresAt());

                res.setTotalAmount(
                                sub.getTotalAmount());

                // 5. Ngày hiệu lực và trạng thái.
                res.setStartDate(sub.getStartDate());
                res.setEndDate(sub.getEndDate());
                res.setStatus(sub.getStatus());

                // 6. Người tạo đơn hộ.
                if (sub.getCreatedBy() != null) {
                        res.setCreatedByStaffName(
                                        sub.getCreatedBy().getFullName());
                }

                // 7. Thời điểm tạo đơn.
                res.setCreatedAt(sub.getCreatedAt());

                return res;
        }
}
