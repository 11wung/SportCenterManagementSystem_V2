package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.RescheduleSlotRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.CoachSlotResponse;
import com.swp391.sportcentermanagementsystem_v2.entity.CoachSlot;
import com.swp391.sportcentermanagementsystem_v2.entity.FacilityRoom;
import com.swp391.sportcentermanagementsystem_v2.entity.TimeSlot;
import com.swp391.sportcentermanagementsystem_v2.entity.User;
import com.swp391.sportcentermanagementsystem_v2.exception.BusinessRuleException;
import com.swp391.sportcentermanagementsystem_v2.repository.CoachSlotRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.FacilityRoomRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.OrderRepository;
import com.swp391.sportcentermanagementsystem_v2.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

// Dời lịch và xử lý sự cố của buổi 1-1 thuộc yêu cầu (BR-ONE-04, BR-ONE-05). Ưu tiên giữ buổi: đổi phòng, đổi coach, dời giờ;
// hoàn tiền (CoachSlotService.managerCancel) chỉ là bước cuối
@Service
@RequiredArgsConstructor
public class CoachSlotRescheduleService {

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("dd/MM HH:mm")
            .withZone(SportClassService.CENTER_ZONE);

    private final CoachSlotRepository slotRepository;
    private final CoachSlotService coachSlotService;
    private final UserRepository userRepository;
    private final TimeSlotService timeSlotService;
    private final FacilityRoomRepository facilityRoomRepository;
    private final OrderRepository orderRepository;
    private final SystemSettingService settings;
    private final NotificationService notificationService;
    private final AuditService auditService;

    // ====================== MEMBER ======================

    // Dời một buổi đã trả tiền sang ngày và khung giờ khác. Báo trước từ N giờ thì dời được (không giới hạn số lần),
    // buổi "chờ xếp lại" thì dời bất cứ lúc nào trước giờ học và được đổi cả coach. Buổi mới giữ giá và đơn của buổi cũ
    @Transactional
    public CoachSlotResponse reschedule(String memberEmail, Long slotId, RescheduleSlotRequest in) {
        User member = coachSlotService.activeUser(memberEmail, "MEMBER", "Chỉ hội viên đang hoạt động mới dời lịch được");
        CoachSlot old = coachSlotService.lockSlot(slotId);
        if (old.getMember() == null || !old.getMember().getId().equals(member.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Bạn chỉ được dời buổi của chính mình");
        }
        if (old.getRequest() == null || !"BOOKED".equals(old.getStatus()) || old.getCategory() == null) {
            throw new BusinessRuleException("Chỉ dời được buổi 1-1 đã thanh toán");
        }
        OffsetDateTime now = OffsetDateTime.now();
        if (!old.getStartAt().isAfter(now)) {
            throw new BusinessRuleException("Buổi học đã bắt đầu");
        }
        boolean centerAsked = old.isNeedsReschedule();
        int hours = settings.getInt("one_on_one.reschedule_before_hours");
        if (!centerAsked && Duration.between(now, old.getStartAt()).toMinutes() < hours * 60L) {
            throw new BusinessRuleException("Chỉ dời được buổi nếu báo trước ít nhất " + hours + " giờ trước khi buổi bắt đầu");
        }

        User coach = old.getCoach();
        if (in.getCoachId() != null && !in.getCoachId().equals(coach.getId())) {
            if (!centerAsked) {
                throw new BusinessRuleException("Chỉ đổi được coach khi trung tâm yêu cầu bạn xếp lại lịch");
            }
            coach = userRepository.findById(in.getCoachId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy coach"));
        }
        if (coach.getRole() == null || !"COACH".equals(coach.getRole().getCode()) || !Boolean.TRUE.equals(coach.getIsActive())) {
            throw new BusinessRuleException("Hãy chọn coach đang hoạt động, coach trước đã không còn dạy");
        }
        if (!coachSlotService.coachTeaches(coach.getId(), old.getCategory().getId())) {
            throw new BusinessRuleException(coach.getFullName() + " không dạy môn " + old.getCategory().getName());
        }
        TimeSlot timeSlot = timeSlotService.requireActive(in.getTimeSlotId());

        // Hủy dòng cũ trước để dòng mới không bị tính trùng với chính nó; lỗi ở các bước sau thì cả giao dịch hoàn tác.
        // DB cấm đổi giờ trên dòng đã đặt (BR-ONE-01), nên buổi chuyển sang một dòng mới
        User previousCoach = old.getCoach();
        old.setStatus("CANCELED");
        old.setCanceledBy(member);
        old.setCanceledAt(now);
        slotRepository.saveAndFlush(old);

        CoachSlot moved = coachSlotService.createSlot(coach, old.getCategory(), null, in.getDate(), timeSlot);
        coachSlotService.checkMemberFree(member.getId(), moved);
        moved.setStatus("BOOKED");
        moved.setMember(member);
        moved.setPrice(old.getPrice());
        moved.setRequest(old.getRequest());
        slotRepository.saveAndFlush(moved);
        orderRepository.findByCoachSlot_Id(old.getId()).ifPresent(order -> {
            order.setCoachSlot(moved);
            orderRepository.saveAndFlush(order);
        });

        auditService.log(member, "COACH_SLOT_RESCHEDULED", "coach_slot", moved.getId(), Map.of(
                "fromSlotId", old.getId(), "fromStartAt", old.getStartAt().toString(), "toStartAt", moved.getStartAt().toString()));
        notificationService.send(coach.getId(), "BOOKING", "Buổi học đã được dời lịch", member.getFullName()
                + " đã dời một buổi 1-1 từ " + WHEN.format(old.getStartAt()) + " sang " + WHEN.format(moved.getStartAt()) + ".");
        if (!previousCoach.getId().equals(coach.getId())) {
            notificationService.send(previousCoach.getId(), "BOOKING", "Buổi học đã bị hủy",
                    "Buổi 1-1 ngày " + WHEN.format(old.getStartAt()) + " đã được chuyển sang coach khác.");
        }
        return coachSlotService.toResponse(moved);
    }

    // ====================== MANAGER ======================

    // Sự cố phòng: đổi sang phòng khác cùng giờ. Không gửi roomId thì tự chọn phòng trống đúng môn. DB cấm sửa phòng
    // trên dòng đã đặt nên buổi chuyển sang dòng mới (CoachSlotService.rebook)
    @Transactional
    public CoachSlotResponse changeRoom(String managerEmail, Long slotId, Long roomId) {
        User manager = coachSlotService.activeUser(managerEmail, "MANAGER", "Chỉ quản lý đang hoạt động mới đổi được phòng");
        CoachSlot slot = requireLive(coachSlotService.lockSlot(slotId));
        if (slot.getCategory() == null) {
            throw new BusinessRuleException("Buổi này chưa gắn môn thể thao");
        }
        LocalDate date = slot.getStartAt().atZoneSameInstant(SportClassService.CENTER_ZONE).toLocalDate();
        LocalTime from = slot.getStartAt().atZoneSameInstant(SportClassService.CENTER_ZONE).toLocalTime();
        LocalTime to = slot.getEndAt().atZoneSameInstant(SportClassService.CENTER_ZONE).toLocalTime();
        Long sportId = slot.getCategory().getId();
        Long currentRoomId = slot.getRoom() == null ? null : slot.getRoom().getId();

        FacilityRoom target;
        if (roomId != null) {
            target = facilityRoomRepository.findById(roomId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy phòng"));
            if (target.getId().equals(currentRoomId)) {
                throw new BusinessRuleException("Buổi học đã ở phòng này");
            }
            if (!coachSlotService.roomUsable(target, sportId, date, from, to, slot.getStartAt(), slot.getEndAt())) {
                throw new BusinessRuleException("Room " + target.getRoomName() + " không dùng được cho buổi này");
            }
        } else {
            target = facilityRoomRepository.findAvailableWithSports().stream()
                    .filter(r -> !r.getId().equals(currentRoomId))
                    .filter(r -> coachSlotService.roomUsable(r, sportId, date, from, to, slot.getStartAt(), slot.getEndAt()))
                    .findFirst()
                    .orElseThrow(() -> new BusinessRuleException("Không còn phòng trống nào khác cho " + slot.getCategory().getName()
                            + " vào giờ đó"));
        }
        CoachSlot moved = coachSlotService.rebook(slot, slot.getCoach(), target, manager);

        auditService.log(manager, "COACH_SLOT_ROOM_CHANGED", "coach_slot", moved.getId(), Map.of(
                "fromSlotId", slotId, "toRoomId", target.getId()));
        String text = "Buổi 1-1 ngày " + WHEN.format(slot.getStartAt()) + " đã chuyển sang phòng " + target.getRoomName() + ".";
        notificationService.send(slot.getMember().getId(), "BOOKING", "Đã đổi phòng", text);
        notificationService.send(slot.getCoach().getId(), "BOOKING", "Đã đổi phòng", text);
        return coachSlotService.toResponse(moved);
    }

    // Sự cố coach: đổi sang coach khác dạy đúng môn và rảnh đúng giờ. Giá chung nên giá và đơn không đổi, member chỉ được báo
    @Transactional
    public CoachSlotResponse changeCoach(String managerEmail, Long slotId, Long coachId) {
        User manager = coachSlotService.activeUser(managerEmail, "MANAGER", "Chỉ quản lý đang hoạt động mới đổi được coach");
        CoachSlot slot = requireLive(coachSlotService.lockSlot(slotId));
        List<User> candidates = coachId != null
                ? List.of(userRepository.findById(coachId)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy coach")))
                : userRepository.findByRole_CodeAndIsActiveTrue("COACH");
        candidates = candidates.stream()
                .filter(c -> !c.getId().equals(slot.getCoach().getId()))
                .filter(c -> c.getRole() != null && "COACH".equals(c.getRole().getCode()) && Boolean.TRUE.equals(c.getIsActive()))
                .toList();
        User replacement = coachSlotService.findReplacement(slot, candidates);
        if (replacement == null) {
            throw new BusinessRuleException(coachId != null
                    ? "Coach đó không dạy môn này hoặc đang bận vào giờ đó"
                    : "Không còn coach nào rảnh vào giờ đó");
        }
        return coachSlotService.toResponse(coachSlotService.reassign(slot, replacement, "trung tâm đã đổi coach", manager));
    }

    // Không đổi được phòng hay coach: giữ buổi, hội viên được báo và chọn giờ mới bất cứ lúc nào trước giờ học
    @Transactional
    public CoachSlotResponse markNeedsReschedule(String managerEmail, Long slotId, String reason) {
        User manager = coachSlotService.activeUser(managerEmail, "MANAGER", "Chỉ quản lý đang hoạt động mới yêu cầu xếp lại lịch được");
        CoachSlot slot = requireLive(coachSlotService.lockSlot(slotId));
        if (slot.getRequest() == null || !"BOOKED".equals(slot.getStatus())) {
            throw new BusinessRuleException("Chỉ buổi 1-1 đã thanh toán mới chuyển sang chờ xếp lại được");
        }
        if (slot.isNeedsReschedule()) {
            throw new BusinessRuleException("Buổi này đang chờ xếp lại giờ");
        }
        slot.setNeedsReschedule(true);
        slotRepository.saveAndFlush(slot);

        auditService.log(manager, "COACH_SLOT_NEEDS_RESCHEDULE", "coach_slot", slotId, Map.of("reason", reason));
        notificationService.sendWithEmail(slot.getMember().getId(), "BOOKING", "Hãy chọn giờ mới cho buổi của bạn",
                "Buổi của bạn ngày " + WHEN.format(slot.getStartAt()) + " không diễn ra được (" + reason
                        + "). Hãy chọn giờ mới bất cứ lúc nào trước khi buổi bắt đầu, bạn vẫn giữ buổi này.",
                "Sport Center - Buổi 1-1 của bạn cần dời sang giờ khác",
                "Buổi 1-1 lúc " + WHEN.format(slot.getStartAt()) + " không thể diễn ra (" + reason
                        + "). Bạn vào mục buổi 1-1 để chọn giờ mới trước giờ học, buổi này vẫn được giữ cho bạn.");
        return coachSlotService.toResponse(slot);
    }

    // Chỉ xử lý được buổi đang giữ chỗ hoặc đã đặt và chưa bắt đầu
    private CoachSlot requireLive(CoachSlot slot) {
        if (!List.of("HELD", "BOOKED").contains(slot.getStatus())) {
            throw new BusinessRuleException("Chỉ xử lý được buổi đang giữ chỗ hoặc đã đặt (trạng thái hiện tại: " + slot.getStatus() + ")");
        }
        if (!slot.getStartAt().isAfter(OffsetDateTime.now())) {
            throw new BusinessRuleException("Buổi học đã bắt đầu");
        }
        return slot;
    }
}
