
package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.CreateMembershipPackageRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.UpdateMembershipPackageRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.MembershipPackageResponse;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.PriceQuoteResponse;
import com.swp391.sportcentermanagementsystem_v2.entity.MembershipPackage;
import com.swp391.sportcentermanagementsystem_v2.exception.BusinessRuleException;
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
            throw new BusinessRuleException("Months must be between 1 and 12!");
        }
        MembershipPackage pkg = membershipPackageRepository.findByIdAndIsActiveTrue(packageId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Membership package not found!"));
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
                        HttpStatus.NOT_FOUND, "Membership package not found!")));
    }

    // Create New Package
    @Transactional
    public MembershipPackageResponse createPackage(CreateMembershipPackageRequest request) {

        // Code luôn được lưu bằng chữ IN HOA.
        String code = request.getCode().trim().toUpperCase(Locale.ROOT);
        String name = request.getName().trim();

        if (membershipPackageRepository.existsByCodeIgnoreCase(code)) {
            throw new BusinessRuleException("Package code already exists!");
        }
        if (membershipPackageRepository.existsByNameIgnoreCase(name)) {
            throw new BusinessRuleException("Package name already exists!");
        }

        MembershipPackage p = new MembershipPackage();
        p.setCode(code);
        p.setName(name);
        p.setDescription(request.getDescription());
        p.setTierLevel(request.getTierLevel());
        p.setMonthlyPrice(request.getMonthlyPrice());
        p.setEarlyRegisterDays(request.getEarlyRegisterDays());
        p.setTuitionDiscountPct(request.getTuitionDiscountPct());
        p.setOneOnOneDiscountPct(request.getOneOnOneDiscountPct());
        p.setHasPersonalPlan(request.getHasPersonalPlan());
        p.setAiLevel(request.getAiLevel());
        p.setMenuAiLevel(request.getMenuAiLevel());
        p.setAssessmentEveryMonths(request.getAssessmentEveryMonths());
        p.setEarlyLeaveRefundSessions(request.getEarlyLeaveRefundSessions());
        p.setWaitlistPriority(request.getWaitlistPriority());
        p.setBirthdayGiftKind(request.getBirthdayGiftKind());
        p.setBirthdayGiftValue(request.getBirthdayGiftValue());
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
                    "Package status must be updated through the status API!");
        }

        MembershipPackage p = membershipPackageRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Membership package not found!"));

        if (request.getName() != null) {
            String name = request.getName().trim();
            if (name.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Package name cannot be blank!");
            }
            // Tên chỉ khác hoa/thường so với tên hiện tại thì không phải trùng gói khác.
            if (!name.equalsIgnoreCase(p.getName())
                    && membershipPackageRepository.existsByNameIgnoreCase(name)) {
                throw new BusinessRuleException("Package name already exists!");
            }
            p.setName(name);
        }
        if (request.getDescription() != null) p.setDescription(request.getDescription());
        if (request.getTierLevel() != null) p.setTierLevel(request.getTierLevel());
        if (request.getMonthlyPrice() != null) p.setMonthlyPrice(request.getMonthlyPrice());
        if (request.getEarlyRegisterDays() != null) p.setEarlyRegisterDays(request.getEarlyRegisterDays());
        if (request.getTuitionDiscountPct() != null) p.setTuitionDiscountPct(request.getTuitionDiscountPct());
        if (request.getOneOnOneDiscountPct() != null) p.setOneOnOneDiscountPct(request.getOneOnOneDiscountPct());
        if (request.getHasPersonalPlan() != null) p.setHasPersonalPlan(request.getHasPersonalPlan());
        if (request.getAiLevel() != null) p.setAiLevel(request.getAiLevel());
        if (request.getMenuAiLevel() != null) p.setMenuAiLevel(request.getMenuAiLevel());
        if (request.getAssessmentEveryMonths() != null) p.setAssessmentEveryMonths(request.getAssessmentEveryMonths());
        if (request.getEarlyLeaveRefundSessions() != null) p.setEarlyLeaveRefundSessions(request.getEarlyLeaveRefundSessions());
        if (request.getWaitlistPriority() != null) p.setWaitlistPriority(request.getWaitlistPriority());
        if (request.getBirthdayGiftKind() != null) p.setBirthdayGiftKind(request.getBirthdayGiftKind());
        if (request.getBirthdayGiftValue() != null) p.setBirthdayGiftValue(request.getBirthdayGiftValue());

        membershipPackageRepository.saveAndFlush(p);
        entityManager.refresh(p);
        return toResponse(p);
    }

    // Update Package Status
    @Transactional
    public MembershipPackageResponse updatePackageStatus(Long id, UpdateMembershipPackageRequest request) {

        if (request.getIsActive() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Package status is required!");
        }

        MembershipPackage p = membershipPackageRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Membership package not found!"));

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
        r.setEarlyRegisterDays(p.getEarlyRegisterDays());
        r.setTuitionDiscountPct(p.getTuitionDiscountPct());
        r.setOneOnOneDiscountPct(p.getOneOnOneDiscountPct());
        r.setHasPersonalPlan(p.getHasPersonalPlan());
        r.setAiLevel(p.getAiLevel());
        r.setMenuAiLevel(p.getMenuAiLevel());
        r.setAssessmentEveryMonths(p.getAssessmentEveryMonths());
        r.setEarlyLeaveRefundSessions(p.getEarlyLeaveRefundSessions());
        r.setWaitlistPriority(p.getWaitlistPriority());
        r.setBirthdayGiftKind(p.getBirthdayGiftKind());
        r.setBirthdayGiftValue(p.getBirthdayGiftValue());
        r.setIsActive(p.getIsActive());
        r.setCreatedAt(p.getCreatedAt());
        r.setUpdatedAt(p.getUpdatedAt());
        return r;
    }
}
