package com.swp391.sportcentermanagementsystem_v2.dto.responseDTO;


import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class CreateEmployeeResponse {
    private Long id;
    private String fullName;
    private String email;
    private String role;
}
