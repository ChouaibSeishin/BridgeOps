package com.bridgeops.workflows.web;

import com.bridgeops.shared.web.*;
import com.bridgeops.workflows.application.InvalidDefinition;
import com.bridgeops.workflows.domain.GraphDefinition.Validation;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.time.Instant;
import org.springframework.dao.*;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(basePackages="com.bridgeops.workflows.web")
public class WorkflowErrors {
    public record Rejected(String code, String message, Validation details, String correlationId, Instant timestamp) {}
    @ExceptionHandler(InvalidDefinition.class) public ResponseEntity<Rejected> invalidGraph(InvalidDefinition failure, HttpServletRequest request) {
        return ResponseEntity.status(422).cacheControl(CacheControl.noStore()).body(new Rejected("INVALID_WORKFLOW", "Correct the saved definition before publishing.", failure.validation(), String.valueOf(request.getAttribute(CorrelationFilter.ATTRIBUTE)), Instant.now()));
    }
    @ExceptionHandler(DomainFailure.class) public void domain(DomainFailure failure, HttpServletRequest request, HttpServletResponse response) throws IOException { ApiErrors.write(request, response, failure.status(), failure.code(), failure.getMessage()); }
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentNotValidException.class, MethodArgumentTypeMismatchException.class, org.springframework.web.bind.ServletRequestBindingException.class, IllegalArgumentException.class})
    public void invalid(HttpServletRequest request, HttpServletResponse response) throws IOException { ApiErrors.write(request, response, 400, "INVALID_REQUEST", "Request is invalid."); }
    @ExceptionHandler(DataIntegrityViolationException.class) public void conflict(HttpServletRequest request, HttpServletResponse response) throws IOException { ApiErrors.write(request, response, 409, "RESOURCE_CONFLICT", "Resource conflicts with existing state."); }
    @ExceptionHandler({DataAccessException.class, TransactionException.class}) public void unavailable(HttpServletRequest request, HttpServletResponse response) throws IOException { ApiErrors.write(request, response, 503, "DEPENDENCY_UNAVAILABLE", "Service is temporarily unavailable."); }
}
