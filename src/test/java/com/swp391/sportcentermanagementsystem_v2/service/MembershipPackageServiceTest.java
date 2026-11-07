package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.CreateMembershipPackageRequest;
import com.swp391.sportcentermanagementsystem_v2.entity.MembershipPackage;
import com.swp391.sportcentermanagementsystem_v2.exception.BusinessRuleException;
import com.swp391.sportcentermanagementsystem_v2.repository.MemberSubscriptionRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.MembershipPackageRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MembershipPackageServiceTest {

    @Mock MembershipPackageRepository packages;
    @Mock MemberSubscriptionRepository subscriptions;
    @Mock EntityManager entityManager;
    @InjectMocks MembershipPackageService service;

    private CreateMembershipPackageRequest request(int tier) {
        CreateMembershipPackageRequest r = new CreateMembershipPackageRequest();
        r.setCode("VANG");
        r.setName("Vàng");
        r.setTierLevel((short) tier);
        r.setMonthlyPrice(BigDecimal.valueOf(900000));
        return r;
    }

    @Test
    void creatingOnATakenTierShiftsSnapshotsBeforePackages() {
        when(packages.existsByTierLevel((short) 3)).thenReturn(true);
        when(packages.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));

        service.createPackage(request(3));

        InOrder order = inOrder(subscriptions, packages);
        order.verify(subscriptions).shiftTierSnapshotsFrom((short) 3);
        order.verify(packages).liftFrom((short) 3);
        order.verify(packages).landLifted();
    }

    @Test
    void creatingOnAFreeTierShiftsNothing() {
        when(packages.existsByTierLevel((short) 5)).thenReturn(false);
        when(packages.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));

        service.createPackage(request(5));

        verify(packages, never()).liftFrom((short) 5);
        verify(subscriptions, never()).shiftTierSnapshotsFrom((short) 5);
    }

    @Test
    void aSoldPackageCannotBeDeleted() {
        when(packages.findById(7L)).thenReturn(Optional.of(new MembershipPackage()));
        when(subscriptions.existsByMembershipPackageId(7L)).thenReturn(true);

        assertThrows(BusinessRuleException.class, () -> service.deletePackage(7L));
        verify(packages, never()).delete(any());
    }

    @Test
    void anUnsoldPackageIsDeleted() {
        MembershipPackage p = new MembershipPackage();
        p.setTierLevel((short) 2);
        when(packages.findById(8L)).thenReturn(Optional.of(p));
        when(subscriptions.existsByMembershipPackageId(8L)).thenReturn(false);

        service.deletePackage(8L);

        // Hạng trống ở giữa được lấp: snapshot hạ trước, rồi gói bị xóa, rồi các hạng cao hơn tụt xuống
        InOrder order = inOrder(subscriptions, packages);
        order.verify(subscriptions).shiftTierSnapshotsDownAbove((short) 2);
        order.verify(packages).delete(p);
        order.verify(packages).liftAbove((short) 2);
        order.verify(packages).landLoweredByOne();
    }

    private MembershipPackage pkg(long id, String name, int tier, int price, int tuition) {
        MembershipPackage p = new MembershipPackage();
        p.setId(id);
        p.setName(name);
        p.setTierLevel((short) tier);
        p.setMonthlyPrice(BigDecimal.valueOf(price));
        p.setTuitionDiscountPct((short) tuition);
        return p;
    }

    @Test
    void aPackageInsertedBetweenTwoTiersCannotBeWorseThanTheLowerOne() {
        when(packages.findAll()).thenReturn(java.util.List.of(pkg(1, "Thap", 3, 500000, 10), pkg(2, "Cao", 4, 1500000, 20)));
        CreateMembershipPackageRequest r = request(4);
        r.setTuitionDiscountPct((short) 5); // thấp hơn hạng 3 (10%)

        BusinessRuleException e = assertThrows(BusinessRuleException.class, () -> service.createPackage(r));

        org.junit.jupiter.api.Assertions.assertTrue(e.getMessage().contains("từ 10% trở lên"));
        verify(packages, never()).saveAndFlush(any());
    }

    @Test
    void aPackageBetweenTwoTiersWithMiddleValuesIsAccepted() {
        when(packages.findAll()).thenReturn(java.util.List.of(pkg(1, "Thap", 3, 500000, 10), pkg(2, "Cao", 4, 1500000, 20)));
        when(packages.existsByTierLevel((short) 4)).thenReturn(true);
        when(packages.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        CreateMembershipPackageRequest r = request(4);
        r.setTuitionDiscountPct((short) 15);

        service.createPackage(r);

        verify(packages).saveAndFlush(any());
    }
}
