package com.swp391.sportcentermanagementsystem_v2.dto.responseDTO;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

// Báo giá nâng cấp từ gói đang dùng sang một gói cao hơn với số tháng cho trước.
// canUpgrade = false thì reason cho biết lý do (mã, FE tự đổi sang câu tiếng Việt):
// NO_ACTIVE, SCHEDULED_EXISTS, NOT_HIGHER_TIER, NO_REMAINING_VALUE, ZERO_AMOUNT.
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class UpgradeQuoteResponse {

    private Long packageId;
    private Integer months;
    private BigDecimal newTotal;
    private BigDecimal credit;
    private BigDecimal amountDue;
    private boolean canUpgrade;
    private String reason;
    // Có giá trị khi phần chưa dùng của gói cũ đủ trả ít nhất 1 tháng gói mới: nâng ngay không trả thêm
    // và được dùng gói mới đúng số ngày này (POST /upgrade-with-credit).
    private Integer creditDays;
}
