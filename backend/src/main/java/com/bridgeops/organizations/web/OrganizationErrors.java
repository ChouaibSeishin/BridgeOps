package com.bridgeops.organizations.web;

import com.bridgeops.shared.web.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.dao.*;
import org.springframework.transaction.TransactionException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice(basePackages="com.bridgeops.organizations.web")
public class OrganizationErrors {
 @ExceptionHandler(DomainFailure.class) public void domain(DomainFailure failure,HttpServletRequest request,HttpServletResponse response)throws IOException{ApiErrors.write(request,response,failure.status(),failure.code(),failure.getMessage());}
 @ExceptionHandler({HttpMessageNotReadableException.class,MethodArgumentNotValidException.class,MethodArgumentTypeMismatchException.class,IllegalArgumentException.class})
 public void invalid(HttpServletRequest request,HttpServletResponse response)throws IOException{ApiErrors.write(request,response,400,"INVALID_REQUEST","Request is invalid.");}
 @ExceptionHandler(DataIntegrityViolationException.class) public void conflict(HttpServletRequest request,HttpServletResponse response)throws IOException{ApiErrors.write(request,response,409,"RESOURCE_CONFLICT","Resource conflicts with existing state.");}
 @ExceptionHandler({DataAccessException.class,TransactionException.class}) public void unavailable(HttpServletRequest request,HttpServletResponse response)throws IOException{ApiErrors.write(request,response,503,"DEPENDENCY_UNAVAILABLE","Service is temporarily unavailable.");}
}
