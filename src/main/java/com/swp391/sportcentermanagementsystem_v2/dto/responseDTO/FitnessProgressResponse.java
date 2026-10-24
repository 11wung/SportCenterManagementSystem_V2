package com.swp391.sportcentermanagementsystem_v2.dto.responseDTO;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

// Một lần đo cơ thể (đánh giá thể lực). Các trường change* là chênh lệch so với lần đo ngay trước đó (null nếu thiếu số liệu)
@Getter
@Setter
@NoArgsConstructor
public class FitnessProgressResponse {
    private Long id;
    private LocalDate recordedDate;
    private String recordedBy;
    private BigDecimal weightKg;
    private BigDecimal heightCm;
    private BigDecimal bmi;
    private BigDecimal bodyFatPct;
    private BigDecimal muscleMassKg;
    private Integer restingHeartRate;
    private BigDecimal flexibilityCm;
    private String notes;
    private BigDecimal weightChange;
    private BigDecimal bodyFatChange;
    private BigDecimal muscleMassChange;
    private Integer restingHeartRateChange;
    private BigDecimal flexibilityChange;
}
