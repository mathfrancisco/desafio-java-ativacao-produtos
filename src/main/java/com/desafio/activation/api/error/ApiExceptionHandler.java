package com.desafio.activation.api.error;

import com.desafio.activation.application.exceptions.DuplicateExternalIdException;
import com.desafio.activation.application.exceptions.RequestNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;
import java.util.List;
import java.util.Map;

/** Maps every error to RFC 9457 Problem Details without leaking internal details. */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private static final URI VALIDATION = URI.create("/problems/validation");

    @ExceptionHandler(RequestNotFoundException.class)
    ProblemDetail handleNotFound(RequestNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "/problems/not-found", "Activation request not found");
    }

    @ExceptionHandler(DuplicateExternalIdException.class)
    ProblemDetail handleDuplicate(DuplicateExternalIdException e) {
        return problem(HttpStatus.CONFLICT, "/problems/duplicate-external-id",
                "externalId was already used with different content");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ProblemDetail handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        var problem = problem(HttpStatus.BAD_REQUEST, VALIDATION.toString(), "Invalid request parameter");
        problem.setProperty("errors", List.of(fieldError(e.getName(), "has an invalid value")));
        return problem;
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception e) {
        log.error("Unexpected error", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "/problems/internal", "An unexpected error occurred");
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException e,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        var problem = problem(HttpStatus.BAD_REQUEST, VALIDATION.toString(), "Request has invalid fields");
        problem.setProperty("errors", e.getBindingResult().getFieldErrors().stream()
                .map(error -> fieldError(error.getField(), error.getDefaultMessage()))
                .toList());
        return ResponseEntity.badRequest().body(problem);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException e,
                                                                            HttpHeaders headers,
                                                                            HttpStatusCode status,
                                                                            WebRequest request) {
        var problem = problem(HttpStatus.BAD_REQUEST, VALIDATION.toString(), "Request has invalid parameters");
        problem.setProperty("errors", e.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> fieldError(result.getMethodParameter().getParameterName(),
                                error.getDefaultMessage())))
                .toList());
        return ResponseEntity.badRequest().body(problem);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException e,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        return ResponseEntity.badRequest()
                .body(problem(HttpStatus.BAD_REQUEST, VALIDATION.toString(), "Malformed JSON request body"));
    }

    /** Adds the correlation id to the problems produced by the Spring MVC defaults. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception e, @Nullable Object body, HttpHeaders headers,
                                                             HttpStatusCode status, WebRequest request) {
        if (body instanceof ProblemDetail problem) {
            problem.setProperty("correlationId", MDC.get("correlationId"));
        }
        return super.handleExceptionInternal(e, body, headers, status, request);
    }

    private static ProblemDetail problem(HttpStatus status, String type, String detail) {
        var problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(type));
        problem.setProperty("correlationId", MDC.get("correlationId"));
        return problem;
    }

    private static Map<String, String> fieldError(String field, String message) {
        return Map.of("field", field, "message", message == null ? "is invalid" : message);
    }
}
