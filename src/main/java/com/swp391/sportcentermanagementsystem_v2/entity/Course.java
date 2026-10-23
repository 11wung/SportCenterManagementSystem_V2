package com.swp391.sportcentermanagementsystem_v2.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Entity
@Table(name = "courses")
@Getter
@Setter
@NoArgsConstructor
public class Course {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private SportCategory category;

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    // BEGINNER, INTERMEDIATE, ADVANCED
    @Column(name = "level", nullable = false, length = 20)
    private String level;

    @Column(name = "total_sessions", nullable = false)
    private Integer totalSessions;

    @Column(name = "default_tuition", nullable = false, precision = 12, scale = 0)
    private BigDecimal defaultTuition;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "prerequisite_course_id")
    private Course prerequisiteCourse;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;
}
