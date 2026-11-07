package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.CheckInRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.FacilityAttendanceResponse;
import com.swp391.sportcentermanagementsystem_v2.entity.FacilityAttendance;
import com.swp391.sportcentermanagementsystem_v2.entity.User;
import com.swp391.sportcentermanagementsystem_v2.exception.BusinessRuleException;
import com.swp391.sportcentermanagementsystem_v2.repository.FacilityAttendanceRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.MemberSubscriptionRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

// Lễ tân ghi hội viên vào/ra trung tâm. Chỉ hội viên còn gói hợp lệ mới được vào
@Slf4j
@Service
@RequiredArgsConstructor
public class FacilityAttendanceService {

    private static final ZoneId VIETNAM_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final FacilityAttendanceRepository attendanceRepository;
    private final UserRepository userRepository;
    private final MemberSubscriptionRepository subscriptionRepository;

    @Transactional
    public FacilityAttendanceResponse checkIn(String staffEmail, CheckInRequest request) {
        User staff = userRepository.findByEmail(staffEmail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy người dùng"));
        // Khóa member để hai lễ tân không cùng check-in một người
        User member = userRepository.findByIdForUpdate(request.getMemberId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy hội viên"));
        if (!"MEMBER".equals(member.getRole().getCode())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy hội viên");
        }
        if (!Boolean.TRUE.equals(member.getIsActive())) {
            throw new BusinessRuleException("Tài khoản hội viên này đã bị khóa");
        }
        LocalDate today = LocalDate.now(VIETNAM_ZONE);
        if (subscriptionRepository.findValidActiveSubscription(member.getId(), today).isEmpty()) {
            throw new BusinessRuleException("Hội viên này không có gói thành viên đang dùng");
        }
        if (attendanceRepository.existsByUser_IdAndCheckOutTimeIsNull(member.getId())) {
            throw new BusinessRuleException("Hội viên này đã check-in rồi");
        }

        FacilityAttendance attendance = new FacilityAttendance();
        attendance.setUser(member);
        attendance.setCheckInTime(OffsetDateTime.now());
        attendance.setRecordedBy(staff);
        String notes = request.getNotes() == null ? "" : request.getNotes().trim();
        attendance.setNotes(notes.isEmpty() ? null : notes);
        attendanceRepository.save(attendance);
        log.info("Member {} checked in by staff {}", member.getId(), staff.getId());
        return new FacilityAttendanceResponse(attendance);
    }

    @Transactional
    public FacilityAttendanceResponse checkOut(Long id) {
        FacilityAttendance attendance = attendanceRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy lượt điểm danh"));
        if (attendance.getCheckOutTime() != null) {
            throw new BusinessRuleException("Hội viên này đã check-out rồi");
        }
        attendance.setCheckOutTime(OffsetDateTime.now());
        attendanceRepository.save(attendance);
        return new FacilityAttendanceResponse(attendance);
    }

    // date null = hôm nay (giờ Việt Nam)
    @Transactional(readOnly = true)
    public List<FacilityAttendanceResponse> list(LocalDate date, String name) {
        LocalDate day = date == null ? LocalDate.now(VIETNAM_ZONE) : date;
        OffsetDateTime from = day.atStartOfDay(VIETNAM_ZONE).toOffsetDateTime();
        OffsetDateTime to = day.plusDays(1).atStartOfDay(VIETNAM_ZONE).toOffsetDateTime();
        return attendanceRepository.search(from, to, name == null ? "" : name.trim()).stream()
                .map(FacilityAttendanceResponse::new)
                .toList();
    }
}
