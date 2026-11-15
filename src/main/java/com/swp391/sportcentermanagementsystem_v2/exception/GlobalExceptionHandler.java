package com.swp391.sportcentermanagementsystem_v2.exception;

import jakarta.persistence.EntityNotFoundException;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatusCode;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(BusinessRuleException.class)
    public ResponseEntity<String> handleBusinessRuleException(BusinessRuleException BRE) {
        return new ResponseEntity<>(BRE.getMessage(), HttpStatus.CONFLICT);
    }

    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<String> handleEntityNotFoundException(EntityNotFoundException ENF) {
        return new ResponseEntity<>(ENF.getMessage(), HttpStatus.NOT_FOUND);
    }

    // Luật được database chốt chặn cuối (trigger, exclusion constraint) trả về 409 kèm câu mô tả.
    // Lỗi database khác không thuộc luật nghiệp vụ thì ném lại, vẫn ra 500 như trước.
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<String> handleDataAccessException(DataAccessException DAE) {
        String detail = NestedExceptionUtils.getMostSpecificCause(DAE).getMessage();
        String message = detail == null ? null : databaseRuleMessage(detail);
        if (message == null) {
            throw DAE;
        }
        return new ResponseEntity<>(message, HttpStatus.CONFLICT);
    }

    private String databaseRuleMessage(String detail) {
        if (detail.contains("ex_session_coach_overlap")) {
            return "Huấn luyện viên đã có buổi dạy trùng giờ này, hãy chọn giờ khác";
        }
        if (detail.contains("ex_slot_coach_overlap")) {
            return "Huấn luyện viên đã có buổi 1-1 trùng giờ này, hãy chọn giờ khác";
        }
        if (detail.contains("ex_session_room_overlap")) {
            return "Phòng đã có buổi học trùng giờ này, hãy chọn giờ hoặc phòng khác";
        }
        // Trigger của database ghi lỗi dạng "BR-CLS-02: ...": đổi mã luật thành câu cho người dùng đọc
        int start = detail.indexOf("BR-");
        if (start < 0) {
            return null;
        }
        int colon = detail.indexOf(':', start);
        String code = colon < 0 ? "" : detail.substring(start, colon);
        return DATABASE_RULES.getOrDefault(code, "Thao tác này vi phạm quy định của trung tâm, vui lòng kiểm tra lại");
    }

    // Câu cho từng luật mà trigger của database chặn (xem database/schema_v3.sql)
    private static final java.util.Map<String, String> DATABASE_RULES = java.util.Map.ofEntries(
            java.util.Map.entry("BR-AUD-01", "Nhật ký hệ thống không được sửa hoặc xóa"),
            java.util.Map.entry("BR-CLS-02", "Sĩ số lớp vượt quá sức chứa của phòng"),
            java.util.Map.entry("BR-CLS-04", "Lớp đã có học viên đăng ký nên không sửa được lịch, học phí hay ngày khai giảng"),
            java.util.Map.entry("BR-SCH-01", "Huấn luyện viên đã có buổi khác trùng giờ này"),
            java.util.Map.entry("BR-SCH-02", "Phòng đang bảo trì, đã đóng hoặc đã có buổi khác trùng giờ này"),
            java.util.Map.entry("BR-SCH-04", "Huấn luyện viên hoặc phòng không thuộc môn của lớp này"),
            java.util.Map.entry("BR-ONE-01", "Buổi 1-1 này đã có người đặt nên không sửa hoặc xóa được"),
            java.util.Map.entry("BR-ENR", "Lớp này hiện không nhận đăng ký"),
            java.util.Map.entry("BR-ENR-02", "Lớp đã khai giảng nên không nhận thêm học viên"),
            java.util.Map.entry("BR-WL-01", "Lớp đã hết chỗ, bạn có thể vào danh sách chờ"));

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<String> handleMethodArgumentNotValidException(MethodArgumentNotValidException BRE) {
        String message = BRE.getBindingResult()
                .getFieldErrors()
                .get(0)
                .getDefaultMessage();
        // Câu mặc định của thư viện kiểm tra dữ liệu là tiếng Anh: thay bằng câu chung
        if (message == null || !message.matches("(?s).*[À-ỹđĐ].*")) {
            message = "Thông tin nhập chưa hợp lệ, vui lòng kiểm tra lại";
        }
        return new ResponseEntity<>(message, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<String> handleInvalidCredentialsException(InvalidCredentialsException BRE) {
        return new ResponseEntity<>(BRE.getMessage(), HttpStatus.UNAUTHORIZED);
    }

    // Handle HTTP exceptions thrown by services and preserve their status codes
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<String> handleResponseStatusException(
            ResponseStatusException ex) {

        HttpStatusCode status = ex.getStatusCode();

        String message = ex.getReason();

        if (message == null) {
            message = "Yêu cầu không thành công";
        }

        return ResponseEntity.status(status).body(message);
    }

}
