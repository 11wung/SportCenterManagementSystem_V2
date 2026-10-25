package com.swp391.sportcentermanagementsystem_v2.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.OffsetDateTime;

// Một đợt bảo lưu của gói. Quỹ ngày, số đợt và chống chồng lấn do trigger/constraint trong DB kiểm tra.
@Entity
@Table(name = "subscription_freezes")
@Getter
@Setter
@NoArgsConstructor
public class SubscriptionFreeze {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "subscription_id", nullable = false)
    private MemberSubscription subscription;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    // Ngày thực tế kết thúc sớm, null nếu chưa kết thúc sớm
    @Column(name = "ended_on")
    private LocalDate endedOn;

    // SCHEDULED, ACTIVE, ENDED hoặc CANCELED
    @Column(name = "status", nullable = false, length = 10)
    private String status = "SCHEDULED";

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private User createdBy;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    // Cột generated trong DB
    @Column(name = "days_used", insertable = false, updatable = false)
    private Short daysUsed;
}
