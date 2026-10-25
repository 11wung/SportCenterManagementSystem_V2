package com.swp391.sportcentermanagementsystem_v2.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

// Đơn mua: trỏ đúng một món (gói, ghi danh lớp hoặc slot coach). Payment gắn vào đơn này.
@Entity
@Table(name = "orders")
@Getter
@Setter
@NoArgsConstructor
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    private User member;

    // Lễ tân tạo đơn hộ, null khi member tự mua
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private User createdBy;

    // ONLINE hoặc COUNTER
    @Column(name = "channel", nullable = false, length = 10)
    private String channel;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "subscription_id")
    private MemberSubscription subscription;

    @Column(name = "registration_id")
    private Long registrationId;

    @Column(name = "coach_slot_id")
    private Long coachSlotId;

    @Column(name = "list_price", nullable = false, precision = 12, scale = 0)
    private BigDecimal listPrice;

    // NONE, TIER hoặc VOUCHER
    @Column(name = "discount_kind", nullable = false, length = 10)
    private String discountKind = "NONE";

    @Column(name = "discount_amount", nullable = false, precision = 12, scale = 0)
    private BigDecimal discountAmount = BigDecimal.ZERO;

    @Column(name = "voucher_id")
    private Long voucherId;

    // Luôn bằng listPrice - discountAmount (ràng buộc ck_order_amounts)
    @Column(name = "total_amount", nullable = false, precision = 12, scale = 0)
    private BigDecimal totalAmount;

    // PENDING, PAID, CANCELED hoặc EXPIRED
    @Column(name = "status", nullable = false, length = 10)
    private String status = "PENDING";

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime updatedAt;
}
