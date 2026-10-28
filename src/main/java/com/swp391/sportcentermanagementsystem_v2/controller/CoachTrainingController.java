package com.swp391.sportcentermanagementsystem_v2.controller;

import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.TrainingRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.CoachDashboardResponse;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.TrainingResponse.*;
import com.swp391.sportcentermanagementsystem_v2.service.TrainingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/coach/training")
@RequiredArgsConstructor
public class CoachTrainingController {
    private final TrainingService service;

    @GetMapping("/dashboard")
    public CoachDashboardResponse dashboard(Authentication auth) { return service.coachDashboard(auth.getName()); }

    @GetMapping("/classes")
    public List<AssignedClass> classes(Authentication auth) { return service.classes(auth.getName()); }
    @GetMapping("/classes/{classId}/members")
    public List<Participant> roster(Authentication auth,@PathVariable long classId) { return service.classRoster(auth.getName(),classId); }
    @GetMapping("/classes/{classId}/members/{memberId}")
    public MemberHistory history(Authentication auth,@PathVariable long classId,@PathVariable long memberId) {
        return service.memberHistory(auth.getName(),classId,memberId);
    }
    @GetMapping("/classes/{classId}/members/{memberId}/history/{kind}")
    public Page<?> historyPage(Authentication auth,@PathVariable long classId,@PathVariable long memberId,
            @PathVariable String kind,@RequestParam(required=false) Long cursor,@RequestParam(defaultValue="50") int limit) {
        return service.coachHistory(auth.getName(),classId,memberId,kind,cursor,limit);
    }
    @GetMapping("/sessions")
    public List<Session> sessions(Authentication auth,@RequestParam(required=false) LocalDate from,@RequestParam(required=false) LocalDate to) {
        return service.sessions(auth.getName(),from,to);
    }
    @GetMapping("/sessions/{id}")
    public SessionDetail session(Authentication auth,@PathVariable long id) { return service.session(auth.getName(),id); }
    @PutMapping("/sessions/{id}/attendance")
    public SessionDetail attendance(Authentication auth,@PathVariable long id,@Valid @RequestBody TrainingRequest.Attendance request) {
        return service.saveAttendance(auth.getName(),id,request);
    }
    @PutMapping("/sessions/{id}/results")
    public Result result(Authentication auth,@PathVariable long id,@Valid @RequestBody TrainingRequest.Result request) {
        return service.saveResult(auth.getName(),id,request);
    }
    @GetMapping("/plans")
    public Page<Plan> plans(Authentication auth,@RequestParam(required=false) Long cursor,@RequestParam(defaultValue="50") int limit) {
        return service.plans(auth.getName(),cursor,limit);
    }
    @GetMapping("/plans/{id}")
    public Plan plan(Authentication auth,@PathVariable long id) { return service.plan(auth.getName(),id); }
    @PostMapping("/plans")
    public Plan createPlan(Authentication auth,@Valid @RequestBody TrainingRequest.Plan request) { return service.savePlan(auth.getName(),null,request); }
    @PutMapping("/plans/{id}")
    public Plan updatePlan(Authentication auth,@PathVariable long id,@Valid @RequestBody TrainingRequest.Plan request) { return service.savePlan(auth.getName(),id,request); }
    @PostMapping("/evaluations")
    public Evaluation createEvaluation(Authentication auth,@Valid @RequestBody TrainingRequest.Evaluation request) { return service.saveEvaluation(auth.getName(),null,request); }
    @PutMapping("/evaluations/{id}")
    public Evaluation updateEvaluation(Authentication auth,@PathVariable long id,@Valid @RequestBody TrainingRequest.Evaluation request) { return service.saveEvaluation(auth.getName(),id,request); }
}
