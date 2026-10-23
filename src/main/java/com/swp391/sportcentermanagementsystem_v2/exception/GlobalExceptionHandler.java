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
            return "Coach already has a session overlapping this time (15-minute gap required)";
        }
        if (detail.contains("ex_session_room_overlap")) {
            return "Room already has a session overlapping this time (15-minute gap required)";
        }
        // Trigger của database ghi lỗi dạng "BR-CLS-02: ...": lấy đúng dòng đó
        int start = detail.indexOf("BR-");
        if (start < 0) {
            return null;
        }
        int end = detail.indexOf('\n', start);
        return end < 0 ? detail.substring(start) : detail.substring(start, end);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<String> handleMethodArgumentNotValidException(MethodArgumentNotValidException BRE) {
        String message = BRE.getBindingResult()
                .getFieldErrors()
                .get(0)
                .getDefaultMessage();
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
            message = "Request failed!";
        }

        return ResponseEntity.status(status).body(message);
    }

}
