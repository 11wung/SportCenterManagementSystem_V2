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
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payer_id", nullable = false)
    private User payer;

    // Nhân viên nhận tiền, null khi thanh toán online
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "received_by")
    private User receivedBy;

    @Column(name = "invoice_number", nullable = false, unique = true, length = 30)
    private String invoiceNumber;

    @Column(name = "transaction_code", nullable = false, unique = true, length = 100)
    private String transactionCode;

    @Column(name = "amount", nullable = false, precision = 12, scale = 0)
    private BigDecimal amount;

    @Column(name = "payment_method", nullable = false, length = 20)
    private String paymentMethod;

    @Column(name = "status", nullable = false, length = 20)
    private String status = "PENDING";

    // PayOS numeric orderCode; unique for all online payments.
    @Column(name = "gateway_order_code", unique = true)
    private Long gatewayOrderCode;

    // PayOS checkoutUrl; reused while valid.
    @Column(name = "payment_url", columnDefinition = "TEXT")
    private String paymentUrl;

    @Column(name = "paid_at")
    private OffsetDateTime paidAt;

    // Thời điểm hết hạn của riêng Payment Link này.
    @Column(name = "expires_at")
    private OffsetDateTime expiresAt;
    
    @Column(name = "gateway_response", columnDefinition = "TEXT")
    private String gatewayResponse;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    // Gói của đơn mua (payment luôn đi qua order)
    public MemberSubscription getSubscription() {
        return order.getSubscription();
    }
}
