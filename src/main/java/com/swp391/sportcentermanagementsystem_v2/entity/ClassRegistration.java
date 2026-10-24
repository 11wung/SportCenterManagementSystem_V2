package com.swp391.sportcentermanagementsystem_v2.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

@Entity
@Table(name = "class_registrations")
@Getter
@Setter
@NoArgsConstructor
public class ClassRegistration {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "class_id", nullable = false)
    private SportClass sportClass;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "registered_at", nullable = false)
    private OffsetDateTime registeredAt;

    // PENDING_PAYMENT, REGISTERED, WAITLISTED, CANCELED, LEFT
    @Column(name = "status", nullable = false, length = 20)
    private String status = "PENDING_PAYMENT";

    @Column(name = "canceled_at")
    private OffsetDateTime canceledAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "registered_by")
    private User registeredBy;

    // Hạn giữ chỗ khi chờ thanh toán (BR-ENR-03)
    @Column(name = "hold_expires_at")
    private OffsetDateTime holdExpiresAt;

    // Thứ hạng tier lúc vào danh sách chờ (BR-WL-02), dùng ở L6
    @Column(name = "waitlist_rank", nullable = false)
    private Short waitlistRank = 0;

    // Hạn của người được báo có chỗ trong danh sách chờ (BR-WL-03), dùng ở L6
    @Column(name = "offer_expires_at")
    private OffsetDateTime offerExpiresAt;

    @PrePersist
    public void prePersist() {
        if (registeredAt == null) {
            registeredAt = OffsetDateTime.now();
        }
    }
}
