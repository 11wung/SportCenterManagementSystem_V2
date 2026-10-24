package com.swp391.sportcentermanagementsystem_v2.dto.responseDTO;

import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Getter
@Setter
@NoArgsConstructor
public class PaymentResponse {

    private Long id;
    private Long subscriptionId;
    // Có giá trị khi payment thuộc một đăng ký class thay vì gói
    private Long registrationId;
    // Có giá trị khi payment thuộc một buổi 1-1
    private Long coachSlotId;

    private String invoiceNumber;
    private String transactionCode;

    private BigDecimal amount;
    private String paymentMethod;
    private String paymentStatus;

    private String subscriptionStatus;

    private LocalDate startDate;
    private LocalDate endDate;

    private OffsetDateTime paidAt;

    private Boolean requiresReview;
}