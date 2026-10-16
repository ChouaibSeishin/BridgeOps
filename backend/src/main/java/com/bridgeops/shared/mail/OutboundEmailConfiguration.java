package com.bridgeops.shared.mail;


import com.bridgeops.shared.web.DomainFailure;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.mail.javamail.JavaMailSenderImpl;

@Configuration
public class OutboundEmailConfiguration {
 @Bean public OutboundEmail outboundEmail(@Value("${bridgeops.invitations.smtp.enabled:false}") boolean enabled,
     @Value("${bridgeops.invitations.smtp.host:}") String host,@Value("${bridgeops.invitations.smtp.port:587}") int port,
     @Value("${bridgeops.invitations.smtp.username:}") String username,@Value("${bridgeops.invitations.smtp.password:}") String password,
     @Value("${bridgeops.invitations.smtp.from:}") String from,@Value("${bridgeops.invitations.smtp.ssl:false}") boolean ssl){
    if(!enabled)return (recipient,subject,text)->{throw new DomainFailure(503,"DELIVERY_UNAVAILABLE");};
    var sender=new JavaMailSenderImpl();sender.setHost(host);sender.setPort(port);sender.setUsername(username);sender.setPassword(password);
    return new SmtpOutboundEmail(sender,from,ssl);
 }
}
