
package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.CreateMembershipPackageRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.UpdateMembershipPackageRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.MembershipPackageResponse;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.PriceQuoteResponse;
import com.swp391.sportcentermanagementsystem_v2.entity.MembershipPackage;
import com.swp391.sportcentermanagementsystem_v2.exception.BusinessRuleException;
import com.swp391.sportcentermanagementsystem_v2.repository.MemberSubscriptionRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.MembershipPackageRepository;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class MembershipPackageService {

    private final MembershipPackageRepository membershipPackageRepository;
    private final SystemSettingService settings;
    private final EntityManager entityManager;
    private final MemberSubscriptionRepository memberSubscriptionRepository;

    // Mua từ 3, 6, 12 tháng thì được giảm. Mức giảm chỉnh trong system_settings,
    // thiếu key thì dùng mặc định của SRS (8 / 15 / 25%).
    public int longTermDiscountPct(int months) {
        if (months >= 12) {
            return settings.getInt("package.long_term_discount.12", 25);
        }
        if (months >= 6) {
            return settings.getInt("package.long_term_discount.6", 15);
        }
        if (months >= 3) {
            return settings.getInt("package.long_term_discount.3", 8);
        }
        return 0;
    }

    // Tổng tiền = giá tháng x số tháng, trừ mức giảm mua dài hạn.
    public BigDecimal calculateTotal(MembershipPackage pkg, int months) {
        BigDecimal gross = pkg.getMonthlyPrice().multiply(BigDecimal.valueOf(months));
        BigDecimal discount = gross
                .multiply(BigDecimal.valueOf(longTermDiscountPct(months)))
                .divide(BigDecimal.valueOf(100), 0, RoundingMode.DOWN);
        return gross.subtract(discount);
    }

    @Transactional(readOnly = true)
    public PriceQuoteResponse quote(Long packageId, int months) {
        if (months < 1 || months > 12) {
            throw new BusinessRuleException("Số tháng phải từ 1 đến 12");
        }
        MembershipPackage pkg = membershipPackageRepository.findByIdAndIsActiveTrue(packageId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Không tìm thấy gói thành viên"));
        return new PriceQuoteResponse(
                pkg.getId(), (short) months, pkg.getMonthlyPrice(),
                longTermDiscountPct(months), calculateTotal(pkg, months));
    }

    // Get All Active Packages
    @Transactional(readOnly = true)
    public List<MembershipPackageResponse> getActivePackages() {
        return membershipPackageRepository.findByIsActiveTrue().stream().map(this::toResponse).toList();
    }

    // Get All Packages (manager): gồm cả gói đang ẩn
    @Transactional(readOnly = true)
    public List<MembershipPackageResponse> getAllPackages() {
        return membershipPackageRepository.findAll().stream().map(this::toResponse).toList();
    }

    // Get Package by ID
    @Transactional(readOnly = true)
    public MembershipPackageResponse getPackageById(Long id) {
        return toResponse(membershipPackageRepository.findByIdAndIsActiveTrue(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Không tìm thấy gói thành viên")));
    }

    // Create New Package
    @Transactional
    public MembershipPackageResponse createPackage(CreateMembershipPackageRequest request) {

        // Code luôn được lưu bằng chữ IN HOA.
        String code = request.getCode().trim().toUpperCase(Locale.ROOT);
        String name = request.getName().trim();

        if (membershipPackageRepository.existsByCodeIgnoreCase(code)) {
            throw new BusinessRuleException("Mã gói đã tồn tại");
        }
        if (membershipPackageRepository.existsByNameIgnoreCase(name)) {
            throw new BusinessRuleException("Tên gói đã tồn tại");
        }

        requireFitsBetweenNeighbours(null, request.getTierLevel(), request.getMonthlyPrice(),
                request.getTuitionDiscountPct(), request.getOneOnOneDiscountPct(), request.getWaitlistPriority(),
                request.getEarlyLeaveRefundSessions(), request.getHasPersonalPlan());

        // Hạng đã có gói thì đây là chèn vào giữa: các gói từ hạng này trở lên được dời lên một bậc
        if (membershipPackageRepository.existsByTierLevel(request.getTierLevel())) {
            memberSubscriptionRepository.shiftTierSnapshotsFrom(request.getTierLevel());
            membershipPackageRepository.liftFrom(request.getTierLevel());
            membershipPackageRepository.landLifted();
        }

        MembershipPackage p = new MembershipPackage();
        p.setCode(code);
        p.setName(name);
        p.setDescription(request.getDescription());
        p.setTierLevel(request.getTierLevel());
        p.setMonthlyPrice(request.getMonthlyPrice());
        p.setTuitionDiscountPct(request.getTuitionDiscountPct());
        p.setOneOnOneDiscountPct(request.getOneOnOneDiscountPct());
        p.setHasPersonalPlan(request.getHasPersonalPlan());
        p.setEarlyLeaveRefundSessions(request.getEarlyLeaveRefundSessions());
        p.setWaitlistPriority(request.getWaitlistPriority());
        p.setIsActive(true);

        MembershipPackage saved = membershipPackageRepository.saveAndFlush(p);
        entityManager.refresh(saved);
        return toResponse(saved);
    }

    // Update Package Information. Gói đã bán không đổi vì quyền lợi đã chụp vào benefits_snapshot.
    @Transactional
    public MembershipPackageResponse updatePackage(Long id, UpdateMembershipPackageRequest request) {

        // Không cho đổi trạng thái bằng API update thông tin.
        if (request.getIsActive() != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Hãy đổi trạng thái gói bằng chức năng đổi trạng thái riêng");
        }

        MembershipPackage p = membershipPackageRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Không tìm thấy gói thành viên"));

        if (request.getName() != null) {
            String name = request.getName().trim();
            if (name.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tên gói không được để trống");
            }
            // Tên chỉ khác hoa/thường so với tên hiện tại thì không phải trùng gói khác.
            if (!name.equalsIgnoreCase(p.getName())
                    && membershipPackageRepository.existsByNameIgnoreCase(name)) {
                throw new BusinessRuleException("Tên gói đã tồn tại");
            }
            p.setName(name);
        }
        if (request.getDescription() != null) p.setDescription(request.getDescription());
        if (request.getTierLevel() != null && !request.getTierLevel().equals(p.getTierLevel())) {
            if (membershipPackageRepository.existsByTierLevel(request.getTierLevel())) {
                throw new BusinessRuleException("Hạng này đã có gói khác, hãy chèn gói mới thay vì đổi hạng");
            }
            p.setTierLevel(request.getTierLevel());
        }
        if (request.getMonthlyPrice() != null) p.setMonthlyPrice(request.getMonthlyPrice());
        if (request.getTuitionDiscountPct() != null) p.setTuitionDiscountPct(request.getTuitionDiscountPct());
        if (request.getOneOnOneDiscountPct() != null) p.setOneOnOneDiscountPct(request.getOneOnOneDiscountPct());
        if (request.getHasPersonalPlan() != null) p.setHasPersonalPlan(request.getHasPersonalPlan());
        if (request.getEarlyLeaveRefundSessions() != null) p.setEarlyLeaveRefundSessions(request.getEarlyLeaveRefundSessions());
        if (request.getWaitlistPriority() != null) p.setWaitlistPriority(request.getWaitlistPriority());

        requireFitsBetweenNeighbours(p.getId(), p.getTierLevel(), p.getMonthlyPrice(), p.getTuitionDiscountPct(),
                p.getOneOnOneDiscountPct(), p.getWaitlistPriority(), p.getEarlyLeaveRefundSessions(), p.getHasPersonalPlan());

        membershipPackageRepository.saveAndFlush(p);
        entityManager.refresh(p);
        return toResponse(p);
    }

    // Hạng càng cao quyền lợi càng không được kém: gói ở `tier` phải nằm giữa hạng ngay dưới và hạng ngay trên
    // (giá phải cao hơn hạng dưới và thấp hơn hạng trên; các quyền lợi còn lại không thấp hơn / không cao hơn).
    // selfId: gói đang sửa (bỏ qua khi tìm hàng xóm); null khi tạo hoặc chèn gói mới
    private void requireFitsBetweenNeighbours(Long selfId, Short tier, BigDecimal price, Short tuition, Short oneOnOne,
                                              Short waitlist, Short earlyLeave, Boolean personalPlan) {
        List<MembershipPackage> others = membershipPackageRepository.findAll().stream()
                .filter(o -> !o.getId().equals(selfId)).toList();
        MembershipPackage lower = others.stream().filter(o -> o.getTierLevel() < tier)
                .max(java.util.Comparator.comparing(MembershipPackage::getTierLevel)).orElse(null);
        MembershipPackage upper = others.stream().filter(o -> o.getTierLevel() >= tier)
                .min(java.util.Comparator.comparing(MembershipPackage::getTierLevel)).orElse(null);

        List<String> problems = new java.util.ArrayList<>();
        if (lower != null) {
            String at = " (hạng " + lower.getTierLevel() + " " + lower.getName() + ")";
            if (price.compareTo(lower.getMonthlyPrice()) <= 0) problems.add("Giá phải cao hơn " + lower.getMonthlyPrice().toPlainString() + " đ" + at);
            if (val(tuition) < val(lower.getTuitionDiscountPct())) problems.add("Giảm học phí phải từ " + lower.getTuitionDiscountPct() + "% trở lên" + at);
            if (val(oneOnOne) < val(lower.getOneOnOneDiscountPct())) problems.add("Giảm buổi 1-1 phải từ " + lower.getOneOnOneDiscountPct() + "% trở lên" + at);
            if (val(waitlist) < val(lower.getWaitlistPriority())) problems.add("Ưu tiên hàng chờ phải từ " + lower.getWaitlistPriority() + " trở lên" + at);
            if (val(earlyLeave) < val(lower.getEarlyLeaveRefundSessions())) problems.add("Số buổi được hoàn khi rời lớp sớm phải từ " + lower.getEarlyLeaveRefundSessions() + " trở lên" + at);
            if (Boolean.TRUE.equals(lower.getHasPersonalPlan()) && !Boolean.TRUE.equals(personalPlan)) problems.add("Phải có bài tập riêng từ coach" + at);
        }
        if (upper != null) {
            String at = " (hạng " + upper.getTierLevel() + " " + upper.getName() + ")";
            if (price.compareTo(upper.getMonthlyPrice()) >= 0) problems.add("Giá phải thấp hơn " + upper.getMonthlyPrice().toPlainString() + " đ" + at);
            if (val(tuition) > val(upper.getTuitionDiscountPct())) problems.add("Giảm học phí không được quá " + upper.getTuitionDiscountPct() + "%" + at);
            if (val(oneOnOne) > val(upper.getOneOnOneDiscountPct())) problems.add("Giảm buổi 1-1 không được quá " + upper.getOneOnOneDiscountPct() + "%" + at);
            if (val(waitlist) > val(upper.getWaitlistPriority())) problems.add("Ưu tiên hàng chờ không được quá " + upper.getWaitlistPriority() + at);
            if (val(earlyLeave) > val(upper.getEarlyLeaveRefundSessions())) problems.add("Số buổi được hoàn khi rời lớp sớm không được quá " + upper.getEarlyLeaveRefundSessions() + at);
            if (!Boolean.TRUE.equals(upper.getHasPersonalPlan()) && Boolean.TRUE.equals(personalPlan)) problems.add("Không được có bài tập riêng vì " + upper.getName() + " không có" + at);
        }
        if (!problems.isEmpty()) {
            throw new BusinessRuleException("Hạng " + tier + " phải nằm giữa các hạng bên cạnh: " + String.join("; ", problems));
        }
    }

    private static int val(Short v) {
        return v == null ? 0 : v;
    }

    // Chỉ xóa hẳn được gói chưa ai mua; gói đã bán thì ẩn để giữ lịch sử mua
    @Transactional
    public void deletePackage(Long id) {
        MembershipPackage p = membershipPackageRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy gói thành viên"));
        if (memberSubscriptionRepository.existsByMembershipPackageId(id)) {
            throw new BusinessRuleException("Gói này đã có người mua nên không xóa được, hãy ẩn gói");
        }
        short tier = p.getTierLevel();
        memberSubscriptionRepository.shiftTierSnapshotsDownAbove(tier);
        membershipPackageRepository.delete(p);
        membershipPackageRepository.flush();
        membershipPackageRepository.liftAbove(tier);
        membershipPackageRepository.landLoweredByOne();
    }

    // Update Package Status
    @Transactional
    public MembershipPackageResponse updatePackageStatus(Long id, UpdateMembershipPackageRequest request) {

        if (request.getIsActive() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cần chọn trạng thái gói");
        }

        MembershipPackage p = membershipPackageRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Không tìm thấy gói thành viên"));

        if (!request.getIsActive().equals(p.getIsActive())) {
            p.setIsActive(request.getIsActive());
            membershipPackageRepository.saveAndFlush(p);
            entityManager.refresh(p);
        }
        return toResponse(p);
    }

    private MembershipPackageResponse toResponse(MembershipPackage p) {
        MembershipPackageResponse r = new MembershipPackageResponse();
        r.setId(p.getId());
        r.setCode(p.getCode());
        r.setName(p.getName());
        r.setDescription(p.getDescription());
        r.setTierLevel(p.getTierLevel());
        r.setMonthlyPrice(p.getMonthlyPrice());
        r.setTuitionDiscountPct(p.getTuitionDiscountPct());
        r.setOneOnOneDiscountPct(p.getOneOnOneDiscountPct());
        r.setHasPersonalPlan(p.getHasPersonalPlan());
        r.setEarlyLeaveRefundSessions(p.getEarlyLeaveRefundSessions());
        r.setWaitlistPriority(p.getWaitlistPriority());
        r.setIsActive(p.getIsActive());
        r.setCreatedAt(p.getCreatedAt());
        r.setUpdatedAt(p.getUpdatedAt());
        return r;
    }
}
