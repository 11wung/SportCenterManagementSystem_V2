package com.swp391.sportcentermanagementsystem_v2.dto.responseDTO;

// Kết quả khi xóa phòng hoặc môn. result: DELETED (chưa từng dùng nên xóa hẳn) hoặc ARCHIVED (đã có người dùng nên chỉ ngừng sử dụng).
// upcomingSessions: số buổi sắp tới còn dùng phòng/môn này (vẫn diễn ra bình thường)
public record RemovalResponse(String result, long upcomingSessions) {}
