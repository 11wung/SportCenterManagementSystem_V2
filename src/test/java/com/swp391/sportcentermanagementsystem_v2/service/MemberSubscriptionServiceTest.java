package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.entity.MemberSubscription;
import com.swp391.sportcentermanagementsystem_v2.entity.User;
import com.swp391.sportcentermanagementsystem_v2.exception.BusinessRuleException;
import com.swp391.sportcentermanagementsystem_v2.repository.MemberSubscriptionRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.MembershipPackageRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.PaymentRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MemberSubscriptionServiceTest {

    @Mock
    MemberSubscriptionRepository subscriptionRepository;
    @Mock
    UserRepository userRepository;
    @Mock
    MembershipPackageRepository packageRepository;
    @Mock
    PaymentRepository paymentRepository;

    @InjectMocks
    MemberSubscriptionService subscriptionService;

    private MemberSubscription order;

    @BeforeEach
    void setUp() {
        User member = new User();
        member.setId(5L);

        order = new MemberSubscription();
        order.setId(10L);
        order.setUser(member);
        order.setStatus("PENDING");

        when(userRepository.findByIdForUpdate(5L)).thenReturn(Optional.of(member));
        when(subscriptionRepository.findById(10L)).thenReturn(Optional.of(order));
        when(paymentRepository.existsBySubscription_IdAndStatusIn(anyLong(), anyList())).thenReturn(false);
    }

    @Test
    void memberCancelsOwnPendingOrder() {
        subscriptionService.cancelPendingOrder(5L, 10L);

        assertEquals("CANCELED", order.getStatus());
    }

    @Test
    void memberCannotCancelSomeoneElsesOrder() {
        User other = new User();
        other.setId(6L);
        when(userRepository.findByIdForUpdate(6L)).thenReturn(Optional.of(other));

        assertThrows(BusinessRuleException.class,
                () -> subscriptionService.cancelPendingOrder(6L, 10L));

        assertEquals("PENDING", order.getStatus());
    }

    @Test
    void orderWithPaymentInProgressCannotBeCanceled() {
        when(paymentRepository.existsBySubscription_IdAndStatusIn(anyLong(), anyList())).thenReturn(true);

        assertThrows(BusinessRuleException.class,
                () -> subscriptionService.cancelPendingOrder(5L, 10L));

        assertEquals("PENDING", order.getStatus());
    }

    @Test
    void onlyPendingOrdersCanBeCanceled() {
        order.setStatus("ACTIVE");

        assertThrows(BusinessRuleException.class,
                () -> subscriptionService.cancelPendingOrder(5L, 10L));

        assertEquals("ACTIVE", order.getStatus());
    }
}
