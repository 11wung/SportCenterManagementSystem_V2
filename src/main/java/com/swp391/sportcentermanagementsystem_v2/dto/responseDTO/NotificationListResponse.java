package com.swp391.sportcentermanagementsystem_v2.dto.responseDTO;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

// 50 thông báo mới nhất kèm tổng số chưa đọc
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class NotificationListResponse {
    private long unreadCount;
    private List<NotificationResponse> notifications;
}
