package com.doctrail.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.doctrail.dto.ai.ask.AiAskRequest;
import com.doctrail.dto.ai.ask.AiAskResponse;
import com.doctrail.dto.user.request.ChatAskRequest;
import com.doctrail.dto.user.response.ChatAskResponse;
import com.doctrail.mapper.ConversationMapper;
import com.doctrail.mapper.MessageMapper;
import com.doctrail.mapper.QaLogMapper;
import com.doctrail.security.AuthenticatedIdentity;
import com.doctrail.service.ai.client.AiAskClient;
import com.doctrail.service.audit.AgentAuditService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Opt-in verification against the configured MySQL instance. The test is
 * skipped unless DOCTRAIL_RUN_MYSQL_IT=true is explicitly supplied.
 */
@SpringBootTest(properties = {
        "doctrail.auth.jwt.secret=stage5-mysql-integration-test-secret-32-bytes",
        "doctrail.storage.backend=local",
        "doctrail.storage.upload-root=target/stage5-mysql-it-uploads",
        "doctrail.ai-service.internal-token=test-token",
        "doctrail.message.recovery-scan-interval=1h"
})
@ActiveProfiles("stage2-auth")
@EnabledIfEnvironmentVariable(named = "DOCTRAIL_RUN_MYSQL_IT", matches = "true")
class StageFiveMessageMySqlIntegrationTest {
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ConversationMapper conversationMapper;

    @Autowired
    private MessageMapper messageMapper;

    @Autowired
    private QaLogMapper qaLogMapper;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private AgentAuditService agentAudit;

    private final AiAskClient aiAskClient = mock(AiAskClient.class);
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private Long userId;
    private Long conversationId;

    @BeforeEach
    void createFixture() {
        String username = "stage5-it-" + UUID.randomUUID();
        userId = insertUser(username);
        conversationId = insertConversation(userId);
    }

    @AfterEach
    void deleteFixture() {
        if (conversationId != null) {
            jdbcTemplate.update("DELETE s FROM agent_step s JOIN agent_run r ON s.run_id = r.run_id WHERE r.conversation_id = ?", conversationId);
            jdbcTemplate.update("DELETE t FROM tool_call t JOIN agent_run r ON t.run_id = r.run_id WHERE r.conversation_id = ?", conversationId);
            jdbcTemplate.update("DELETE FROM agent_run WHERE conversation_id = ?", conversationId);
            jdbcTemplate.update("DELETE FROM qa_log WHERE conversation_id = ?", conversationId);
            jdbcTemplate.update("UPDATE message SET reply_to_message_id = NULL WHERE conversation_id = ?", conversationId);
            jdbcTemplate.update("DELETE FROM message WHERE conversation_id = ?", conversationId);
            jdbcTemplate.update("DELETE FROM conversation WHERE id = ?", conversationId);
        }
        if (userId != null) {
            jdbcTemplate.update("DELETE FROM `user` WHERE id = ?", userId);
        }
        executor.shutdownNow();
    }

    @Test
    void concurrentSameRequestIdUsesOnePythonCallAndOneTerminalWrite() throws Exception {
        UUID requestId = UUID.randomUUID();
        CountDownLatch pythonEntered = new CountDownLatch(1);
        CountDownLatch releasePython = new CountDownLatch(1);
        AtomicInteger pythonCalls = new AtomicInteger();
        when(aiAskClient.ask(org.mockito.ArgumentMatchers.any(AiAskRequest.class)))
                .thenAnswer(invocation -> {
                    AiAskRequest request = invocation.getArgument(0);
                    pythonCalls.incrementAndGet();
                    pythonEntered.countDown();
                    assertThat(releasePython.await(10, TimeUnit.SECONDS)).isTrue();
                    return successfulResponse(request);
                });

        StageTwoMessageServiceImpl service = new StageTwoMessageServiceImpl(
                conversationMapper, messageMapper, qaLogMapper, aiAskClient, objectMapper,
                transactionManager, agentAudit, Duration.ofSeconds(900));
        AuthenticatedIdentity identity = new AuthenticatedIdentity(userId, "stage5-it", "USER");
        ChatAskRequest request = new ChatAskRequest("并发幂等验证", conversationId);

        Future<ChatAskResponse> first = executor.submit(
                () -> service.send(identity, request, requestId.toString()));
        assertThat(pythonEntered.await(10, TimeUnit.SECONDS)).isTrue();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM agent_run WHERE request_id = ?", String.class, requestId.toString()))
                .isEqualTo("RUNNING");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT finished_at IS NULL FROM agent_run WHERE request_id = ?", Integer.class, requestId.toString()))
                .isEqualTo(1);

        Future<?> duplicate = executor.submit(
                () -> service.send(identity, request, requestId.toString()));
        assertThatThrownBy(() -> duplicate.get(10, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(com.doctrail.exception.RequestInProgressException.class);

        releasePython.countDown();
        ChatAskResponse firstResponse = first.get(10, TimeUnit.SECONDS);

        assertThat(pythonCalls).hasValue(1);
        assertThat(firstResponse.mock()).isFalse();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM message WHERE request_id = ?", Integer.class, requestId.toString()))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM message WHERE conversation_id = ? AND role = 'assistant'",
                Integer.class, conversationId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT request_state FROM message WHERE request_id = ?", String.class, requestId.toString()))
                .isEqualTo("SUCCEEDED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT answer_snapshot IS NOT NULL FROM message WHERE request_id = ?",
                Integer.class, requestId.toString())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM qa_log WHERE request_id = ?", Integer.class, requestId.toString()))
                .isEqualTo(1);

        ChatAskResponse replay = service.send(identity, request, requestId.toString());
        assertThat(replay).isEqualTo(firstResponse);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_run WHERE request_id = ?", Integer.class, requestId.toString()))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM agent_run WHERE request_id = ?", String.class, requestId.toString()))
                .isEqualTo("COMPLETED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT finished_at IS NOT NULL FROM agent_run WHERE request_id = ?", Integer.class, requestId.toString()))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT run_id FROM agent_run WHERE request_id = ?", String.class, requestId.toString()))
                .isEqualTo(firstResponse.runId());
        assertThat(pythonCalls).hasValue(1);
    }

    private AiAskResponse successfulResponse(AiAskRequest request) {
        return new AiAskResponse(
                "基于正式索引的并发测试回答", List.of(), "knowledge_qa", request.runId(), request.traceId(), null,
                new AiAskResponse.ExecutionTrace("COMPLETED", List.of(), List.of()), false);
    }

    private Long insertUser(String username) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO `user` (username, password_hash, role) VALUES (?, ?, 'USER')",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, username);
            statement.setString(2, "stage5-integration-test-placeholder");
            return statement;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    private Long insertConversation(Long ownerId) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO conversation (user_id, title) VALUES (?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, ownerId);
            statement.setString(2, "stage5 mysql integration");
            return statement;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }
}
