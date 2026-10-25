package com.swp391.sportcentermanagementsystem_v2.dto.responseDTO;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PricingPreviewResponse {
    private BigDecimal listPrice;
    private BigDecimal tierDiscount;
    private BigDecimal voucherDiscount;
    // Ưu đãi thực áp: NONE, TIER hoặc VOUCHER
    private String discountKind;
    private BigDecimal discountAmount;
    private Long voucherId;
    private BigDecimal total;
}
