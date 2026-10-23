package com.swp391.sportcentermanagementsystem_v2.dto.responseDTO;

import com.swp391.sportcentermanagementsystem_v2.entity.Course;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
public class CourseResponse {
    private Long id;
    private Long sportCategoryId;
    private String sportCategoryName;
    private String name;
    private String description;
    private String level;
    private Integer totalSessions;
    private BigDecimal defaultTuition;
    private Long prerequisiteCourseId;
    private boolean active;

    public CourseResponse(Course course) {
        this.id = course.getId();
        this.sportCategoryId = course.getCategory().getId();
        this.sportCategoryName = course.getCategory().getName();
        this.name = course.getName();
        this.description = course.getDescription();
        this.level = course.getLevel();
        this.totalSessions = course.getTotalSessions();
        this.defaultTuition = course.getDefaultTuition();
        // getId() trên proxy lazy không chạy thêm query
        this.prerequisiteCourseId = course.getPrerequisiteCourse() == null
                ? null
                : course.getPrerequisiteCourse().getId();
        this.active = course.isActive();
    }
}
