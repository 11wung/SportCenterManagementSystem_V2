package com.swp391.sportcentermanagementsystem_v2.controller;

import com.swp391.sportcentermanagementsystem_v2.service.ImageService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.time.Duration;

// Trả ảnh ra trình duyệt. Đường dẫn ảnh có ?v= đổi theo lần cập nhật nên cho lưu lại lâu
final class ImageResponses {

    private ImageResponses() {}

    static ResponseEntity<byte[]> of(ImageService.Image image) {
        if (image == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(image.type()))
                .cacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePublic())
                .body(image.data());
    }
}
