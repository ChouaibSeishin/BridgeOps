package com.bridgeops.organizations.infrastructure;
import com.bridgeops.organizations.application.InvitationDelivery;
import com.bridgeops.shared.mail.OutboundEmail;
import java.time.Instant;
import org.springframework.mail.javamail.JavaMailSenderImpl;
public final class SmtpInvitationDelivery implements InvitationDelivery {
 private final OutboundEmail email;
 public SmtpInvitationDelivery(OutboundEmail email){this.email=email;}
 public SmtpInvitationDelivery(JavaMailSenderImpl sender,String from,boolean ssl){this(new com.bridgeops.shared.mail.SmtpOutboundEmail(sender,from,ssl));}
 @Override public void deliver(String recipient,String token,Instant expires){email.send(recipient,"BridgeOps invitation","You have been invited to BridgeOps. This recipient-bound token can be used once before "+expires+".\nSubmit it in the invitation API request body; never put it in a URL or logs.\n\n"+token+"\n\nExisting accounts must sign in to accept. New accounts use invitation-only enrollment. Ignore this message if unexpected.");}
}
