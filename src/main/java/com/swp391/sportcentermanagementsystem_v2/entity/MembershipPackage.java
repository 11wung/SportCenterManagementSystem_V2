package com.swp391.sportcentermanagementsystem_v2.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

// Mỗi dòng là một tier (Khởi Động, Bứt Tốc, ...) kèm quyền lợi. Số tháng chọn lúc mua.
@Entity
@Table(name = "membership_packages")
@Getter
@Setter
@NoArgsConstructor
public class MembershipPackage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", nullable = false, length = 30, unique = true)
    private String code;

    @Column(name = "name", nullable = false, length = 150, unique = true)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "tier_level", nullable = false)
    private Short tierLevel;

    @Column(name = "monthly_price", nullable = false, precision = 12, scale = 0)
    private BigDecimal monthlyPrice;

    // Số ngày được đăng ký lớp sớm
    @Column(name = "early_register_days", nullable = false)
    private Short earlyRegisterDays = 0;

    @Column(name = "tuition_discount_pct", nullable = false)
    private Short tuitionDiscountPct = 0;

    @Column(name = "one_on_one_discount_pct", nullable = false)
    private Short oneOnOneDiscountPct = 0;

    @Column(name = "has_personal_plan", nullable = false)
    private Boolean hasPersonalPlan = false;

    // NONE, LIMITED hoặc UNLIMITED
    @Column(name = "ai_level", nullable = false, length = 10)
    private String aiLevel = "NONE";

    @Column(name = "menu_ai_level", nullable = false, length = 10)
    private String menuAiLevel = "NONE";

    // Null nếu tier không có đánh giá thể lực
    @Column(name = "assessment_every_months")
    private Short assessmentEveryMonths;

    @Column(name = "freeze_days", nullable = false)
    private Short freezeDays = 0;

    @Column(name = "early_leave_refund_sessions", nullable = false)
    private Short earlyLeaveRefundSessions = 0;

    @Column(name = "waitlist_priority", nullable = false)
    private Short waitlistPriority = 0;

    // NONE, PERCENT hoặc FREE_ONE_ON_ONE
    @Column(name = "birthday_gift_kind", nullable = false, length = 20)
    private String birthdayGiftKind = "NONE";

    @Column(name = "birthday_gift_value", nullable = false, precision = 12, scale = 0)
    private BigDecimal birthdayGiftValue = BigDecimal.ZERO;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime updatedAt;
}
