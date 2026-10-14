package com.swp391.sportcentermanagementsystem_v2.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

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
}
