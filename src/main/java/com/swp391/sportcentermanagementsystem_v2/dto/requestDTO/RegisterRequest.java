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
public class RegisterRequest {
    @NotBlank
    @Size(max = 150)
    private String fullName;

    @NotBlank
    @Email
    @Size(max = 255)
    private String email;

    @NotBlank
    @Pattern(regexp = "^(03|05|07|08|09)[0-9]{8}$")
    private String phone;

    @NotBlank
    @Size(min = 8)
    private String password;
}
