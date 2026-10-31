package com.swp391.sportcentermanagementsystem_v2.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

// Một suất buổi 1-1 do coach đăng (BR-ONE). Mỗi dòng chỉ có một lần được đặt: suất bị hủy thì dòng đó
// giữ nguyên lịch sử và một dòng OPEN mới được tạo cùng giờ, để đơn và thanh toán luôn đi theo đúng một lần đặt
@Entity
@Table(name = "coach_slots")
@Getter
@Setter
@NoArgsConstructor
public class CoachSlot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "coach_id", nullable = false)
    private User coach;

    // Phòng và môn của buổi 1-1. Trống ở các suất tạo trước khi có hai cột này
    @ManyToOne
    @JoinColumn(name = "room_id")
    private FacilityRoom room;

    @ManyToOne
    @JoinColumn(name = "category_id")
    private SportCategory category;

    @Column(name = "start_at", nullable = false)
    private OffsetDateTime startAt;

    @Column(name = "end_at", nullable = false)
    private OffsetDateTime endAt;

    // ONE_ON_ONE
    @Column(name = "kind", nullable = false, length = 12)
    private String kind = "ONE_ON_ONE";

    // OPEN, HELD, BOOKED, CANCELED
    @Column(name = "status", nullable = false, length = 10)
    private String status = "OPEN";

    // Người đặt, rỗng khi OPEN. Dòng CANCELED giữ nguyên người đặt để làm lịch sử
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id")
    private User member;

    // Hạn giữ chỗ khi chờ thanh toán (BR-ONE-03)
    @Column(name = "held_until")
    private OffsetDateTime heldUntil;

    // Giá chụp lúc đặt
    @Column(name = "price", precision = 12, scale = 0)
    private BigDecimal price;

    // Người hủy; rỗng nếu hủy do hết hạn giữ chỗ (hệ thống)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "canceled_by")
    private User canceledBy;

    @Column(name = "canceled_at")
    private OffsetDateTime canceledAt;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;
}
