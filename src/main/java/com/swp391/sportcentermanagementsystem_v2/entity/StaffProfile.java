package com.swp391.sportcentermanagementsystem_v2.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "staff_profiles")
@Getter
@Setter
@NoArgsConstructor
public class StaffProfile {
    @Id
    @Column(name = "user_id")
    private Long userId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(name = "job_title")
    private String jobTitle;

    @Column(name = "shift_schedule")
    private String shiftSchedule;
}
