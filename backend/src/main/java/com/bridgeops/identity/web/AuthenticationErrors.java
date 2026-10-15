package com.bridgeops.identity.web;

import com.bridgeops.shared.web.ApiErrors;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice(assignableTypes=AuthenticationController.class)
public class AuthenticationErrors {
    @ExceptionHandler({HttpMessageNotReadableException.class,MethodArgumentNotValidException.class})
    public void invalid(HttpServletRequest request,HttpServletResponse response) throws IOException {
        ApiErrors.write(request,response,400,"INVALID_REQUEST","Request body is invalid.");
    }
    @ExceptionHandler({DataAccessException.class,TransactionException.class})
    public void unavailable(HttpServletRequest request,HttpServletResponse response) throws IOException {
        ApiErrors.write(request,response,503,"DEPENDENCY_UNAVAILABLE","Service is temporarily unavailable.");
    }
}