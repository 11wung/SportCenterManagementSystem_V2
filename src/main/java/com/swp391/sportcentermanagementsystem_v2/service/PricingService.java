package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.PricingPreviewResponse;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.VoucherListResponse;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.VoucherResponse;
import com.swp391.sportcentermanagementsystem_v2.entity.ClassRegistration;
import com.swp391.sportcentermanagementsystem_v2.entity.CoachSlot;
import com.swp391.sportcentermanagementsystem_v2.exception.BusinessRuleException;
import com.swp391.sportcentermanagementsystem_v2.repository.ClassRegistrationRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.CoachSlotRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.OrderRepository;
import com.swp391.sportcentermanagementsystem_v2.service.VoucherService.Voucher;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

// Tính giá một lần mua sau ưu đãi (BR-BEN-01, BR-PAY-05). Quy tắc làm tròn: mọi khoản giảm theo phần trăm là
// giá gốc x phần trăm / 100, làm tròn HALF_UP về đồng (cột NUMERIC(12,0)). Tổng = giá gốc - khoản giảm nên luôn là số nguyên.
// Giảm của tier chỉ áp cho lớp và buổi 1-1; gói chỉ có voucher. Có cả giảm tier lẫn voucher thì lấy khoản lớn hơn,
// bằng nhau thì dùng ưu đãi của gói để giữ lại voucher.
@Service
@RequiredArgsConstructor
public class PricingService {

    public static final String CLASS = "CLASS";
    public static final String ONE_ON_ONE = "ONE_ON_ONE";
    public static final String PACKAGE = "PACKAGE";

    public record Quote(BigDecimal listPrice, BigDecimal tierDiscount, BigDecimal voucherDiscount,
            String discountKind, BigDecimal discountAmount, Long voucherId) {

        public BigDecimal total() {
            return listPrice.subtract(discountAmount);
        }
    }

    private final BenefitService benefitService;
    private final VoucherService voucherService;
    private final ClassRegistrationRepository registrationRepository;
    private final CoachSlotRepository slotRepository;
    private final OrderRepository orderRepository;

    public Quote quote(Long memberId, String kind, BigDecimal listPrice, Voucher voucher, LocalDate today) {
        BigDecimal tier = tierDiscount(memberId, kind, listPrice, today);
        BigDecimal byVoucher = voucher == null ? BigDecimal.ZERO : voucherDiscount(voucher, listPrice);
        if (byVoucher.compareTo(tier) > 0) {
            return new Quote(listPrice, tier, byVoucher, "VOUCHER", byVoucher, voucher.id());
        }
        return new Quote(listPrice, tier, byVoucher, tier.signum() > 0 ? "TIER" : "NONE", tier, null);
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

    public BigDecimal voucherDiscount(Voucher voucher, BigDecimal listPrice) {
        return switch (voucher.kind()) {
            case "PERCENT" -> percentOf(listPrice, voucher.value());
            case "AMOUNT" -> voucher.value().min(listPrice);
            case "FREE_ONE_ON_ONE" -> listPrice;
            default -> BigDecimal.ZERO;
        };
    }

    // Lý do voucher không dùng được cho member và loại mua này; rỗng nếu dùng được
    public Optional<String> unusableReason(Voucher voucher, Long memberId, String kind) {
        if (!voucher.userId().equals(memberId)) {
            return Optional.of("Voucher does not belong to this Member!");
        }
        if (voucher.usedAt() != null) {
            return Optional.of("Voucher has already been used!");
        }
        if (!voucher.expiresAt().isAfter(OffsetDateTime.now())) {
            return Optional.of("Voucher has expired!");
        }
        if (!voucher.appliesTo().equals(kind)) {
            return Optional.of("Voucher does not apply to this purchase!");
        }
        return Optional.empty();
    }

    @Transactional(readOnly = true)
    public PricingPreviewResponse preview(Long memberId, String kind, Long targetId, Long voucherId) {
        BigDecimal listPrice = listPriceOf(memberId, kind, targetId);
        Voucher voucher = null;
        if (voucherId != null) {
            voucher = voucherService.findById(voucherId)
                    .orElseThrow(() -> new BusinessRuleException("Voucher not found!"));
            unusableReason(voucher, memberId, kind).ifPresent(msg -> {
                throw new BusinessRuleException(msg);
            });
            if (voucherService.isHeldByOtherOrder(voucherId, orderIdOf(kind, targetId))) {
                throw new BusinessRuleException("Voucher is being used for another payment!");
            }
        }
        Quote q = quote(memberId, kind, listPrice, voucher, today());
        return new PricingPreviewResponse(q.listPrice(), q.tierDiscount(), q.voucherDiscount(), q.discountKind(),
                q.discountAmount(), q.voucherId(), q.total());
    }

    // Danh sách voucher kiểu chọn voucher: có đơn đang xét (targetId) thì xếp theo số tiền giảm thực tế giảm dần,
    // voucher không dùng được xếp cuối; chưa có đơn thì xếp theo mức giảm của voucher (phần trăm trước, rồi số tiền,
    // rồi buổi 1-1 miễn phí). Hạn dùng gần hơn được ưu tiên khi bằng nhau
    @Transactional(readOnly = true)
    public VoucherListResponse vouchers(Long memberId, String kind, Long targetId) {
        if (targetId != null && kind == null) {
            throw new BusinessRuleException("kind is required when targetId is given!");
        }
        BigDecimal listPrice = targetId == null ? null : listPriceOf(memberId, kind, targetId);
        Long orderId = targetId == null ? -1L : orderIdOf(kind, targetId);
        BigDecimal tier = listPrice == null ? null : tierDiscount(memberId, kind, listPrice, today());

        List<VoucherResponse> items = voucherService.findUsable(memberId, kind).stream().map(v -> {
            BigDecimal discount = listPrice == null ? null : voucherDiscount(v, listPrice);
            String reason = voucherService.isHeldByOtherOrder(v.id(), orderId)
                    ? "Voucher is being used for another payment!" : null;
            return new VoucherResponse(v.id(), v.source(), v.kind(), v.value(), v.appliesTo(), v.expiresAt(),
                    discount, reason == null, reason, false);
        }).sorted(listPrice == null ? byVoucherValue() : byDiscount()).toList();

        // Voucher đầu danh sách chỉ được đề xuất khi thật sự hơn ưu đãi của gói
        if (tier != null) {
            items.stream().filter(VoucherResponse::isUsable).findFirst()
                    .filter(v -> v.getDiscountAmount().compareTo(tier) > 0)
                    .ifPresent(v -> v.setRecommended(true));
        }
        return new VoucherListResponse(tier, items);
    }

    private Comparator<VoucherResponse> byDiscount() {
        return Comparator.comparing(VoucherResponse::isUsable).reversed()
                .thenComparing(Comparator.comparing(VoucherResponse::getDiscountAmount).reversed())
                .thenComparing(VoucherResponse::getExpiresAt);
    }

    private Comparator<VoucherResponse> byVoucherValue() {
        return Comparator.comparing((VoucherResponse v) -> kindRank(v.getKind()))
                .thenComparing(Comparator.comparing(VoucherResponse::getValue).reversed())
                .thenComparing(VoucherResponse::getExpiresAt);
    }

    private static int kindRank(String kind) {
        return switch (kind) {
            case "PERCENT" -> 0;
            case "AMOUNT" -> 1;
            default -> 2;
        };
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

    // Đơn hiện có của đối tượng (nếu đã tạo) để voucher của chính đơn đó không bị tính là "đang bị giữ"
    private Long orderIdOf(String kind, Long targetId) {
        return (CLASS.equals(kind)
                ? orderRepository.findByRegistration_Id(targetId)
                : orderRepository.findByCoachSlot_Id(targetId))
                .map(o -> o.getId()).orElse(-1L);
    }

    private static BigDecimal percentOf(BigDecimal price, BigDecimal percent) {
        return price.multiply(percent).divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP);
    }

    private static LocalDate today() {
        return LocalDate.now(SportClassService.CENTER_ZONE);
    }
}
