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
// không chặn đăng ký.
@Slf4j
@Service
@RequiredArgsConstructor
public class ClassEvaluationService {

    private final ClassEvaluationRepository evaluationRepository;
    private final ClassRegistrationRepository registrationRepository;
    private final SportClassRepository sportClassRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;

    // Học viên của class (đã ghi danh đến hết lớp) và đánh giá của họ nếu đã có. Chỉ coach của class xem được
    @Transactional(readOnly = true)
    public List<ClassEvaluationResponse> listForClass(Long classId, String coachEmail) {
        SportClass sportClass = sportClassRepository.findById(classId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy lớp"));
        requireClassCoach(sportClass, coachEmail);

        // Không trả dữ liệu xếp loại trong giai đoạn chức năng đang được ẩn.
        return registrationRepository.findBySportClass_IdAndStatusInOrderById(classId, List.of("REGISTERED")).stream()
                .map(r -> toResponse(r, sportClass, null))
                .toList();
    }

    @Transactional
    public ClassEvaluationResponse evaluate(Long classId, Long registrationId, String grade, String comment,
            String coachEmail) {
        throw new BusinessRuleException("Tính năng xếp loại cuối khóa đang tạm ẩn");
    }

    @Transactional(readOnly = true)
    public List<ClassEvaluationResponse> myEvaluations(String memberEmail) {
        User member = userRepository.findByEmail(memberEmail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy người dùng"));
        // Member không nhận grade/comment/notification khi xếp loại cuối khóa đang tạm ẩn.
        return List.of();
    }

    // Chỉ coach đang dạy class (kể cả coach được thay giữa lớp) mới đánh giá được
    private User requireClassCoach(SportClass sportClass, String coachEmail) {
        User coach = userRepository.findByEmail(coachEmail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy người dùng"));
        if (!sportClass.getCoach().getId().equals(coach.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Chỉ coach của lớp này mới được đánh giá học viên");
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
