package com.bridgeops.organizations.infrastructure;

import com.bridgeops.shared.web.DomainFailure;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.mail.*;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import static org.assertj.core.api.Assertions.*;

class SmtpInvitationDeliveryTest {
 static class Sink extends JavaMailSenderImpl {
    SimpleMailMessage captured;boolean fail;
    @Override public void send(SimpleMailMessage message){if(fail)throw new MailAuthenticationException("synthetic-provider-detail");captured=message;}
 }
 Sink sender(){var sender=new Sink();sender.setHost("smtp.example.invalid");sender.setPort(587);sender.setUsername("synthetic-mail-user");sender.setPassword("synthetic-mail-fixture");return sender;}
 @Test void startTlsAuthenticationHostnameAndTimeoutsAreMandatory(){
    var sender=sender();sender.getJavaMailProperties().setProperty("mail.smtp.ssl.trust","*");var delivery=new SmtpInvitationDelivery(sender,"sender@example.invalid",false);
    var properties=sender.getJavaMailProperties();assertThat(properties.getProperty("mail.smtp.auth")).isEqualTo("true");
    assertThat(properties.getProperty("mail.smtp.starttls.enable")).isEqualTo("true");assertThat(properties.getProperty("mail.smtp.starttls.required")).isEqualTo("true");
    assertThat(properties.getProperty("mail.smtp.ssl.checkserveridentity")).isEqualTo("true");assertThat(properties.getProperty("mail.smtp.ssl.trust")).isNull();assertThat(properties.getProperty("mail.debug")).isEqualTo("false");
    for(String field:java.util.List.of("connectiontimeout","timeout","writetimeout"))assertThat(properties.getProperty("mail.smtp."+field)).isEqualTo("5000");
    delivery.deliver("recipient@example.invalid","synthetic-one-use-proof",Instant.now().plusSeconds(3600));
    assertThat(sender.captured.getTo()).containsExactly("recipient@example.invalid");assertThat(sender.captured.getText()).contains("synthetic-one-use-proof").doesNotContain("?token=","#token=");
 }
 @Test void implicitTlsModeEncryptsTransportAndRejectsInvalidMailboxes(){
    var sender=sender();var delivery=new SmtpInvitationDelivery(sender,"sender@example.invalid",true);
    assertThat(sender.getJavaMailProperties().getProperty("mail.smtp.ssl.enable")).isEqualTo("true");
    assertThatThrownBy(()->delivery.deliver("victim@example.invalid,extra","synthetic",Instant.now())).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(()->delivery.deliver("victim@example.invalid\r\nBcc:extra@example.invalid","synthetic",Instant.now())).isInstanceOf(IllegalArgumentException.class);assertThat(sender.captured).isNull();
 }
 @Test void disabledAndIncompleteConfigurationFailClosedWithoutMail(){
    var config=new com.bridgeops.shared.mail.OutboundEmailConfiguration();var disabled=config.outboundEmail(false,"",587,"","","",false);
    assertThatThrownBy(()->disabled.send("recipient@example.invalid","Synthetic subject","Synthetic body")).isInstanceOf(DomainFailure.class).hasMessage("Operation was not accepted.");
    assertThatThrownBy(()->config.outboundEmail(true,"",587,"","","",false)).isInstanceOf(IllegalStateException.class).hasMessage("SMTP configuration is incomplete.");
 }
 @Test void providerFailureIsRedactedAndNotChainedIntoHttpErrors(){
    var sender=sender();sender.fail=true;var delivery=new SmtpInvitationDelivery(sender,"sender@example.invalid",false);
    assertThatThrownBy(()->delivery.deliver("recipient@example.invalid","synthetic-one-use-proof",Instant.now())).isInstanceOf(DomainFailure.class)
        .hasMessage("Operation was not accepted.").hasNoCause();
 }
}
