package com.swp391.sportcentermanagementsystem_v2.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Một cặp (thứ trong tuần, khung giờ) của yêu cầu buổi 1-1. weekday: 1 = thứ 2 đến 7 = chủ nhật.
// Yêu cầu cũ không có dòng nào: đúng thứ của firstDate mỗi tuần, giờ là timeSlot của yêu cầu
@Entity
@Table(name = "coach_slot_request_days")
@Getter
@Setter
@NoArgsConstructor
public class CoachSlotRequestDay {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "request_id", nullable = false)
    private CoachSlotRequest request;

    @Column(name = "weekday", nullable = false)
    private short weekday;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "time_slot_id", nullable = false)
    private TimeSlot timeSlot;

    public CoachSlotRequestDay(CoachSlotRequest request, int weekday, TimeSlot timeSlot) {
        this.request = request;
        this.weekday = (short) weekday;
        this.timeSlot = timeSlot;
    }
}
