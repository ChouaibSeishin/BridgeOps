package com.bridgeops.integrations;

import com.bridgeops.integrations.application.WebhookAdmission;
import com.bridgeops.integrations.persistence.WebhookDeliveryStore;
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

class WebhookOutageIT {
    @Test void actualDatabaseOutageCannotAcknowledgeRouteAdmissionOrAcceptance(){
        try(var postgres=new PostgreSQLContainer(DockerImageName.parse("postgres@sha256:77f585114c32fbca283dc835b0596f4e52b51b4c6662d7810b2f4084f60a1873").asCompatibleSubstituteFor("postgres")).withInitScript("organization-runtime.sql")){
            postgres.start();String url=postgres.getJdbcUrl()+"&connectTimeout=1&socketTimeout=1";Flyway.configure().dataSource(url,postgres.getUsername(),postgres.getPassword()).load().migrate();
            var source=new DriverManagerDataSource(url,"bridgeops_app","synthetic-runtime-fixture");var jdbc=new JdbcTemplate(source);var deliveries=new WebhookDeliveryStore(jdbc);var admission=new WebhookAdmission(jdbc,Clock.systemUTC());var tx=new TransactionTemplate(new DataSourceTransactionManager(source));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM webhook_delivery_receipts",Long.class)).isZero();postgres.stop();
            assertThatThrownBy(()->deliveries.route(UUID.randomUUID())).isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThatThrownBy(()->admission.acquire(new WebhookDeliveryStore.Route(UUID.randomUUID(),UUID.randomUUID(),0,UUID.randomUUID()))).isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThatThrownBy(()->tx.execute(status->{throw new AssertionError("A delivery cannot be accepted without its database transaction.");})).isInstanceOf(org.springframework.transaction.CannotCreateTransactionException.class);
        }
    }
}
