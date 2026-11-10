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

    // So sánh với khoảng liền trước có cùng số ngày.
    private LocalDate previousPeriodFrom;
    private LocalDate previousPeriodTo;
    private BigDecimal previousPeriodRevenue;
    private BigDecimal changeAmount;
    private BigDecimal changePercent;

    // Số khoản đã thu nhưng đang có ghi chú "cần xem xét" (vẫn nằm trong tổng).
    private long needsReviewCount;

    // Tiền hoàn của các giao dịch trong kỳ: totalRevenue đã trừ phần đã hoàn, grossRevenue là tổng thu trước hoàn
    private BigDecimal grossRevenue;
    private BigDecimal refundedAmount;
    private long refundedCount;
    private BigDecimal pendingRefundAmount;
    private long pendingRefundCount;

    private List<RevenueLine> byMethod;
    private List<RevenueLine> byPackage;
    // Hai nhóm chính mà Manager cần theo dõi riêng: Membership và học phí lớp (bao gồm 1-1).
    private List<RevenueLine> byRevenueType;
    // Phân rã chi tiết hơn, giữ lại để không mất báo cáo theo nguồn cụ thể.
    private List<RevenueLine> bySource;
    private List<RevenueLine> byPeriod;
}
