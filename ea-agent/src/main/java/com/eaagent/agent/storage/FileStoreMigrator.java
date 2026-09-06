package com.eaagent.agent.storage;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.eaagent.ontology.mapper.AgentRunMapper;
import com.eaagent.ontology.model.AgentRunEntity;
import com.eaagent.ontology.model.AgentScopeFileEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 存量 workspace 回填迁移器（仅 {@code ea.agentscope.file-store=postgres} 装配）。
 *
 * <p>把默认文件系统模式下落盘的 {@code .agentscope/workspace/{sessionId}/...} 幂等写入 PG BaseStore，
 * 使切换存储后既有会话文件仍可读。命名空间按 RemoteFilesystemSpec 的 USER 隔离 + 租户段还原：
 * <pre>
 *   {sessionId}/agents/ea-operator/sessions/*  → ["agents","ea-operator","users","tenant-{id}","sessions"]
 *   {sessionId}/memory/*                       → ["agents","ea-operator","users","tenant-{id}","memory"]
 * </pre>
 * 租户经 {@code agent_run.session_id} 反查（同 session 取任一 run 的 tenant_id）；反查不到
 * 的 session（如根级 memory/、agents/default、无 run 关联的孤儿目录）跳过并 warn——归属不明
 * 宁缺勿错，避免误写其他租户命名空间。幂等：目标键已存在则跳过，已迁移的 run 不会重复写入。
 */
@Component
@ConditionalOnProperty(value = "ea.agentscope.file-store", havingValue = "postgres")
public class FileStoreMigrator {
    private static final Logger log = LoggerFactory.getLogger(FileStoreMigrator.class);

    private static final String AGENT_ID = "ea-operator";
    private static final String USERS = "users";
    private static final String ROUTE_SESSIONS = "sessions";
    private static final String ROUTE_MEMORY = "memory";
    private static final String ROUTE_ROOT = "root";

    private final Path workspace;
    private final AgentRunMapper runMapper;
    private final com.eaagent.ontology.mapper.AgentScopeFileMapper fileMapper;

    public FileStoreMigrator(
            @org.springframework.beans.factory.annotation.Value("${ea.agentscope.workspace-dir:.agentscope/workspace}") String workspace,
            AgentRunMapper runMapper,
            com.eaagent.ontology.mapper.AgentScopeFileMapper fileMapper) {
        this.workspace = Path.of(workspace);
        this.runMapper = runMapper;
        this.fileMapper = fileMapper;
    }

    @PostConstruct
    public void migrate() {
        if (!Files.isDirectory(workspace)) {
            log.info("workspace 不存在，跳过存量回填: {}", workspace);
            return;
        }
        int sessions = 0;
        int files = 0;
        int skipped = 0;
        try (Stream<Path> dirs = Files.list(workspace)) {
            List<Path> dirList = dirs.filter(Files::isDirectory).toList();
            for (Path dir : dirList) {
                String sessionId = dir.getFileName().toString();
                // 根级 agents/、memory/、.skills-cache 等非 session 目录：无 sessionId 归属，跳过
                if (sessionId.startsWith(".") || "agents".equals(sessionId) || "memory".equals(sessionId)) {
                    skipped++;
                    continue;
                }
                Long tenantId = tenantOf(sessionId);
                if (tenantId == null) {
                    log.warn("session {} 在 agent_run 无归属 run，跳过回填（无法确定租户）", sessionId);
                    skipped++;
                    continue;
                }
                files += migrateSession(sessionId, tenantId);
                sessions++;
            }
        } catch (IOException e) {
            log.warn("扫描 workspace 失败，跳过存量回填: {}", e.getMessage());
            return;
        }
        log.info("存量 workspace 回填完成：{} 个 session，{} 个文件，跳过 {} 项", sessions, files, skipped);
    }

    private Long tenantOf(String sessionId) {
        return runMapper.selectList(new QueryWrapper<AgentRunEntity>()
                        .select(AgentRunEntity.COL_TENANT_ID)
                        .eq(AgentRunEntity.COL_SESSION_ID, sessionId)
                        .last("LIMIT 1"))
                .stream().findFirst().map(AgentRunEntity::getTenantId).orElse(null);
    }

    /** 迁移单个 session 目录，返回写入文件数。 */
    private int migrateSession(String sessionId, Long tenantId) {
        Path sessionDir = workspace.resolve(sessionId);
        int count = 0;
        // sessions 路由
        Path sessionsDir = sessionDir.resolve("agents").resolve(AGENT_ID).resolve("sessions");
        if (Files.isDirectory(sessionsDir)) {
            count += migrateRoute(sessionId, tenantId, sessionsDir, ROUTE_SESSIONS);
        }
        // memory 路由
        Path memoryDir = sessionDir.resolve("memory");
        if (Files.isDirectory(memoryDir)) {
            count += migrateRoute(sessionId, tenantId, memoryDir, ROUTE_MEMORY);
        }
        return count;
    }

    private int migrateRoute(String sessionId, Long tenantId, Path routeDir, String routeSegment) {
        List<String> namespace = List.of("agents", AGENT_ID, USERS, "tenant-" + tenantId, routeSegment);
        String ns = PgBaseStore.namespaceJoin(namespace);
        int count = 0;
        try (Stream<Path> files = Files.list(routeDir)) {
            List<Path> fileList = files.filter(Files::isRegularFile).toList();
            for (Path file : fileList) {
                String key = file.getFileName().toString();
                if (keyExists(tenantId, ns, key)) {
                    continue; // 幂等
                }
                try {
                    Map<String, Object> value = fileDataValue(file);
                    AgentScopeFileEntity e = new AgentScopeFileEntity();
                    e.setTenantId(tenantId);
                    e.setNamespace(ns);
                    e.setItemKey(key);
                    e.setValue(value);
                    e.setVersion(1L);
                    fileMapper.insert(e);
                    count++;
                } catch (IOException ioe) {
                    log.warn("session {} route {} 读取文件 {} 失败，跳过: {}",
                            sessionId, routeSegment, key, ioe.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("session {} route {} 扫描失败: {}", sessionId, routeSegment, e.getMessage());
        }
        return count;
    }

    private boolean keyExists(Long tenantId, String ns, String key) {
        return fileMapper.selectCount(new QueryWrapper<AgentScopeFileEntity>()
                .eq(AgentScopeFileEntity.COL_TENANT_ID, tenantId)
                .eq(AgentScopeFileEntity.COL_NAMESPACE, ns)
                .eq(AgentScopeFileEntity.COL_ITEM_KEY, key)) > 0;
    }

    /** 文件 → fileDataToStoreValue 等价 Map（content/encoding/created_at/modified_at）。 */
    private static Map<String, Object> fileDataValue(Path file) throws IOException {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("content", Files.readString(file));
        v.put("encoding", "UTF-8");
        FileTime mtime = Files.getLastModifiedTime(file);
        v.put("modified_at", mtime.toInstant().toString());
        v.put("created_at", Instant.now().toString()); // 文件系统无 ctime 约定，用当前时间占位
        return v;
    }
}