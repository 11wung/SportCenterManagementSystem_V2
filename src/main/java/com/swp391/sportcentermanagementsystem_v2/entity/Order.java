package com.swp391.sportcentermanagementsystem_v2.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

// Một lần mua: gói, ghi danh class hoặc slot coach (BR-PAY-05).
@Entity
@Table(name = "orders")
@Getter
@Setter
@NoArgsConstructor
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false)
    private User member;

    // Nhân viên tạo đơn, null khi member tự mua online
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private User createdBy;

    // ONLINE, COUNTER
    @Column(name = "channel", nullable = false, length = 10)
    private String channel;

    // DB bắt buộc đúng một trong ba: subscription, registration, coach_slot
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "subscription_id")
    private MemberSubscription subscription;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "registration_id")
    private ClassRegistration registration;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "coach_slot_id")
    private CoachSlot coachSlot;

    @Column(name = "list_price", nullable = false, precision = 12, scale = 0)
    private BigDecimal listPrice;

    // NONE, TIER, VOUCHER
    @Column(name = "discount_kind", nullable = false, length = 10)
    private String discountKind = "NONE";

    @Column(name = "discount_amount", nullable = false, precision = 12, scale = 0)
    private BigDecimal discountAmount = BigDecimal.ZERO;

    // DB ràng buộc total = list_price - discount_amount
    @Column(name = "total_amount", nullable = false, precision = 12, scale = 0)
    private BigDecimal totalAmount;

    // PENDING, PAID, CANCELED, EXPIRED
    @Column(name = "status", nullable = false, length = 10)
    private String status = "PENDING";

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private OffsetDateTime updatedAt;
}
