package com.swp391.sportcentermanagementsystem_v2.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "coach_profiles")
@Getter
@Setter
@NoArgsConstructor
public class CoachProfile {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(name = "specialty")
    private String specialty;

    @Column(name = "years_experience")
    private Short yearsExperience;

    @Column(name = "certifications", columnDefinition = "TEXT")
    private String certifications;

    @Column(name = "rating", precision = 2, scale = 1)
    private BigDecimal rating;

    // Hạng coach quyết định giá buổi 1-1 (BR-ONE-02): COACH, SENIOR, MASTER
    @Column(name = "coach_rank", nullable = false, length = 10)
    private String coachRank = "COACH";

    // Bảng nối coach_sports: coach dạy được những môn nào (BR-SCH-04)
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "coach_sports",
            joinColumns = @JoinColumn(name = "coach_id"),
            inverseJoinColumns = @JoinColumn(name = "category_id"))
    private Set<SportCategory> sports = new HashSet<>();
}
