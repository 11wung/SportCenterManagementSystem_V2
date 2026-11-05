package com.swp391.sportcentermanagementsystem_v2.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.OffsetDateTime;

// Yêu cầu buổi 1-1: member xin N buổi, cùng khung giờ mỗi tuần, liên tiếp. Suất chỉ được tạo khi manager duyệt
@Entity
@Table(name = "coach_slot_requests")
@Getter
@Setter
@NoArgsConstructor
public class CoachSlotRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false)
    private User member;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "coach_id", nullable = false)
    private User coach;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private SportCategory category;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "time_slot_id", nullable = false)
    private TimeSlot timeSlot;

    // Ngày của buổi đầu; các buổi sau cách nhau đúng một tuần
    @Column(name = "first_date", nullable = false)
    private LocalDate firstDate;

    @Column(name = "sessions", nullable = false)
    private short sessions;

    // PENDING, APPROVED, PAID, REJECTED, CANCELED, EXPIRED
    @Column(name = "status", nullable = false, length = 10)
    private String status = "PENDING";

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "decided_by")
    private User decidedBy;

    @Column(name = "decided_at")
    private OffsetDateTime decidedAt;

    @Column(name = "reject_reason", length = 500)
    private String rejectReason;

    // PENDING: hạn để manager duyệt. APPROVED: hạn để member trả tiền
    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;
}
