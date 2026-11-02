package com.swp391.sportcentermanagementsystem_v2.controller;

import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.CreateSupportRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.UpdateSupportStatusRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.SupportRequestResponse;
import com.swp391.sportcentermanagementsystem_v2.service.SupportRequestService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/receptionist/support-requests")
@RequiredArgsConstructor
public class SupportRequestController {

    private final SupportRequestService supportRequestService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SupportRequestResponse create(@Valid @RequestBody CreateSupportRequest request) {
        return supportRequestService.create(request);
    }

    @GetMapping
    public List<SupportRequestResponse> list(@RequestParam(required = false) String status,
                                             @RequestParam(required = false) Long memberId) {
        return supportRequestService.list(status, memberId);
    }

    @PatchMapping("/{id}/status")
    public SupportRequestResponse changeStatus(Authentication authentication, @PathVariable Long id,
                                               @Valid @RequestBody UpdateSupportStatusRequest request) {
        return supportRequestService.changeStatus(id, request.getStatus(), authentication.getName());
    }
}
