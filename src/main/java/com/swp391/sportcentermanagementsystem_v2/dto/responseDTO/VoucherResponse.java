package com.swp391.sportcentermanagementsystem_v2.dto.responseDTO;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class VoucherResponse {
    private Long id;
    private String source;
    // PERCENT, AMOUNT, FREE_ONE_ON_ONE
    private String kind;
    private BigDecimal value;
    // CLASS, ONE_ON_ONE, PACKAGE
    private String appliesTo;
    private OffsetDateTime expiresAt;
    // Số tiền voucher giảm cho đơn đang xét; null nếu chưa chọn đơn
    private BigDecimal discountAmount;
    private boolean usable;
    private String unusableReason;
    // Voucher đứng đầu và giảm nhiều hơn ưu đãi của gói
    private boolean recommended;
}
