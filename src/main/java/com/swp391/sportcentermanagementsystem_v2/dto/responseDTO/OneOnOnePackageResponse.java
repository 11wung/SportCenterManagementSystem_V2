package com.swp391.sportcentermanagementsystem_v2.dto.responseDTO;

import com.swp391.sportcentermanagementsystem_v2.entity.OneOnOnePackage;

import java.math.BigDecimal;
import java.math.RoundingMode;

// pricePerSession chỉ để hiển thị (giá gói chia số buổi, làm tròn đến đồng)
public record OneOnOnePackageResponse(Long id, Long sportCategoryId, String sportName, String name, String description,
                                      int sessions, BigDecimal price, BigDecimal pricePerSession, boolean active) {

    public OneOnOnePackageResponse(OneOnOnePackage p) {
        this(p.getId(), p.getCategory().getId(), p.getCategory().getName(), p.getName(), p.getDescription(),
                p.getSessions(), p.getPrice(),
                p.getPrice().divide(BigDecimal.valueOf(p.getSessions()), 0, RoundingMode.HALF_UP),
                Boolean.TRUE.equals(p.getIsActive()));
    }
}
