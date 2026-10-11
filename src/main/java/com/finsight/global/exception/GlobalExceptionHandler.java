package com.finsight.global.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.OffsetDateTime;
import java.time.ZoneId;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final ZoneId KOREA_ZONE = ZoneId.of("Asia/Seoul");

    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleUnsupportedMethod(
            org.springframework.web.HttpRequestMethodNotSupportedException e,
            HttpServletRequest request) {
        var response = ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED);
        var supported = e.getSupportedHttpMethods();
        if (supported != null) response.allow(supported.toArray(org.springframework.http.HttpMethod[]::new));
        return response.body(new ErrorResponse(OffsetDateTime.now(KOREA_ZONE),
                405, "Method Not Allowed", "지원하지 않는 HTTP 메서드입니다.", request.getRequestURI()));
    }

    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleMissingResource(
            org.springframework.web.servlet.resource.NoResourceFoundException e,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(
                OffsetDateTime.now(KOREA_ZONE), 404, "Not Found",
                "요청한 경로를 찾을 수 없습니다.", request.getRequestURI()));
    }

    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException e,
            HttpServletRequest request) {
        String message = request.getRequestURI().equals("/api/v1/news")
                ? "유효하지 않은 뉴스 조회 조건입니다."
                : "요청 값의 형식이 올바르지 않습니다.";
        return ResponseEntity.badRequest().body(new ErrorResponse(OffsetDateTime.now(KOREA_ZONE),
                400, "Bad Request", message, request.getRequestURI()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleMalformedJson(
            HttpMessageNotReadableException e,
            HttpServletRequest request
    ) {
        String path = request.getRequestURI();

        boolean isInterestApi = "/api/v1/users/me/interests".equals(path);
        boolean isConsentApi = "/api/v1/users/me/consents".equals(path);
        boolean isReflectionApi = path.matches(
                "^/api/v1/learning-sessions/[^/]+/reflections$"
        );

        String message = isInterestApi
                ? "유효하지 않은 관심사 정보입니다."
                : (isConsentApi || isReflectionApi)
                  ? "잘못된 요청입니다."
                  : "요청 JSON 형식이 올바르지 않습니다.";

        return ResponseEntity.badRequest().body(
                new ErrorResponse(
                        OffsetDateTime.now(KOREA_ZONE),
                        400,
                        "Bad Request",
                        message,
                        path
                )
        );
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApiException(
            ApiException e,
            HttpServletRequest request
    ) {
        HttpStatus status = e.getStatus();

        ErrorResponse response = new ErrorResponse(
                OffsetDateTime.now(KOREA_ZONE),
                status.value(),
                status.getReasonPhrase(),
                e.getMessage(),
                request.getRequestURI()
        );

        return ResponseEntity
                .status(status)
                .body(response);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(
            MethodArgumentNotValidException e,
            HttpServletRequest request
    ) {
        String message = e.getBindingResult()
                .getFieldErrors()
                .stream()
                .findFirst()
                .map(error -> error.getDefaultMessage())
                .orElse("요청 값이 올바르지 않습니다.");

        ErrorResponse response = new ErrorResponse(
                OffsetDateTime.now(KOREA_ZONE),
                HttpStatus.BAD_REQUEST.value(),
                HttpStatus.BAD_REQUEST.getReasonPhrase(),
                message,
                request.getRequestURI()
        );

        return ResponseEntity
                .badRequest()
                .body(response);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleException(
            Exception e,
            HttpServletRequest request
    ) {
        ErrorResponse response = new ErrorResponse(
                OffsetDateTime.now(KOREA_ZONE),
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase(),
                "서버 내부 오류가 발생했습니다.",
                request.getRequestURI()
        );

        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(response);
    }
}
