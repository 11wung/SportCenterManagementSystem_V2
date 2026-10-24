package com.swp391.sportcentermanagementsystem_v2.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

// BR-CLS-08: coach đánh giá từng học viên khi class kết thúc, thang A, B, C, D kèm nhận xét. Mỗi đăng ký một đánh giá
@Entity
@Table(name = "class_evaluations")
@Getter
@Setter
@NoArgsConstructor
public class ClassEvaluation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "registration_id", nullable = false, unique = true)
    private ClassRegistration registration;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "coach_id", nullable = false)
    private User coach;

    @Column(name = "grade", nullable = false, length = 1, columnDefinition = "char(1)")
    private String grade;

    @Column(name = "comment", columnDefinition = "text")
    private String comment;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;
}
