package com.swp391.sportcentermanagementsystem_v2.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;

@Entity
@Table(name = "member_subscriptions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor

public class MemberSubscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "package_id", nullable = false)
    private MembershipPackage membershipPackage;

    @Column(name = "package_name_snapshot", nullable = false, length = 150)
    private String packageNameSnapshot;

    // Số tháng khách chọn khi mua (1 đến 12)
    @Column(name = "months", nullable = false)
    private Short months;

    @Column(name = "tier_level_snapshot", nullable = false)
    private Short tierLevelSnapshot;

    // NEW, RENEWAL hoặc UPGRADE
    @Column(name = "purchase_type", nullable = false, length = 10)
    private String purchaseType = "NEW";

    // Subscription cũ được dùng để khấu trừ khi Upgrade
    @Column(name = "upgrade_from_subscription_id")
    private Long upgradeFromSubscriptionId;

    // Khoản tiền được giảm từ giá trị còn lại của gói cũ
    @Column(name = "upgrade_credit_amount", nullable = false, precision = 12, scale = 0)
    private BigDecimal upgradeCreditAmount = BigDecimal.ZERO;

    // Thời gian thanh toán hết hạn do PayOS xác định
    @Column(name = "payment_expires_at")
    private OffsetDateTime paymentExpiresAt;

    @Column(name = "total_amount", nullable = false, precision = 12, scale = 0)
    private BigDecimal totalAmount;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(name = "status", nullable = false, length = 20)
    private String status = "PENDING";

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private User createdBy;

    // Quyền lợi của tier được chốt lúc mua (freeze_days, tuition_discount_pct, ...)
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "benefits_snapshot", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> benefitsSnapshot = new HashMap<>();

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false, updatable = false, insertable = false)
    private OffsetDateTime updatedAt;
}
