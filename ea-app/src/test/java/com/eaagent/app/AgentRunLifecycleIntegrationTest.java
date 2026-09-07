package com.eaagent.app;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.eaagent.agent.engine.AgentEngine;
import com.eaagent.agent.engine.RunContext;
import com.eaagent.agent.event.EngineEvent;
import com.eaagent.agent.service.AgentRunWatchdog;
import com.eaagent.agent.service.AgentService;
import com.eaagent.common.BizException;
import com.eaagent.common.ErrorCode;
import com.eaagent.ontology.mapper.AgentRunMapper;
import com.eaagent.ontology.model.AgentRunEntity;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.LocalCacheScope;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/** Real PostgreSQL predicates, partial uniqueness and stale-snapshot races; no application/LLM/Redis server. */
@Testcontainers(disabledWithoutDocker = true)
class AgentRunLifecycleIntegrationTest {
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    private static DriverManagerDataSource dataSource;
    private static JdbcTemplate jdbc;
    private static AgentRunMapper mapper;
    private static SqlSessionTemplate sessions;
    private ExecutorService workers;

    @BeforeAll
    static void createSchema() {
        dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl() + "?stringtype=unspecified", POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
        configuration.setEnvironment(new Environment("lifecycle-test", new SpringManagedTransactionFactory(), dataSource));
        configuration.addMapper(AgentRunMapper.class);
        sessions = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration));
        mapper = sessions.getMapper(AgentRunMapper.class);
        jdbc.update("INSERT INTO tenant (id, name, domain) OVERRIDING SYSTEM VALUE VALUES (1, 'one', 'one'), (2, 'two', 'two')");
        jdbc.update("INSERT INTO tenant_user (id, tenant_id, login_name, name, password_hash, role) OVERRIDING SYSTEM VALUE "
                + "VALUES (1, 1, 'one', 'one', 'unused', 'USER'), (2, 1, 'two', 'two', 'unused', 'USER'), "
                + "(3, 2, 'three', 'three', 'unused', 'USER')");
    }

    @BeforeEach
    void resetRuns() {
        jdbc.update("DELETE FROM agent_run");
        workers = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void stopWorkers() throws InterruptedException {
        workers.shutdownNow();
        assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS), "lifecycle workers must finish");
    }

    @Test
    void concurrentStartAllowsOnlyOneRunAndMapsDatabaseConflict() throws Exception {
        CyclicBarrier bothRead = new CyclicBarrier(2);
        AgentRunMapper racingMapper = proxy((method, args) -> {
            Object result = invokeMapper(method, args);
            if (method.getName().equals("selectOne") && args.length == 1) {
                bothRead.await(10, TimeUnit.SECONDS);
            }
            return result;
        });
        AgentService service = service(racingMapper, List.of());
        Future<Object> first = workers.submit(() -> startOutcome(service));
        Future<Object> second = workers.submit(() -> startOutcome(service));

        List<Object> results = List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));

        assertEquals(1, results.stream().filter(AgentRunEntity.class::isInstance).count());
        assertEquals(1, results.stream().filter(ErrorCode.STATE_NOT_ALLOWED::equals).count());
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM agent_run", Long.class));
    }

    @Test
    void inflightExclusionIsOwnerScopedAndTerminalRunsReleaseSession() {
        AgentService service = service(mapper, List.of());
        AgentRunEntity first = service.startRun(1L, 1L, "USER", "first", "same", null);
        AgentRunEntity otherUser = service.startRun(1L, 2L, "USER", "second", "same", null);
        AgentRunEntity otherTenant = service.startRun(2L, 3L, "USER", "third", "same", null);
        assertEquals(ErrorCode.STATE_NOT_ALLOWED, assertThrows(BizException.class,
                () -> service.startRun(1L, 1L, "USER", "duplicate", "same", null)).getErrorCode());

        jdbc.update("UPDATE agent_run SET status = 'COMPLETED' WHERE id = ?", first.getId());
        AgentRunEntity next = service.startRun(1L, 1L, "USER", "next", "same", null);

        assertEquals(List.of("NEW", "NEW", "NEW"), List.of(status(otherUser), status(otherTenant), status(next)));
        assertEquals(4L, jdbc.queryForObject("SELECT count(*) FROM agent_run", Long.class));
    }

    @Test
    void twoStaleNewSnapshotsExecuteEngineOnlyOnce() throws Exception {
        AgentRunEntity run = insertRun("NEW", Instant.now());
        AgentRunEntity firstSnapshot = mapper.selectById(run.getId());
        AgentRunEntity secondSnapshot = mapper.selectById(run.getId());
        AtomicInteger executions = new AtomicInteger();
        CountDownLatch engineEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AgentEngine engine = engine(() -> {
            executions.incrementAndGet();
            engineEntered.countDown();
            await(release);
            return Flux.empty();
        });
        AgentService service = service(mapper, List.of(engine));
        Future<?> executing = workers.submit(() -> service.resume(firstSnapshot, new SseEmitter()));
        try {
            assertTrue(engineEntered.await(10, TimeUnit.SECONDS));
            assertEquals(ErrorCode.STATE_NOT_ALLOWED, assertThrows(BizException.class,
                    () -> service.resume(secondSnapshot, new SseEmitter())).getErrorCode());
        } finally {
            release.countDown();
        }
        executing.get(10, TimeUnit.SECONDS);

        assertEquals(1, executions.get());
        assertEquals("COMPLETED", status(run));
    }

    @Test
    void watchdogDoesNotFailRunWhoseHeartbeatArrivesAfterScan() {
        Instant staleAt = Instant.now().minusSeconds(3600);
        AgentRunEntity active = insertRun("EXECUTING", staleAt);
        AgentRunEntity abandoned = insertRun("NEW", staleAt);
        AgentRunEntity stalled = insertRun("PLANNING", staleAt);
        AgentRunMapper racingMapper = proxy((method, args) -> {
            Object result = invokeMapper(method, args);
            if (method.getName().equals("selectList")) {
                jdbc.update("UPDATE agent_run SET updated_at = now() WHERE id = ?", active.getId());
            }
            return result;
        });

        new AgentRunWatchdog(racingMapper, 60_000, 900_000).sweep();

        assertEquals("EXECUTING", status(active));
        assertEquals("CANCELLED", status(abandoned));
        assertEquals("FAILED", status(stalled));
    }

    @Test
    void lifecycleEventsCannotOverwriteEngineSummaryOrUsage() {
        AgentRunEntity run = insertRun("NEW", Instant.now());
        AgentEngine engine = engine(() -> {
            persistStats(run);
            return Flux.just(new EngineEvent("tool_call", "{\"tool\":\"lookup\"}"),
                    new EngineEvent("text_delta", "{\"text\":\"reply\"}"));
        });

        service(mapper, List.of(engine)).resume(mapper.selectById(run.getId()), new SseEmitter());

        assertEquals("COMPLETED", status(run));
        assertStatsSurvive(run);
    }

    @Test
    void engineFailureDoesNotOverwriteLastPersistedStats() {
        AgentRunEntity run = insertRun("NEW", Instant.now());
        AgentEngine engine = engine(() -> {
            persistStats(run);
            return Flux.error(new IllegalStateException("engine failure after stats"));
        });

        service(mapper, List.of(engine)).resume(mapper.selectById(run.getId()), new SseEmitter());

        assertEquals("FAILED", status(run));
        assertStatsSurvive(run);
    }

    @Test
    void watchdogFailureCannotBeResurrectedByLateEngineEvent() {
        AgentRunEntity run = insertRun("NEW", Instant.now());
        AgentEngine engine = engine(() -> {
            jdbc.update("UPDATE agent_run SET updated_at = ? WHERE id = ?",
                    Timestamp.from(Instant.now().minusSeconds(3600)), run.getId());
            new AgentRunWatchdog(mapper, 60_000, 900_000).sweep();
            return Flux.just(new EngineEvent("text_delta", "{\"text\":\"too late\"}"));
        });

        service(mapper, List.of(engine)).resume(mapper.selectById(run.getId()), new SseEmitter());

        assertEquals("FAILED", status(run));
    }

    @Test
    void terminalEventDoesNotReleaseSessionBeforeEngineCleanup() throws Exception {
        AgentRunEntity run = insertRun("NEW", Instant.now());
        CountDownLatch cleanupEntered = new CountDownLatch(1);
        CountDownLatch releaseCleanup = new CountDownLatch(1);
        AgentEngine engine = engine(() -> Flux.just(new EngineEvent("done", "{}"))
                .concatWith(Mono.<EngineEvent>fromRunnable(() -> {
                    cleanupEntered.countDown();
                    await(releaseCleanup);
                    persistStats(run);
                })));
        AgentService service = service(mapper, List.of(engine));
        Future<?> executing = workers.submit(() -> service.resume(mapper.selectById(run.getId()), new SseEmitter()));
        try {
            assertTrue(cleanupEntered.await(10, TimeUnit.SECONDS));
            assertEquals(ErrorCode.STATE_NOT_ALLOWED, assertThrows(BizException.class,
                    () -> service.startRun(run.getTenantId(), run.getUserId(), "USER", "next", run.getSessionId(), null))
                    .getErrorCode());
        } finally {
            releaseCleanup.countDown();
        }
        executing.get(10, TimeUnit.SECONDS);

        assertEquals("COMPLETED", status(run));
        assertStatsSurvive(run);
    }

    @Test
    void migrationRepairsHistoricalDuplicatesWithoutCrossingOwners() throws Exception {
        jdbc.execute("CREATE SCHEMA run_upgrade");
        jdbc.execute("CREATE TABLE run_upgrade.agent_run (LIKE public.agent_run INCLUDING DEFAULTS INCLUDING IDENTITY)");
        jdbc.update("INSERT INTO run_upgrade.agent_run (id, tenant_id, user_id, session_id, goal, status, updated_at) "
                + "OVERRIDING SYSTEM VALUE VALUES (1, 1, 1, 'same', 'old new', 'NEW', now() - interval '2 hours'), "
                + "(2, 1, 1, 'same', 'old executing', 'EXECUTING', now() - interval '1 hour'), "
                + "(3, 1, 1, 'same', 'fresh executing', 'EXECUTING', now()), "
                + "(4, 1, 2, 'same', 'other owner', 'NEW', now())");

        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.execute("SET LOCAL search_path TO run_upgrade, public");
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V18__agent_run_inflight_unique.sql"));
            connection.commit();
        }

        assertEquals(List.of("CANCELLED", "FAILED", "EXECUTING", "NEW"),
                jdbc.queryForList("SELECT status FROM run_upgrade.agent_run ORDER BY id", String.class));
    }

    private static Object startOutcome(AgentService service) {
        try {
            return service.startRun(1L, 1L, "USER", "same goal", "same", null);
        } catch (BizException e) {
            return e.getErrorCode();
        }
    }

    private static AgentRunEntity insertRun(String status, Instant updatedAt) {
        AgentRunEntity run = new AgentRunEntity();
        run.setTenantId(1L);
        run.setUserId(1L);
        run.setRole("USER");
        run.setSessionId("session-" + jdbc.queryForObject("SELECT count(*) FROM agent_run", Long.class));
        run.setGoal("goal");
        run.setStatus(status);
        run.setCreatedAt(updatedAt);
        run.setUpdatedAt(updatedAt);
        mapper.insert(run);
        return run;
    }

    private static String status(AgentRunEntity run) {
        return jdbc.queryForObject("SELECT status FROM agent_run WHERE id = ?", String.class, run.getId());
    }

    private static void persistStats(AgentRunEntity run) {
        jdbc.update("UPDATE agent_run SET tokens_used = 42, summary = 'durable reply', usage = '{\"input_tokens\":42}', "
                + "cost = 0.125, prompt_info = '{\"input_len\":12}', tool_calls = '[{\"name\":\"lookup\"}]' WHERE id = ?",
                run.getId());
    }

    private static void assertStatsSurvive(AgentRunEntity run) {
        AgentRunEntity saved = mapper.selectById(run.getId());
        assertEquals(42L, saved.getTokensUsed());
        assertEquals("durable reply", saved.getSummary());
        assertEquals(Map.of("input_tokens", 42), saved.getUsage());
        assertEquals(0, new BigDecimal("0.125").compareTo(saved.getCost()));
        assertEquals(Map.of("input_len", 12), saved.getPromptInfo());
        assertEquals(List.of(Map.of("name", "lookup")), saved.getToolCalls());
    }

    private static AgentService service(AgentRunMapper mapper, List<AgentEngine> engines) {
        ListOperations<String, String> steps = mock(ListOperations.class);
        StringRedisTemplate redis = new StringRedisTemplate() {
            @Override
            public ListOperations<String, String> opsForList() {
                return steps;
            }

            @Override
            public Boolean delete(String key) {
                return false;
            }
        };
        return new AgentService(mapper, redis, engines);
    }

    private static AgentEngine engine(Supplier<Flux<EngineEvent>> stream) {
        return new AgentEngine() {
            @Override
            public boolean available() {
                return true;
            }

            @Override
            public Flux<EngineEvent> stream(RunContext rc, String userInput) {
                return stream.get();
            }
        };
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS), "blocked engine must be released");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static AgentRunMapper proxy(MapperCall call) {
        return (AgentRunMapper) Proxy.newProxyInstance(AgentRunMapper.class.getClassLoader(),
                new Class<?>[]{AgentRunMapper.class}, (proxy, method, args) -> call.invoke(method, args));
    }

    private static Object invokeMapper(Method method, Object[] args) throws Throwable {
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
