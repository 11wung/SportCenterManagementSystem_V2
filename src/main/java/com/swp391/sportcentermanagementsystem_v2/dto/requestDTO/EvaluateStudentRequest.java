package com.swp391.sportcentermanagementsystem_v2.dto.requestDTO;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class EvaluateStudentRequest {

    @NotBlank(message = "Vui lòng chọn xếp loại")
    @Pattern(regexp = "[ABCD]", message = "Xếp loại chỉ có A, B, C hoặc D")
    private String grade;

    @Size(max = 2000, message = "Nhận xét tối đa 2000 ký tự")
    private String comment;
}
