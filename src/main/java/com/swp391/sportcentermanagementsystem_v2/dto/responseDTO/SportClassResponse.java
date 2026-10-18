
package com.swp391.sportcentermanagementsystem_v2.dto.responseDTO;

import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

@Getter
@Setter
public class SportClassResponse {

    private Long id;

    private Long categoryId;
    private String categoryName;

    private Long coachId;
    private String coachName;

    private String name;
    private String description;
    private String level;
    private Integer maxCapacity;
    private Short requiredTierLevel;
    private String status;

    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
