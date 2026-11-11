package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.ClassEvaluationResponse;
import com.swp391.sportcentermanagementsystem_v2.entity.ClassEvaluation;
import com.swp391.sportcentermanagementsystem_v2.entity.ClassRegistration;
import com.swp391.sportcentermanagementsystem_v2.entity.SportClass;
import com.swp391.sportcentermanagementsystem_v2.entity.User;
import com.swp391.sportcentermanagementsystem_v2.exception.BusinessRuleException;
import com.swp391.sportcentermanagementsystem_v2.repository.ClassEvaluationRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.ClassSessionRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.ClassRegistrationRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.SportClassRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

// BR-CLS-08: khi class kết thúc, coach đánh giá từng học viên theo thang A, B, C, D kèm nhận xét. Điểm chỉ để nhận xét,
// không chặn đăng ký.
@Service
@RequiredArgsConstructor
public class ClassEvaluationService {

    private final ClassEvaluationRepository evaluationRepository;
    private final ClassRegistrationRepository registrationRepository;
    private final SportClassRepository sportClassRepository;
    private final UserRepository userRepository;
    private final ClassSessionRepository classSessionRepository;

    // Xếp loại chỉ hiển thị trong lớp đã hoàn thành, ngay trong danh sách lớp phụ trách của coach.
    // Danh sách trả grade/comment đã lưu để coach có thể mở lại và chỉnh sửa.
    @Transactional(readOnly = true)
    public List<ClassEvaluationResponse> listForClass(Long classId, String coachEmail) {
        SportClass sportClass = sportClassRepository.findById(classId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy lớp"));
        requireClassCoach(sportClass, coachEmail);
        requireCompleted(sportClass);

        Map<Long, ClassEvaluation> evaluations = evaluationRepository.findByRegistration_SportClass_Id(classId)
                .stream().collect(Collectors.toMap(e -> e.getRegistration().getId(), Function.identity()));

        return registrationRepository.findBySportClass_IdAndStatusInOrderById(classId, List.of("REGISTERED")).stream()
                .map(registration -> toResponse(registration, sportClass, evaluations.get(registration.getId())))
                .toList();
    }

    // Lưu lần đầu hoặc cập nhật đánh giá đã có. Dữ liệu được lưu trong class_evaluations theo registration_id.
    @Transactional
    public ClassEvaluationResponse evaluate(Long classId, Long registrationId, String grade, String comment,
            String coachEmail) {
        SportClass sportClass = sportClassRepository.findById(classId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy lớp"));
        User coach = requireClassCoach(sportClass, coachEmail);
        requireCompleted(sportClass);

        ClassRegistration registration = registrationRepository.findById(registrationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy học viên trong lớp"));
        if (registration.getSportClass() == null || !registration.getSportClass().getId().equals(classId)) {
            throw new BusinessRuleException("Học viên này không thuộc lớp đang chọn");
        }
        if (!"REGISTERED".equals(registration.getStatus())) {
            throw new BusinessRuleException("Chỉ xếp loại học viên đã hoàn thành khóa học");
        }

        String normalizedGrade = grade == null ? "" : grade.trim().toUpperCase();
        if (!List.of("A", "B", "C", "D").contains(normalizedGrade)) {
            throw new BusinessRuleException("Xếp loại chỉ chấp nhận A, B, C hoặc D");
        }

        ClassEvaluation evaluation = evaluationRepository.findByRegistration_Id(registrationId)
                .orElseGet(ClassEvaluation::new);
        evaluation.setRegistration(registration);
        evaluation.setCoach(coach);
        evaluation.setGrade(normalizedGrade);
        evaluation.setComment(comment == null || comment.isBlank() ? null : comment.trim());
        evaluationRepository.saveAndFlush(evaluation);

        ClassEvaluation saved = evaluationRepository.findByRegistration_Id(registrationId).orElse(evaluation);
        return toResponse(registration, sportClass, saved);
    }

    @Transactional(readOnly = true)
    public List<ClassEvaluationResponse> myEvaluations(String memberEmail) {
        // Không phát hành grade/comment qua Member API theo chính sách hiện tại.
        return List.of();
    }

    private void requireCompleted(SportClass sportClass) {
        if (!"COMPLETED".equals(sportClass.getStatus())) {
            throw new BusinessRuleException("Chỉ hiển thị và xếp loại khi lớp đã hoàn thành");
        }
    }

    // Chỉ coach đang dạy class (kể cả coach được thay giữa lớp) mới đánh giá được
    private User requireClassCoach(SportClass sportClass, String coachEmail) {
        User coach = userRepository.findByEmail(coachEmail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy người dùng"));
        boolean currentCoach = sportClass.getCoach() != null
                && sportClass.getCoach().getId().equals(coach.getId());
        boolean assignedToSession = !currentCoach
                && classSessionRepository.existsBySportClass_IdAndCoach_Id(sportClass.getId(), coach.getId());
        if (!currentCoach && !assignedToSession) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Chỉ coach phụ trách lớp hoặc từng được phân công dạy buổi trong lớp mới được đánh giá học viên");
        }
        return coach;
    }

    private ClassEvaluationResponse toResponse(ClassRegistration r, SportClass sportClass, ClassEvaluation e) {
        return new ClassEvaluationResponse(r.getId(), sportClass.getId(), sportClass.getName(),
                r.getUser().getId(), r.getUser().getFullName(),
                e == null ? null : e.getGrade(), e == null ? null : e.getComment(),
                e == null ? null : e.getCoach().getFullName(), e == null ? null : e.getCreatedAt());
    }
}
