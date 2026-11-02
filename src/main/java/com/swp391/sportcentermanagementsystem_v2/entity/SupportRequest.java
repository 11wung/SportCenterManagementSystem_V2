package com.swp391.sportcentermanagementsystem_v2.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

// Yêu cầu hỗ trợ của hội viên, do lễ tân ghi nhận và xử lý
@Entity
@Table(name = "support_requests")
@Getter
@Setter
@NoArgsConstructor
public class SupportRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false)
    private User member;

    // Người nhận xử lý đầu tiên
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "handled_by")
    private User handledBy;

    @Column(name = "subject", nullable = false, length = 200)
    private String subject;

    @Column(name = "content", nullable = false, columnDefinition = "text")
    private String content;

    // OPEN, IN_PROGRESS, RESOLVED, CLOSED
    @Column(name = "status", nullable = false, length = 20)
    private String status = "OPEN";

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "resolved_at")
    private OffsetDateTime resolvedAt;
}
