package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.exception.BusinessRuleException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.time.OffsetDateTime;

// Giờ mở cửa chung của trung tâm (cấu hình center.open_minute, center.close_minute): lớp, buổi 1-1 và khung đánh giá
// thể lực không được nằm ngoài khoảng này
@Service
@RequiredArgsConstructor
public class CenterHoursService {

    private final SystemSettingService settings;

    public void requireWithin(LocalTime start, LocalTime end, String what) {
        int open = settings.getInt("center.open_minute");
        int close = settings.getInt("center.close_minute");
        if (minuteOf(start) < open || minuteOf(end) > close) {
            throw new BusinessRuleException(what + " must be within the center opening hours ("
                    + format(open) + " to " + format(close) + ")");
        }
    }

    // Khung giờ có ngày: phải bắt đầu và kết thúc trong cùng một ngày theo giờ trung tâm
    public void requireWithin(OffsetDateTime start, OffsetDateTime end, String what) {
        var s = start.atZoneSameInstant(SportClassService.CENTER_ZONE);
        var e = end.atZoneSameInstant(SportClassService.CENTER_ZONE);
        if (!s.toLocalDate().equals(e.toLocalDate())) {
            throw new BusinessRuleException(what + " must start and end on the same day");
        }
        requireWithin(s.toLocalTime(), e.toLocalTime(), what);
    }

    private int minuteOf(LocalTime t) {
        return t.getHour() * 60 + t.getMinute();
    }

    private String format(int minutes) {
        return String.format("%02d:%02d", minutes / 60, minutes % 60);
    }
}
