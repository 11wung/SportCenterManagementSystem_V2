package com.swp391.sportcentermanagementsystem_v2.dto.requestDTO;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
public class OneOnOnePackageRequest {

    @NotNull(message = "Hãy chọn môn")
    private Long sportCategoryId;

    @NotBlank(message = "Hãy nhập tên gói")
    @Size(max = 150, message = "Tên gói tối đa 150 ký tự")
    private String name;

    @Size(max = 1000, message = "Mô tả tối đa 1000 ký tự")
    private String description;

    @NotNull(message = "Hãy nhập số buổi")
    @Min(value = 1, message = "Gói có ít nhất 1 buổi")
    @Max(value = 60, message = "Gói có tối đa 60 buổi")
    private Integer sessions;

    @NotNull(message = "Hãy nhập giá gói")
    @Positive(message = "Giá gói phải lớn hơn 0")
    private BigDecimal price;
}
