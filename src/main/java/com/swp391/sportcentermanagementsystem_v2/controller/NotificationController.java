package com.swp391.sportcentermanagementsystem_v2.controller;

import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.NotificationListResponse;
import com.swp391.sportcentermanagementsystem_v2.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

// BR-NTF-01: thông báo trong ứng dụng, mọi người dùng đã đăng nhập xem được thông báo của mình
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    // GET /api/notifications/me?unreadOnly=false - 50 thông báo mới nhất kèm số chưa đọc
    @GetMapping("/me")
    public NotificationListResponse myNotifications(Authentication authentication,
            @RequestParam(defaultValue = "false") boolean unreadOnly) {
        return notificationService.myNotifications(authentication.getName(), unreadOnly);
    }

    // PATCH /api/notifications/{id}/read - Đánh dấu đã đọc một thông báo của mình
    @PatchMapping("/{id}/read")
    public ResponseEntity<Void> markRead(Authentication authentication, @PathVariable Long id) {
        notificationService.markRead(authentication.getName(), id);
        return ResponseEntity.noContent().build();
    }

    // PATCH /api/notifications/read-all - Đánh dấu đã đọc tất cả
    @PatchMapping("/read-all")
    public Map<String, Integer> markAllRead(Authentication authentication) {
        return Map.of("updated", notificationService.markAllRead(authentication.getName()));
    }
}
