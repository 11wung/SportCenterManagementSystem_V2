package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.CreateVoucherCampaignRequest;
import com.swp391.sportcentermanagementsystem_v2.entity.User;
import com.swp391.sportcentermanagementsystem_v2.exception.BusinessRuleException;
import com.swp391.sportcentermanagementsystem_v2.repository.UserRepository;
import com.swp391.sportcentermanagementsystem_v2.service.VoucherCampaignService.Campaign;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

// Quy tắc nhập mã chiến dịch (redeemProblem) và kiểm tra khi manager tạo chiến dịch
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class VoucherCampaignServiceTest {

    private static final OffsetDateTime NOW = OffsetDateTime.now();

    @Mock
    JdbcTemplate jdbcTemplate;
    @Mock
    UserRepository userRepository;
    @Mock
    VoucherService voucherService;
    @Mock
    AuditService auditService;

    @InjectMocks
    VoucherCampaignService service;

    private static Campaign campaign(Integer maxUses, long startsInDays, long endsInDays) {
        return new Campaign(1L, "PERCENT", BigDecimal.TEN, "CLASS", maxUses,
                NOW.plusDays(startsInDays), NOW.plusDays(endsInDays));
    }

    // ---- nhập mã ----

    @Test
    void codeInsideItsPeriodWithUsesLeftCanBeRedeemed() {
        assertEquals(Optional.empty(), VoucherCampaignService.redeemProblem(campaign(5, -1, 10), NOW, 2, false));
    }

    @Test
    void codeWithoutALimitIsNeverUsedUp() {
        assertEquals(Optional.empty(), VoucherCampaignService.redeemProblem(campaign(null, -1, 10), NOW, 100000, false));
    }

    @Test
    void codeBeforeItsStartIsRejected() {
        assertTrue(VoucherCampaignService.redeemProblem(campaign(5, 1, 10), NOW, 0, false)
                .orElseThrow().contains("not started"));
    }

    @Test
    void codeAfterItsEndIsRejected() {
        assertTrue(VoucherCampaignService.redeemProblem(campaign(5, -10, -1), NOW, 0, false)
                .orElseThrow().contains("ended"));
    }

    @Test
    void memberCannotRedeemTheSameCodeTwice() {
        assertTrue(VoucherCampaignService.redeemProblem(campaign(5, -1, 10), NOW, 1, true)
                .orElseThrow().contains("already"));
    }

    @Test
    void codeWithAllUsesTakenIsRejected() {
        assertTrue(VoucherCampaignService.redeemProblem(campaign(5, -1, 10), NOW, 5, false)
                .orElseThrow().contains("run out"));
    }

    // ---- tạo chiến dịch ----

    private CreateVoucherCampaignRequest request(String kind, String value, String appliesTo, long startsInDays,
            long endsInDays) {
        CreateVoucherCampaignRequest r = new CreateVoucherCampaignRequest();
        r.setCode("TET2027");
        r.setKind(kind);
        r.setValue(new BigDecimal(value));
        r.setAppliesTo(appliesTo);
        r.setStartsAt(NOW.plusDays(startsInDays));
        r.setEndsAt(NOW.plusDays(endsInDays));
        return r;
    }

    @BeforeEach
    void setUp() {
        User manager = new User();
        manager.setId(1L);
        when(userRepository.findByEmail("manager@example.com")).thenReturn(Optional.of(manager));
    }

    @Test
    void endBeforeStartIsRejectedBeforeAnyDatabaseWrite() {
        assertThrows(BusinessRuleException.class,
                () -> service.create("manager@example.com", request("PERCENT", "10", "CLASS", 5, 1)));
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void campaignAlreadyOverIsRejected() {
        assertThrows(BusinessRuleException.class,
                () -> service.create("manager@example.com", request("PERCENT", "10", "CLASS", -10, -1)));
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void percentOverOneHundredIsRejected() {
        assertThrows(BusinessRuleException.class,
                () -> service.create("manager@example.com", request("PERCENT", "150", "CLASS", 0, 10)));
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void freeOneOnOneVoucherCannotApplyToClassesOrPackages() {
        assertThrows(BusinessRuleException.class,
                () -> service.create("manager@example.com", request("FREE_ONE_ON_ONE", "0", "CLASS", 0, 10)));
        assertThrows(BusinessRuleException.class,
                () -> service.create("manager@example.com", request("FREE_ONE_ON_ONE", "0", "PACKAGE", 0, 10)));
        verifyNoInteractions(jdbcTemplate);
    }
}
