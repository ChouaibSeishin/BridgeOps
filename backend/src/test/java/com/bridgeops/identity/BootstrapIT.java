package com.bridgeops.identity;

import com.bridgeops.identity.bootstrap.*;
import com.bridgeops.identity.application.AccountPasswords;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class BootstrapIT {
    @Container static final PostgreSQLContainer postgres=new PostgreSQLContainer(DockerImageName.parse(
        "postgres@sha256:77f585114c32fbca283dc835b0596f4e52b51b4c6662d7810b2f4084f60a1873").asCompatibleSubstituteFor("postgres"));
    static DriverManagerDataSource source;static JdbcTemplate jdbc;static AccountPasswords passwords;
    @BeforeAll static void migrate(){
        source=new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());jdbc=new JdbcTemplate(source);
        jdbc.execute("CREATE ROLE bridgeops_app NOLOGIN");
        Flyway.configure().dataSource(source).load().migrate();passwords=new AccountPasswords();
    }
    @BeforeEach void reset(){
        jdbc.execute("TRUNCATE users,installation_state CASCADE");jdbc.update("INSERT INTO installation_state(singleton_id) VALUES(1)");
    }
    BootstrapProvisioner provisioner(){return new BootstrapProvisioner(jdbc,new TransactionTemplate(new JdbcTransactionManager(source)),passwords,Clock.systemUTC());}
    char[] secret(){return "synthetic-bootstrap-fixture".toCharArray();}
    @Test void createsOneVerifiedAccountAndGrantWithoutAnyTenantAndErasesInput(){
        char[] input=secret();UUID id=provisioner().provision(" Operator@Example.Invalid ","Operator",input);
        assertThat(input).containsOnly('\0');
        assertThat(jdbc.queryForObject("SELECT email_verified_at IS NOT NULL FROM users WHERE id=?",Boolean.class,id)).isTrue();
        assertThat(jdbc.queryForObject("SELECT normalized_email FROM users WHERE id=?",String.class,id)).isEqualTo("operator@example.invalid");
        assertThat(passwords.matches(new String(secret()),jdbc.queryForObject("SELECT password_hash FROM users WHERE id=?",String.class,id))).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_grants WHERE user_id=? AND grant_code='organization.create'",Integer.class,id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT initial_user_id FROM installation_state",UUID.class)).isEqualTo(id);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM identity_security_events WHERE event_type='BOOTSTRAP_COMPLETED'",Integer.class)).isEqualTo(1);
    }
    @Test void repeatedBootstrapAndPreexistingAccountsAreRefused(){
        provisioner().provision("operator@example.invalid","Operator",secret());
        assertThatThrownBy(()->provisioner().provision("other@example.invalid","Other",secret())).isInstanceOf(BootstrapProvisioner.Unavailable.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users",Integer.class)).isEqualTo(1);
        jdbc.update("UPDATE installation_state SET initial_user_id=NULL,bootstrap_consumed_at=NULL");
        assertThatThrownBy(()->provisioner().provision("other@example.invalid","Other",secret())).isInstanceOf(BootstrapProvisioner.Unavailable.class);
    }
    @Test void concurrentBootstrapHasExactlyOneWinner() throws Exception{
        var gate=new CountDownLatch(1);try(var pool=Executors.newFixedThreadPool(2)){
            Callable<Boolean> task=()->{gate.await();try{provisioner().provision(UUID.randomUUID()+"@example.invalid","Operator",secret());return true;}
                catch(BootstrapProvisioner.Unavailable refused){return false;}};
            var a=pool.submit(task);var b=pool.submit(task);gate.countDown();assertThat(List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_grants",Integer.class)).isEqualTo(1);
    }
    @Test void auditFailureRollsBackAccountGrantAndMarker(){
        jdbc.execute("CREATE FUNCTION reject_bootstrap_event() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'synthetic rollback'; END $$");
        jdbc.execute("CREATE TRIGGER reject_bootstrap BEFORE INSERT ON identity_security_events FOR EACH ROW EXECUTE FUNCTION reject_bootstrap_event()");
        try{assertThatThrownBy(()->provisioner().provision("operator@example.invalid","Operator",secret())).isInstanceOf(org.springframework.dao.DataAccessException.class);}
        finally{jdbc.execute("DROP TRIGGER reject_bootstrap ON identity_security_events");jdbc.execute("DROP FUNCTION reject_bootstrap_event()");}
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_grants",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT bootstrap_consumed_at IS NULL FROM installation_state",Boolean.class)).isTrue();
        provisioner().provision("operator@example.invalid","Operator",secret());
    }
    @Test void offlineCommandSucceedsWithoutPrintingSecretsAndRefusesARepeat(){
        var env=new HashMap<String,String>();env.put("BOOTSTRAP_ENABLED","true");env.put("BOOTSTRAP_EMAIL","operator@example.invalid");
        env.put("BOOTSTRAP_DISPLAY_NAME","Operator");env.put("DATABASE_URL",postgres.getJdbcUrl());env.put("DATABASE_USER",postgres.getUsername());env.put("DATABASE_PASSWORD",postgres.getPassword());
        String canary=new String(secret());var capture=new java.io.ByteArrayOutputStream();var output=new java.io.PrintStream(capture);
        assertThat(OfflineBootstrap.run(new String[]{"bootstrap"},env,new java.io.ByteArrayInputStream((canary+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8)),output,output)).isZero();
        assertThat(OfflineBootstrap.run(new String[]{"bootstrap"},env,new java.io.ByteArrayInputStream((canary+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8)),output,output)).isEqualTo(3);
        assertThat(capture.toString(java.nio.charset.StandardCharsets.UTF_8)).doesNotContain(canary,"operator@example.invalid");
    }
    @Test void invalidPasswordDoesNotConsumeStateAndRuntimeCannotBootstrap(){
        char[] weak="short".toCharArray();assertThatThrownBy(()->provisioner().provision("operator@example.invalid","Operator",weak)).isInstanceOf(IllegalArgumentException.class);
        assertThat(weak).containsOnly('\0');assertThat(jdbc.queryForObject("SELECT bootstrap_consumed_at IS NULL FROM installation_state",Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT has_table_privilege('bridgeops_app','installation_state','SELECT') OR has_table_privilege('bridgeops_app','installation_state','UPDATE') OR has_table_privilege('bridgeops_app','users','INSERT') OR has_table_privilege('bridgeops_app','user_grants','INSERT')",Boolean.class)).isFalse();
    }
}
