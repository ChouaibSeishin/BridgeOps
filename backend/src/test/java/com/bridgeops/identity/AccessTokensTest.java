package com.bridgeops.identity;

import com.bridgeops.identity.application.AccessTokens;
import com.bridgeops.identity.domain.SessionOutcome.Granted;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.*;
import java.security.*;
import java.security.interfaces.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.BadJwtException;
import static org.assertj.core.api.Assertions.*;

class AccessTokensTest {
    private static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");
    private static final KeyPair KEYS = keys();
    private static final KeyPair OTHER = keys();
    private final UUID user = UUID.randomUUID(), family = UUID.randomUUID();
    private AccessTokens tokens(KeyPair pair, String id, Instant now, Map<String, AccessTokens.PreviousKey> previous) {
        return new AccessTokens(id, (RSAPublicKey) pair.getPublic(), (RSAPrivateKey) pair.getPrivate(),
                "https://bridgeops.invalid", "bridgeops-browser", previous, Clock.fixed(now, ZoneOffset.UTC));
    }
    @Test void roundTripHasIdentityOnlyAndRedactedDiagnostics() throws Exception {
        var tokens = tokens(KEYS, "current", NOW, Map.of());
        var issued = tokens.issue(new Granted(user, family, NOW.plusSeconds(604800), "unused"));
        assertThat(tokens.verify(issued.value())).isEqualTo(new AccessTokens.Identity(user, family, NOW.plusSeconds(600)));
        var jwt = SignedJWT.parse(issued.value());
        assertThat(jwt.getJWTClaimsSet().getClaims().keySet()).containsExactlyInAnyOrder("sub","sid","iss","aud","iat","exp","jti");
        assertThat(issued.toString()).doesNotContain(issued.value());
    }
    @Test void expiryIsCappedAndExactBoundaryRejected() {
        var issued = tokens(KEYS, "current", NOW, Map.of()).issue(new Granted(user, family, NOW.plusSeconds(20), "unused"));
        assertThat(issued.expiresAt()).isEqualTo(NOW.plusSeconds(20));
        assertThatThrownBy(() -> tokens(KEYS,"current",NOW.plusSeconds(20),Map.of()).verify(issued.value())).isInstanceOf(BadJwtException.class);
    }
    @Test void invalidClaimsAreRejectedEvenWithValidSignature() throws Exception {
        for (String claim : List.of("iss","aud","sub","sid","jti","iat","exp")) {
            var claims = claims();
            claims.put(claim, switch (claim) {
                case "iat" -> NOW.plusSeconds(1).getEpochSecond();
                case "exp" -> NOW.plusSeconds(601).getEpochSecond();
                case "aud" -> List.of("other");
                default -> "invalid";
            });
            reject(sign(KEYS,"current",claims,"JWT"));
            claims = claims(); claims.remove(claim); reject(sign(KEYS,"current",claims,"JWT"));
        }
    }
    @Test void signatureAlgorithmTypeKeyAndMalformedCredentialsAreRejected() throws Exception {
        reject(sign(OTHER,"current",claims(),"JWT")); reject(sign(KEYS,"unknown",claims(),"JWT"));
        reject(sign(KEYS,"current",claims(),"other"));
        reject(new PlainJWT(new JWTClaimsSet.Builder().subject(user.toString()).build()).serialize());
        reject("malformed"); reject(null); reject("x".repeat(4097));
        var signed = sign(KEYS,"current",claims(),"JWT");
        String[] parts = signed.split("\\.");
        reject(parts[0] + "." + Base64.getUrlEncoder().withoutPadding().encodeToString("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8)) + "." + parts[2]);
    }
    @Test void signingRotationHasExplicitBoundedOverlap() {
        var issued = tokens(KEYS,"old",NOW,Map.of()).issue(new Granted(user,family,NOW.plusSeconds(6000),"unused"));
        var previous = Map.of("old", new AccessTokens.PreviousKey((RSAPublicKey)KEYS.getPublic(),NOW.plusSeconds(60)));
        assertThat(tokens(OTHER,"new",NOW,previous).verify(issued.value()).userId()).isEqualTo(user);
        assertThatThrownBy(() -> tokens(OTHER,"new",NOW.plusSeconds(60),previous).verify(issued.value())).isInstanceOf(BadJwtException.class);
        assertThatThrownBy(() -> tokens(OTHER,"new",NOW,Map.of("old",new AccessTokens.PreviousKey((RSAPublicKey)KEYS.getPublic(),NOW.plusSeconds(601)))))
                .isInstanceOf(IllegalArgumentException.class);
    }
    private void reject(String raw) { assertThatThrownBy(() -> tokens(KEYS,"current",NOW,Map.of()).verify(raw)).isInstanceOf(BadJwtException.class); }
    private Map<String,Object> claims() {
        var claims = new HashMap<String,Object>();
        claims.put("sub",user.toString()); claims.put("sid",family.toString()); claims.put("jti",UUID.randomUUID().toString());
        claims.put("iss","https://bridgeops.invalid"); claims.put("aud",List.of("bridgeops-browser"));
        claims.put("iat",NOW.getEpochSecond()); claims.put("exp",NOW.plusSeconds(600).getEpochSecond()); return claims;
    }
    private String sign(KeyPair keys, String id, Map<String,Object> values, String type) throws Exception {
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(id).type(new JOSEObjectType(type)).build(), JWTClaimsSet.parse(values));
        jwt.sign(new RSASSASigner((RSAPrivateKey)keys.getPrivate())); return jwt.serialize();
    }
    private static KeyPair keys() {
        try { var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); return generator.generateKeyPair(); }
        catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
    }
}