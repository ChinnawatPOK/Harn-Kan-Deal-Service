package com.harnkan.deal.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.harnkan.deal.exception.DealNotFoundException;
import com.harnkan.deal.exception.DealPermissionException;
import com.harnkan.deal.exception.DealStateException;
import com.harnkan.deal.exception.DealValidationException;
import com.harnkan.deal.exception.PaymentServiceException;
import com.harnkan.deal.web.dto.ApiErrorDto;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(DealValidationException.class)
    public ResponseEntity<ApiErrorDto> handleValidation(DealValidationException e) {
        return ResponseEntity.badRequest().body(ApiErrorDto.of("VALIDATION_ERROR", e.getMessage()));
    }

    @ExceptionHandler(DealNotFoundException.class)
    public ResponseEntity<ApiErrorDto> handleNotFound(DealNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiErrorDto.of("DEAL_NOT_FOUND", e.getMessage()));
    }

    @ExceptionHandler(DealPermissionException.class)
    public ResponseEntity<ApiErrorDto> handlePermission(DealPermissionException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiErrorDto.of("FORBIDDEN", e.getMessage()));
    }

    @ExceptionHandler(DealStateException.class)
    public ResponseEntity<ApiErrorDto> handleState(DealStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiErrorDto.of("INVALID_STATE", e.getMessage()));
    }

    @ExceptionHandler(PaymentServiceException.class)
    public ResponseEntity<ApiErrorDto> handlePaymentServiceDown(PaymentServiceException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(ApiErrorDto.of("PAYMENT_SERVICE_UNAVAILABLE", e.getMessage()));
    }
}
