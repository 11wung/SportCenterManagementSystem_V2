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
public class PaymentResponse {

    private Long id;

    private Long subscriptionId;

    private Long payerId;

    private String invoiceNumber;

    private String transactionCode;

    private BigDecimal amount;

    private String paymentMethod;

    private String status;

    private OffsetDateTime paidAt;

    private OffsetDateTime createdAt;
}

