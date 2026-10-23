package com.swp391.sportcentermanagementsystem_v2.dto.responseDTO;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Getter
@Setter
public class SportClassResponse {

    private Long id;

    private Long courseId;
    private String courseName;
    private String level;
    private Integer totalSessions;

    private Long categoryId;
    private String categoryName;

    private Long coachId;
    private String coachName;

    private String name;
    private String description;
    private Integer maxCapacity;
    private BigDecimal tuition;
    private LocalDate startDate;
    private LocalDate registrationOpensOn;
    private String status;

    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
