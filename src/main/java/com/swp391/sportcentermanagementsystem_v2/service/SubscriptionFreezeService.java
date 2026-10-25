package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.CreateFreezeRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.SubscriptionFreezeResponse;
import com.swp391.sportcentermanagementsystem_v2.entity.MemberSubscription;
import com.swp391.sportcentermanagementsystem_v2.entity.SubscriptionFreeze;
import com.swp391.sportcentermanagementsystem_v2.entity.User;
import com.swp391.sportcentermanagementsystem_v2.exception.BusinessRuleException;
import com.swp391.sportcentermanagementsystem_v2.repository.MemberSubscriptionRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.SubscriptionFreezeRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.UserRepository;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;

// Bảo lưu gói: quỹ ngày, số đợt, độ dài tối thiểu và chống chồng lấn do DB kiểm tra
// (trigger trg_check_freeze và constraint ex_freeze_overlap). Service chỉ đổi ngày hết hạn của gói.
@Service
@RequiredArgsConstructor
public class SubscriptionFreezeService {

    private static final ZoneId VIETNAM_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final SubscriptionFreezeRepository freezeRepository;
    private final MemberSubscriptionRepository subscriptionRepository;
    private final UserRepository userRepository;
    private final EntityManager entityManager;

    @Transactional
    public SubscriptionFreezeResponse createFreeze(Long memberId, Long subscriptionId, CreateFreezeRequest request) {
        User member = userRepository.findByIdForUpdate(memberId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Member not found!"));
        MemberSubscription sub = ownedActiveSubscription(memberId, subscriptionId);

        LocalDate today = LocalDate.now(VIETNAM_ZONE);
        LocalDate start = request.getStartDate();
        LocalDate end = request.getEndDate();

        if (start.isBefore(today)) {
            throw new BusinessRuleException("Freeze cannot start in the past!");
        }
        if (end.isBefore(start)) {
            throw new BusinessRuleException("End date must not be before start date!");
        }
        if (!start.isBefore(sub.getEndDate())) {
            throw new BusinessRuleException("Freeze must start before the subscription ends!");
        }

        SubscriptionFreeze freeze = new SubscriptionFreeze();
        freeze.setSubscription(sub);
        freeze.setStartDate(start);
        freeze.setEndDate(end);
        freeze.setStatus(start.equals(today) ? "ACTIVE" : "SCHEDULED");
        freeze.setCreatedBy(member);

        saveOrExplain(freeze);

        // Ngày bảo lưu được cộng vào ngày hết hạn.
        sub.setEndDate(sub.getEndDate().plusDays(daysBetween(start, end)));
        subscriptionRepository.saveAndFlush(sub);

        entityManager.refresh(freeze);
        return toResponse(freeze, sub);
    }

    @Transactional(readOnly = true)
    public List<SubscriptionFreezeResponse> listFreezes(Long memberId, Long subscriptionId) {
        MemberSubscription sub = subscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Subscription not found!"));
        if (!sub.getUser().getId().equals(memberId)) {
            throw new BusinessRuleException("This subscription does not belong to you!");
        }
        return freezeRepository.findBySubscription_IdOrderByStartDateDesc(subscriptionId).stream()
                .map(f -> toResponse(f, sub))
                .toList();
    }

    // Hủy đợt chưa bắt đầu: trả lại số ngày đã cộng.
    @Transactional
    public SubscriptionFreezeResponse cancelFreeze(Long memberId, Long subscriptionId, Long freezeId) {
        userRepository.findByIdForUpdate(memberId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Member not found!"));
        MemberSubscription sub = ownedActiveSubscription(memberId, subscriptionId);
        SubscriptionFreeze freeze = ownedFreeze(sub, freezeId);

        if (!"SCHEDULED".equals(freeze.getStatus())) {
            throw new BusinessRuleException("Only scheduled freezes can be canceled!");
        }

        freeze.setStatus("CANCELED");
        freezeRepository.saveAndFlush(freeze);

        sub.setEndDate(sub.getEndDate().minusDays(daysBetween(freeze.getStartDate(), freeze.getEndDate())));
        subscriptionRepository.saveAndFlush(sub);

        entityManager.refresh(freeze);
        return toResponse(freeze, sub);
    }

    // Kết thúc sớm đợt đang chạy: ngày chưa dùng được trả lại cho gói.
    @Transactional
    public SubscriptionFreezeResponse endFreezeEarly(Long memberId, Long subscriptionId, Long freezeId) {
        userRepository.findByIdForUpdate(memberId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Member not found!"));
        MemberSubscription sub = ownedActiveSubscription(memberId, subscriptionId);
        SubscriptionFreeze freeze = ownedFreeze(sub, freezeId);

        if (!"ACTIVE".equals(freeze.getStatus())) {
            throw new BusinessRuleException("Only an active freeze can be ended early!");
        }

        LocalDate today = LocalDate.now(VIETNAM_ZONE);
        if (!today.isBefore(freeze.getEndDate())) {
            throw new BusinessRuleException("This freeze ends today, nothing to give back!");
        }

        long unusedDays = ChronoUnit.DAYS.between(today, freeze.getEndDate());
        freeze.setStatus("ENDED");
        freeze.setEndedOn(today);
        saveOrExplain(freeze);

        sub.setEndDate(sub.getEndDate().minusDays(unusedDays));
        subscriptionRepository.saveAndFlush(sub);

        entityManager.refresh(freeze);
        return toResponse(freeze, sub);
    }

    // Scheduler gọi định kỳ: SCHEDULED tới ngày thì ACTIVE, ACTIVE hết ngày thì ENDED.
    @Transactional
    public void syncStatuses(LocalDate today) {
        for (SubscriptionFreeze f : freezeRepository.findByStatusAndEndDateLessThan("ACTIVE", today)) {
            f.setStatus("ENDED");
            f.setEndedOn(f.getEndDate());
        }
        for (SubscriptionFreeze f : freezeRepository.findByStatusAndStartDateLessThanEqual("SCHEDULED", today)) {
            if (f.getEndDate().isBefore(today)) {
                f.setStatus("ENDED");
                f.setEndedOn(f.getEndDate());
            } else {
                f.setStatus("ACTIVE");
            }
        }
    }

    private MemberSubscription ownedActiveSubscription(Long memberId, Long subscriptionId) {
        MemberSubscription sub = subscriptionRepository.findByIdForUpdate(subscriptionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Subscription not found!"));
        if (!sub.getUser().getId().equals(memberId)) {
            throw new BusinessRuleException("This subscription does not belong to you!");
        }
        if (!"ACTIVE".equals(sub.getStatus())) {
            throw new BusinessRuleException("Only an active subscription can be frozen!");
        }
        return sub;
    }

    private SubscriptionFreeze ownedFreeze(MemberSubscription sub, Long freezeId) {
        SubscriptionFreeze freeze = freezeRepository.findById(freezeId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Freeze not found!"));
        if (!freeze.getSubscription().getId().equals(sub.getId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Freeze not found!");
        }
        return freeze;
    }

    // Lỗi từ trigger/constraint (BR-FRZ-xx) trả về cho người dùng đọc được.
    private void saveOrExplain(SubscriptionFreeze freeze) {
        try {
            freezeRepository.saveAndFlush(freeze);
        } catch (DataAccessException ex) {
            String msg = NestedExceptionUtils.getMostSpecificCause(ex).getMessage();
            if (msg != null && msg.contains("BR-FRZ")) {
                throw new BusinessRuleException(msg.lines().findFirst().orElse(msg));
            }
            if (msg != null && msg.contains("ex_freeze_overlap")) {
                throw new BusinessRuleException("This period overlaps another freeze!");
            }
            throw ex;
        }
    }

    private static long daysBetween(LocalDate start, LocalDate end) {
        return ChronoUnit.DAYS.between(start, end) + 1;
    }

    private SubscriptionFreezeResponse toResponse(SubscriptionFreeze f, MemberSubscription sub) {
        SubscriptionFreezeResponse r = new SubscriptionFreezeResponse();
        r.setId(f.getId());
        r.setSubscriptionId(sub.getId());
        r.setStartDate(f.getStartDate());
        r.setEndDate(f.getEndDate());
        r.setEndedOn(f.getEndedOn());
        r.setStatus(f.getStatus());
        r.setDaysUsed(f.getDaysUsed());
        r.setSubscriptionEndDate(sub.getEndDate());
        return r;
    }
}
