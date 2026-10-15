package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.CreateSubscriptionRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.MemberSubscriptionResponse;
import com.swp391.sportcentermanagementsystem_v2.entity.MemberSubscription;
import com.swp391.sportcentermanagementsystem_v2.entity.MembershipPackage;
import com.swp391.sportcentermanagementsystem_v2.entity.User;
import com.swp391.sportcentermanagementsystem_v2.exception.BusinessRuleException;
import com.swp391.sportcentermanagementsystem_v2.repository.MemberSubscriptionRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.MembershipPackageRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor

public class MemberSubscriptionService {

    private final MemberSubscriptionRepository subscriptionRepository;
    private final UserRepository userRepository;
    private final MembershipPackageRepository packageRepository;

    // Chức năng chính : đăng ký/gia hạn gói tập
    @Transactional
    public MemberSubscriptionResponse registerOrRenewSubscription(Long memberId, CreateSubscriptionRequest request,
            Long staffId) {
        // Kiểm tra member có tồn tại và đúng role hay không
        User member = userRepository.findById(memberId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No member found !"));

        if (!"MEMBER".equals(member.getRole().getCode())) {
            throw new BusinessRuleException("This user is not MEMBER !");
        }

        // Kiểm tra gói tập isActive và đang mở bán
        MembershipPackage MembershipPackage = packageRepository.findByIdAndIsActiveTrue(request.getPackageId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "This Package is not exist or no longer available !"));

        // Tìm gói tập Active gần nhất của member
        Optional<MemberSubscription> currentActiveSub = subscriptionRepository
                .findFirstByUserIdAndStatusOrderByEndDateDesc(memberId, "ACTIVE");
        LocalDate startDate;
        LocalDate endDate;

        // Kiểm tra điều kiện để phân biệt đăng ký mới hay gia hạn
        if (currentActiveSub.isPresent() && currentActiveSub.get().getEndDate() != null
                && !currentActiveSub.get().getEndDate().isBefore(LocalDate.now())) {
            // Trường hợp A: đã có gói và vẫn còn hạn -> tính là gia hạn cộng dồn ngày

            MemberSubscription oldSub = currentActiveSub.get();
            // ngày bắt đầu sẽ giữ nguyên từ gói cũ
            startDate = oldSub.getStartDate();

            // ngày kết thúc được cộng dồn nối tiếp từ ngày hết hạn của gói cũ
            endDate = oldSub.getEndDate().plusMonths(MembershipPackage.getDurationMonths());

            // đóng gói cũ sang 'EXPIRED' vì đã được kế thừa vào gói mới có thời hạn cộng
            // dồn
            oldSub.setStatus("EXPIRED");
            subscriptionRepository.save(oldSub);
        } else {
            // trường hợp chưa có gói nào hết hoặc gói cũ đã hết hạn thì tính mới từ đầu
            startDate = LocalDate.now();
            endDate = startDate.plusMonths(MembershipPackage.getDurationMonths());
        }

        // Lấy thông tin của lễ tân nếu lễ tân làm hộ tại quầy
        User staff = (staffId != null) ? userRepository.findById(staffId).orElse(null) : null;

        // Tạo đối tượng subcription mới và lưu vào snapshot
        MemberSubscription newSubscription = new MemberSubscription();
        newSubscription.setUser(member);
        newSubscription.setMembershipPackage(MembershipPackage);
        newSubscription.setPackageNameSnapshot(MembershipPackage.getName());
        newSubscription.setMaxClassesPerWeekSnapshot(MembershipPackage.getMaxClassesPerWeek());
        newSubscription.setTotalAmount(MembershipPackage.getPrice());
        newSubscription.setStartDate(startDate);
        newSubscription.setEndDate(endDate);
        newSubscription.setStatus("PENDING");
        newSubscription.setCreatedBy(staff);

        // lưu vào DB
        MemberSubscription saveMemberSubscription = subscriptionRepository.save(newSubscription);

        // chuyển sang DTO và return
        return mapToResponse(saveMemberSubscription);

    }

    // Lấy danh sách toàn bộ lịch sử gói tập của Member
    @Transactional(readOnly = true)
    public List<MemberSubscriptionResponse> getMySubscriptions(String email) {
        User member = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No member found!"));

        return subscriptionRepository.findByUserIdOrderByCreatedAtDesc(member.getId())
                .stream()
                .map(this::mapToResponse)
                .toList();
    }

    // Lấy thông tin gói tập đang ACTIVE hiện tại của Member
    @Transactional(readOnly = true)
    public MemberSubscriptionResponse getMyActiveSubscription(String email) {
        User member = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No member found!"));

        return subscriptionRepository.findValidActiveSubscription(member.getId(), LocalDate.now())
                .map(this::mapToResponse)
                .orElseThrow(() -> new BusinessRuleException("You currently have no active Package!"));
    }

    // Lễ tân xem lịch sử gói tập của 1 học viên bất kỳ theo memberId
    @Transactional(readOnly = true)
    public List<MemberSubscriptionResponse> getSubscriptionsByMemberId(Long memberId) {
        if (!userRepository.existsById(memberId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No member found with ID: " + memberId);
        }

        return subscriptionRepository.findByUserIdOrderByCreatedAtDesc(memberId)
                .stream()
                .map(this::mapToResponse)
                .toList();
    }

    // Cung cấp cho Flow 2 (Đăng ký lớp học - BR-BKG-01)
    @Transactional(readOnly = true)
    public Optional<MemberSubscription> checkActiveSubscriptionForBooking(Long memberId) {
        return subscriptionRepository.findValidActiveSubscription(memberId, LocalDate.now());
    }

    // hàm map từ entity sang response DTO
    private MemberSubscriptionResponse mapToResponse(MemberSubscription sub) {
        MemberSubscriptionResponse res = new MemberSubscriptionResponse();
        res.setId(sub.getId());
        res.setMemberId(sub.getUser().getId());
        res.setMemberName(sub.getUser().getFullName());
        res.setPackageId(sub.getMembershipPackage().getId());
        res.setPackageName(sub.getPackageNameSnapshot());
        res.setMaxClassesPerWeek(sub.getMaxClassesPerWeekSnapshot());
        res.setTotalAmount(sub.getTotalAmount());
        res.setStartDate(sub.getStartDate());
        res.setEndDate(sub.getEndDate());
        res.setStatus(sub.getStatus());
        if (sub.getCreatedBy() != null) {
            res.setCreatedByStaffName(sub.getCreatedBy().getFullName());
        }
        res.setCreatedAt(sub.getCreatedAt());
        return res;
    }
}
