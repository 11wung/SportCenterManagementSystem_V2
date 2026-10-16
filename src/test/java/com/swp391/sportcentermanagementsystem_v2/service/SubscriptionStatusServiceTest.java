package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.entity.MemberSubscription;
import com.swp391.sportcentermanagementsystem_v2.entity.User;
import com.swp391.sportcentermanagementsystem_v2.repository.MemberSubscriptionRepository;
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
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SubscriptionStatusServiceTest {

    @Mock
    MemberSubscriptionRepository subscriptionRepository;
    @Mock
    UserRepository userRepository;
    @Mock
    PaymentRepository paymentRepository;

    @InjectMocks
    SubscriptionStatusService statusService;

    private MemberSubscription subscription;

    @BeforeEach
    void setUp() {
        User member = new User();
        member.setId(5L);

        subscription = new MemberSubscription();
        subscription.setId(10L);
        subscription.setUser(member);
        subscription.setStatus("PENDING");

        when(subscriptionRepository.findById(10L)).thenReturn(Optional.of(subscription));
        when(subscriptionRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(subscription));
        when(userRepository.findByIdForUpdate(5L)).thenReturn(Optional.of(member));
        when(paymentRepository.existsBySubscription_IdAndStatusIn(anyLong(), anyList())).thenReturn(false);
    }

    @Test
    void stalePendingOrderWithoutPaymentIsCanceled() {
        statusService.cancelStalePendingOrder(10L);

        assertEquals("CANCELED", subscription.getStatus());
    }

    @Test
    void stalePendingOrderWithActivePaymentIsKept() {
        // Payment đang xử lý hoặc đã thu: không được hủy đơn.
        when(paymentRepository.existsBySubscription_IdAndStatusIn(anyLong(), anyList())).thenReturn(true);

        statusService.cancelStalePendingOrder(10L);

        assertEquals("PENDING", subscription.getStatus());
    }

    @Test
    void orderPaidInTheMeantimeIsKept() {
        // Đơn vừa được thanh toán giữa lúc job đọc danh sách và lúc hủy.
        subscription.setStatus("ACTIVE");

        statusService.cancelStalePendingOrder(10L);

        assertEquals("ACTIVE", subscription.getStatus());
    }
}
