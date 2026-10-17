package com.swp391.sportcentermanagementsystem_v2.dto.responseDTO;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

// Một dòng của báo cáo doanh thu: nhãn (phương thức, gói hoặc kỳ), tổng tiền, số giao dịch.
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class RevenueLine {

    private String label;
    private BigDecimal amount;
    private long paymentCount;
}
