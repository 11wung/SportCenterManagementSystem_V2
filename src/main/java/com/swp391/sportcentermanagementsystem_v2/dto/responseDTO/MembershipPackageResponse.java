
package com.swp391.sportcentermanagementsystem_v2.dto.responseDTO;

import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Getter
@Setter
@NoArgsConstructor
public class MembershipPackageResponse {

    private Long id;

    private String code;

    private String name;

    private String description;

    private Short durationMonths;

    private BigDecimal price;

    private Short maxClassesPerWeek;

    private Boolean isActive;

    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;
}
