
package com.swp391.sportcentermanagementsystem_v2.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "payments")
@Getter
@Setter
@NoArgsConstructor
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "subscription_id", nullable = false)
    private MemberSubscription subscription;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payer_id", nullable = false)
    private User payer;

    // Receptionist nhận tiền (NULL nếu thanh toán online)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "received_by")
    private User receivedBy;

    @Column(name = "invoice_number",
            nullable = false, unique = true, length = 30)
    private String invoiceNumber;

    @Column(name = "transaction_code",
            nullable = false, unique = true, length = 100)
    private String transactionCode;

    @Column(name = "amount",
            nullable = false, precision = 12, scale = 0)
    private BigDecimal amount;

    @Column(name = "payment_method",
            nullable = false, length = 20)
    private String paymentMethod;

    @Column(name = "status", nullable = false, length = 20)
    private String status = "PENDING";

    @Column(name = "paid_at")
    private OffsetDateTime paidAt;

    @Column(name = "gateway_response", columnDefinition = "TEXT")
    private String gatewayResponse;

    @Column(name = "created_at",
            insertable = false, updatable = false)
    private OffsetDateTime createdAt;
}
