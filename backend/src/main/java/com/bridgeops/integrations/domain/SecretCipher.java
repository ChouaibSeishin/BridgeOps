package com.bridgeops.integrations.domain;

import com.bridgeops.shared.web.DomainFailure;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.*;

/** Authenticated envelope only; callers must authorize access before resolving a secret. */
public final class SecretCipher {
    @FunctionalInterface public interface Keys { byte[] read(String keyId); }
    public record Context(UUID organization,UUID reference,String purpose) {
        public Context {
            Objects.requireNonNull(organization);Objects.requireNonNull(reference);
            if(purpose==null||!Set.of("REST_CREDENTIAL","WEBHOOK_SIGNING").contains(purpose))throw new IllegalArgumentException("Invalid secret context.");
        }
        byte[] aad(String keyId){return ("BridgeOps.secret.v1\n"+organization+"\n"+reference+"\n"+purpose+"\n"+keyId).getBytes(StandardCharsets.UTF_8);}
    }
    public record Envelope(String keyId,byte[] nonce,byte[] ciphertext) {
        public Envelope {
            if(keyId==null||!keyId.matches("[a-zA-Z0-9_-]{1,48}")||nonce==null||nonce.length!=12||ciphertext==null||ciphertext.length<17||ciphertext.length>4112)
                throw new IllegalArgumentException("Invalid secret envelope.");
            nonce=nonce.clone();ciphertext=ciphertext.clone();
        }
        @Override public byte[] nonce(){return nonce.clone();}
        @Override public byte[] ciphertext(){return ciphertext.clone();}
        @Override public String toString(){return "SecretEnvelope[keyId="+keyId+"]";}
    }
    private final Keys keys;private final SecureRandom random;
    public SecretCipher(Keys keys){this(keys,new SecureRandom());}
    SecretCipher(Keys keys,SecureRandom random){this.keys=Objects.requireNonNull(keys);this.random=Objects.requireNonNull(random);}
    public Envelope encrypt(Context context,String keyId,byte[] plaintext){
        if(context==null||keyId==null||!keyId.matches("[a-zA-Z0-9_-]{1,48}")||plaintext==null||plaintext.length<1||plaintext.length>4096)
            throw new IllegalArgumentException("Invalid secret input.");
        byte[] nonce=new byte[12];random.nextBytes(nonce);
        return new Envelope(keyId,nonce,crypt(Cipher.ENCRYPT_MODE,context,keyId,nonce,plaintext));
    }
    public byte[] decrypt(Context context,Envelope envelope){return crypt(Cipher.DECRYPT_MODE,Objects.requireNonNull(context),envelope.keyId(),envelope.nonce(),envelope.ciphertext());}
    private byte[] crypt(int mode,Context context,String keyId,byte[] nonce,byte[] input){
        byte[] key=null;
        try {
            byte[] supplied=keys.read(keyId);if(supplied==null||supplied.length!=32)throw new GeneralSecurityException();key=supplied.clone();
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(mode,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));
            cipher.updateAAD(context.aad(keyId));return cipher.doFinal(input);
        } catch(GeneralSecurityException|RuntimeException unavailable){throw new DomainFailure(503,"SECRETS_UNAVAILABLE");}
        finally{if(key!=null)Arrays.fill(key,(byte)0);}
    }
}
