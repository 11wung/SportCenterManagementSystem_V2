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

    @NotBlank(message = "Email cannot be blank")
    @Pattern(regexp = "^[^@\\s]+@[^@\\s]+\\.[A-Za-z]{2,}$", message = "Invalid email format")
    @Size(max = 255, message = "Email must not exceed 255 characters")
    private String email;

    @NotBlank(message = "Phone cannot be blank")
    @Pattern(regexp = "^(03|05|07|08|09)[0-9]{8}$",
            message = "Phone must be 10 digits starting with 03, 05, 07, 08 or 09")
    private String phone;

    @NotBlank(message = "Password cannot be blank")
    @Size(min = 8, message = "Password must be at least 8 characters")
    private String password;
}
