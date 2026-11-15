package com.swp391.sportcentermanagementsystem_v2.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

// Cách viết ngày, giờ, tiền trong thông báo và câu báo lỗi cho người dùng đọc: 20/11/2026, 18:00 20/11/2026, 500.000 đ
public final class DisplayFormat {
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter TIME_DAY = DateTimeFormatter.ofPattern("HH:mm 'ngày' dd/MM/yyyy");
    private static final String[] WEEKDAYS = {"Thứ 2", "Thứ 3", "Thứ 4", "Thứ 5", "Thứ 6", "Thứ 7", "Chủ nhật"};

    private DisplayFormat() {
    }

    public static String day(LocalDate date) {
        return date == null ? "" : date.format(DAY);
    }

    // Giờ theo múi giờ trung tâm
    public static String timeDay(OffsetDateTime at) {
        return at == null ? "" : at.atZoneSameInstant(SportClassService.CENTER_ZONE).format(TIME_DAY);
    }

    public static String money(BigDecimal amount) {
        if (amount == null) {
            return "0 đ";
        }
        return String.format("%,d đ", amount.setScale(0, java.math.RoundingMode.HALF_UP).longValueExact()).replace(',', '.');
    }

    // 1 = Thứ 2 ... 7 = Chủ nhật
    public static String weekday(int dayOfWeek) {
        return dayOfWeek >= 1 && dayOfWeek <= 7 ? WEEKDAYS[dayOfWeek - 1] : "";
    }
}
