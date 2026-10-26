package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.CreateVoucherCampaignRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.VoucherCampaignResponse;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.VoucherResponse;
import com.swp391.sportcentermanagementsystem_v2.entity.User;
import com.swp391.sportcentermanagementsystem_v2.exception.BusinessRuleException;
import com.swp391.sportcentermanagementsystem_v2.repository.UserRepository;
import com.swp391.sportcentermanagementsystem_v2.service.VoucherService.Voucher;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

// Voucher chiến dịch (BR-VCH-02): manager tạo mã theo dịp, member nhập mã để nhận voucher.
// Mỗi member nhận một lần mỗi chiến dịch (chỉ mục uq_voucher_campaign_user). Dùng JdbcTemplate như VoucherService
@Service
@RequiredArgsConstructor
public class VoucherCampaignService {

    record Campaign(Long id, String kind, BigDecimal value, String appliesTo, Integer maxUses,
            OffsetDateTime startsAt, OffsetDateTime endsAt) {
    }

    private final JdbcTemplate jdbcTemplate;
    private final UserRepository userRepository;
    private final VoucherService voucherService;
    private final AuditService auditService;

    @Transactional
    public VoucherCampaignResponse create(String managerEmail, CreateVoucherCampaignRequest request) {
        User manager = userByEmail(managerEmail);
        if (!request.getEndsAt().isAfter(request.getStartsAt())) {
            throw new BusinessRuleException("End time must be after start time!");
        }
        if (!request.getEndsAt().isAfter(OffsetDateTime.now())) {
            throw new BusinessRuleException("End time must be in the future!");
        }
        if ("PERCENT".equals(request.getKind()) && request.getValue().compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new BusinessRuleException("A percent discount cannot exceed 100!");
        }
        // Voucher buổi 1-1 miễn phí chỉ có nghĩa với buổi 1-1, nếu không sẽ miễn phí cả lớp hoặc gói
        if ("FREE_ONE_ON_ONE".equals(request.getKind()) && !"ONE_ON_ONE".equals(request.getAppliesTo())) {
            throw new BusinessRuleException("A free 1-1 voucher must apply to ONE_ON_ONE!");
        }

        // Lưu mã in hoa để tra mã không phân biệt hoa thường luôn ra đúng một chiến dịch
        String code = request.getCode().toUpperCase();
        Long id;
        try {
            id = jdbcTemplate.queryForObject("""
                    INSERT INTO voucher_campaigns (code, kind, value, applies_to, max_uses, starts_at, ends_at, created_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                    """, Long.class, code, request.getKind(), request.getValue(), request.getAppliesTo(),
                    request.getMaxUses(), request.getStartsAt(), request.getEndsAt(), manager.getId());
        } catch (DuplicateKeyException ex) {
            throw new BusinessRuleException("This code already exists!");
        }
        auditService.log(manager, "VOUCHER_CAMPAIGN_CREATED", "voucher_campaign", id,
                Map.of("code", code, "kind", request.getKind(), "appliesTo", request.getAppliesTo()));
        return findResponse(id);
    }

    @Transactional(readOnly = true)
    public List<VoucherCampaignResponse> list() {
        return jdbcTemplate.query(SELECT_RESPONSE + " ORDER BY c.id DESC", (rs, i) -> toResponse(rs));
    }

    // Kết thúc chiến dịch ngay: không ai nhập thêm được. Voucher đã nhận vẫn dùng đến hết hạn của nó.
    // Chiến dịch chưa bắt đầu thì đặt kết thúc sát giờ bắt đầu (ràng buộc ends_at > starts_at)
    @Transactional
    public VoucherCampaignResponse end(String managerEmail, Long campaignId) {
        User manager = userByEmail(managerEmail);
        int updated = jdbcTemplate.update("""
                UPDATE voucher_campaigns SET ends_at = GREATEST(now(), starts_at + interval '1 second')
                WHERE id = ? AND ends_at > now()
                """, campaignId);
        if (updated == 0) {
            if (jdbcTemplate.queryForObject("SELECT count(*) FROM voucher_campaigns WHERE id = ?", Long.class,
                    campaignId) == 0) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Campaign not found!");
            }
            throw new BusinessRuleException("This campaign has already ended!");
        }
        auditService.log(manager, "VOUCHER_CAMPAIGN_ENDED", "voucher_campaign", campaignId, Map.of());
        return findResponse(campaignId);
    }

    // Member nhập mã. Khóa dòng chiến dịch để hai người cùng nhận lượt cuối không vượt max_uses
    @Transactional
    public VoucherResponse redeem(String memberEmail, String code) {
        User member = userByEmail(memberEmail);
        if (!Boolean.TRUE.equals(member.getIsActive())) {
            throw new BusinessRuleException("This account is deactivated!");
        }

        Campaign campaign = jdbcTemplate.query("""
                SELECT id, kind, value, applies_to, max_uses, starts_at, ends_at FROM voucher_campaigns
                WHERE upper(code) = upper(?) FOR UPDATE
                """, (rs, i) -> new Campaign(rs.getLong("id"), rs.getString("kind"), rs.getBigDecimal("value"),
                rs.getString("applies_to"), (Integer) rs.getObject("max_uses"),
                rs.getObject("starts_at", OffsetDateTime.class), rs.getObject("ends_at", OffsetDateTime.class)),
                code.trim()).stream().findFirst()
                .orElseThrow(() -> new BusinessRuleException("Voucher code not found!"));

        long redeemed = jdbcTemplate.queryForObject("SELECT count(*) FROM vouchers WHERE campaign_id = ?",
                Long.class, campaign.id());
        boolean already = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM vouchers WHERE campaign_id = ? AND user_id = ?", Long.class,
                campaign.id(), member.getId()) > 0;
        redeemProblem(campaign, OffsetDateTime.now(), redeemed, already).ifPresent(msg -> {
            throw new BusinessRuleException(msg);
        });

        Long voucherId = jdbcTemplate.queryForObject("""
                INSERT INTO vouchers (user_id, source, kind, value, applies_to, campaign_id, expires_at)
                VALUES (?, 'CAMPAIGN', ?, ?, ?, ?, ?) RETURNING id
                """, Long.class, member.getId(), campaign.kind(), campaign.value(), campaign.appliesTo(),
                campaign.id(), campaign.endsAt());
        Voucher v = voucherService.findById(voucherId).orElseThrow();
        return new VoucherResponse(v.id(), v.source(), v.kind(), v.value(), v.appliesTo(), v.expiresAt(),
                null, true, null, false);
    }

    // Lý do mã không nhập được; rỗng nếu nhập được
    static Optional<String> redeemProblem(Campaign campaign, OffsetDateTime now, long redeemed, boolean already) {
        if (now.isBefore(campaign.startsAt())) {
            return Optional.of("This campaign has not started yet!");
        }
        if (!now.isBefore(campaign.endsAt())) {
            return Optional.of("This campaign has ended!");
        }
        if (already) {
            return Optional.of("You have already used this code!");
        }
        if (campaign.maxUses() != null && redeemed >= campaign.maxUses()) {
            return Optional.of("This code has run out of uses!");
        }
        return Optional.empty();
    }

    private static final String SELECT_RESPONSE = """
            SELECT c.id, c.code, c.kind, c.value, c.applies_to, c.max_uses, c.starts_at, c.ends_at,
                   (SELECT count(*) FROM vouchers v WHERE v.campaign_id = c.id) AS redeemed
            FROM voucher_campaigns c
            """;

    private VoucherCampaignResponse findResponse(Long id) {
        return jdbcTemplate.query(SELECT_RESPONSE + " WHERE c.id = ?", (rs, i) -> toResponse(rs), id)
                .stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Campaign not found!"));
    }

    private static VoucherCampaignResponse toResponse(java.sql.ResultSet rs) throws java.sql.SQLException {
        OffsetDateTime startsAt = rs.getObject("starts_at", OffsetDateTime.class);
        OffsetDateTime endsAt = rs.getObject("ends_at", OffsetDateTime.class);
        Integer maxUses = (Integer) rs.getObject("max_uses");
        long redeemed = rs.getLong("redeemed");
        OffsetDateTime now = OffsetDateTime.now();
        boolean active = !now.isBefore(startsAt) && now.isBefore(endsAt) && (maxUses == null || redeemed < maxUses);
        return new VoucherCampaignResponse(rs.getLong("id"), rs.getString("code"), rs.getString("kind"),
                rs.getBigDecimal("value"), rs.getString("applies_to"), maxUses, redeemed, startsAt, endsAt, active);
    }

    private User userByEmail(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found!"));
    }
}
