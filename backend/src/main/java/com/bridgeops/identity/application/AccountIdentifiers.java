package com.bridgeops.identity.application;

import java.util.Locale;

public final class AccountIdentifiers {
    private AccountIdentifiers() { }
    public static String email(String raw) {
        if(raw==null) throw new IllegalArgumentException("Invalid account details.");
        String value=raw.trim(); int at=value.indexOf('@');
        if(value.length()>254 || !value.matches("[\\x21-\\x7E]+") || at<=0 || at==value.length()-1 || at!=value.lastIndexOf('@'))
            throw new IllegalArgumentException("Invalid account details.");
        return value.toLowerCase(Locale.ROOT);
    }
    public static String displayName(String raw) {
        if(raw==null || raw.isBlank() || raw.trim().length()>120 || raw.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid account details.");
        return raw.trim();
    }
}
