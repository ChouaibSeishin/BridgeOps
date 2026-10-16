package com.bridgeops.shared.mail;



import com.bridgeops.shared.web.DomainFailure;

import org.springframework.mail.*;
import org.springframework.mail.javamail.JavaMailSenderImpl;

public final class SmtpOutboundEmail implements OutboundEmail {
 private final JavaMailSenderImpl sender;private final String from;
 public SmtpOutboundEmail(JavaMailSenderImpl sender,String from,boolean ssl){
    if(sender.getHost()==null || sender.getHost().isBlank() || sender.getUsername()==null || sender.getUsername().isBlank() || sender.getPassword()==null || sender.getPassword().isBlank() || sender.getPort()<1 || sender.getPort()>65535)throw new IllegalStateException("SMTP configuration is incomplete.");
    this.from=mailbox(from);this.sender=sender;sender.setProtocol("smtp");
    var properties=sender.getJavaMailProperties();properties.setProperty("mail.debug","false");properties.setProperty("mail.smtp.auth","true");
    properties.setProperty("mail.smtp.ssl.enable",Boolean.toString(ssl));properties.setProperty("mail.smtp.starttls.enable",Boolean.toString(!ssl));properties.setProperty("mail.smtp.starttls.required",Boolean.toString(!ssl));
    properties.setProperty("mail.smtp.ssl.checkserveridentity","true");properties.remove("mail.smtp.ssl.trust");
    for(String timeout:java.util.List.of("connectiontimeout","timeout","writetimeout"))properties.setProperty("mail.smtp."+timeout,"5000");
 }
 private static String mailbox(String raw){
    if(raw==null || raw.length()>254 || !raw.matches("[\\x21-\\x7E]+") || raw.indexOf('@')<1)throw new IllegalArgumentException("Invalid mailbox.");
    String normalized=raw.toLowerCase(java.util.Locale.ROOT);
    try{var address=new jakarta.mail.internet.InternetAddress(normalized,true);address.validate();if(!normalized.equals(address.getAddress()))throw new IllegalArgumentException();}
    catch(jakarta.mail.internet.AddressException | IllegalArgumentException invalid){throw new IllegalArgumentException("Invalid mailbox.");}
    return normalized;
 }
 @Override public void send(String recipient,String subject,String text){
    var message=new SimpleMailMessage();message.setFrom(from);message.setTo(mailbox(recipient));message.setSubject(subject);message.setText(text);
    try{sender.send(message);}catch(MailException unavailable){throw new DomainFailure(503,"DELIVERY_UNAVAILABLE");}
 }
}