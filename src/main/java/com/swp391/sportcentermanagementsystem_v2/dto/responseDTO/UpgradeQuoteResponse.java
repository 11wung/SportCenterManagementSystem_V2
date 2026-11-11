package com.swp391.sportcentermanagementsystem_v2.dto.responseDTO;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/** Báo giá nâng cấp theo chênh lệch giá; mỗi lần nâng cấp có hiệu lực 30 ngày. */
@Getter
@Setter
@NoArgsConstructor
public class UpgradeQuoteResponse {

    private Long packageId;
    private String currentPackageName;
    private BigDecimal currentPackagePrice;
    private BigDecimal newPackagePrice;
    private BigDecimal amountDue;
    private Integer durationDays;
    private boolean canUpgrade;
    // NO_ACTIVE, PENDING_EXISTS, SCHEDULED_EXISTS, NOT_HIGHER_TIER, CURRENT_PACKAGE_ALREADY_USED, PRICE_NOT_HIGHER.
    private String reason;

    // Deprecated fields retained so an older frontend can still read the quote response.
    private Integer months;
    private BigDecimal newTotal;
    private BigDecimal credit;
    private BigDecimal discountAmount;
    private Integer creditDays;
    private String upgradeMode;
}
