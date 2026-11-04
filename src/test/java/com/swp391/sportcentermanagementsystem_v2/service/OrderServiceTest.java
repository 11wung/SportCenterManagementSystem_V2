package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.entity.ClassRegistration;
import com.swp391.sportcentermanagementsystem_v2.entity.CoachSlot;
import com.swp391.sportcentermanagementsystem_v2.entity.Order;
import com.swp391.sportcentermanagementsystem_v2.entity.User;
import com.swp391.sportcentermanagementsystem_v2.repository.OrderRepository;
import com.swp391.sportcentermanagementsystem_v2.service.PricingService.Quote;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// OrderService.applyPricing: ghi ưu đãi của tier vào đơn
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderServiceTest {

    @Mock
    OrderRepository orderRepository;
    @Mock
    PricingService pricingService;

    @InjectMocks
    OrderService orderService;

    private User member;
    private Order classOrder;

    @BeforeEach
    void setUp() {
        member = new User();
        member.setId(5L);

        classOrder = new Order();
        classOrder.setId(50L);
        classOrder.setRegistration(new ClassRegistration());
        classOrder.setListPrice(new BigDecimal("1200000"));
        classOrder.setTotalAmount(new BigDecimal("1200000"));
    }

    @Test
    void tierDiscountIsWrittenIntoTheOrder() {
        when(pricingService.quote(eq(5L), eq("CLASS"), eq(classOrder.getListPrice()), any()))
                .thenReturn(new Quote(classOrder.getListPrice(), "TIER", new BigDecimal("60000")));

        Order priced = orderService.applyPricing(classOrder, member);

        assertEquals("TIER", priced.getDiscountKind());
        assertEquals(new BigDecimal("60000"), priced.getDiscountAmount());
        assertEquals(new BigDecimal("1140000"), priced.getTotalAmount());
    }

    @Test
    void oneOnOneOrderIsPricedAsOneOnOne() {
        Order slotOrder = new Order();
        slotOrder.setId(51L);
        slotOrder.setCoachSlot(new CoachSlot());
        slotOrder.setListPrice(new BigDecimal("400000"));
        when(pricingService.quote(eq(5L), eq("ONE_ON_ONE"), any(), any()))
                .thenReturn(new Quote(slotOrder.getListPrice(), "NONE", BigDecimal.ZERO));

        orderService.applyPricing(slotOrder, member);

        verify(pricingService).quote(eq(5L), eq("ONE_ON_ONE"), any(), any());
    }
}
