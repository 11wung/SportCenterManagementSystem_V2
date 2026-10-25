package com.swp391.sportcentermanagementsystem_v2.dto.responseDTO;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

// Báo giá thử một tier với số tháng cho trước (máy tính giá trên trang SRS).
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PriceQuoteResponse {

    private Long packageId;
    private Short months;
    private BigDecimal monthlyPrice;
    private Integer longTermDiscountPct;
    private BigDecimal totalAmount;
}
