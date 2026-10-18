package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.CreateSubscriptionRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.MemberSubscriptionResponse;
import com.swp391.sportcentermanagementsystem_v2.entity.MemberSubscription;
import com.swp391.sportcentermanagementsystem_v2.entity.MembershipPackage;
import com.swp391.sportcentermanagementsystem_v2.entity.Role;
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

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.any;
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

    // Dựng Member và gói 100.000đ / 1 tháng để thử tạo đơn mua nhiều kỳ.
    private MemberSubscriptionResponse createOrder(int quantity, short packageMonths) {
        Role role = new Role();
        role.setCode("MEMBER");
        User buyer = new User();
        buyer.setId(7L);
        buyer.setRole(role);

        MembershipPackage pack = new MembershipPackage();
        pack.setId(1L);
        pack.setName("Basic");
        pack.setPrice(new BigDecimal("100000"));
        pack.setDurationMonths(packageMonths);
        pack.setTierLevel((short) 1);

        when(userRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(buyer));
        when(packageRepository.findByIdAndIsActiveTrue(1L)).thenReturn(Optional.of(pack));
        when(subscriptionRepository.save(any())).thenAnswer(call -> call.getArgument(0));

        CreateSubscriptionRequest request = new CreateSubscriptionRequest();
        request.setPackageId(1L);
        request.setQuantity(quantity);

        return subscriptionService.createSubscriptionOrder(7L, request, null, "NEW");
    }

    @Test
    void buyingThreePeriodsMultipliesPriceAndMonths() {
        MemberSubscriptionResponse result = createOrder(3, (short) 1);

        assertEquals(new BigDecimal("300000"), result.getTotalAmount());
        assertEquals((short) 3, result.getDurationMonths());
    }

    @Test
    void quantityDefaultsToOne() {
        assertEquals(1, new CreateSubscriptionRequest().getQuantity());
    }

    @Test
    void totalDurationBeyondShortRangeIsRejected() {
        assertThrows(BusinessRuleException.class, () -> createOrder(Integer.MAX_VALUE, (short) 12));
    }
}
