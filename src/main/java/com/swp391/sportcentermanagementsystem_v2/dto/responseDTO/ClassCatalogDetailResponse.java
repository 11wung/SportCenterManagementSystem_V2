package com.swp391.sportcentermanagementsystem_v2.dto.responseDTO;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

@Getter
@AllArgsConstructor
public class ClassCatalogDetailResponse {
    private ClassCatalogResponse classInfo;
    private List<ClassScheduleResponse> schedules;
    private List<ClassSessionResponse> sessions;
}
