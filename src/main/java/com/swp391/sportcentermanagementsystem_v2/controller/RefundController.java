package com.swp391.sportcentermanagementsystem_v2.controller;

import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.CompleteRefundRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.RefundResponse;
import jakarta.validation.Valid;
import com.swp391.sportcentermanagementsystem_v2.service.RefundService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/refunds")
@RequiredArgsConstructor
public class RefundController {

    private final RefundService refundService;

    // Lễ tân và Manager: danh sách hoàn tiền. Lễ tân luôn chỉ thấy khoản chờ; Manager lọc được theo status
    @GetMapping
    public List<RefundResponse> list(Authentication authentication,
                                     @RequestParam(required = false) String status) {
        return refundService.list(authentication.getName(), status);
    }

    // Quầy bấm "Gửi mã cho hội viên": mã nhận tiền đi qua thông báo và email của hội viên
    @PostMapping("/{id}/send-code")
    public void sendCode(Authentication authentication, @PathVariable Long id) {
        refundService.sendClaimCode(id, authentication.getName());
    }

    // Bấm "đã hoàn" sau khi đã trả tiền lại cho khách; phải nhập đúng mã nhận tiền của hội viên
    @PostMapping("/{id}/complete")
    public RefundResponse complete(Authentication authentication, @PathVariable Long id,
                                   @Valid @RequestBody CompleteRefundRequest request) {
        return refundService.complete(id, authentication.getName(), request.getCode());
    }
}
