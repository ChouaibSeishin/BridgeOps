package com.bridgeops.identity.web;
import com.bridgeops.identity.application.*;
import com.bridgeops.identity.application.AccessTokens.Identity;
import com.bridgeops.shared.web.ApiErrors;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/v1/auth")
@ConditionalOnProperty(name="bridgeops.auth.enabled",havingValue="true")
public class PasswordController {
 public record Change(@NotBlank @Size(max=256) String currentPassword,@NotBlank @Size(max=256) String newPassword){@Override public String toString(){return "Change[REDACTED]";}}
 public record Forgot(@NotBlank @Size(max=254) String email){@Override public String toString(){return "Forgot[REDACTED]";}}
 public record Reset(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{43}") String token,@NotBlank @Size(max=256) String newPassword){@Override public String toString(){return "Reset[REDACTED]";}}
 public record Acknowledgement(String message){}
 private final PasswordRecoveryService recovery;private final RecoveryRequests requests;private final AuthenticationThrottle throttle;private final BrowserCookies cookies;private final RefreshTokens hashes;
 public PasswordController(PasswordRecoveryService recovery,RecoveryRequests requests,AuthenticationThrottle throttle,BrowserCookies cookies,RefreshTokens hashes){this.recovery=recovery;this.requests=requests;this.throttle=throttle;this.cookies=cookies;this.hashes=hashes;}
 @PostMapping("/password/change") public void change(@AuthenticationPrincipal Identity identity,@Valid @RequestBody Change body,HttpServletRequest request,HttpServletResponse response)throws IOException{
  if(!allow("password-change",identity.userId().toString(),request,response))return;
  recovery.change(identity.userId(),identity.familyId(),body.currentPassword(),body.newPassword());cookies.clear(response);response.setStatus(204);
 }
 @PostMapping("/forgot") public Acknowledgement forgot(@Valid @RequestBody Forgot body,HttpServletRequest request,HttpServletResponse response)throws IOException{
  String email=AccountIdentifiers.email(body.email());if(!allow("forgot",email,request,response))return null;
  requests.submit(email);response.setStatus(202);return new Acknowledgement("Recovery requested. If recovery is available for this account, instructions may arrive. Delivery is not guaranteed.");
 }
 @PostMapping("/reset") public void reset(@Valid @RequestBody Reset body,HttpServletRequest request,HttpServletResponse response)throws IOException{
  if(!allow("reset",hashes.hash(body.token()),request,response))return;
  recovery.reset(body.token(),body.newPassword());cookies.clear(response);response.setStatus(204);
 }
 private boolean allow(String operation,String scope,HttpServletRequest request,HttpServletResponse response)throws IOException{
  long retry=throttle.acquire(operation,request.getRemoteAddr(),scope);if(retry==0)return true;
  response.setHeader("Retry-After",Long.toString(retry));ApiErrors.write(request,response,429,"RATE_LIMITED","Too many authentication requests.");return false;
 }
}
