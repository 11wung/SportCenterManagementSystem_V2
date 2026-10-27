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
public class ResetPasswordRequest {

    @NotBlank
    @Email(message = "Invalid email format")
    @Size(max = 255)
    private String email;

    @NotBlank
    @Pattern(regexp = "^[0-9]{6}$", message = "Code must be 6 digits")
    private String code;

    // Cùng luật với mật khẩu lúc đăng ký (RegisterRequest)
    @NotBlank
    @Size(min = 8, max = 72, message = "Password must be 8 to 72 characters")
    private String newPassword;
}
