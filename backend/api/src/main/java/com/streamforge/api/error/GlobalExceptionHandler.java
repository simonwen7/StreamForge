package com.streamforge.api.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import tools.jackson.core.exc.StreamReadException;

import java.net.URI;
import java.util.List;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final URI PROBLEM_TYPE = URI.create("about:blank");

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        String detail = duplicateKey(ex)
                ? "Request body contains a duplicate JSON key."
                : "Request body is malformed or unreadable.";
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Malformed Request", detail);
        return handleExceptionInternal(ex, problem, headers, HttpStatus.BAD_REQUEST, request);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Validation Failed", "Request validation failed.");
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(this::safeFieldError)
                .toList();
        problem.setProperty("errors", errors);
        return handleExceptionInternal(ex, problem, headers, HttpStatus.BAD_REQUEST, request);
    }

    @ExceptionHandler(InvalidRequestException.class)
    public ResponseEntity<ProblemDetail> handleInvalidRequest(InvalidRequestException ex) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Validation Failed", "Request validation failed.");
        problem.setProperty("errors", ex.errors().stream()
                .map(error -> Map.of("field", error.field(), "message", error.message()))
                .toList());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(problem);
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ProblemDetail> handleNotFound(NotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(problem(HttpStatus.NOT_FOUND, "Not Found", ex.getMessage()));
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ProblemDetail> handleConflict(ConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(problem(HttpStatus.CONFLICT, "Conflict", ex.getMessage()));
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ProblemDetail> handleDataAccess(DataAccessException ex, WebRequest request) {
        log.error("Database access failed for {}", request.getDescription(false), ex);
        ProblemDetail problem = problem(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Service Unavailable",
                "The service is temporarily unavailable.");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(problem);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex, WebRequest request) {
        log.error("Unhandled exception for {}", request.getDescription(false), ex);
        ProblemDetail problem = problem(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Internal Server Error",
                "An unexpected error occurred.");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problem);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex,
            Object body,
            HttpHeaders headers,
            HttpStatusCode statusCode,
            WebRequest request) {
        Object safeBody = body instanceof ProblemDetail ? body : problem(
                HttpStatus.valueOf(statusCode.value()),
                HttpStatus.valueOf(statusCode.value()).getReasonPhrase(),
                "The request could not be completed.");
        return super.handleExceptionInternal(ex, safeBody, headers, statusCode, request);
    }

    private static boolean duplicateKey(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof StreamReadException && current.getMessage() != null
                    && (current.getMessage().startsWith("Duplicate field")
                    || current.getMessage().startsWith("Duplicate Object property"))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private Map<String, String> safeFieldError(FieldError error) {
        String message = error.getDefaultMessage() == null ? "invalid" : error.getDefaultMessage();
        return Map.of("field", error.getField(), "message", message);
    }

    private ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setType(PROBLEM_TYPE);
        return problem;
    }
}
