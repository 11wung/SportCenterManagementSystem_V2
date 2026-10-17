package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.MemberStatsResponse;
import com.swp391.sportcentermanagementsystem_v2.repository.MemberSubscriptionRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MemberStatsServiceTest {

    @Mock
    UserRepository userRepository;

    @Mock
    MemberSubscriptionRepository subscriptionRepository;

    @InjectMocks
    MemberStatsService statsService;

    private record PackageRow(String packageName, long memberCount)
            implements MemberSubscriptionRepository.PackageCount {

        public String getPackageName() {
            return packageName;
        }

        public long getMemberCount() {
            return memberCount;
        }
    }

    @Test
    void mapsRepositoryCountsIntoTheResponse() {
        when(userRepository.countByRole_Code("MEMBER")).thenReturn(10L);
        when(userRepository.countByRole_CodeAndIsActive("MEMBER", true)).thenReturn(8L);
        when(userRepository.countByRole_CodeAndIsActive("MEMBER", false)).thenReturn(2L);
        when(userRepository.countByRole_CodeAndCreatedAtGreaterThanEqual(eq("MEMBER"), any())).thenReturn(3L);
        when(userRepository.countActiveMembersWithoutPackage()).thenReturn(4L);
        when(subscriptionRepository.countMembersWithValidActive(any())).thenReturn(5L);
        when(subscriptionRepository.countMembersByStatus("SCHEDULED")).thenReturn(1L);
        when(subscriptionRepository.countMembersByStatus("PENDING")).thenReturn(2L);
        when(subscriptionRepository.countMembersExpiringBetween(any(), any())).thenReturn(6L);
        when(subscriptionRepository.countValidActiveByPackage(any()))
                .thenReturn(List.of(new PackageRow("Basic", 3), new PackageRow("Plus", 2)));

        MemberStatsResponse result = statsService.getStats(7);

        assertEquals(10, result.getTotalMembers());
        assertEquals(8, result.getActiveAccounts());
        assertEquals(2, result.getLockedAccounts());
        assertEquals(3, result.getNewMembersLast30Days());
        assertEquals(5, result.getMembersWithActivePackage());
        assertEquals(1, result.getMembersWithScheduledPackage());
        assertEquals(2, result.getMembersWithPendingOrder());
        assertEquals(4, result.getMembersWithoutPackage());
        assertEquals(6, result.getExpiringSoon());
        assertEquals(7, result.getExpiringWithinDays());
        assertEquals("Basic", result.getActiveByPackage().get(0).getLabel());
        assertEquals(3, result.getActiveByPackage().get(0).getCount());
        assertEquals("Plus", result.getActiveByPackage().get(1).getLabel());
    }

    @Test
    void expiringWindowStartsTodayAndEndsNDaysLaterInVietnamTime() {
        when(subscriptionRepository.countValidActiveByPackage(any())).thenReturn(List.of());

        statsService.getStats(10);

        LocalDate today = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"));
        ArgumentCaptor<LocalDate> from = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<LocalDate> until = ArgumentCaptor.forClass(LocalDate.class);
        verify(subscriptionRepository).countMembersExpiringBetween(from.capture(), until.capture());

        assertEquals(today, from.getValue());
        assertEquals(today.plusDays(10), until.getValue());
    }

    @Test
    void expiringWithinDaysOutsideOneToNinetyIsRejected() {
        for (int invalid : new int[] { 0, -1, 91 }) {
            ResponseStatusException ex = assertThrows(
                    ResponseStatusException.class, () -> statsService.getStats(invalid));
            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        }
    }

    @Test
    void boundariesOneAndNinetyAreAllowed() {
        when(subscriptionRepository.countValidActiveByPackage(any())).thenReturn(List.of());

        assertEquals(1, statsService.getStats(1).getExpiringWithinDays());
        assertEquals(90, statsService.getStats(90).getExpiringWithinDays());
    }
}
