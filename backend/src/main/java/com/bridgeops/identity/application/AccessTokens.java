package com.bridgeops.identity.application;

import com.bridgeops.identity.domain.SessionOutcome.Granted;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;

/** Identity-only JWTs. The HTTP boundary must also check live account/family state. */
public final class AccessTokens {
    public static final Duration LIFETIME = Duration.ofMinutes(10);
    private final String keyId;
    private final String issuer;
    private final String audience;
    private final Clock clock;
    private final JwtEncoder encoder;
    private final Map<String, Verifier> verifiers;
    public record PreviousKey(RSAPublicKey key, Instant acceptUntil) { }
    private record Verifier(NimbusJwtDecoder decoder, Instant acceptUntil) { }
    public record Identity(UUID userId, UUID familyId, Instant expiresAt) { }
    public static final class Issued {
        private final String value;
        private final Instant expiresAt;
        Issued(String value, Instant expiresAt) { this.value = value; this.expiresAt = expiresAt; }
        public String value() { return value; }
        public Instant expiresAt() { return expiresAt; }
        @Override public String toString() { return "AccessTokens.Issued[REDACTED]"; }
    }

    public AccessTokens(String keyId, RSAPublicKey publicKey, RSAPrivateKey privateKey,
            String issuer, String audience, Map<String, PreviousKey> previous, Clock clock) {
        if (keyId == null || !keyId.matches("[A-Za-z0-9_-]{1,64}") || publicKey.getModulus().bitLength() < 2048
                || !publicKey.getModulus().equals(privateKey.getModulus())
                || issuer == null || issuer.isBlank() || audience == null || audience.isBlank()
                || previous.size() > 1 || previous.containsKey(keyId)) {
            throw new IllegalArgumentException("Invalid access signing configuration.");
        }
        this.keyId = keyId; this.issuer = issuer; this.audience = audience; this.clock = clock;
        this.encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(new RSAKey.Builder(publicKey)
                .privateKey(privateKey).keyID(keyId).build())));
        var configured = new java.util.HashMap<String, Verifier>();
        configured.put(keyId, verifier(publicKey, Instant.MAX));
        previous.forEach((id, key) -> {
            if (!id.matches("[A-Za-z0-9_-]{1,64}") || key.key().getModulus().bitLength() < 2048
                    || key.acceptUntil().isAfter(clock.instant().plus(LIFETIME))) {
                throw new IllegalArgumentException("Previous signing key overlap exceeds ten minutes.");
            }
            configured.put(id, verifier(key.key(), key.acceptUntil()));
        });
        this.verifiers = Map.copyOf(configured);
    }

    public Issued issue(Granted session) {
        Instant now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        Instant expires = now.plus(LIFETIME);
        if (session.expiresAt().isBefore(expires)) expires = session.expiresAt().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        if (!expires.isAfter(now)) throw new IllegalArgumentException("Session has expired.");
        var claims = JwtClaimsSet.builder().issuer(issuer).audience(List.of(audience))
                .subject(session.userId().toString()).claim("sid", session.familyId().toString())
                .issuedAt(now).expiresAt(expires).id(UUID.randomUUID().toString()).build();
        String raw = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256)
                .keyId(keyId).type("JWT").build(), claims)).getTokenValue();
        return new Issued(raw, expires);
    }

    public Identity verify(String raw) {
        try {
            if (raw == null || raw.length() > 4096) throw new IllegalArgumentException();
            // Parse only to select a locally configured key. Never follow token URLs or trust unverified claims.
            var header = com.nimbusds.jwt.SignedJWT.parse(raw).getHeader();
            Verifier verifier = verifiers.get(header.getKeyID());
            if (!"RS256".equals(header.getAlgorithm().getName()) || verifier == null
                    || !clock.instant().isBefore(verifier.acceptUntil())) throw new IllegalArgumentException();
            Jwt jwt = verifier.decoder().decode(raw);
            return new Identity(UUID.fromString(jwt.getSubject()), UUID.fromString(jwt.getClaimAsString("sid")), jwt.getExpiresAt());
        } catch (Exception invalid) {
            throw new BadJwtException("Invalid access credential.");
        }
    }

    private Verifier verifier(RSAPublicKey key, Instant acceptUntil) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(key).signatureAlgorithm(SignatureAlgorithm.RS256).build();
        decoder.setJwtValidator(jwt -> {
            try {
                Instant now = clock.instant();
                Instant issued = jwt.getIssuedAt(), expires = jwt.getExpiresAt();
                boolean valid = "JWT".equals(jwt.getHeaders().get("typ"))
                        && issuer.equals(jwt.getClaimAsString("iss")) && List.of(audience).equals(jwt.getAudience())
                        && issued != null && expires != null && !issued.isAfter(now) && expires.isAfter(now)
                        && expires.isAfter(issued) && !expires.isAfter(issued.plus(LIFETIME));
                UUID.fromString(jwt.getSubject()); UUID.fromString(jwt.getClaimAsString("sid")); UUID.fromString(jwt.getId());
                if (jwt.hasClaim("nbf") && jwt.getNotBefore().isAfter(now)) valid = false;
                if (valid) return OAuth2TokenValidatorResult.success();
            } catch (RuntimeException invalid) { /* Generic rejection; never log raw credentials. */ }
            return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
        });
        return new Verifier(decoder, acceptUntil);
    }
}