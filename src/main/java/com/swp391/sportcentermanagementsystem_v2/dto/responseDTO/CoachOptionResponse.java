package com.swp391.sportcentermanagementsystem_v2.dto.responseDTO;

import lombok.Getter;

import java.util.List;

// Coach để member chọn khi gửi yêu cầu 1-1: chỉ tên và các môn dạy, không lộ email hay số điện thoại
@Getter
public class CoachOptionResponse {

    private final Long id;
    private final String fullName;
    private final List<SportCategoryResponse> sports;

    public CoachOptionResponse(Long id, String fullName, List<SportCategoryResponse> sports) {
        this.id = id;
        this.fullName = fullName;
        this.sports = sports;
    }
}
