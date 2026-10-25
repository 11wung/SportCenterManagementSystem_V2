package com.swp391.sportcentermanagementsystem_v2.repository;

import com.swp391.sportcentermanagementsystem_v2.entity.SubscriptionFreeze;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface SubscriptionFreezeRepository extends JpaRepository<SubscriptionFreeze, Long> {

    List<SubscriptionFreeze> findBySubscription_IdOrderByStartDateDesc(Long subscriptionId);

    // Đợt đang chạy hoặc đã lên lịch (chưa kết thúc, chưa hủy)
    List<SubscriptionFreeze> findBySubscription_IdAndStatusIn(Long subscriptionId, List<String> statuses);

    // Đợt bảo lưu bao phủ ngày :date của gói
    Optional<SubscriptionFreeze> findFirstBySubscription_IdAndStatusInAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
            Long subscriptionId, List<String> statuses, LocalDate startDate, LocalDate endDate);

    // Đợt cần chuyển trạng thái theo ngày (scheduler)
    List<SubscriptionFreeze> findByStatusAndStartDateLessThanEqual(String status, LocalDate date);

    List<SubscriptionFreeze> findByStatusAndEndDateLessThan(String status, LocalDate date);
}
