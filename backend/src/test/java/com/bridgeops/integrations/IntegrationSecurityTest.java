package com.bridgeops.integrations;

import com.bridgeops.integrations.domain.*;
import com.bridgeops.shared.web.DomainFailure;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class IntegrationSecurityTest {
    final byte[] key=new byte[32];final UUID org=UUID.randomUUID(),ref=UUID.randomUUID();
    SecretCipher.Context context(){return new SecretCipher.Context(org,ref,"REST_CREDENTIAL");}
    final DestinationPolicy policy=new DestinationPolicy(List.of("api.example.com"));
    @Test void ciphertextIsRandomizedAndNeverContainsCanary(){
        var cipher=new SecretCipher(id->key);byte[] canary="canary-not-a-real-provider-credential".getBytes(StandardCharsets.UTF_8);
        var a=cipher.encrypt(context(),"v1",canary);var b=cipher.encrypt(context(),"v1",canary);
        assertThat(a.nonce()).isNotEqualTo(b.nonce());assertThat(a.ciphertext()).isNotEqualTo(b.ciphertext());
        assertThat(new String(a.ciphertext(),StandardCharsets.UTF_8)).doesNotContain(new String(canary,StandardCharsets.UTF_8));
        assertThat(a.toString()).doesNotContain("canary");assertThat(cipher.decrypt(context(),a)).isEqualTo(canary);
    }
    @Test void envelopeCannotMoveAcrossTenantReferenceOrPurpose(){
        var cipher=new SecretCipher(id->key);var envelope=cipher.encrypt(context(),"v1",new byte[]{1});
        for(var c:List.of(new SecretCipher.Context(UUID.randomUUID(),ref,"REST_CREDENTIAL"),new SecretCipher.Context(org,UUID.randomUUID(),"REST_CREDENTIAL"),new SecretCipher.Context(org,ref,"WEBHOOK_SIGNING")))
            unavailable(()->cipher.decrypt(c,envelope));
    }
    @Test void tamperedCiphertextNonceOrKeyCannotDecrypt(){
        var cipher=new SecretCipher(id->key);var envelope=cipher.encrypt(context(),"v1",new byte[]{1});byte[] ct=envelope.ciphertext();ct[0]^=1;
        unavailable(()->cipher.decrypt(context(),new SecretCipher.Envelope("v1",envelope.nonce(),ct)));
        byte[] nonce=envelope.nonce();nonce[0]^=1;unavailable(()->cipher.decrypt(context(),new SecretCipher.Envelope("v1",nonce,envelope.ciphertext())));
        unavailable(()->cipher.decrypt(context(),new SecretCipher.Envelope("v2",envelope.nonce(),envelope.ciphertext())));
    }
    @Test void missingAndWrongLengthKeysFailClosed(){
        for(byte[] invalid:new byte[][]{null,new byte[16],new byte[31],new byte[33]})unavailable(()->new SecretCipher(id->invalid).encrypt(context(),"v1",new byte[]{1}));
    }
    @Test void historicalKeysCanDecryptAfterActiveKeyChanges(){
        var keys=new HashMap<String,byte[]>();keys.put("old",new byte[32]);byte[] next=new byte[32];Arrays.fill(next,(byte)7);keys.put("new",next);
        var cipher=new SecretCipher(keys::get);var old=cipher.encrypt(context(),"old",new byte[]{42});assertThat(cipher.decrypt(context(),old)).containsExactly(42);
        var replacement=cipher.encrypt(context(),"new",new byte[]{43});assertThat(cipher.decrypt(context(),replacement)).containsExactly(43);
        keys.remove("old");unavailable(()->cipher.decrypt(context(),old));
    }
    @Test void defensiveCopiesAndBoundsProtectEnvelope(){
        var cipher=new SecretCipher(id->key);var e=cipher.encrypt(context(),"v1",new byte[]{42});e.nonce()[0]^=1;e.ciphertext()[0]^=1;assertThat(cipher.decrypt(context(),e)).containsExactly(42);
        for(int size:new int[]{0,4097})assertThatThrownBy(()->cipher.encrypt(context(),"v1",new byte[size])).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->cipher.encrypt(context(),"../key",new byte[]{1})).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void baseRequiresExactAllowlistedHttpsHostAndStandardPort(){
        assertThat(policy.base("https://api.example.com/").toString()).isEqualTo("https://api.example.com/");
        for(String url:List.of("http://api.example.com","https://api.example.com:8443","https://user:pass@api.example.com","https://api.example.com/path","https://api.example.com?x=1","https://api.example.com#x","https://other.example.com","https://127.0.0.1","https://[::1]","https://api.example.com.","https://api.example.com@127.0.0.1","https://api.example.com%00.evil.test"))denied(()->policy.base(url));
    }
    @Test void pathsCannotTraverseOrOverrideHostOrInjectHeaders(){
        assertThat(policy.path("/v1/tickets/abc_123")).isEqualTo("/v1/tickets/abc_123");
        for(String path:List.of("//host","/../a","/%2e%2e/a","/a?x=1","/a#f","https://other.test","/a\\b","/a\r\nX:x"))denied(()->policy.path(path));
    }
    @Test void forbiddenIpv4AndIpv6RangesAreRejected()throws Exception {
        for(String ip:List.of("0.0.0.0","10.1.1.1","100.64.0.1","127.1.2.3","169.254.169.254","172.16.0.1","192.168.1.1","192.0.0.9","192.0.2.1","192.88.99.1","198.19.0.1","198.51.100.1","203.0.113.1","224.0.0.1","255.255.255.255","::","::1","::ffff:127.0.0.1","fc00::1","fe80::1","ff02::1","64:ff9b::7f00:1","2001:db8::1","2001::1","2002:7f00:1::1","3fff::1","3ffe::1"))
            assertThat(DestinationPolicy.publicAddress(InetAddress.getByName(ip))).as(ip).isFalse();
        for(String ip:List.of("8.8.8.8","1.1.1.1","2606:4700:4700::1111","2001:4860:4860::8888"))assertThat(DestinationPolicy.publicAddress(InetAddress.getByName(ip))).as(ip).isTrue();
    }
    @Test void mixedDnsAnswersAndRebindingAreRejectedAtConnectionResolution()throws Exception {
        var publicIp=InetAddress.getByName("8.8.8.8");var privateIp=InetAddress.getByName("127.0.0.1");
        assertThat(policy.answers("api.example.com",new InetAddress[]{publicIp})).containsExactly(publicIp);
        denied(()->policy.answers("api.example.com",new InetAddress[]{publicIp,privateIp}));denied(()->policy.answers("api.example.com",new InetAddress[]{privateIp}));
        denied(()->policy.answers("other.example.com",new InetAddress[]{publicIp}));denied(()->policy.answers("api.example.com",new InetAddress[0]));
    }
    void unavailable(Runnable action){assertThatThrownBy(action::run).isInstanceOfSatisfying(DomainFailure.class,e->{assertThat(e.status()).isEqualTo(503);assertThat(e.getMessage()).doesNotContain("canary","credential");});}
    void denied(Runnable action){assertThatThrownBy(action::run).isInstanceOfSatisfying(DomainFailure.class,e->assertThat(e.code()).isEqualTo("DESTINATION_NOT_ALLOWED"));}
}
