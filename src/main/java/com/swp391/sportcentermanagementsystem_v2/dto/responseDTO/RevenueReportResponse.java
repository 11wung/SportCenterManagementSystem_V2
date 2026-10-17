package com.swp391.sportcentermanagementsystem_v2.dto.responseDTO;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
public class RevenueReportResponse {

    private LocalDate from;
    private LocalDate to;

    // DAY, MONTH hoặc QUARTER: kiểu gom của byPeriod.
    private String groupBy;

    private BigDecimal totalRevenue;
    private long paymentCount;

    // Số khoản đã thu nhưng đang có ghi chú "cần xem xét" (vẫn nằm trong tổng).
    private long needsReviewCount;

    private List<RevenueLine> byMethod;
    private List<RevenueLine> byPackage;
    private List<RevenueLine> byPeriod;
}
