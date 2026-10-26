package com.swp391.sportcentermanagementsystem_v2.dto.requestDTO;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Getter
@Setter
@NoArgsConstructor
public class CreateVoucherCampaignRequest {

    @NotNull(message = "Code is required")
    @Pattern(regexp = "[A-Za-z0-9_-]{3,40}", message = "Code must be 3-40 letters, digits, '_' or '-'")
    private String code;

    @NotNull(message = "Kind is required")
    @Pattern(regexp = "PERCENT|AMOUNT|FREE_ONE_ON_ONE", message = "Kind must be PERCENT, AMOUNT or FREE_ONE_ON_ONE")
    private String kind;

    // Phần trăm (0-100), số tiền, hoặc 0 với buổi 1-1 miễn phí
    @NotNull(message = "Value is required")
    @DecimalMin(value = "0", message = "Value must not be negative")
    @DecimalMax(value = "999999999", message = "Value is too large")
    private BigDecimal value;

    @NotNull(message = "AppliesTo is required")
    @Pattern(regexp = "CLASS|ONE_ON_ONE|PACKAGE", message = "AppliesTo must be CLASS, ONE_ON_ONE or PACKAGE")
    private String appliesTo;

    // Null nghĩa là không giới hạn số lượt
    @Min(value = 1, message = "Max uses must be at least 1")
    private Integer maxUses;

    @NotNull(message = "Start time is required")
    private OffsetDateTime startsAt;

    @NotNull(message = "End time is required")
    private OffsetDateTime endsAt;
}
