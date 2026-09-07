package com.eaagent.app;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.eaagent.api.dto.TemplateReviewRequest;
import com.eaagent.api.dto.TemplateWriteRequest;
import com.eaagent.app.service.AgentChatService;
import com.eaagent.app.service.TemplateService;
import com.eaagent.common.BizException;
import com.eaagent.common.ErrorCode;
import com.eaagent.common.Roles;
import com.eaagent.ontology.mapper.AgentChatMapper;
import com.eaagent.ontology.mapper.CampaignMapper;
import com.eaagent.ontology.mapper.TemplateMapper;
import com.eaagent.ontology.mapper.TemplateReviewMapper;
import com.eaagent.ontology.model.TemplateEntity;
import com.eaagent.ontology.model.TemplateReviewEntity;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.LocalCacheScope;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real PostgreSQL CAS/locks/FKs and the service's actual transaction annotations; no LLM or Redis. */
@Testcontainers(disabledWithoutDocker = true)
class TemplateReviewIntegrationTest {
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
            .withUrlParam("stringtype", "unspecified");

    private static DriverManagerDataSource dataSource;
    private static JdbcTemplate jdbc;
    private static SqlSessionTemplate sessions;
    private static TemplateMapper templates;
    private static TemplateReviewMapper reviews;
    private static AgentChatService chats;
    private TemplateService service;
    private ExecutorService workers;

    @BeforeAll
    static void createSchema() {
        dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
        configuration.setEnvironment(new Environment("template-review-test", new SpringManagedTransactionFactory(), dataSource));
        configuration.addMapper(TemplateMapper.class);
        configuration.addMapper(TemplateReviewMapper.class);
        configuration.addMapper(AgentChatMapper.class);
        configuration.addMapper(CampaignMapper.class);
        sessions = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration));
        templates = sessions.getMapper(TemplateMapper.class);
        reviews = sessions.getMapper(TemplateReviewMapper.class);
        chats = new AgentChatService(sessions.getMapper(AgentChatMapper.class));
        jdbc.update("INSERT INTO tenant (id, name, domain) OVERRIDING SYSTEM VALUE VALUES (1, 'one', 'one'), (2, 'two', 'two')");
        jdbc.update("INSERT INTO tenant_user (id, tenant_id, login_name, name, password_hash, role) OVERRIDING SYSTEM VALUE "
                + "VALUES (1, 1, 'reviewer', 'reviewer', 'unused', 'REVIEWER'), "
                + "(2, 1, 'operator', 'operator', 'unused', 'OPERATOR'), (3, 2, 'other', 'other', 'unused', 'REVIEWER')");
    }

    @BeforeEach
    void resetTemplates() {
        jdbc.update("DELETE FROM template");
        jdbc.update("DELETE FROM agent_chat");
        service = transactional(templates, reviews);
        workers = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void stopWorkers() throws InterruptedException {
        workers.shutdownNow();
        assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS));
    }

    @Test
    void permissionsOwnershipAndInvalidVersionsCannotCreateReviewsOrChangeStatus() {
        TemplateEntity t = pending();
        Long otherUserChat = chats.createChat(1L, 2L, "private chat").getId();
        Long otherTenantChat = chats.createChat(2L, 3L, "other tenant chat").getId();

        assertError(ErrorCode.FORBIDDEN, () -> service.addReview(1L, t.getId(), 2L, Roles.OPERATOR,
                review(t.getVersion(), "APPROVE", "", otherUserChat)));
        assertError(ErrorCode.CHAT_NOT_FOUND, () -> service.addReview(1L, t.getId(), 1L, Roles.REVIEWER,
                review(t.getVersion(), "COMMENT", "not my chat", otherUserChat)));
        assertError(ErrorCode.CHAT_NOT_FOUND, () -> service.addReview(1L, t.getId(), 1L, Roles.REVIEWER,
                review(t.getVersion(), "COMMENT", "not my tenant", otherTenantChat)));
        assertError(ErrorCode.OBJECT_NOT_FOUND, () -> service.addReview(2L, t.getId(), 3L, Roles.REVIEWER,
                review(t.getVersion(), "APPROVE", "", null)));
        assertError(ErrorCode.OBJECT_NOT_FOUND, () -> service.listReviews(2L, t.getId()));
        assertError(ErrorCode.PARAM_ERROR, () -> service.addReview(1L, t.getId(), 1L, Roles.REVIEWER,
                review(t.getVersion(), "REJECT", " \n ", null)));
        assertError(ErrorCode.PARAM_ERROR, () -> service.addReview(1L, t.getId(), 2L, Roles.OPERATOR,
                review(t.getVersion(), "COMMENT", "", null)));
        assertError(ErrorCode.PARAM_ERROR, () -> service.addReview(1L, t.getId(), 1L, Roles.REVIEWER,
                review(t.getVersion(), "APPROVE", "x".repeat(2001), null)));
        assertError(ErrorCode.STATE_NOT_ALLOWED, () -> service.addReview(1L, t.getId(), 2L, Roles.OPERATOR,
                review(t.getVersion() - 1, "COMMENT", "old text", null)));
        assertError(ErrorCode.STATE_NOT_ALLOWED, () -> service.addReview(1L, t.getId(), 1L, Roles.REVIEWER,
                review(t.getVersion() - 1, "APPROVE", "", null)));

        assertEquals(List.of(), service.listReviews(1L, t.getId()));
        assertState(t.getId(), TemplateEntity.REVIEW_PENDING, t.getVersion());
    }

    @Test
    void historyRetainsOriginalTextAndIdentityAcrossEditsAndCannotDecideTwice() {
        TemplateEntity t = pending();
        Long chatId = chats.createChat(1L, 2L, "private chat").getId();
        TemplateReviewEntity comment = service.addReview(1L, t.getId(), 2L, Roles.OPERATOR,
                review(t.getVersion(), "COMMENT", "核对称呼", chatId));
        assertState(t.getId(), TemplateEntity.REVIEW_PENDING, t.getVersion());
        TemplateReviewEntity rejected = service.addReview(1L, t.getId(), 1L, Roles.REVIEWER,
                review(t.getVersion(), "REJECT", "修改称呼", null));
        assertState(t.getId(), TemplateEntity.REVIEW_REJECTED, t.getVersion() + 1);
        assertError(ErrorCode.STATE_NOT_ALLOWED, () -> service.addReview(1L, t.getId(), 1L, Roles.REVIEWER,
                review(t.getVersion(), "APPROVE", "", null)));
        assertError(ErrorCode.STATE_NOT_ALLOWED, () -> service.addReview(1L, t.getId(), 1L, Roles.REVIEWER,
                review(t.getVersion() + 1, "APPROVE", "", null)));

        TemplateEntity edited = service.update(1L, t.getId(), writeRequest("Revised", "Hello {{full_name}}"));
        assertEquals(t.getVersion() + 2, edited.getVersion());
        List<TemplateReviewEntity> history = service.listReviews(1L, t.getId());
        assertEquals(List.of(comment.getId(), rejected.getId()), history.stream().map(TemplateReviewEntity::getId).toList());
        assertEquals(List.of("COMMENT", "REJECT"), history.stream().map(TemplateReviewEntity::getDecision).toList());
        assertEquals(2L, history.get(0).getUserId());
        assertEquals(1L, history.get(1).getUserId());
        assertEquals(chatId, history.get(0).getChatId());
        assertNull(history.get(1).getChatId());
        assertEquals("核对称呼", history.get(0).getComment());
        for (TemplateReviewEntity entry : history) {
            assertEquals(t.getVersion(), entry.getTemplateVersion());
            assertEquals(t.getTitle(), entry.getTitle());
            assertEquals(t.getContent(), entry.getContent());
        }
        TemplateEntity submitted = service.submit(1L, t.getId());
        service.addReview(1L, t.getId(), 1L, Roles.REVIEWER, review(submitted.getVersion(), "APPROVE", "", null));
        assertState(t.getId(), TemplateEntity.REVIEW_APPROVED, submitted.getVersion() + 1);
    }

    @Test
    void concurrentFinalDecisionsCommitExactlyOneRecordAndOneTransition() throws Exception {
        TemplateEntity t = pending();
        CyclicBarrier start = new CyclicBarrier(2);
        Future<Object> approved = workers.submit(() -> decideAfter(start, t, "APPROVE"));
        Future<Object> rejected = workers.submit(() -> decideAfter(start, t, "REJECT"));
        List<Object> outcomes = List.of(approved.get(15, TimeUnit.SECONDS), rejected.get(15, TimeUnit.SECONDS));
        assertEquals(1L, outcomes.stream().filter(TemplateReviewEntity.class::isInstance).count());
        assertEquals(1L, outcomes.stream().filter(ErrorCode.STATE_NOT_ALLOWED::equals).count());
        List<TemplateReviewEntity> history = service.listReviews(1L, t.getId());
        assertEquals(1, history.size());
        assertState(t.getId(), "APPROVE".equals(history.get(0).getDecision())
                ? TemplateEntity.REVIEW_APPROVED : TemplateEntity.REVIEW_REJECTED, t.getVersion() + 1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void staleEditOrSubmitCannotOverwriteACompletedReview(boolean submit) {
        TemplateEntity draft = service.create(1L, 2L, writeRequest("Original", "Hello {{name}}"));
        AtomicBoolean raced = new AtomicBoolean();
        TemplateMapper staleReader = mapperProxy(TemplateMapper.class, (method, args) -> {
            Object result = invoke(templates, method, args);
            if (method.getName().equals("selectOne") && args.length == 1 && raced.compareAndSet(false, true)) {
                TemplateEntity submitted = service.submit(1L, draft.getId());
                service.addReview(1L, draft.getId(), 1L, Roles.REVIEWER,
                        review(submitted.getVersion(), "APPROVE", "", null));
            }
            return result;
        });
        TemplateService staleService = transactional(staleReader, reviews);
        assertError(ErrorCode.STATE_NOT_ALLOWED, () -> {
            if (submit) {
                staleService.submit(1L, draft.getId());
            } else {
                staleService.update(1L, draft.getId(), writeRequest("Unreviewed", "different text"));
            }
        });
        assertState(draft.getId(), TemplateEntity.REVIEW_APPROVED, 2);
        assertEquals("Hello {{name}}", service.get(1L, draft.getId()).getContent());
        assertEquals(1, service.listReviews(1L, draft.getId()).size());
    }

    @Test
    void reviewInsertFailureRollsBackBothAuditAndFinalStatus() {
        TemplateEntity t = pending();
        TemplateReviewMapper failingReviews = mapperProxy(TemplateReviewMapper.class, (method, args) -> {
            Object result = invoke(reviews, method, args);
            if (method.getName().equals("insert")) {
                throw new IllegalStateException("failure after database insert");
            }
            return result;
        });
        TemplateService failingService = transactional(templates, failingReviews);
        assertThrows(IllegalStateException.class, () -> failingService.addReview(1L, t.getId(), 1L, Roles.REVIEWER,
                review(t.getVersion(), "APPROVE", "", null)));
        assertState(t.getId(), TemplateEntity.REVIEW_PENDING, t.getVersion());
        assertEquals(List.of(), service.listReviews(1L, t.getId()));
        service.addReview(1L, t.getId(), 1L, Roles.REVIEWER, review(t.getVersion(), "APPROVE", "", null));
        assertState(t.getId(), TemplateEntity.REVIEW_APPROVED, t.getVersion() + 1);
    }

    @Test
    void deletingChatPreservesReviewAndDeletingTemplateRemovesItsReviews() {
        TemplateEntity t = pending();
        Long chatId = chats.createChat(1L, 2L, "private chat").getId();
        service.addReview(1L, t.getId(), 2L, Roles.OPERATOR, review(t.getVersion(), "COMMENT", "保存批注", chatId));
        jdbc.update("DELETE FROM agent_chat WHERE id = ?", chatId);
        TemplateReviewEntity saved = service.listReviews(1L, t.getId()).get(0);
        assertNull(saved.getChatId());
        assertEquals("保存批注", saved.getComment());
        assertEquals(t.getContent(), saved.getContent());
        service.delete(1L, t.getId());
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM template_review WHERE template_id = ?", Long.class, t.getId()));
        assertError(ErrorCode.OBJECT_NOT_FOUND, () -> service.addReview(1L, t.getId(), 2L, Roles.OPERATOR,
                review(t.getVersion(), "COMMENT", "late comment", null)));
    }

    private TemplateEntity pending() {
        return service.submit(1L, service.create(1L, 2L, writeRequest("Original", "Hello {{name}}")).getId());
    }

    private Object decideAfter(CyclicBarrier start, TemplateEntity template, String decision) throws Exception {
        start.await(10, TimeUnit.SECONDS);
        try {
            return service.addReview(1L, template.getId(), 1L, Roles.REVIEWER,
                    review(template.getVersion(), decision, "人工决定", null));
        } catch (BizException e) {
            return e.getErrorCode();
        }
    }

    private static TemplateWriteRequest writeRequest(String title, String content) {
        TemplateWriteRequest request = new TemplateWriteRequest();
        request.setTitle(title);
        request.setContent(content);
        request.setChannel("console");
        return request;
    }

    private static TemplateReviewRequest review(long version, String decision, String comment, Long chatId) {
        TemplateReviewRequest request = new TemplateReviewRequest();
        request.setVersion(version);
        request.setDecision(decision);
        request.setComment(comment);
        request.setChatId(chatId);
        return request;
    }

    private static TemplateService transactional(TemplateMapper templateMapper, TemplateReviewMapper reviewMapper) {
        ProxyFactory proxy = new ProxyFactory(new TemplateService(templateMapper, sessions.getMapper(CampaignMapper.class),
                reviewMapper, chats));
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(dataSource),
                new AnnotationTransactionAttributeSource()));
        return (TemplateService) proxy.getProxy();
    }

    private void assertState(Long id, String status, long version) {
        TemplateEntity saved = service.get(1L, id);
        assertEquals(status, saved.getReviewStatus());
        assertEquals(version, saved.getVersion());
    }

    private static void assertError(ErrorCode error, Runnable action) {
        assertEquals(error, assertThrows(BizException.class, action::run).getErrorCode());
    }

    private static <T> T mapperProxy(Class<T> type, MapperCall call) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> call.invoke(method, args)));
    }

    private static Object invoke(Object mapper, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(mapper, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    @FunctionalInterface
    private interface MapperCall {
        Object invoke(Method method, Object[] args) throws Throwable;
    }
}
