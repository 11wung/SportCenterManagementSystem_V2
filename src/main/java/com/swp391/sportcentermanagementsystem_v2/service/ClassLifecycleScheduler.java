package com.swp391.sportcentermanagementsystem_v2.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

@Slf4j
@Component
@RequiredArgsConstructor
public class ClassLifecycleScheduler {

    private final SportClassService sportClassService;

    // Mỗi ngày lúc 00:05 giờ Việt Nam: class đến ngày khai giảng thì chuyển sang đang học,
    // class hết buổi thì chuyển sang kết thúc.
    // shortcut: nếu máy chủ tắt đúng lúc 00:05 thì phải đợi 00:05 hôm sau mới cập nhật, đổi sang chạy mỗi giờ nếu cần
    @Scheduled(cron = "0 5 0 * * *", zone = "Asia/Ho_Chi_Minh")
    public void advanceClassLifecycle() {
        try {
            sportClassService.advanceLifecycle(LocalDate.now(SportClassService.CENTER_ZONE));
        } catch (RuntimeException ex) {
            log.error("Failed to advance class lifecycle", ex);
        }
    }
}
