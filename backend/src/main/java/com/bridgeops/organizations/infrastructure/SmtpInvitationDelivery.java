package com.bridgeops.organizations.infrastructure;

import com.bridgeops.identity.application.AccountIdentifiers;
import com.bridgeops.organizations.application.InvitationDelivery;
import com.bridgeops.shared.web.DomainFailure;
import java.time.Instant;
import org.springframework.mail.*;
import org.springframework.mail.javamail.JavaMailSenderImpl;

public final class SmtpInvitationDelivery implements InvitationDelivery {
 private final JavaMailSenderImpl sender;private final String from;
 public SmtpInvitationDelivery(JavaMailSenderImpl sender,String from,boolean ssl){
    if(sender.getHost()==null || sender.getHost().isBlank() || sender.getUsername()==null || sender.getUsername().isBlank() || sender.getPassword()==null || sender.getPassword().isBlank() || sender.getPort()<1 || sender.getPort()>65535)throw new IllegalStateException("SMTP configuration is incomplete.");
    this.from=mailbox(from);this.sender=sender;sender.setProtocol("smtp");
    var properties=sender.getJavaMailProperties();properties.setProperty("mail.debug","false");properties.setProperty("mail.smtp.auth","true");
    properties.setProperty("mail.smtp.ssl.enable",Boolean.toString(ssl));properties.setProperty("mail.smtp.starttls.enable",Boolean.toString(!ssl));properties.setProperty("mail.smtp.starttls.required",Boolean.toString(!ssl));
    properties.setProperty("mail.smtp.ssl.checkserveridentity","true");properties.remove("mail.smtp.ssl.trust");
    for(String timeout:java.util.List.of("connectiontimeout","timeout","writetimeout"))properties.setProperty("mail.smtp."+timeout,"5000");
 }
 private static String mailbox(String raw){
    String normalized=AccountIdentifiers.email(raw);
    try{var address=new jakarta.mail.internet.InternetAddress(normalized,true);address.validate();if(!normalized.equals(address.getAddress()))throw new IllegalArgumentException();}
    catch(jakarta.mail.internet.AddressException | IllegalArgumentException invalid){throw new IllegalArgumentException("Invalid mailbox.");}
    return normalized;
 }
 @Override public void deliver(String recipient,String token,Instant expires){
    var message=new SimpleMailMessage();message.setFrom(from);message.setTo(mailbox(recipient));message.setSubject("BridgeOps invitation");
    message.setText("You have been invited to BridgeOps. This recipient-bound token can be used once before "+expires+".\nSubmit it in the invitation API request body; never put it in a URL or logs.\n\n"+token+"\n\nExisting accounts must sign in to accept. New accounts use invitation-only enrollment. Ignore this message if unexpected.");
    try{sender.send(message);}catch(MailException unavailable){throw new DomainFailure(503,"DELIVERY_UNAVAILABLE");}
 }
}
