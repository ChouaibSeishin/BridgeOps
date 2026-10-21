package com.bridgeops.integrations.application;

import com.bridgeops.integrations.domain.SecretCipher;
import com.bridgeops.organizations.application.TenantAuthorization;
import com.bridgeops.shared.web.DomainFailure;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Durable opaque references. Resolved plaintext is private to approved integration adapters. */
@Service
public class SecretStore {
    private final JdbcTemplate jdbc;private final TenantAuthorization authority;private final Clock clock;
    private final String active;private final SecretCipher cipher;
    public SecretStore(JdbcTemplate jdbc,TenantAuthorization authority,Clock clock,@Value("${bridgeops.integrations.secrets.directory:}")String directory,@Value("${bridgeops.integrations.secrets.active-key-id:local-v1}")String active){
        this.jdbc=jdbc;this.authority=authority;this.clock=clock;this.active=active;
        if(!active.matches("[a-zA-Z0-9_-]{1,48}"))throw new IllegalArgumentException("Invalid encryption key ID.");
        this.cipher=new SecretCipher(id->{
            try{
                if(directory.isBlank()||!id.matches("[a-zA-Z0-9_-]{1,48}"))throw new IllegalArgumentException();
                Path root=Path.of(directory).toAbsolutePath().normalize(),file=root.resolve(id+".key").normalize();
                if(!file.startsWith(root)||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)!=32)throw new IllegalArgumentException();
                // Read-only secret mounts can expose synthetic POSIX modes (not host ACLs), notably Docker Desktop.
                if(!Files.getFileStore(file).isReadOnly()&&Files.getFileStore(file).supportsFileAttributeView("posix"))for(var permission:Files.getPosixFilePermissions(file))if(permission.name().startsWith("GROUP_")||permission.name().startsWith("OTHERS_"))throw new IllegalArgumentException();
                return Files.readAllBytes(file);
            }catch(Exception unavailable){throw new DomainFailure(503,"SECRETS_UNAVAILABLE");}
        });
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public UUID put(UUID actor,UUID family,UUID org,String purpose,byte[] plaintext){
        authority.write(actor,family,org,"integration.manage");return seal(org,purpose,plaintext);
    }
    /** Only trusted endpoint/configuration services in this module may generate or resolve raw secrets. */
    UUID seal(UUID org,String purpose,byte[] plaintext){
        UUID ref=UUID.randomUUID();var envelope=cipher.encrypt(new SecretCipher.Context(org,ref,purpose),active,plaintext);
        jdbc.update("INSERT INTO integration_secrets(id,organization_id,purpose,key_id,nonce,ciphertext,created_at) VALUES(?,?,?,?,?,?,?)",ref,org,purpose,envelope.keyId(),envelope.nonce(),envelope.ciphertext(),java.sql.Timestamp.from(clock.instant()));return ref;
    }
    byte[] resolve(UUID org,UUID ref,String purpose){
        var rows=jdbc.query("SELECT key_id,nonce,ciphertext FROM integration_secrets WHERE organization_id=? AND id=? AND purpose=?",(r,i)->new SecretCipher.Envelope(r.getString(1),r.getBytes(2),r.getBytes(3)),org,ref,purpose);
        if(rows.isEmpty())throw new DomainFailure(503,"SECRETS_UNAVAILABLE");return cipher.decrypt(new SecretCipher.Context(org,ref,purpose),rows.getFirst());
    }
}
