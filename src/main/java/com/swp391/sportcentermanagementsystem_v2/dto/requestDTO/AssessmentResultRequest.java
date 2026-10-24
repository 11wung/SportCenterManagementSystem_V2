package com.swp391.sportcentermanagementsystem_v2.dto.requestDTO;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

// BR-BEN-03: các chỉ số coach đo và nhận xét, tất cả tùy chọn nhưng phải có ít nhất một chỉ số hoặc nhận xét
@Getter
@Setter
@NoArgsConstructor
public class AssessmentResultRequest {

    @DecimalMin(value = "1", message = "Weight must be greater than 0")
    @DecimalMax(value = "500", message = "Weight is out of range")
    private BigDecimal weightKg;

    @DecimalMin(value = "30", message = "Height must be at least 30 cm")
    @DecimalMax(value = "260", message = "Height is out of range")
    private BigDecimal heightCm;

    @DecimalMin(value = "0", message = "Body fat must be between 0 and 100")
    @DecimalMax(value = "100", message = "Body fat must be between 0 and 100")
    private BigDecimal bodyFatPct;

    @DecimalMin(value = "1", message = "Muscle mass must be greater than 0")
    @DecimalMax(value = "300", message = "Muscle mass is out of range")
    private BigDecimal muscleMassKg;

    @Min(value = 30, message = "Resting heart rate must be between 30 and 220")
    @Max(value = 220, message = "Resting heart rate must be between 30 and 220")
    private Integer restingHeartRate;

    @DecimalMin(value = "-50", message = "Flexibility must be between -50 and 100 cm")
    @DecimalMax(value = "100", message = "Flexibility must be between -50 and 100 cm")
    private BigDecimal flexibilityCm;

    // Nhận xét và khuyến nghị của coach
    @Size(max = 2000, message = "Notes must not exceed 2000 characters")
    private String notes;
}
