package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.entity.MemberSubscription;
import com.swp391.sportcentermanagementsystem_v2.entity.PurchaseOrder;
import com.swp391.sportcentermanagementsystem_v2.entity.User;
import com.swp391.sportcentermanagementsystem_v2.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;

    // Mỗi gói có tối đa một order, tạo lại QR hay trả tiền mặt sau khi tạo QR đều dùng lại order đó.
    // Giá chưa có ưu đãi riêng: total_amount của gói đã gồm giảm theo số tháng và trừ tiền nâng gói
    @Transactional
    public PurchaseOrder getOrCreateForSubscription(MemberSubscription subscription, User member,
            User createdBy, String channel) {
        return orderRepository.findBySubscription_Id(subscription.getId()).orElseGet(() -> {
            PurchaseOrder order = new PurchaseOrder();
            order.setMember(member);
            order.setCreatedBy(createdBy);
            order.setChannel(channel);
            order.setSubscription(subscription);
            order.setListPrice(subscription.getTotalAmount());
            order.setTotalAmount(subscription.getTotalAmount());
            return orderRepository.save(order);
        });
    }
}
