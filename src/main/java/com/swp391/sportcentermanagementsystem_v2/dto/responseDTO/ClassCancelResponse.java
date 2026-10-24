package com.swp391.sportcentermanagementsystem_v2.dto.responseDTO;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;

@Getter
@AllArgsConstructor
public class ClassCancelResponse {
    private Long classId;
    private String status;
    // Đăng ký chưa trả tiền bị hủy (không phải hoàn)
    private int canceledUnpaidRegistrations;
    // Đăng ký đã trả tiền: có khoản hoàn chờ xử lý
    private int refundedRegistrations;
    private BigDecimal refundTotal;
    private int canceledSessions;
}
