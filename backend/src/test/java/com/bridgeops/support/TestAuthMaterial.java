package com.bridgeops.support;
import java.nio.file.*;
import java.security.*;
import java.util.Base64;
/** Disposable test keys, never application configuration or persistent fixture credentials. */
public final class TestAuthMaterial implements AutoCloseable {
 public final Path directory;
 public TestAuthMaterial(){try{directory=Files.createTempDirectory("bridgeops-disposable-auth-");var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);var pair=generator.generateKeyPair();Files.writeString(directory.resolve("private.pem"),pem("PRIVATE KEY",pair.getPrivate().getEncoded()));Files.writeString(directory.resolve("public.pem"),pem("PUBLIC KEY",pair.getPublic().getEncoded()));byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);Files.write(directory.resolve("csrf.key"),bytes);}catch(Exception failure){throw new IllegalStateException("Cannot create disposable authentication material.");}}
 private String pem(String label,byte[] bytes){return "-----BEGIN "+label+"-----\n"+Base64.getMimeEncoder(64,new byte[]{10}).encodeToString(bytes)+"\n-----END "+label+"-----\n";}
 @Override public void close()throws Exception{for(String name:java.util.List.of("private.pem","public.pem","csrf.key"))Files.deleteIfExists(directory.resolve(name));Files.deleteIfExists(directory);}
}
