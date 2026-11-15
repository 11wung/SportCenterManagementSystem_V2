package com.swp391.sportcentermanagementsystem_v2.dto.requestDTO;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class CreateSupportRequest {

    @NotNull(message = "Vui lòng chọn hội viên")
    private Long memberId;

    @NotBlank(message = "Vui lòng nhập tiêu đề")
    @Size(max = 200, message = "Tiêu đề tối đa 200 ký tự")
    private String subject;

    @NotBlank(message = "Vui lòng nhập nội dung")
    @Size(max = 2000, message = "Nội dung tối đa 2000 ký tự")
    private String content;
}
