package com.swp391.sportcentermanagementsystem_v2.dto.responseDTO;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class MemberSubscriptionResponse {

    private Long id;

    private Long memberId;
    private String memberName;

    private Long packageId;
    private String packageName;

    private Short maxClassesPerWeek;
    private Short durationMonths;

    // Snapshot cấp quyền lợi khi tạo đơn
    private Short tierLevel;

    // NEW, RENEWAL hoặc UPGRADE
    private String purchaseType;

    // Subscription gốc nếu đây là đơn UPGRADE
    private Long upgradeFromSubscriptionId;

    private BigDecimal upgradeCreditAmount;

    private OffsetDateTime paymentExpiresAt;

    private BigDecimal totalAmount;

    private LocalDate startDate;
    private LocalDate endDate;

    private String status;

    private String createdByStaffName;

    private OffsetDateTime createdAt;
}