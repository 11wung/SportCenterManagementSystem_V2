package com.swp391.sportcentermanagementsystem_v2.dto.responseDTO;

import com.swp391.sportcentermanagementsystem_v2.entity.SupportRequest;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

@Getter
@Setter
@NoArgsConstructor
public class SupportRequestResponse {
    private Long id;
    private Long memberId;
    private String memberName;
    private String memberEmail;
    private String subject;
    private String content;
    private String status;
    private String handledByName;
    private OffsetDateTime createdAt;
    private OffsetDateTime resolvedAt;

    public SupportRequestResponse(SupportRequest r) {
        this.id = r.getId();
        this.memberId = r.getMember().getId();
        this.memberName = r.getMember().getFullName();
        this.memberEmail = r.getMember().getEmail();
        this.subject = r.getSubject();
        this.content = r.getContent();
        this.status = r.getStatus();
        this.handledByName = r.getHandledBy() == null ? null : r.getHandledBy().getFullName();
        this.createdAt = r.getCreatedAt();
        this.resolvedAt = r.getResolvedAt();
    }
}
