package com.bridgeops.identity.bootstrap;

import com.bridgeops.identity.application.*;
import java.io.*;
import java.nio.charset.*;
import java.time.Clock;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** No web context, component scanning, startup runner or automatic migration execution. */
public final class OfflineBootstrap {
    private OfflineBootstrap() { }
    public static int run(String[] args,Map<String,String> env,InputStream input,PrintStream output,PrintStream error) {
        if(!"true".equals(env.get("BOOTSTRAP_ENABLED"))) {error.println("Bootstrap is disabled. Explicit operator enablement is required.");return 2;}
        char[] secret=null;
        try {
            if(args.length!=1 || !"bootstrap".equals(args[0])) throw new IllegalArgumentException();
            String email=AccountIdentifiers.email(env.get("BOOTSTRAP_EMAIL"));
            String name=AccountIdentifiers.displayName(env.get("BOOTSTRAP_DISPLAY_NAME"));
            String url=required(env,"DATABASE_URL"),user=required(env,"DATABASE_USER"),password=required(env,"DATABASE_PASSWORD");
            if(!url.startsWith("jdbc:postgresql://")) throw new IllegalArgumentException();
            secret=(input==System.in && System.console()!=null) ? System.console().readPassword("Initial account password: ") : readSecret(input);
            if(secret==null)throw new IllegalArgumentException();
            var source=new DriverManagerDataSource(url,user,password);
            Properties properties=new Properties();properties.setProperty("connectTimeout","5");properties.setProperty("socketTimeout","10");
            source.setConnectionProperties(properties);
            var transactions=new TransactionTemplate(new JdbcTransactionManager(source));
            new BootstrapProvisioner(new JdbcTemplate(source),transactions,new AccountPasswords(),Clock.systemUTC()).provision(email,name,secret);
            output.println("Initial account provisioned. No organization was created.");return 0;
        } catch(BootstrapProvisioner.Unavailable consumed) {error.println("Installation bootstrap is unavailable or already consumed.");return 3;}
        catch(IllegalArgumentException | IOException invalid) {error.println("Bootstrap input/configuration is invalid. No input values are displayed.");return 2;}
        catch(Exception unavailable) {error.println("Bootstrap failed. Verify migrations and operator database permissions; no credentials are displayed.");return 4;}
        finally {if(secret!=null)Arrays.fill(secret,'\0');}
    }
    private static String required(Map<String,String> env,String key) {
        String value=env.get(key);if(value==null || value.isBlank())throw new IllegalArgumentException();return value;
    }
    static char[] readSecret(InputStream input) throws IOException {
        var reader=new InputStreamReader(input,StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT));
        char[] buffer=new char[128];int length=0;
        try {
            int next;
            while((next=reader.read())!=-1 && next!='\n') {
                if(next=='\r') {if(reader.read()!='\n')throw new IOException();break;}
                if(length==buffer.length)throw new IOException();buffer[length++]=(char)next;
            }
            if(length==0)throw new IOException();return Arrays.copyOf(buffer,length);
        } finally {Arrays.fill(buffer,'\0');}
    }
}
