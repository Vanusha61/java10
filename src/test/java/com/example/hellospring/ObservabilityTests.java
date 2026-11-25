package com.example.hellospring;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.OutputStreamAppender;
import io.micrometer.core.instrument.MeterRegistry;
import net.logstash.logback.encoder.LogstashEncoder;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "spring.datasource.url=jdbc:h2:mem:observability;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"})
@ActiveProfiles("test")
@AutoConfigureObservability
class ObservabilityTests {
    @Autowired TestRestTemplate http;
    @Autowired MessageService service;
    @Autowired MeterRegistry registry;
    @Autowired PlatformTransactionManager transactions;
    @Autowired JdbcTemplate jdbc;
    @LocalManagementPort int managementPort;

    private double count() { return registry.get("messages.created").counter().count(); }
    private CreateMessageRequest message() { return new CreateMessageRequest("Test message", UUID.randomUUID().toString(), null); }

    @Test
    void actuatorEndpointsExposePrometheusCounter() {
        String base = "http://localhost:" + managementPort + "/actuator/";
        assertThat(http.getForEntity(base + "health", String.class).getStatusCode().value()).isEqualTo(200);
        assertThat(http.getForObject(base + "health", String.class)).contains("UP");
        assertThat(http.getForObject(base + "info", String.class)).contains("Messages API");
        String metrics = http.getForObject(base + "prometheus", String.class);
        assertThat(metrics).contains("# TYPE messages_created_total counter");
        assertThat(http.getForEntity(base + "env", String.class).getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void committedMessagesIncludingBatchIncrementCounter() {
        double before = count();
        service.saveMessage(message());
        service.saveBatch(List.of(message(), message()));
        assertThat(count()).isEqualTo(before + 3);
    }

    @Test
    void rollbackDoesNotIncrementCounterOrLeaveMessage() {
        double before = count();
        long rows = jdbc.queryForObject("SELECT COUNT(*) FROM messages", Long.class);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            service.saveMessage(message());
            status.setRollbackOnly();
        });
        assertThat(count()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM messages", Long.class)).isEqualTo(rows);
        assertThatThrownBy(() -> service.saveMessage(new CreateMessageRequest("bad", null, Long.MAX_VALUE)))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(count()).isEqualTo(before);
    }

    @Test
    void consoleUsesJsonEncoder() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        var appender = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).getAppender("CONSOLE");
        assertThat(appender).isInstanceOf(OutputStreamAppender.class);
        assertThat(((OutputStreamAppender<?>) appender).getEncoder()).isInstanceOf(LogstashEncoder.class);
    }
}
