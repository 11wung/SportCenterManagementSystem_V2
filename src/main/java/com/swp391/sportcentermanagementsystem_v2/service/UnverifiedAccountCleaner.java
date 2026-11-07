package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.entity.User;
import com.swp391.sportcentermanagementsystem_v2.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

// Mỗi ngày xóa tài khoản hội viên chưa xác thực email quá hạn mà chưa có dữ liệu nào,
// để email gõ sai không chiếm chỗ email và số điện thoại mãi. Tài khoản có dữ liệu thì không đụng tới.
@Slf4j
@Component
@RequiredArgsConstructor
public class UnverifiedAccountCleaner {

    private static final ZoneId VIETNAM_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final UserRepository userRepository;
    private final UserDataGuard userDataGuard;
    private final SystemSettingService settings;

    @Scheduled(cron = "0 30 3 * * *", zone = "Asia/Ho_Chi_Minh")
    public void cleanUp() {
        int days = settings.getInt("auth.unverified_cleanup_days", 7);
        List<User> stale = userRepository.findUnverifiedMembersCreatedBefore(
                OffsetDateTime.now(VIETNAM_ZONE).minusDays(days));

        int deleted = 0;
        for (User user : stale) {
            try {
                if (!userDataGuard.hasBusinessData(user.getId())) {
                    userRepository.delete(user);
                    deleted++;
                }
            } catch (DataAccessException ex) {
                // Một tài khoản lỗi không làm dừng cả đợt dọn, lần sau sẽ thử lại
                log.error("Cannot delete unverified account id={}", user.getId(), ex);
            }
        }
        if (deleted > 0) {
            log.info("Deleted {} unverified accounts older than {} days", deleted, days);
        }
    }
}
