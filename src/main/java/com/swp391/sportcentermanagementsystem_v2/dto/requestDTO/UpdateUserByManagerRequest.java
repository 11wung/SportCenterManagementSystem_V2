package com.swp391.sportcentermanagementsystem_v2.dto.requestDTO;

import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

@Getter
@Setter
public class UpdateUserByManagerRequest {
    @Size(max = 150)
    @Pattern(
            regexp = "(?s).*\\S.*",
            message = "Họ tên không được để trống"
    )
    private String fullName;

    @Pattern(
            regexp = "^(03|05|07|08|09)[0-9]{8}$",
            message = "Số điện thoại không hợp lệ"
    )
    private String phone;

    @Pattern(
            regexp = "MALE|FEMALE|OTHER",
            message = "Giới tính không hợp lệ"
    )
    private String gender;

    @Past(message = "Date of birth must be in the past")
    private LocalDate dateOfBirth;

    @Pattern(
            regexp = "MEMBER|COACH|RECEPTIONIST",
            message = "Vai trò không hợp lệ"
    )
    private String roleCode;
}
