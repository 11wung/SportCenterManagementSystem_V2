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

    @Column(name = "status", nullable = false, length = 20)
    private String status = "REGISTERED";

    @Column(name = "canceled_at")
    private OffsetDateTime canceledAt;

    @Column(name = "is_penalized", nullable = false)
    private Boolean isPenalized = false;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "registered_by")
    private User registeredBy;

    @PrePersist
    public void prePersist() {
        if (registeredAt == null) {
            registeredAt = OffsetDateTime.now();
        }
    }
}
