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
public class CreateEmployeeRequest {
    @NotBlank(message = "Họ tên không được để trống")
    @Size(max = 150)
    private String fullName;

    @NotBlank(message = "Vui lòng nhập số điện thoại")
    @Email(message = "Email chưa đúng dạng, ví dụ ten@gmail.com")
    @Size(max = 255)
    private String email;

    @NotBlank
    @Pattern(regexp = "^(03|05|07|08|09)[0-9]{8}$")
    private String phone;

    @NotBlank
    @Size(min = 8, message = "Mật khẩu cần từ 8 ký tự")
    private String password;

    @NotBlank(message = "Vui lòng chọn vai trò")
    private String roleCode;
}
