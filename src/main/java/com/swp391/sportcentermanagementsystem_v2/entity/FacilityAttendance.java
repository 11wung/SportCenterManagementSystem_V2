package com.swp391.sportcentermanagementsystem_v2.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

// Một lượt ra vào trung tâm do lễ tân ghi. Khác với điểm danh tham gia buổi học (class_attendances)
@Entity
@Table(name = "facility_attendances")
@Getter
@Setter
@NoArgsConstructor
public class FacilityAttendance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "check_in_time", nullable = false)
    private OffsetDateTime checkInTime;

    // null = đang ở trong trung tâm
    @Column(name = "check_out_time")
    private OffsetDateTime checkOutTime;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recorded_by")
    private User recordedBy;

    @Column(name = "notes", length = 500)
    private String notes;
}
