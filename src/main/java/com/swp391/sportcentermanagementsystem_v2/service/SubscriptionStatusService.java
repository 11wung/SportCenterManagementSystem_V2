
package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.entity.MemberSubscription;
import com.swp391.sportcentermanagementsystem_v2.repository.MemberSubscriptionRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.OrderRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.PaymentRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.UserRepository;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
public class SubscriptionStatusService {

    private final MemberSubscriptionRepository subscriptionRepository;
    private final UserRepository userRepository;
    private final PaymentRepository paymentRepository;
    private final OrderRepository orderRepository;

    // Đồng bộ trạng thái Membership của một Member.
    // Phải xử lý EXPIRED trước ACTIVE để tránh trùng ACTIVE.
    @Transactional
    public void updateMemberSubscriptionStatus(
            Long memberId,
            LocalDate today) {

        // 1. Khóa Member để đồng bộ với PaymentService.
        userRepository.findByIdForUpdate(memberId)
                .orElseThrow(() -> new IllegalStateException(
                        "Member not found: " + memberId));

        // 2. Lấy các gói đang ACTIVE hoặc SCHEDULED.
        List<MemberSubscription> subscriptions = subscriptionRepository.findByUserIdAndStatusIn(
                memberId,
                List.of("ACTIVE", "SCHEDULED"));

        // 3. Chuyển những gói đã hết hạn sang EXPIRED.
        for (MemberSubscription sub : subscriptions) {

            if (sub.getEndDate() != null
                    && !sub.getEndDate().isAfter(today)) {

                sub.setStatus("EXPIRED");
                subscriptionRepository.save(sub);
            }
        }

        // 4. Ghi các thay đổi EXPIRED xuống database trước.
        // Tránh vi phạm unique index một ACTIVE cho mỗi Member.
        subscriptionRepository.flush();

        // 5. Kích hoạt các gói SCHEDULED đã tới ngày bắt đầu.
        for (MemberSubscription sub : subscriptions) {

            if ("SCHEDULED".equals(sub.getStatus())
                    && sub.getStartDate() != null
                    && sub.getEndDate() != null
                    && !sub.getStartDate().isAfter(today)
                    && sub.getEndDate().isAfter(today)) {

                sub.setStatus("ACTIVE");
                subscriptionRepository.save(sub);
            }
        }

        // Transaction sẽ commit khi method kết thúc.
        // Nếu cập nhật lỗi, các thay đổi của Member này rollback.
    }

    // Hủy đơn PENDING bị bỏ quên. Khóa theo thứ tự Member -> Subscription
    // giống PaymentService và kiểm tra lại trong khóa, vì Member có thể
    // vừa trả tiền hoặc vừa tạo QR.
    @Transactional
    public void cancelStalePendingOrder(Long subscriptionId) {

        MemberSubscription summary = subscriptionRepository
                .findById(subscriptionId)
                .orElse(null);

        if (summary == null) {
            return;
        }

        userRepository.findByIdForUpdate(summary.getUser().getId())
                .orElseThrow(() -> new IllegalStateException(
                        "Member not found for subscription: " + subscriptionId));

        MemberSubscription sub = subscriptionRepository
                .findByIdForUpdate(subscriptionId)
                .orElse(null);

        if (sub == null || !"PENDING".equals(sub.getStatus())) {
            return;
        }

        if (paymentRepository.existsByOrder_Subscription_IdAndStatusIn(
                subscriptionId,
                List.of("CREATING", "PENDING", "SUCCESS"))) {
            return;
        }

        sub.setStatus("CANCELED");
        subscriptionRepository.save(sub);
        orderRepository.findBySubscription_Id(subscriptionId)
                .ifPresent(o -> o.setStatus("EXPIRED"));
    }
}
