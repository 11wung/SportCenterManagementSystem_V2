package com.swp391.sportcentermanagementsystem_v2.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "sport_classes")
@Getter
@Setter
@NoArgsConstructor
public class SportClass {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "course_id", nullable = false)
    private Course course;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "coach_id", nullable = false)
    private User coach;

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "max_capacity", nullable = false)
    private Integer maxCapacity;

    @Column(name = "tuition", nullable = false, precision = 12, scale = 0)
    private BigDecimal tuition;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "registration_opens_on", nullable = false)
    private LocalDate registrationOpensOn;

    // DRAFT, OPEN, IN_PROGRESS, COMPLETED, CANCELED
    @Column(name = "status", nullable = false, length = 15)
    private String status = "DRAFT";

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private OffsetDateTime updatedAt;
}
