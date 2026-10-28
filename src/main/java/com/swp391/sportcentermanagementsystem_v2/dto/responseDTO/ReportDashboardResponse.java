package com.swp391.sportcentermanagementsystem_v2.dto.responseDTO;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter
@Setter
@NoArgsConstructor
public class ReportDashboardResponse {
    private LocalDate from;
    private LocalDate to;
    private BigDecimal totalRevenue = BigDecimal.ZERO;
    private long newMembers;
    private BigDecimal occupancyRatePercent = BigDecimal.ZERO;
    private long occupiedSeats;
    private long totalCapacity;
    private long pendingRefundCount;
    private BigDecimal pendingRefundAmount = BigDecimal.ZERO;
}
