package com.swp391.sportcentermanagementsystem_v2.dto.responseDTO;

import com.swp391.sportcentermanagementsystem_v2.entity.CoachSlot;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Duration;
import java.time.OffsetDateTime;

// Khung đánh giá thể lực (miễn phí nên không có giá)
@Getter
@Setter
@NoArgsConstructor
public class AssessmentSlotResponse {
    private Long id;
    private Long coachId;
    private String coachName;
    private OffsetDateTime startAt;
    private OffsetDateTime endAt;
    private long minutes;
    private String status;
    // Người đặt: chỉ có khi BOOKED
    private Long memberId;
    private String memberName;

    public AssessmentSlotResponse(CoachSlot s) {
        this.id = s.getId();
        this.coachId = s.getCoach().getId();
        this.coachName = s.getCoach().getFullName();
        this.startAt = s.getStartAt();
        this.endAt = s.getEndAt();
        this.minutes = Duration.between(s.getStartAt(), s.getEndAt()).toMinutes();
        this.status = s.getStatus();
        if (s.getMember() != null) {
            this.memberId = s.getMember().getId();
            this.memberName = s.getMember().getFullName();
        }
    }
}
