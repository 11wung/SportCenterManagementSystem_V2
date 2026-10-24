package com.swp391.sportcentermanagementsystem_v2.dto.requestDTO;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ClassRegistrationRequest {

    @NotNull(message = "User ID is required")
    @Positive(message = "User ID must be greater than 0")
    private Long userId;

    @NotNull(message = "Class ID is required")
    @Positive(message = "Class ID must be greater than 0")
    private Long classId;

    // BR-CLS-07: khóa nâng cao phải xác nhận đã đọc cảnh báo
    private boolean acknowledgeAdvancedWarning;
}
