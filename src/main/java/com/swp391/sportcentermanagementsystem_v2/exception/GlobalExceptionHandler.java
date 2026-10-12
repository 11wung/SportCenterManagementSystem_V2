package com.swp391.sportcentermanagementsystem_v2.exception;


import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(BusinessRuleException.class)
    public ResponseEntity<String> handleBusinessRuleException(BusinessRuleException BRE) {
        return new ResponseEntity<>(BRE.getMessage(), HttpStatus.CONFLICT);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<String> handleMethodArgumentNotValidException(MethodArgumentNotValidException BRE) {
        String message = BRE.getBindingResult()
                .getFieldErrors()
                .get(0)
                .getDefaultMessage();
        return new ResponseEntity<>(message, HttpStatus.BAD_REQUEST);
    }
}
