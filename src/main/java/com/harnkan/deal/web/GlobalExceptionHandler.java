package com.harnkan.deal.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import io.grpc.Status;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.harnkan.deal.exception.DealNotFoundException;
import com.harnkan.deal.exception.DealPermissionException;
import com.harnkan.deal.exception.DealStateException;
import com.harnkan.deal.exception.DealValidationException;
import com.harnkan.deal.exception.ParticipantNotFoundException;
import com.harnkan.deal.exception.PaymentServiceException;
import com.harnkan.deal.exception.UnauthorizedException;
import com.harnkan.deal.dto.ApiErrorDto;

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
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiErrorDto.of(e.getCode(), e.getMessage()));
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ApiErrorDto> handleUnauthorized(UnauthorizedException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiErrorDto.of("UNAUTHORIZED", e.getMessage()));
    }

    @ExceptionHandler(ParticipantNotFoundException.class)
    public ResponseEntity<ApiErrorDto> handleParticipantNotFound(ParticipantNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiErrorDto.of("PARTICIPANT_NOT_FOUND", e.getMessage()));
    }

    @ExceptionHandler({ MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class })
    public ResponseEntity<ApiErrorDto> handleBadRequest(Exception e) {
        return ResponseEntity.badRequest().body(ApiErrorDto.of("VALIDATION_ERROR", "Invalid request: " + e.getMessage()));
    }

    @ExceptionHandler(PaymentServiceException.class)
    public ResponseEntity<ApiErrorDto> handlePaymentServiceDown(PaymentServiceException e) {
        if (e.is(Status.Code.ALREADY_EXISTS)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiErrorDto.of("ALREADY_JOINED", e.getMessage()));
        }
        if (e.is(Status.Code.FAILED_PRECONDITION)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ApiErrorDto.of("PAYMENT_STATE_CONFLICT", e.getMessage()));
        }
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(ApiErrorDto.of("PAYMENT_SERVICE_UNAVAILABLE", e.getMessage()));
    }
}
