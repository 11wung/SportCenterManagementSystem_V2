package com.swp391.sportcentermanagementsystem_v2.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

// Một khoản chờ hoàn tiền hoặc đã hoàn (BR-RFD). Hóa đơn gốc giữ nguyên (BR-INV-03)
@Entity
@Table(name = "refunds")
@Getter
@Setter
@NoArgsConstructor
public class Refund {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payment_id", nullable = false)
    private Payment payment;

    @Column(name = "amount", nullable = false, precision = 12, scale = 0)
    private BigDecimal amount;

    @Column(name = "reason", nullable = false, length = 255)
    private String reason;

    // CASH (tiền mặt đưa lại tại quầy) hoặc TRANSFER (chuyển khoản lại), theo cách đã trả (BR-RFD-02)
    @Column(name = "method", nullable = false, length = 10)
    private String method;

    // PENDING, DONE
    @Column(name = "status", nullable = false, length = 10)
    private String status = "PENDING";

    @Column(name = "requested_at", insertable = false, updatable = false)
    private OffsetDateTime requestedAt;

    // Người bấm "đã hoàn" và lúc nào (BR-RFD-03)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "processed_by")
    private User processedBy;

    @Column(name = "processed_at")
    private OffsetDateTime processedAt;
}
