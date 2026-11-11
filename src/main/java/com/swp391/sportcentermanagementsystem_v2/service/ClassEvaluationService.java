package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.ClassEvaluationResponse;
import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.BulkEvaluateStudentsRequest.StudentEvaluationItem;
import com.swp391.sportcentermanagementsystem_v2.entity.ClassEvaluation;
import com.swp391.sportcentermanagementsystem_v2.entity.ClassRegistration;
import com.swp391.sportcentermanagementsystem_v2.entity.SportClass;
import com.swp391.sportcentermanagementsystem_v2.entity.User;
import com.swp391.sportcentermanagementsystem_v2.exception.BusinessRuleException;
import com.swp391.sportcentermanagementsystem_v2.repository.ClassEvaluationRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.ClassRegistrationRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.SportClassRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

// BR-CLS-08: Khi lớp kết thúc, coach chấm từng học viên theo thang A, B, C, D và lưu nhận xét.
@Service
@RequiredArgsConstructor
public class ClassEvaluationService {

    private final ClassEvaluationRepository evaluationRepository;
    private final ClassRegistrationRepository registrationRepository;
    private final SportClassRepository sportClassRepository;
    private final UserRepository userRepository;

    @PersistenceContext
    private EntityManager entityManager;

    // Coach xem danh sách học viên cùng điểm/nhận xét đã lưu (nếu có).
    @Transactional(readOnly = true)
    public List<ClassEvaluationResponse> listForClass(Long classId, String coachEmail) {
        SportClass sportClass = sportClassRepository.findById(classId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy lớp"));
        requireClassCoach(sportClass, coachEmail);

        Map<Long, ClassEvaluation> evaluations = new HashMap<>();
        for (ClassEvaluation evaluation : evaluationRepository.findByRegistration_SportClass_Id(classId)) {
            evaluations.put(evaluation.getRegistration().getId(), evaluation);
        }

        return registrationRepository.findBySportClass_IdAndStatusInOrderById(classId, List.of("REGISTERED"))
                .stream()
                .map(registration -> toResponse(registration, sportClass, evaluations.get(registration.getId())))
                .toList();
    }

    // Mỗi đăng ký chỉ được chấm một lần; chỉ chấm sau khi lớp đã COMPLETED.
    @Transactional
    public ClassEvaluationResponse evaluate(Long classId, Long registrationId, String grade, String comment,
            String coachEmail) {
        SportClass sportClass = sportClassRepository.findById(classId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy lớp"));
        User coach = requireClassCoach(sportClass, coachEmail);

        if (!"COMPLETED".equals(sportClass.getStatus())) {
            throw new BusinessRuleException("Chỉ được xếp loại khi lớp học đã kết thúc");
        }

        ClassRegistration registration = registrationRepository.findById(registrationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy đăng ký học"));
        if (!registration.getSportClass().getId().equals(classId)) {
            throw new BusinessRuleException("Học viên không thuộc lớp này");
        }
        if (!"REGISTERED".equals(registration.getStatus())) {
            throw new BusinessRuleException("Chỉ được đánh giá học viên đã đăng ký lớp thành công");
        }
        if (evaluationRepository.existsByRegistration_Id(registrationId)) {
            throw new BusinessRuleException("Học viên này đã được xếp loại cuối khóa");
        }

        ClassEvaluation evaluation = new ClassEvaluation();
        evaluation.setRegistration(registration);
        evaluation.setCoach(coach);
        evaluation.setGrade(grade);
        evaluation.setComment(comment);
        evaluation = evaluationRepository.saveAndFlush(evaluation);
        entityManager.refresh(evaluation);

        return toResponse(registration, sportClass, evaluation);
    }

    // Lưu hàng loạt: tạo mới hoặc cập nhật đánh giá đã có của từng học viên.
    @Transactional
    public List<ClassEvaluationResponse> evaluateAll(Long classId, List<StudentEvaluationItem> items,
            String coachEmail) {
        SportClass sportClass = sportClassRepository.findById(classId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy lớp"));
        User coach = requireClassCoach(sportClass, coachEmail);

        if (!"COMPLETED".equals(sportClass.getStatus())) {
            throw new BusinessRuleException("Chỉ được xếp loại khi lớp học đã kết thúc");
        }

        java.util.Set<Long> registrationIds = new java.util.HashSet<>();
        for (StudentEvaluationItem item : items) {
            if (!registrationIds.add(item.getRegistrationId())) {
                throw new BusinessRuleException("Danh sách chứa học viên bị trùng");
            }
        }

        List<ClassRegistration> classRegistrations = registrationRepository
                .findBySportClass_IdAndStatusInOrderById(classId, List.of("REGISTERED"));
        java.util.Set<Long> expectedRegistrationIds = new java.util.HashSet<>();
        for (ClassRegistration registration : classRegistrations) {
            expectedRegistrationIds.add(registration.getId());
        }
        if (!registrationIds.equals(expectedRegistrationIds)) {
            throw new BusinessRuleException("Vui lòng gửi đầy đủ đánh giá của tất cả học viên trong lớp");
        }

        Map<Long, ClassEvaluation> existingEvaluations = new HashMap<>();
        for (ClassEvaluation evaluation : evaluationRepository.findByRegistration_SportClass_Id(classId)) {
            existingEvaluations.put(evaluation.getRegistration().getId(), evaluation);
        }

        List<ClassEvaluationResponse> responses = new java.util.ArrayList<>();
        for (StudentEvaluationItem item : items) {
            ClassRegistration registration = registrationRepository.findById(item.getRegistrationId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                            "Không tìm thấy đăng ký học: " + item.getRegistrationId()));

            if (!registration.getSportClass().getId().equals(classId)) {
                throw new BusinessRuleException("Học viên không thuộc lớp này: " + item.getRegistrationId());
            }
            if (!"REGISTERED".equals(registration.getStatus())) {
                throw new BusinessRuleException("Chỉ được đánh giá học viên đã đăng ký lớp thành công");
            }

            ClassEvaluation evaluation = existingEvaluations.get(item.getRegistrationId());
            if (evaluation == null) {
                evaluation = new ClassEvaluation();
                evaluation.setRegistration(registration);
            }
            evaluation.setCoach(coach);
            evaluation.setGrade(item.getGrade());
            evaluation.setComment(item.getComment());
            evaluation = evaluationRepository.saveAndFlush(evaluation);
            entityManager.refresh(evaluation);
            existingEvaluations.put(item.getRegistrationId(), evaluation);
            responses.add(toResponse(registration, sportClass, evaluation));
        }
        return responses;
    }

    // Member xem lại kết quả đánh giá của chính mình.
    @Transactional(readOnly = true)
    public List<ClassEvaluationResponse> myEvaluations(String memberEmail) {
        User member = userRepository.findByEmail(memberEmail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy người dùng"));

        return evaluationRepository.findByRegistration_User_IdOrderByCreatedAtDesc(member.getId())
                .stream()
                .map(evaluation -> toResponse(evaluation.getRegistration(),
                        evaluation.getRegistration().getSportClass(), evaluation))
                .toList();
    }

    private User requireClassCoach(SportClass sportClass, String coachEmail) {
        User coach = userRepository.findByEmail(coachEmail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy người dùng"));
        if (sportClass.getCoach() == null || !sportClass.getCoach().getId().equals(coach.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Chỉ coach phụ trách lớp này mới được đánh giá học viên");
        }
        return coach;
    }

    private ClassEvaluationResponse toResponse(ClassRegistration registration, SportClass sportClass,
            ClassEvaluation evaluation) {
        return new ClassEvaluationResponse(registration.getId(), sportClass.getId(), sportClass.getName(),
                registration.getUser().getId(), registration.getUser().getFullName(),
                evaluation == null ? null : evaluation.getGrade(),
                evaluation == null ? null : evaluation.getComment(),
                evaluation == null ? null : evaluation.getCoach().getFullName(),
                evaluation == null ? null : evaluation.getCreatedAt());
    }
}
