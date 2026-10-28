package com.swp391.sportcentermanagementsystem_v2.controller;

import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.TrainingResponse.Dashboard;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.TrainingResponse.Page;
import com.swp391.sportcentermanagementsystem_v2.service.TrainingService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;

@RestController
@RequestMapping("/api/member/training")
@RequiredArgsConstructor
public class MemberTrainingController {
    private final TrainingService service;
    @GetMapping
    public Dashboard dashboard(Authentication auth,@RequestParam(required=false) LocalDate from,@RequestParam(required=false) LocalDate to) {
        return service.dashboard(auth.getName(),from,to);
    }
    @GetMapping("/history/{kind}")
    public Page<?> history(Authentication auth,@PathVariable String kind,@RequestParam(required=false) Long cursor,
            @RequestParam(defaultValue="50") int limit) {
        return service.memberHistoryPage(auth.getName(),kind,cursor,limit);
    }
}
