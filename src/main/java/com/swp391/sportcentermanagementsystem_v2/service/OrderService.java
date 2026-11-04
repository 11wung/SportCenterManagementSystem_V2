package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.entity.ClassRegistration;
import com.swp391.sportcentermanagementsystem_v2.entity.CoachSlot;
import com.swp391.sportcentermanagementsystem_v2.entity.MemberSubscription;
import com.swp391.sportcentermanagementsystem_v2.entity.Order;
import com.swp391.sportcentermanagementsystem_v2.entity.User;
import com.swp391.sportcentermanagementsystem_v2.repository.OrderRepository;
import com.swp391.sportcentermanagementsystem_v2.service.PricingService.Quote;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final PricingService pricingService;

    // Tính lại ưu đãi của tier cho đơn chưa trả tiền (BR-BEN-01) rồi ghi vào đơn.
    // Gọi mỗi lần tạo thanh toán mới, nên đổi hạng giữa hai lần thử vẫn đúng
    @Transactional
    public Order applyPricing(Order order, User member) {
        Quote quote = pricingService.quote(member.getId(), kindOf(order), order.getListPrice(),
                LocalDate.now(SportClassService.CENTER_ZONE));
        order.setDiscountKind(quote.discountKind());
        order.setDiscountAmount(quote.discountAmount());
        order.setTotalAmount(quote.total());
        return order;
    }

    private static String kindOf(Order order) {
        if (order.getRegistration() != null) {
            return PricingService.CLASS;
        }
        return order.getCoachSlot() != null ? PricingService.ONE_ON_ONE : PricingService.PACKAGE;
    }

    // Mỗi gói có tối đa một order, tạo lại QR hay trả tiền mặt sau khi tạo QR đều dùng lại order đó.
    // Giá chưa có ưu đãi riêng: total_amount của gói đã gồm giảm theo số tháng và trừ tiền nâng gói
    @Transactional
    public Order getOrCreateForSubscription(MemberSubscription subscription, User member,
            User createdBy, String channel) {
        return orderRepository.findBySubscription_Id(subscription.getId()).orElseGet(() -> {
            Order order = new Order();
            order.setMember(member);
            order.setCreatedBy(createdBy);
            order.setChannel(channel);
            order.setSubscription(subscription);
            order.setListPrice(subscription.getTotalAmount());
            order.setTotalAmount(subscription.getTotalAmount());
            return orderRepository.save(order);
        });
    }

    // Mỗi suất 1-1 có tối đa một order, giá = giá đã chụp lúc đặt
    @Transactional
    public Order getOrCreateForSlot(CoachSlot slot, User member, User createdBy, String channel) {
        return orderRepository.findByCoachSlot_Id(slot.getId()).orElseGet(() -> {
            Order order = new Order();
            order.setMember(member);
            order.setCreatedBy(createdBy);
            order.setChannel(channel);
            order.setCoachSlot(slot);
            order.setListPrice(slot.getPrice());
            order.setTotalAmount(slot.getPrice());
            return orderRepository.save(order);
        });
    }

    // Mỗi đăng ký có tối đa một order, giá = học phí của class
    @Transactional
    public Order getOrCreateForRegistration(ClassRegistration registration, User member,
            User createdBy, String channel) {
        return orderRepository.findByRegistration_Id(registration.getId()).orElseGet(() -> {
            Order order = new Order();
            order.setMember(member);
            order.setCreatedBy(createdBy);
            order.setChannel(channel);
            order.setRegistration(registration);
            order.setListPrice(registration.getSportClass().getTuition());
            order.setTotalAmount(registration.getSportClass().getTuition());
            return orderRepository.save(order);
        });
    }
}
