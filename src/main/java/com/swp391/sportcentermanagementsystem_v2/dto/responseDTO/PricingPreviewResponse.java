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
    // Ưu đãi thực áp: NONE hoặc TIER
    private String discountKind;
    private BigDecimal discountAmount;
    private BigDecimal total;
}
