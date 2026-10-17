package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.CountLine;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.MemberStatsResponse;
import com.swp391.sportcentermanagementsystem_v2.repository.MemberSubscriptionRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.UserRepository;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

@Service
@RequiredArgsConstructor
public class MemberStatsService {

    private final UserRepository userRepository;
    private final MemberSubscriptionRepository subscriptionRepository;

    private static final ZoneId VIETNAM_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private static final int MIN_EXPIRING_DAYS = 1;
    private static final int MAX_EXPIRING_DAYS = 90;
    private static final int NEW_MEMBER_DAYS = 30;

    // Ảnh chụp thống kê thành viên tại thời điểm gọi (ngày tính theo giờ Việt Nam).
    @Transactional(readOnly = true)
    public MemberStatsResponse getStats(int expiringWithinDays) {

        if (expiringWithinDays < MIN_EXPIRING_DAYS || expiringWithinDays > MAX_EXPIRING_DAYS) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "expiringWithinDays must be between " + MIN_EXPIRING_DAYS
                            + " and " + MAX_EXPIRING_DAYS + "!");
        }

        LocalDate today = LocalDate.now(VIETNAM_ZONE);
        OffsetDateTime since = today.minusDays(NEW_MEMBER_DAYS)
                .atStartOfDay(VIETNAM_ZONE).toOffsetDateTime();

        MemberStatsResponse response = new MemberStatsResponse();

        response.setDate(today);
        response.setTotalMembers(userRepository.countByRole_Code("MEMBER"));
        response.setActiveAccounts(userRepository.countByRole_CodeAndIsActive("MEMBER", true));
        response.setLockedAccounts(userRepository.countByRole_CodeAndIsActive("MEMBER", false));
        response.setNewMembersLast30Days(
                userRepository.countByRole_CodeAndCreatedAtGreaterThanEqual("MEMBER", since));
        response.setMembersWithActivePackage(subscriptionRepository.countMembersWithValidActive(today));
        response.setMembersWithScheduledPackage(subscriptionRepository.countMembersByStatus("SCHEDULED"));
        response.setMembersWithPendingOrder(subscriptionRepository.countMembersByStatus("PENDING"));
        response.setMembersWithoutPackage(userRepository.countActiveMembersWithoutPackage());
        response.setExpiringWithinDays(expiringWithinDays);
        response.setExpiringSoon(subscriptionRepository.countMembersExpiringBetween(
                today, today.plusDays(expiringWithinDays)));

        List<CountLine> byPackage = subscriptionRepository.countValidActiveByPackage(today).stream()
                .map(row -> new CountLine(row.getPackageName(), row.getMemberCount()))
                .toList();
        response.setActiveByPackage(byPackage);

        return response;
    }
}
