package com.swp391.sportcentermanagementsystem_v2.dto.responseDTO;

import com.swp391.sportcentermanagementsystem_v2.entity.SportCategory;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.AllArgsConstructor;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class SportCategoryResponse {
    private Long id;
    private String name;
    private String description;
    private boolean archived;
    // Đường dẫn ảnh minh họa, null khi môn chưa có ảnh
    private String imageUrl;
    // Đường dẫn video tải lên, null khi chưa có (7 môn cũ vẫn dùng video sẵn ở giao diện)
    private String videoUrl;

    public SportCategoryResponse(SportCategory category) {
        this.id = category.getId();
        this.name = category.getName();
        this.description = category.getDescription();
        this.archived = category.getArchivedAt() != null;
        this.imageUrl = com.swp391.sportcentermanagementsystem_v2.service.ImageService.url(
                "/api/sport-categories", category.getId(), category.getImageType(), category.getImageUpdatedAt());
        this.videoUrl = com.swp391.sportcentermanagementsystem_v2.service.VideoService.url(
                category.getId(), category.getVideoType(), category.getVideoUpdatedAt());
    }
}
