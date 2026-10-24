package com.swp391.sportcentermanagementsystem_v2.dto.responseDTO;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

// Năng lực so với nhu cầu đánh giá thể lực trong N ngày tới. Nhu cầu chỉ là ước tính (giả định hội viên dùng hết lượt)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AssessmentCapacityResponse {
    private OffsetDateTime from;
    private OffsetDateTime to;
    private long openSlots;
    private long bookedSlots;
    private long entitledMembers;
    private long estimatedDemand;
    // Số khung còn thiếu so với nhu cầu ước tính (0 nếu đủ)
    private long shortage;
}
