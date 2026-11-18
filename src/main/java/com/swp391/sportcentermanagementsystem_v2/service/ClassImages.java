package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.entity.SportCategory;
import com.swp391.sportcentermanagementsystem_v2.entity.SportClass;

// Ảnh hiển thị của một lớp: ảnh riêng của lớp, không có thì ảnh của môn
public final class ClassImages {

    private ClassImages() {}

    public static String effectiveUrl(SportClass sportClass) {
        String own = ImageService.url("/api/sport-classes", sportClass.getId(), sportClass.getImageType(),
                sportClass.getImageUpdatedAt());
        if (own != null) {
            return own;
        }
        SportCategory category = sportClass.getCourse().getCategory();
        return ImageService.url("/api/sport-categories", category.getId(), category.getImageType(),
                category.getImageUpdatedAt());
    }
}
