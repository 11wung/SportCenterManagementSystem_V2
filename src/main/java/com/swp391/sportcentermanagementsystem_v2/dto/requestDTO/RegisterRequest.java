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

    @NotBlank(message = "Họ tên không được để trống")
    @Size(max = 150)
    private String fullName;

    @NotBlank(message = "Vui lòng nhập email")
    @Pattern(regexp = "^[^@\\s]+@[^@\\s]+\\.[A-Za-z]{2,}$", message = "Email chưa đúng dạng, ví dụ ten@gmail.com")
    @Size(max = 255, message = "Email tối đa 255 ký tự")
    private String email;

    @NotBlank(message = "Vui lòng nhập số điện thoại")
    @Pattern(regexp = "^(03|05|07|08|09)[0-9]{8}$",
            message = "Số điện thoại gồm 10 số, bắt đầu bằng 03, 05, 07, 08 hoặc 09")
    private String phone;

    @NotBlank(message = "Vui lòng nhập mật khẩu")
    @Size(min = 8, message = "Mật khẩu cần từ 8 ký tự")
    private String password;
}
