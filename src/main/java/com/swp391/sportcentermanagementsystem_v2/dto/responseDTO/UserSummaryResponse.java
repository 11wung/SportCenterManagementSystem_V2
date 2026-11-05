package com.swp391.sportcentermanagementsystem_v2.dto.responseDTO;


import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class UserSummaryResponse {
    private Long id;
    private String fullName;
    private String email;
    private String phone;
    private String role;
    private boolean isActive;
    // Email đã được xác thực bằng mã chưa (hai trạng thái: đã xác thực, chờ xác thực)
    private boolean emailVerified;
    // Chỉ có với coach: các môn đang dạy và hạng; tài khoản khác để null
    private List<SportCategoryResponse> sports;
    private String coachRank;
}
