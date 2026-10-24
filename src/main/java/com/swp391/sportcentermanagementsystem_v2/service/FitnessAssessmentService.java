package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.AssessmentResultRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.FitnessProgressResponse;
import com.swp391.sportcentermanagementsystem_v2.entity.CoachSlot;
import com.swp391.sportcentermanagementsystem_v2.entity.User;
import com.swp391.sportcentermanagementsystem_v2.exception.BusinessRuleException;
import com.swp391.sportcentermanagementsystem_v2.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

// BR-BEN-03: kết quả đánh giá thể lực là một dòng trong workout_progress (lịch sử số đo cơ thể của Flow 4). Dùng
// JdbcTemplate thay vì entity để không trùng với entity của Flow 4 về sau
@Service
@RequiredArgsConstructor
public class FitnessAssessmentService {

    private final JdbcTemplate jdbcTemplate;
    private final CoachSlotService coachSlotService;
    private final UserRepository userRepository;

    // Coach được gán ghi kết quả sau khi buổi đo đã bắt đầu. Mỗi khung chỉ ghi một lần
    @Transactional
    public FitnessProgressResponse record(String coachEmail, Long slotId, AssessmentResultRequest r) {
        User coach = coachSlotService.activeUser(coachEmail, "COACH", "Only active Coaches can record an assessment");
        CoachSlot slot = coachSlotService.lockSlot(slotId);
        if (!"ASSESSMENT".equals(slot.getKind()) || !"BOOKED".equals(slot.getStatus())) {
            throw new BusinessRuleException("Only a booked assessment slot can have a result");
        }
        if (!slot.getCoach().getId().equals(coach.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This assessment is assigned to another coach");
        }
        if (slot.getStartAt().isAfter(OffsetDateTime.now())) {
            throw new BusinessRuleException("The assessment has not started yet");
        }
        boolean hasNotes = r.getNotes() != null && !r.getNotes().isBlank();
        if (!hasNotes && r.getWeightKg() == null && r.getHeightCm() == null && r.getBodyFatPct() == null
                && r.getMuscleMassKg() == null && r.getRestingHeartRate() == null && r.getFlexibilityCm() == null) {
            throw new BusinessRuleException("Record at least one measurement or a note");
        }
        Integer recorded = jdbcTemplate.queryForObject(
                "select count(*) from workout_progress where assessment_slot_id = ?", Integer.class, slotId);
        if (recorded != null && recorded > 0) {
            throw new BusinessRuleException("The result of this assessment has already been recorded");
        }

        Long id = jdbcTemplate.queryForObject("""
                INSERT INTO workout_progress (member_id, recorded_by, recorded_date, weight_kg, height_cm, body_fat_pct,
                                              muscle_mass_kg, resting_heart_rate, flexibility_cm, notes, assessment_slot_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """, Long.class, slot.getMember().getId(), coach.getId(), LocalDate.now(SportClassService.CENTER_ZONE),
                r.getWeightKg(), r.getHeightCm(), r.getBodyFatPct(), r.getMuscleMassKg(), r.getRestingHeartRate(),
                r.getFlexibilityCm(), hasNotes ? r.getNotes().trim() : null, slotId);
        return historyOf(slot.getMember().getId()).stream().filter(p -> p.getId().equals(id)).findFirst().orElseThrow();
    }

    // Lịch sử số đo của member, mới nhất trước, kèm BMI và chênh lệch so với lần đo gần nhất có cùng chỉ số
    @Transactional(readOnly = true)
    public List<FitnessProgressResponse> myProgress(String memberEmail) {
        User member = userRepository.findByEmail(memberEmail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        return historyOf(member.getId());
    }

    private List<FitnessProgressResponse> historyOf(Long memberId) {
        List<BigDecimal> profileHeight = jdbcTemplate.queryForList(
                "select height_cm from member_profiles where user_id = ? and height_cm is not null", BigDecimal.class, memberId);
        BigDecimal knownHeight = profileHeight.isEmpty() ? null : profileHeight.get(0);

        List<FitnessProgressResponse> ordered = jdbcTemplate.query("""
                SELECT p.id, p.recorded_date, u.full_name AS recorded_by, p.weight_kg, p.height_cm, p.body_fat_pct,
                       p.muscle_mass_kg, p.resting_heart_rate, p.flexibility_cm, p.notes
                FROM workout_progress p LEFT JOIN users u ON u.id = p.recorded_by
                WHERE p.member_id = ?
                ORDER BY p.recorded_date, p.id
                """, (rs, i) -> {
            FitnessProgressResponse p = new FitnessProgressResponse();
            p.setId(rs.getLong("id"));
            p.setRecordedDate(rs.getObject("recorded_date", LocalDate.class));
            p.setRecordedBy(rs.getString("recorded_by"));
            p.setWeightKg(rs.getBigDecimal("weight_kg"));
            p.setHeightCm(rs.getBigDecimal("height_cm"));
            p.setBodyFatPct(rs.getBigDecimal("body_fat_pct"));
            p.setMuscleMassKg(rs.getBigDecimal("muscle_mass_kg"));
            p.setRestingHeartRate(rs.getObject("resting_heart_rate", Integer.class));
            p.setFlexibilityCm(rs.getBigDecimal("flexibility_cm"));
            p.setNotes(rs.getString("notes"));
            return p;
        }, memberId);

        BigDecimal lastWeight = null;
        BigDecimal lastFat = null;
        BigDecimal lastMuscle = null;
        Integer lastHeartRate = null;
        BigDecimal lastFlex = null;
        List<FitnessProgressResponse> result = new ArrayList<>();
        for (FitnessProgressResponse p : ordered) {
            if (p.getHeightCm() != null) {
                knownHeight = p.getHeightCm();
            }
            if (p.getWeightKg() != null && knownHeight != null) {
                BigDecimal meters = knownHeight.divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
                p.setBmi(p.getWeightKg().divide(meters.multiply(meters), 1, RoundingMode.HALF_UP));
            }
            if (p.getWeightKg() != null) {
                p.setWeightChange(lastWeight == null ? null : p.getWeightKg().subtract(lastWeight));
                lastWeight = p.getWeightKg();
            }
            if (p.getBodyFatPct() != null) {
                p.setBodyFatChange(lastFat == null ? null : p.getBodyFatPct().subtract(lastFat));
                lastFat = p.getBodyFatPct();
            }
            if (p.getMuscleMassKg() != null) {
                p.setMuscleMassChange(lastMuscle == null ? null : p.getMuscleMassKg().subtract(lastMuscle));
                lastMuscle = p.getMuscleMassKg();
            }
            if (p.getRestingHeartRate() != null) {
                p.setRestingHeartRateChange(lastHeartRate == null ? null : p.getRestingHeartRate() - lastHeartRate);
                lastHeartRate = p.getRestingHeartRate();
            }
            if (p.getFlexibilityCm() != null) {
                p.setFlexibilityChange(lastFlex == null ? null : p.getFlexibilityCm().subtract(lastFlex));
                lastFlex = p.getFlexibilityCm();
            }
            result.add(p);
        }
        Collections.reverse(result);
        return result;
    }
}
