package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.ClassEvaluationResponse;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

// BR-CLS-08: khi class kết thúc, coach đánh giá từng học viên theo thang A, B, C, D kèm nhận xét. Điểm chỉ để nhận xét,
// không chặn đăng ký. BR-VCH-02: đánh giá xong thì học viên nhận voucher "Hoàn thành"
@Slf4j
@Service
@RequiredArgsConstructor
public class ClassEvaluationService {

    private final ClassEvaluationRepository evaluationRepository;
    private final ClassRegistrationRepository registrationRepository;
    private final SportClassRepository sportClassRepository;
    private final UserRepository userRepository;
    private final VoucherService voucherService;
    private final NotificationService notificationService;

    // Học viên của class (đã ghi danh đến hết lớp) và đánh giá của họ nếu đã có. Chỉ coach của class xem được
    @Transactional(readOnly = true)
    public List<ClassEvaluationResponse> listForClass(Long classId, String coachEmail) {
        SportClass sportClass = sportClassRepository.findById(classId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Class not found"));
        requireClassCoach(sportClass, coachEmail);

        Map<Long, ClassEvaluation> byRegistration = evaluationRepository.findByRegistration_SportClass_Id(classId).stream()
                .collect(Collectors.toMap(e -> e.getRegistration().getId(), Function.identity()));
        return registrationRepository.findBySportClass_IdAndStatusInOrderById(classId, List.of("REGISTERED")).stream()
                .map(r -> toResponse(r, sportClass, byRegistration.get(r.getId())))
                .toList();
    }

    @Transactional
    public ClassEvaluationResponse evaluate(Long classId, Long registrationId, String grade, String comment,
            String coachEmail) {
        SportClass sportClass = sportClassRepository.findByIdForUpdate(classId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Class not found"));
        User coach = requireClassCoach(sportClass, coachEmail);
        if (!"COMPLETED".equals(sportClass.getStatus())) {
            throw new BusinessRuleException("A class can be evaluated only after it is completed (status: "
                    + sportClass.getStatus() + ")");
        }

        ClassRegistration registration = registrationRepository.findByIdForUpdate(registrationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Registration not found"));
        if (!registration.getSportClass().getId().equals(classId)) {
            throw new BusinessRuleException("This registration does not belong to the class");
        }
        if (!"REGISTERED".equals(registration.getStatus())) {
            throw new BusinessRuleException("Only students who stayed in the class can be evaluated (status: "
                    + registration.getStatus() + ")");
        }
        if (evaluationRepository.existsByRegistration_Id(registrationId)) {
            throw new BusinessRuleException("This student has already been evaluated");
        }

        ClassEvaluation evaluation = new ClassEvaluation();
        evaluation.setRegistration(registration);
        evaluation.setCoach(coach);
        evaluation.setGrade(grade);
        evaluation.setComment(comment);
        evaluationRepository.saveAndFlush(evaluation);

        voucherService.issueCompletion(registration.getUser().getId());
        notificationService.send(registration.getUser().getId(), "ANNOUNCEMENT", "Class evaluation",
                "Your coach evaluated you in " + sportClass.getName() + ": grade " + grade + ".");
        return toResponse(registration, sportClass, evaluation);
    }

    @Transactional(readOnly = true)
    public List<ClassEvaluationResponse> myEvaluations(String memberEmail) {
        User member = userRepository.findByEmail(memberEmail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        return evaluationRepository.findByRegistration_User_IdOrderByCreatedAtDesc(member.getId()).stream()
                .map(e -> toResponse(e.getRegistration(), e.getRegistration().getSportClass(), e))
                .toList();
    }

    // Chỉ coach đang dạy class (kể cả coach được thay giữa lớp) mới đánh giá được
    private User requireClassCoach(SportClass sportClass, String coachEmail) {
        User coach = userRepository.findByEmail(coachEmail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        if (!sportClass.getCoach().getId().equals(coach.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the coach of this class can evaluate its students");
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
