package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.entity.ClassSession;
import com.swp391.sportcentermanagementsystem_v2.entity.CoachProfile;
import com.swp391.sportcentermanagementsystem_v2.entity.Course;
import com.swp391.sportcentermanagementsystem_v2.entity.Role;
import com.swp391.sportcentermanagementsystem_v2.entity.SportCategory;
import com.swp391.sportcentermanagementsystem_v2.entity.SportClass;
import com.swp391.sportcentermanagementsystem_v2.entity.User;
import com.swp391.sportcentermanagementsystem_v2.repository.ClassSessionRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.CoachProfileRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.SportClassRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

// Coach cả lớp nghỉ việc: chỉ buổi đang là coach cũ được chuyển, buổi manager gán riêng cho coach khác giữ nguyên
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReplaceCoachTest {

    @Mock
    SportClassRepository sportClassRepository;
    @Mock
    UserRepository userRepository;
    @Mock
    CoachProfileRepository coachProfileRepository;
    @Mock
    ClassSessionRepository classSessionRepository;
    @Mock
    CoachSlotService coachSlotService;
    @Mock
    AuditService auditService;
    @Mock
    NotificationService notificationService;

    @InjectMocks
    SportClassService service;

    private static User user(long id, String role) {
        Role r = new Role();
        r.setCode(role);
        User u = new User();
        u.setId(id);
        u.setRole(r);
        u.setIsActive(true);
        u.setEmail("u" + id + "@example.com");
        u.setFullName("User " + id);
        return u;
    }

    private static ClassSession session(int no, User coach) {
        ClassSession s = new ClassSession();
        s.setSessionNo(no);
        s.setSessionDate(LocalDate.now().plusDays(no));
        s.setStartTime(LocalTime.of(10, 0));
        s.setEndTime(LocalTime.of(11, 45));
        s.setCoach(coach);
        return s;
    }

    @Test
    void sessionAssignedToAnotherCoachKeepsItsCoach() {
        User manager = user(1, "MANAGER");
        User oldCoach = user(10, "COACH");
        User overrideCoach = user(11, "COACH");
        User newCoach = user(12, "COACH");

        SportCategory category = new SportCategory();
        category.setId(7L);
        Course course = new Course();
        course.setCategory(category);
        SportClass sportClass = new SportClass();
        sportClass.setStatus("OPEN");
        sportClass.setCoach(oldCoach);
        sportClass.setCourse(course);

        CoachProfile profile = new CoachProfile();
        profile.setSports(Set.of(category));

        ClassSession ofOldCoach = session(1, oldCoach);
        ClassSession ofOverrideCoach = session(2, overrideCoach);

        when(userRepository.findByEmail("manager@example.com")).thenReturn(Optional.of(manager));
        when(sportClassRepository.findByIdForUpdate(5L)).thenReturn(Optional.of(sportClass));
        when(userRepository.findById(12L)).thenReturn(Optional.of(newCoach));
        when(coachProfileRepository.findById(12L)).thenReturn(Optional.of(profile));
        when(classSessionRepository.findBySportClassIdOrderBySessionNo(5L))
                .thenReturn(List.of(ofOldCoach, ofOverrideCoach));

        service.replaceCoach(5L, 12L, "resigned", "manager@example.com");

        assertEquals(newCoach, ofOldCoach.getCoach());
        assertEquals(overrideCoach, ofOverrideCoach.getCoach());
        assertEquals(newCoach, sportClass.getCoach());
    }
}
