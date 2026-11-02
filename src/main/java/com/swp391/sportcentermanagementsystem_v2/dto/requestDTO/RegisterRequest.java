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
public class RegisterRequest {

    @NotBlank(message = "Full name cannot be blank")
    @Size(max = 150)
    private String fullName;

    @NotBlank(message = "Email không được để trống")
    @Pattern(regexp = "^[^@\\s]+@[^@\\s]+\\.[A-Za-z]{2,}$", message = "Email không hợp lệ")
    @Size(max = 255, message = "Email tối đa 255 ký tự")
    private String email;

    @NotBlank(message = "Số điện thoại không được để trống")
    @Pattern(regexp = "^(03|05|07|08|09)[0-9]{8}$",
            message = "Số điện thoại phải gồm 10 số, bắt đầu bằng 03, 05, 07, 08 hoặc 09")
    private String phone;

    @NotBlank
    @Size(min = 8, message = "Password must be at least 8 charaters")
    private String password;
}
