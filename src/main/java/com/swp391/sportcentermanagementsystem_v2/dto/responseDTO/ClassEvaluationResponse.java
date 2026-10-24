package com.swp391.sportcentermanagementsystem_v2.dto.responseDTO;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

// Một dòng danh sách đánh giá của class: grade và comment rỗng nếu học viên chưa được đánh giá
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ClassEvaluationResponse {
    private Long registrationId;
    private Long classId;
    private String className;
    private Long memberId;
    private String memberName;
    private String grade;
    private String comment;
    private String coachName;
    private OffsetDateTime evaluatedAt;
}
