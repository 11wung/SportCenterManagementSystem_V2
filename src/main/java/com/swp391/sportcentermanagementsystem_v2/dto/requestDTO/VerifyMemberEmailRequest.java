package com.swp391.sportcentermanagementsystem_v2.dto.requestDTO;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class VerifyMemberEmailRequest {

    @NotBlank(message = "Vui lòng nhập mã xác thực")
    @Pattern(regexp = "^[0-9]{6}$", message = "Mã xác thực gồm đúng 6 chữ số")
    private String code;
}
