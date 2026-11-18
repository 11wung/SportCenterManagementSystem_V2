package com.swp391.sportcentermanagementsystem_v2.dto.responseDTO;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Getter
@Setter
public class SportClassResponse {

    private Long id;

    private Long courseId;
    private String courseName;
    private String level;
    private Integer totalSessions;

    private Long categoryId;
    private String categoryName;

    private Long coachId;
    private String coachName;

    private String name;
    private String description;
    private Integer maxCapacity;
    private BigDecimal tuition;
    private LocalDate startDate;
    private LocalDate registrationOpensOn;
    // Ảnh dùng để hiển thị: ảnh riêng của lớp, không có thì ảnh của môn, không có nữa thì null
    private String imageUrl;
    // true khi lớp có ảnh riêng (để form sửa biết có ảnh để xóa)
    private boolean ownImage;
    private String status;

    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
