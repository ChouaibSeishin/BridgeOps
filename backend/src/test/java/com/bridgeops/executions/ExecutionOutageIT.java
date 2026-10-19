package com.bridgeops.executions;

import com.bridgeops.executions.application.*;
import com.bridgeops.executions.persistence.ExecutionStore;
import java.time.Clock;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import static org.assertj.core.api.Assertions.*;

class ExecutionOutageIT {
    @Test void actualPostgresOutagePreventsClaimAndSubmissionTransactionAcceptance(){
        try(var postgres=new PostgreSQLContainer(DockerImageName.parse("postgres@sha256:77f585114c32fbca283dc835b0596f4e52b51b4c6662d7810b2f4084f60a1873").asCompatibleSubstituteFor("postgres")).withInitScript("organization-runtime.sql")){
            postgres.start();String url=postgres.getJdbcUrl()+"&connectTimeout=1&socketTimeout=1";
            Flyway.configure().dataSource(url,postgres.getUsername(),postgres.getPassword()).load().migrate();
            var source=new DriverManagerDataSource(url,"bridgeops_app","synthetic-runtime-fixture");var store=new ExecutionStore(new JdbcTemplate(source));
            var manager=new DataSourceTransactionManager(source);var runtime=new ExecutionRuntime(store,manager,null,null,null,null,null,Clock.systemUTC(),new ExecutionRandom(),null);
            assertThat(store.jdbc().queryForObject("SELECT count(*) FROM workflow_executions",Long.class)).isZero();
            // Stops only this method's disposable container, never the user's Compose stack.
            postgres.stop();
            assertThatThrownBy(()->runtime.claim(UUID.randomUUID(),4,2,30)).isInstanceOf(org.springframework.transaction.CannotCreateTransactionException.class);
            var transaction=new TransactionTemplate(manager);
            assertThatThrownBy(()->transaction.execute(status->{throw new AssertionError("No submission may run without its database transaction.");})).isInstanceOf(org.springframework.transaction.CannotCreateTransactionException.class);
        }
    }
}
