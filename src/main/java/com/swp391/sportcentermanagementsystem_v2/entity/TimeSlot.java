package com.swp391.sportcentermanagementsystem_v2.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalTime;

// Slot giờ cố định của trung tâm. Giờ của slot không sửa được, chỉ tắt (is_active = false)
@Entity
@Table(name = "time_slots")
@Getter
@Setter
@NoArgsConstructor
public class TimeSlot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "slot_no", nullable = false, unique = true)
    private Short slotNo;

    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;
}
