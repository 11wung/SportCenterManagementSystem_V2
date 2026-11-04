package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.PricingPreviewResponse;
import com.swp391.sportcentermanagementsystem_v2.entity.ClassRegistration;
import com.swp391.sportcentermanagementsystem_v2.entity.CoachSlot;
import com.swp391.sportcentermanagementsystem_v2.exception.BusinessRuleException;
import com.swp391.sportcentermanagementsystem_v2.repository.ClassRegistrationRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.CoachSlotRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

// Tính giá một lần mua sau ưu đãi (BR-BEN-01, BR-PAY-05). Quy tắc làm tròn: mọi khoản giảm theo phần trăm là
// giá gốc x phần trăm / 100, làm tròn HALF_UP về đồng (cột NUMERIC(12,0)). Tổng = giá gốc - khoản giảm nên luôn là số nguyên.
// Ưu đãi duy nhất là giảm của tier, chỉ áp cho lớp và buổi 1-1; gói không có giảm thêm.
@Service
@RequiredArgsConstructor
public class PricingService {

    public static final String CLASS = "CLASS";
    public static final String ONE_ON_ONE = "ONE_ON_ONE";
    public static final String PACKAGE = "PACKAGE";

    public record Quote(BigDecimal listPrice, String discountKind, BigDecimal discountAmount) {

        public BigDecimal total() {
            return listPrice.subtract(discountAmount);
        }
    }

    private final BenefitService benefitService;
    private final ClassRegistrationRepository registrationRepository;
    private final CoachSlotRepository slotRepository;

    public Quote quote(Long memberId, String kind, BigDecimal listPrice, LocalDate today) {
        BigDecimal tier = tierDiscount(memberId, kind, listPrice, today);
        return new Quote(listPrice, tier.signum() > 0 ? "TIER" : "NONE", tier);
    }

    public BigDecimal tierDiscount(Long memberId, String kind, BigDecimal listPrice, LocalDate today) {
        String key = switch (kind) {
            case CLASS -> "tuition_discount_pct";
            case ONE_ON_ONE -> "one_on_one_discount_pct";
            default -> null;
        };
        if (key == null) {
            return BigDecimal.ZERO;
        }
        return percentOf(listPrice, BigDecimal.valueOf(benefitService.intBenefit(memberId, key, today)));
    }

    @Transactional(readOnly = true)
    public PricingPreviewResponse preview(Long memberId, String kind, Long targetId) {
        BigDecimal listPrice = listPriceOf(memberId, kind, targetId);
        Quote q = quote(memberId, kind, listPrice, today());
        return new PricingPreviewResponse(q.listPrice(), q.discountKind(), q.discountAmount(), q.total());
    }

    private BigDecimal listPriceOf(Long memberId, String kind, Long targetId) {
        return switch (kind) {
            case CLASS -> {
                ClassRegistration r = registrationRepository.findById(targetId)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Registration not found!"));
                if (!r.getUser().getId().equals(memberId)) {
                    throw new BusinessRuleException("Registration does not belong to this Member!");
                }
                yield r.getSportClass().getTuition();
            }
            case ONE_ON_ONE -> {
                CoachSlot s = slotRepository.findById(targetId)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Slot not found!"));
                if (s.getMember() == null || !s.getMember().getId().equals(memberId)) {
                    throw new BusinessRuleException("Slot does not belong to this Member!");
                }
                yield s.getPrice();
            }
            default -> throw new BusinessRuleException("Pricing preview supports only CLASS and ONE_ON_ONE!");
        };
    }

    private static BigDecimal percentOf(BigDecimal price, BigDecimal percent) {
        return price.multiply(percent).divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP);
    }

    private static LocalDate today() {
        return LocalDate.now(SportClassService.CENTER_ZONE);
    }
}
