package com.bridgeops.organizations.infrastructure;
import com.bridgeops.organizations.application.InvitationDelivery;
import com.bridgeops.shared.mail.OutboundEmail;
import org.springframework.context.annotation.*;
@Configuration
public class InvitationDeliveryConfiguration {
 @Bean InvitationDelivery invitationDelivery(OutboundEmail email){return new SmtpInvitationDelivery(email);}
}
