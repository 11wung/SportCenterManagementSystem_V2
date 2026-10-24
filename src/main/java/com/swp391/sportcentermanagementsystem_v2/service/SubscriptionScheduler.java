
package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.repository.MemberSubscriptionRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class SubscriptionScheduler {

    private final MemberSubscriptionRepository subscriptionRepository;
    private final SubscriptionStatusService subscriptionStatusService;
    private final PaymentService paymentService;
    private final OnlinePaymentService onlinePaymentService;
    private final ClassRegistrationService classRegistrationService;
    private final CoachSlotService coachSlotService;

    private static final ZoneId VIETNAM_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    // Đơn PENDING chưa có thanh toán nào sau số ngày này sẽ bị hủy.
    private static final long STALE_PENDING_DAYS = 1;

    // Tự động kiểm tra Subscription mỗi 5 phút,
    // bao gồm các mốc 00:00, 00:05, 00:10...
    //
    // Nếu một lần chạy thất bại, lần chạy sau sẽ
    // tiếp tục tìm những Subscription chưa được cập nhật.
    @Scheduled(cron = "0 */5 * * * *", zone = "Asia/Ho_Chi_Minh")
    public void updateSubscriptionStatuses() {
        log.info("Subscription Scheduler is running!");

        LocalDate today = LocalDate.now(
                ZoneId.of("Asia/Ho_Chi_Minh"));

        // 1. Chỉ tìm Member có Subscription cần cập nhật.
        List<Long> memberIds;

        try {
            memberIds = subscriptionRepository
                    .findMemberIdsNeedingStatusUpdate(today);
            log.info("Members requiring updates: {}", memberIds);
        } catch (RuntimeException ex) {
            log.error(
                    "Cannot retrieve subscriptions for scheduled update",
                    ex);
            return;
        }

        // 2. Xử lý từng Member trong transaction riêng.
        for (Long memberId : memberIds) {

            try {
                subscriptionStatusService
                        .updateMemberSubscriptionStatus(
                                memberId,
                                today);

            } catch (RuntimeException ex) {

                // Member lỗi sẽ được thử lại ở lần chạy sau.
                // Không làm gián đoạn xử lý các Member khác.
                log.error(
                        "Failed to update subscriptions for memberId={}",
                        memberId,
                        ex);
            }
        }
    }

    // Tự hủy đơn PENDING bị bỏ quên (đặc biệt đơn tiền mặt không có hạn),
    // để Member không bị kẹt "đã có đơn chờ thanh toán".
    @Scheduled(cron = "0 */5 * * * *", zone = "Asia/Ho_Chi_Minh")
    public void cancelStalePendingOrders() {

        OffsetDateTime cutoff = OffsetDateTime.now(VIETNAM_ZONE)
                .minusDays(STALE_PENDING_DAYS);

        List<Long> subscriptionIds;

        try {
            subscriptionIds = subscriptionRepository
                    .findPendingIdsCreatedBefore(cutoff);
        } catch (RuntimeException ex) {
            log.error("Cannot retrieve stale pending orders", ex);
            return;
        }

        for (Long subscriptionId : subscriptionIds) {

            try {
                subscriptionStatusService
                        .cancelStalePendingOrder(subscriptionId);

            } catch (RuntimeException ex) {
                log.error(
                        "Failed to cancel stale order subscriptionId={}",
                        subscriptionId,
                        ex);
            }
        }
    }

    // Đóng payment QR quá hạn (hỏi PayOS), để đơn hết bị chặn hủy/thu tiền mặt.
    @Scheduled(cron = "0 */5 * * * *", zone = "Asia/Ho_Chi_Minh")
    public void reconcileExpiredPayments() {
        onlinePaymentService.reconcileExpiredPayments();
    }

    // Nhả chỗ ghi danh quá hạn giữ chỗ (BR-ENR-03). Cùng nhịp với đối soát thanh toán; câu SQL tự bỏ qua
    // đăng ký có thanh toán chưa ngã ngũ nên không phụ thuộc thứ tự chạy so với đối soát
    @Scheduled(cron = "0 */5 * * * *", zone = "Asia/Ho_Chi_Minh")
    public void releaseExpiredRegistrationHolds() {
        int released = classRegistrationService.expireHolds();
        if (released > 0) {
            log.info("Released {} expired registration hold(s)", released);
        }
    }

    // Nhả suất 1-1 quá hạn giữ chỗ (BR-ONE-03), cùng nhịp và cùng luật như nhả chỗ ghi danh
    @Scheduled(cron = "0 */5 * * * *", zone = "Asia/Ho_Chi_Minh")
    public void releaseExpiredSlotHolds() {
        int released = coachSlotService.releaseExpiredHolds();
        if (released > 0) {
            log.info("Released {} expired 1-1 slot hold(s)", released);
        }
    }

    // Thử lại kích hoạt Membership cho Payment đã nhận tiền nhưng chưa kích hoạt xong.
    @Scheduled(cron = "0 */5 * * * *", zone = "Asia/Ho_Chi_Minh")
    public void retryPendingActivations() {
        paymentService.retryPendingActivations();
    }
}
