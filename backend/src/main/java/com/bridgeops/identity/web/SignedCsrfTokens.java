package com.bridgeops.identity.web;

import com.bridgeops.identity.application.RefreshTokens;
import jakarta.servlet.http.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Clock;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.security.web.csrf.*;

/** Signed double-submit cookie; signature binds the token to the presented refresh credential. */
public final class SignedCsrfTokens implements CsrfTokenRepository {
    private static final long LIFETIME = 1800;
    private static final String BINDING = SignedCsrfTokens.class.getName() + ".binding";
    private final byte[] key;
    private final BrowserCookies cookies;
    private final RefreshTokens random;
    private final Clock clock;
    public SignedCsrfTokens(byte[] key, BrowserCookies cookies, RefreshTokens random, Clock clock) {
        if (key.length != 32) throw new IllegalArgumentException("CSRF key must contain 32 random bytes.");
        this.key = key.clone(); this.cookies = cookies; this.random = random; this.clock = clock;
    }
    @Override public CsrfToken generateToken(HttpServletRequest request) {
        String payload = random.generate() + "." + (clock.instant().getEpochSecond() + LIFETIME);
        return token(payload + "." + sign(payload, binding(request)));
    }
    @Override public CsrfToken loadToken(HttpServletRequest request) {
        String raw = cookies.csrf(request);
        if (raw == null || raw.length() > 180) return null;
        try {
            String[] fields = raw.split("\\.", -1);
            if (fields.length != 3 || !random.isWellFormed(fields[0]) || !random.isWellFormed(fields[2])) return null;
            long expires = Long.parseLong(fields[1]), now = clock.instant().getEpochSecond();
            if (expires <= now || expires > now + LIFETIME) return null;
            if (!MessageDigest.isEqual(fields[2].getBytes(StandardCharsets.US_ASCII),
                    sign(fields[0] + "." + fields[1], binding(request)).getBytes(StandardCharsets.US_ASCII))) return null;
            return token(raw);
        } catch (IllegalArgumentException invalid) { return null; }
    }
    @Override public void saveToken(CsrfToken token, HttpServletRequest request, HttpServletResponse response) {
        cookies.csrf(response, token == null ? "" : token.getToken(), token == null ? 0 : LIFETIME);
    }
    public void rotate(HttpServletRequest request, HttpServletResponse response, String refresh) {
        request.setAttribute(BINDING, refresh == null ? "-" : random.hash(refresh));
        saveToken(generateToken(request), request, response);
    }
    private String binding(HttpServletRequest request) {
        if (request.getAttribute(BINDING) instanceof String binding) return binding;
        String refresh = cookies.refresh(request);
        return refresh == null ? "-" : random.hash(refresh);
    }
    private String sign(String payload, String binding) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal((payload + "." + binding).getBytes(StandardCharsets.US_ASCII)));
        } catch (GeneralSecurityException unavailable) { throw new IllegalStateException("CSRF signing unavailable."); }
    }
    private CsrfToken token(String raw) { return new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", raw); }
}