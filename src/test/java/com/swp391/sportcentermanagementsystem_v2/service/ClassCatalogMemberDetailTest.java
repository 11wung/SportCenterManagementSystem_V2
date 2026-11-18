package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.entity.User;
import com.swp391.sportcentermanagementsystem_v2.repository.ClassRegistrationRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.SportClassRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collection;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Chi tiết lớp mở từ thời khóa biểu: chỉ hội viên có đăng ký ở lớp đó được xem
class ClassCatalogMemberDetailTest {

    private final SportClassRepository sportClassRepository = mock(SportClassRepository.class);
    private final ClassRegistrationRepository registrationRepository = mock(ClassRegistrationRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final ClassCatalogService service = new ClassCatalogService(sportClassRepository, registrationRepository,
            mock(ClassScheduleService.class), mock(ClassSessionService.class), userRepository);

    private User member() {
        User u = new User();
        u.setId(9L);
        when(userRepository.findByEmail("m@example.com")).thenReturn(Optional.of(u));
        return u;
    }

    @Test
    void aMemberWhoNeverJoinedTheClassCannotSeeIt() {
        member();
        when(registrationRepository.existsBySportClass_IdAndUser_IdAndStatusIn(anyLong(), anyLong(), any(Collection.class)))
                .thenReturn(false);

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.getDetailForMember(5L, "m@example.com"));

        assertEquals(HttpStatus.FORBIDDEN, e.getStatusCode());
        verify(sportClassRepository, never()).findWithDetailsById(anyLong());
    }

    @Test
    void anUnknownMemberIsRejected() {
        when(userRepository.findByEmail("x@example.com")).thenReturn(Optional.empty());

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.getDetailForMember(5L, "x@example.com"));

        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
    }
}
