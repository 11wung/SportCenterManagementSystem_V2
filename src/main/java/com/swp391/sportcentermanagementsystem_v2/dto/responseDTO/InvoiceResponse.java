package com.swp391.sportcentermanagementsystem_v2.dto.responseDTO;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Getter
@Setter
@NoArgsConstructor
public class InvoiceResponse {

    private String invoiceNumber;
    private String transactionCode;
    private OffsetDateTime paidAt;

    private String memberName;
    private String memberEmail;
    private String memberPhone;

    private String packageName;
    private Short durationMonths;
    private String purchaseType;
    private LocalDate startDate;
    private LocalDate endDate;

    private BigDecimal packagePrice;
    // Legacy field: luôn bằng 0 vì hệ thống không còn ví/credit nâng cấp.
    private BigDecimal upgradeCreditAmount;
    // Giá gói hiện tại được trừ khỏi giá gói mới.
    private BigDecimal upgradeDiscountAmount;
    // Phần chênh lệch member thực tế thanh toán khi nâng cấp.
    private BigDecimal upgradePriceDifference;
    private BigDecimal amount;

    private String paymentMethod;

    // Tên lễ tân thu tiền; rỗng nếu trả online.
    private String receivedByName;
}
