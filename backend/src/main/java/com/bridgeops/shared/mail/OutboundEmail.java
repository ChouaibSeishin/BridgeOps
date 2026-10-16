package com.bridgeops.shared.mail;
/** Module-neutral outbound boundary. Callers own message purpose; transport never logs payloads. */
@FunctionalInterface
public interface OutboundEmail { void send(String recipient,String subject,String text); }
