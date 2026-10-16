package com.bridgeops.identity.web;
import com.bridgeops.shared.web.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
@RestControllerAdvice(assignableTypes=PasswordController.class)
public class PasswordErrors {
 @ExceptionHandler(DomainFailure.class) void domain(DomainFailure failure,HttpServletRequest request,HttpServletResponse response)throws IOException{ApiErrors.write(request,response,failure.status(),failure.code(),"Password operation was not accepted.");}
 @ExceptionHandler({IllegalArgumentException.class,HttpMessageNotReadableException.class,MethodArgumentNotValidException.class}) void invalid(HttpServletRequest request,HttpServletResponse response)throws IOException{ApiErrors.write(request,response,400,"INVALID_REQUEST","Request body is invalid.");}
 @ExceptionHandler({DataAccessException.class,TransactionException.class}) void unavailable(HttpServletRequest request,HttpServletResponse response)throws IOException{ApiErrors.write(request,response,503,"DEPENDENCY_UNAVAILABLE","Service is temporarily unavailable.");}
}
