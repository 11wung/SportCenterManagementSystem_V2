package com.swp391.sportcentermanagementsystem_v2.service;

import com.swp391.sportcentermanagementsystem_v2.dto.requestDTO.CreateTimeSlotRequest;
import com.swp391.sportcentermanagementsystem_v2.dto.responseDTO.TimeSlotResponse;
import com.swp391.sportcentermanagementsystem_v2.entity.TimeSlot;
import com.swp391.sportcentermanagementsystem_v2.exception.BusinessRuleException;
import com.swp391.sportcentermanagementsystem_v2.repository.TimeSlotRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

// Slot giờ cố định: lịch tuần của class, buổi 1-1 và khung đánh giá thể lực đều chọn theo slot.
// Giờ của slot không sửa được, chỉ tắt; lịch đã tạo giữ giờ đã copy
@Service
@RequiredArgsConstructor
public class TimeSlotService {

    private final TimeSlotRepository timeSlotRepository;
    private final CenterHoursService centerHours;
    private final SystemSettingService settings;

    @Transactional
    public TimeSlotResponse create(CreateTimeSlotRequest request) {
        if (!request.getEndTime().isAfter(request.getStartTime())) {
            throw new BusinessRuleException("End time must be after start time");
        }
        centerHours.requireWithin(request.getStartTime(), request.getEndTime(), "A time slot");
        if (timeSlotRepository.existsBySlotNo(request.getSlotNo())) {
            throw new BusinessRuleException("Slot number " + request.getSlotNo() + " already exists");
        }

        // BR-SCH-01: hai slot phải cách nhau tối thiểu khoảng nghỉ, để hai buổi liên tiếp của một coach không bị báo trùng
        int gap = settings.getInt("schedule.buffer_minutes");
        for (TimeSlot other : timeSlotRepository.findAllByOrderByStartTime()) {
            boolean tooClose = request.getStartTime().isBefore(other.getEndTime().plusMinutes(gap))
                    && other.getStartTime().isBefore(request.getEndTime().plusMinutes(gap));
            if (tooClose) {
                throw new BusinessRuleException("The slot overlaps or is less than " + gap
                        + " minutes apart from slot " + other.getSlotNo() + " ("
                        + other.getStartTime() + " to " + other.getEndTime() + ")");
            }
        }

        TimeSlot slot = new TimeSlot();
        slot.setSlotNo(request.getSlotNo());
        slot.setStartTime(request.getStartTime());
        slot.setEndTime(request.getEndTime());
        try {
            return new TimeSlotResponse(timeSlotRepository.saveAndFlush(slot));
        } catch (DataIntegrityViolationException ex) {
            // hai manager tạo cùng số slot cùng lúc
            throw new BusinessRuleException("Slot number " + request.getSlotNo() + " already exists");
        }
    }

    @Transactional
    public TimeSlotResponse setActive(Long id, boolean active) {
        TimeSlot slot = findById(id);
        slot.setActive(active);
        return new TimeSlotResponse(timeSlotRepository.save(slot));
    }

    @Transactional(readOnly = true)
    public List<TimeSlotResponse> listActive() {
        return timeSlotRepository.findByActiveTrueOrderByStartTime().stream().map(TimeSlotResponse::new).toList();
    }

    @Transactional(readOnly = true)
    public List<TimeSlot> activeSlots() {
        return timeSlotRepository.findByActiveTrueOrderByStartTime();
    }

    @Transactional(readOnly = true)
    public List<TimeSlotResponse> listAll() {
        return timeSlotRepository.findAllByOrderByStartTime().stream().map(TimeSlotResponse::new).toList();
    }

    // Slot để gắn vào lịch hoặc suất mới: phải tồn tại và đang bật
    @Transactional(readOnly = true)
    public TimeSlot requireActive(Long id) {
        TimeSlot slot = findById(id);
        if (!slot.isActive()) {
            throw new BusinessRuleException("Time slot " + slot.getSlotNo() + " is turned off");
        }
        return slot;
    }

    // Giờ thật của slot vào một ngày cụ thể, theo giờ trung tâm
    static OffsetDateTime startAt(TimeSlot slot, LocalDate date) {
        return date.atTime(slot.getStartTime()).atZone(SportClassService.CENTER_ZONE).toOffsetDateTime();
    }

    static OffsetDateTime endAt(TimeSlot slot, LocalDate date) {
        return date.atTime(slot.getEndTime()).atZone(SportClassService.CENTER_ZONE).toOffsetDateTime();
    }

    private TimeSlot findById(Long id) {
        return timeSlotRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Time slot not found with ID: " + id));
    }
}
