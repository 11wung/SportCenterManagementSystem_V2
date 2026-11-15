package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.CreateSupportRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.SupportRequestResponse;
import com.swp391.sportcentermanagementsystem_v2.entity.SupportRequest;
import com.swp391.sportcentermanagementsystem_v2.entity.User;
import com.swp391.sportcentermanagementsystem_v2.exception.BusinessRuleException;
import com.swp391.sportcentermanagementsystem_v2.repository.SupportRequestRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

// Lễ tân ghi nhận yêu cầu hỗ trợ của hội viên và đẩy nó qua các trạng thái xử lý
@Service
@RequiredArgsConstructor
public class SupportRequestService {

    // Chỉ đi tới, không quay lại; CLOSED là điểm cuối
    // Tên trạng thái hiện trong câu báo lỗi
    private static final Map<String, String> SUPPORT_STATUS = Map.of(
            "OPEN", "Mới", "IN_PROGRESS", "Đang xử lý", "RESOLVED", "Đã giải quyết", "CLOSED", "Đã đóng");

    private static final Map<String, List<String>> TRANSITIONS = Map.of(
            "OPEN", List.of("IN_PROGRESS", "RESOLVED", "CLOSED"),
            "IN_PROGRESS", List.of("RESOLVED", "CLOSED"),
            "RESOLVED", List.of("CLOSED"),
            "CLOSED", List.of());

    private final SupportRequestRepository supportRequestRepository;
    private final UserRepository userRepository;

    @Transactional
    public SupportRequestResponse create(CreateSupportRequest request) {
        User member = userRepository.findById(request.getMemberId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy hội viên"));
        if (!"MEMBER".equals(member.getRole().getCode())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy hội viên");
        }

        SupportRequest support = new SupportRequest();
        support.setMember(member);
        support.setSubject(request.getSubject().trim());
        support.setContent(request.getContent().trim());
        support.setCreatedAt(OffsetDateTime.now());
        return new SupportRequestResponse(supportRequestRepository.save(support));
    }

    @Transactional(readOnly = true)
    public List<SupportRequestResponse> list(String status, Long memberId) {
        if (status != null && !status.isBlank() && !TRANSITIONS.containsKey(status)) {
            throw new BusinessRuleException("Trạng thái không hợp lệ, chỉ có: Mới, Đang xử lý, Đã giải quyết, Đã đóng");
        }
        return supportRequestRepository
                .search(status == null ? "" : status, memberId == null ? 0L : memberId).stream()
                .map(SupportRequestResponse::new)
                .toList();
    }

    @Transactional
    public SupportRequestResponse changeStatus(Long id, String newStatus, String staffEmail) {
        User staff = userRepository.findByEmail(staffEmail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy người dùng"));
        SupportRequest support = supportRequestRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy yêu cầu hỗ trợ"));
        if (!TRANSITIONS.containsKey(newStatus)) {
            throw new BusinessRuleException("Trạng thái không hợp lệ, chỉ có: Mới, Đang xử lý, Đã giải quyết, Đã đóng");
        }
        if (!TRANSITIONS.get(support.getStatus()).contains(newStatus)) {
            throw new BusinessRuleException(
                    "Không thể chuyển yêu cầu từ \"" + SUPPORT_STATUS.get(support.getStatus()) + "\" sang \"" + SUPPORT_STATUS.get(newStatus) + "\"");
        }

        support.setStatus(newStatus);
        if (support.getHandledBy() == null) {
            support.setHandledBy(staff);
        }
        if (("RESOLVED".equals(newStatus) || "CLOSED".equals(newStatus)) && support.getResolvedAt() == null) {
            support.setResolvedAt(OffsetDateTime.now());
        }
        return new SupportRequestResponse(supportRequestRepository.save(support));
    }
}
