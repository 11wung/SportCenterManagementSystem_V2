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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClassEvaluationServiceTest {

    @Mock ClassEvaluationRepository evaluationRepository;
    @Mock ClassRegistrationRepository registrationRepository;
    @Mock SportClassRepository sportClassRepository;
    @Mock UserRepository userRepository;
    @Mock ClassSessionRepository classSessionRepository;

    @InjectMocks ClassEvaluationService service;

    private User coach;
    private User member;
    private SportClass sportClass;
    private ClassRegistration registration;

    @BeforeEach
    void setUp() {
        coach = new User();
        coach.setId(10L);
        coach.setEmail("coach@example.com");
        coach.setFullName("Coach");

        member = new User();
        member.setId(20L);
        member.setFullName("Member");

        sportClass = new SportClass();
        sportClass.setId(30L);
        sportClass.setName("Tennis beginner");
        sportClass.setStatus("COMPLETED");
        sportClass.setCoach(coach);

        registration = new ClassRegistration();
        registration.setId(40L);
        registration.setSportClass(sportClass);
        registration.setUser(member);
        registration.setStatus("REGISTERED");

        when(sportClassRepository.findById(30L)).thenReturn(Optional.of(sportClass));
    }

    @Test
    void savesFinalGradeAndCommentForCompletedClass() {
        when(userRepository.findByEmail("coach@example.com")).thenReturn(Optional.of(coach));
        when(registrationRepository.findById(40L)).thenReturn(Optional.of(registration));
        when(evaluationRepository.findByRegistration_Id(40L)).thenReturn(Optional.empty());
        when(evaluationRepository.saveAndFlush(any(ClassEvaluation.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ClassEvaluationResponse result = service.evaluate(30L, 40L, "a", "Tiến bộ tốt", "coach@example.com");

        assertEquals("A", result.getGrade());
        assertEquals("Tiến bộ tốt", result.getComment());
        verify(evaluationRepository).saveAndFlush(any(ClassEvaluation.class));
    }

    @Test
    void listReturnsPreviouslySavedGradeForCompletedClass() {
        when(userRepository.findByEmail("coach@example.com")).thenReturn(Optional.of(coach));
        ClassEvaluation saved = new ClassEvaluation();
        saved.setRegistration(registration);
        saved.setCoach(coach);
        saved.setGrade("B");
        saved.setComment("Ổn định");
        when(evaluationRepository.findByRegistration_SportClass_Id(30L)).thenReturn(List.of(saved));
        when(registrationRepository.findBySportClass_IdAndStatusInOrderById(30L, List.of("REGISTERED")))
                .thenReturn(List.of(registration));

        List<ClassEvaluationResponse> result = service.listForClass(30L, "coach@example.com");

        assertEquals(1, result.size());
        assertEquals("B", result.get(0).getGrade());
        assertEquals("Ổn định", result.get(0).getComment());
    }

    @Test
    void cannotEvaluateBeforeClassIsCompleted() {
        when(userRepository.findByEmail("coach@example.com")).thenReturn(Optional.of(coach));
        sportClass.setStatus("IN_PROGRESS");

        assertThrows(BusinessRuleException.class,
                () -> service.evaluate(30L, 40L, "A", "Comment", "coach@example.com"));
    }
    @Test
    void coachWhoTaughtASessionCanEvaluateTheirCompletedAssignedClass() {
        User previousCoach = new User();
        previousCoach.setId(11L);
        previousCoach.setFullName("Previous Coach");
        when(userRepository.findByEmail("previous@example.com")).thenReturn(Optional.of(previousCoach));
        when(classSessionRepository.existsBySportClass_IdAndCoach_Id(30L, 11L)).thenReturn(true);
        when(registrationRepository.findById(40L)).thenReturn(Optional.of(registration));
        when(evaluationRepository.findByRegistration_Id(40L)).thenReturn(Optional.empty());
        when(evaluationRepository.saveAndFlush(any(ClassEvaluation.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ClassEvaluationResponse result = service.evaluate(30L, 40L, "B", "Từng dạy lớp này", "previous@example.com");

        assertEquals("B", result.getGrade());
        verify(evaluationRepository).saveAndFlush(any(ClassEvaluation.class));
    }

    @Test
    void resubmittingUpdatesTheExistingEvaluation() {
        when(userRepository.findByEmail("coach@example.com")).thenReturn(Optional.of(coach));
        when(registrationRepository.findById(40L)).thenReturn(Optional.of(registration));
        ClassEvaluation existing = new ClassEvaluation();
        existing.setRegistration(registration);
        existing.setCoach(coach);
        existing.setGrade("C");
        existing.setComment("Cần cố gắng");
        when(evaluationRepository.findByRegistration_Id(40L)).thenReturn(Optional.of(existing));
        when(evaluationRepository.saveAndFlush(existing)).thenReturn(existing);

        ClassEvaluationResponse result = service.evaluate(30L, 40L, "A", "Đã tiến bộ", "coach@example.com");

        assertEquals("A", result.getGrade());
        assertEquals("Đã tiến bộ", result.getComment());
        assertEquals("A", existing.getGrade());
        verify(evaluationRepository).saveAndFlush(existing);
    }

}
