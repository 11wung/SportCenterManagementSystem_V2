package com.swp391.sportcentermanagementsystem_v2.dto.responseDTO;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

// Kết quả hoàn tiền của một buổi: số học viên được hoàn và tổng tiền hoàn
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class SessionRefundResponse {
    private Long sessionId;
    private int refundedMembers;
    private BigDecimal refundTotal;
}
