package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.entity.User;
import com.swp391.sportcentermanagementsystem_v2.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.OffsetDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UnverifiedAccountCleanerTest {

    @Mock
    UserRepository userRepository;
    @Mock
    UserDataGuard userDataGuard;
    @Mock
    SystemSettingService settings;

    @InjectMocks
    UnverifiedAccountCleaner cleaner;

    private User empty;
    private User withData;
    private User another;

    private static User account(Long id) {
        User u = new User();
        u.setId(id);
        return u;
    }

    @BeforeEach
    void setUp() {
        when(settings.getInt(anyString(), anyInt())).thenAnswer(inv -> inv.getArgument(1));
        empty = account(1L);
        withData = account(2L);
        another = account(3L);
        when(userRepository.findUnverifiedMembersCreatedBefore(any(OffsetDateTime.class)))
                .thenReturn(List.of(empty, withData, another));
        when(userDataGuard.hasBusinessData(2L)).thenReturn(true);
    }

    @Test
    void emptyAccountsAreDeletedAndAccountsWithDataAreLeftAlone() {
        cleaner.cleanUp();

        verify(userRepository).delete(empty);
        verify(userRepository).delete(another);
        verify(userRepository, never()).delete(withData);
    }

    @Test
    void oneAccountThatFailsDoesNotStopTheOthers() {
        doThrow(new DataIntegrityViolationException("still referenced")).when(userRepository).delete(empty);

        cleaner.cleanUp();

        verify(userRepository).delete(another);
    }
}
