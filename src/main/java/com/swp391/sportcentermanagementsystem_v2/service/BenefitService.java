package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.entity.MemberSubscription;
import com.swp391.sportcentermanagementsystem_v2.repository.MemberSubscriptionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Map;

// Quyền lợi tier member được hưởng vào một ngày, đọc từ benefits_snapshot đã chốt lúc mua gói (BR-PKG-05).
// Không có gói còn hạn thì không hưởng quyền lợi nào.
@Service
@RequiredArgsConstructor
public class BenefitService {

    private final MemberSubscriptionRepository subscriptionRepository;

    @Transactional(readOnly = true)
    public Map<String, Object> benefitsOf(Long memberId, LocalDate date) {
        return subscriptionRepository.findValidActiveSubscription(memberId, date)
                .map(MemberSubscription::getBenefitsSnapshot)
                .orElse(Map.of());
    }

    // Quyền lợi dạng số (số ngày, phần trăm, số buổi). Không có quyền lợi thì là 0
    @Transactional(readOnly = true)
    public int intBenefit(Long memberId, String key, LocalDate date) {
        return benefitsOf(memberId, date).get(key) instanceof Number n ? n.intValue() : 0;
    }
}
