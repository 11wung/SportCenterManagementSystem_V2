package com.swp391.sportcentermanagementsystem_v2.dto.responseDTO;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.util.List;

// Thống kê thành viên tại thời điểm gọi. Các nhóm có thể chồng nhau, không cộng lại với nhau.
@Getter
@Setter
public class MemberStatsResponse {

    private LocalDate date;
    private long totalMembers;
    private long activeAccounts;
    private long lockedAccounts;
    private long newMembersLast30Days;
    private long membersWithActivePackage;
    private long membersWithScheduledPackage;
    private long membersWithPendingOrder;
    private long membersWithoutPackage;
    private long expiringSoon;
    private int expiringWithinDays;
    private List<CountLine> activeByPackage;
}
