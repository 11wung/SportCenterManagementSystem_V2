package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.entity.MemberSubscription;
import com.swp391.sportcentermanagementsystem_v2.repository.MemberSubscriptionRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.SubscriptionFreezeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

// Quyền lợi tier member được hưởng vào một ngày, đọc từ benefits_snapshot đã chốt lúc mua gói (BR-PKG-05).
// Không có gói còn hạn, hoặc gói đang trong đợt bảo lưu, thì không hưởng quyền lợi nào (BR-FRZ-03, BR-FRZ-08).
@Service
@RequiredArgsConstructor
public class BenefitService {

    private static final List<String> FREEZE_STATUSES = List.of("SCHEDULED", "ACTIVE");

    private final MemberSubscriptionRepository subscriptionRepository;
    private final SubscriptionFreezeRepository freezeRepository;

    @Transactional(readOnly = true)
    public Map<String, Object> benefitsOf(Long memberId, LocalDate date) {
        return subscriptionRepository.findValidActiveSubscription(memberId, date)
                .filter(sub -> !isFrozen(sub, date))
                .map(MemberSubscription::getBenefitsSnapshot)
                .orElse(Map.of());
    }

    // Quyền lợi dạng số (số ngày, phần trăm, số buổi). Không có quyền lợi thì là 0
    @Transactional(readOnly = true)
    public int intBenefit(Long memberId, String key, LocalDate date) {
        return benefitsOf(memberId, date).get(key) instanceof Number n ? n.intValue() : 0;
    }

    private boolean isFrozen(MemberSubscription sub, LocalDate date) {
        return freezeRepository
                .findFirstBySubscription_IdAndStatusInAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                        sub.getId(), FREEZE_STATUSES, date, date)
                .isPresent();
    }
}
