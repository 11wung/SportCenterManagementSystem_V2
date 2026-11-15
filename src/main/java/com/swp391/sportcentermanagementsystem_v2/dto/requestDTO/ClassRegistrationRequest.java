package com.swp391.sportcentermanagementsystem_v2.dto.requestDTO;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ClassRegistrationRequest {

    @NotNull(message = "Vui lòng chọn người dùng")
    @Positive(message = "Vui lòng chọn người dùng")
    private Long userId;

    @NotNull(message = "Vui lòng chọn lớp")
    @Positive(message = "Vui lòng chọn lớp")
    private Long classId;

    // BR-CLS-07: khóa nâng cao phải xác nhận đã đọc cảnh báo
    private boolean acknowledgeAdvancedWarning;
}
