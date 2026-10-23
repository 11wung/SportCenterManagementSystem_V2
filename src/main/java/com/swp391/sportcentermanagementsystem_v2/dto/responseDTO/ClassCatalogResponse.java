package com.swp391.sportcentermanagementsystem_v2.dto.responseDTO;

import com.swp391.sportcentermanagementsystem_v2.entity.SportClass;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

// Một class trong danh mục cho khách và member xem
@Getter
@Setter
@NoArgsConstructor
public class ClassCatalogResponse {
    private Long id;
    private String name;
    private String description;
    private Long courseId;
    private String courseName;
    private String level;
    private Integer totalSessions;
    private Long categoryId;
    private String categoryName;
    private Long coachId;
    private String coachName;
    private BigDecimal tuition;
    private LocalDate startDate;
    private LocalDate registrationOpensOn;
    private Integer maxCapacity;
    private Integer seatsLeft;
    // BR-CLS-07: khóa nâng cao thì mọi lần đăng ký đều hiện cảnh báo
    private boolean advancedWarning;
    private String prerequisiteCourseName;

    public ClassCatalogResponse(SportClass c, long taken) {
        this.id = c.getId();
        this.name = c.getName();
        this.description = c.getDescription();
        this.courseId = c.getCourse().getId();
        this.courseName = c.getCourse().getName();
        this.level = c.getCourse().getLevel();
        this.totalSessions = c.getCourse().getTotalSessions();
        this.categoryId = c.getCourse().getCategory().getId();
        this.categoryName = c.getCourse().getCategory().getName();
        this.coachId = c.getCoach().getId();
        this.coachName = c.getCoach().getFullName();
        this.tuition = c.getTuition();
        this.startDate = c.getStartDate();
        this.registrationOpensOn = c.getRegistrationOpensOn();
        this.maxCapacity = c.getMaxCapacity();
        this.seatsLeft = (int) Math.max(0, c.getMaxCapacity() - taken);
        this.advancedWarning = "ADVANCED".equals(c.getCourse().getLevel());
        this.prerequisiteCourseName = c.getCourse().getPrerequisiteCourse() == null
                ? null
                : c.getCourse().getPrerequisiteCourse().getName();
    }
}
