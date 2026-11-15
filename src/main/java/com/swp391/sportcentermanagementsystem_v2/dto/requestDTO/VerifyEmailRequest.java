package com.swp391.sportcentermanagementsystem_v2.dto.requestDTO;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class VerifyEmailRequest {

    @NotBlank
    @Email(message = "Email chưa đúng dạng, ví dụ ten@gmail.com")
    @Size(max = 255)
    private String email;

    @NotBlank
    @Pattern(regexp = "^[0-9]{6}$", message = "Mã gồm 6 số")
    private String code;
}
