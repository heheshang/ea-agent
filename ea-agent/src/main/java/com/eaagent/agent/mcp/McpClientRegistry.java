package com.eaagent.agent.mcp;

import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP（Model Context Protocol）客户端注册中心（详细设计 4.2·ADR-5 落地）：
 * 按 {@code ea.agentscope.mcp.servers} 配置（名称/传输方式/endpoint/headers/工具白名单）惰性构建
 * {@link McpClientWrapper}，并在每个会话 Toolkit 装配时把 MCP 工具并入工具集——LLM 侧与本地
 * 七工具（Query Objects / applyAction / callFunction）无感知差异，统计与 tool_calls 明细自然覆盖。
 *
 * <p>传输支持：stdio（command+args）、streamable-http（url）、sse（url）。单个 server 构建/注册
 * 失败仅降级该 server（log warn/error），不阻塞 Agent 启动与会话执行。
 *
 * <p>wrapper 全局共享（按 server name 缓存）：同一 MCP server 只连接一次，所有租户会话复用；
 * 每个会话 Toolkit 独立注册（引擎 sessions.computeIfAbsent 保证每会话只装配一次）。
 */
@Component
public class McpClientRegistry {

    private static final Logger log = LoggerFactory.getLogger(McpClientRegistry.class);

    /** 启动/注册超时（秒）。 */
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final List<Map<String, Object>> serverConfigs;
    private volatile Map<String, McpClientWrapper> clients;

    public McpClientRegistry(
            @Value("${ea.agentscope.mcp.servers:}") String serversJson) {
        this.serverConfigs = parseServers(serversJson);
    }

    /** 解析配置 JSON（yaml/env 均可传 JSON 数组），非法配置降级为空列表不阻塞启动。 */
    private static List<Map<String, Object>> parseServers(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(json, new com.fasterxml.jackson.core.type.TypeReference<>() {
                    });
        } catch (Exception e) {
            log.warn("mcp servers config is not a valid JSON array ({}), degraded to no servers",
                    e.toString());
            return List.of();
        }
    }

    /** 已配置的 MCP server 数量（未连接时按配置计数）。 */
    public int configured() {
        return serverConfigs.size();
    }

    /** 按配置惰性构建全部 MCP client（首次调用连接；失败 server 跳过）。线程安全。 */
    public List<McpClientWrapper> clients() {
        Map<String, McpClientWrapper> snapshot = clients;
        if (snapshot == null) {
            synchronized (this) {
                snapshot = clients;
                if (snapshot == null) {
                    snapshot = buildAll();
                    clients = snapshot;
                }
            }
        }
        return snapshot.isEmpty() ? List.of() : new ArrayList<>(snapshot.values());
    }

    /** 把全部已连接 MCP server 的工具并入 Toolkit（每个 wrapper 注册一次；失败仅降级该 server）。 */
    public void registerInto(Toolkit toolkit) {
        for (McpClientWrapper w : clients()) {
            try {
                toolkit.registerMcpClient(w).block(TIMEOUT);
                log.info("MCP client '{}' tools registered into toolkit", w.getName());
            } catch (Exception e) {
                log.error("MCP client '{}' registration failed, degraded: {}",
                        w.getName(), e.toString());
            }
        }
    }

    @PreDestroy
    public void close() {
        Map<String, McpClientWrapper> snapshot = clients;
        if (snapshot != null) {
            snapshot.values().forEach(w -> {
                try {
                    w.close();
                } catch (Exception ignored) {
                    // close 失败不影响停机
                }
            });
            clients = null;
        }
    }

    private Map<String, McpClientWrapper> buildAll() {
        Map<String, McpClientWrapper> built = new LinkedHashMap<>();
        for (Map<String, Object> cfg : serverConfigs) {
            McpClientWrapper w = buildOne(cfg);
            if (w != null) {
                built.put(w.getName(), w);
            }
        }
        return built;
    }

    private McpClientWrapper buildOne(Map<String, Object> cfg) {
        String name = str(cfg, "name");
        if (name == null || name.isBlank()) {
            log.warn("mcp server config missing 'name', skipped");
            return null;
        }
        String transport = str(cfg, "transport");
        try {
            McpClientBuilder b = McpClientBuilder.create(name).timeout(TIMEOUT);
            if ("streamable-http".equalsIgnoreCase(transport)) {
                b = b.streamableHttpTransport(str(cfg, "url"));
            } else if ("sse".equalsIgnoreCase(transport)) {
                b = b.sseTransport(str(cfg, "url"));
            } else { // 默认 stdio
                List<String> args = strList(cfg, "args");
                Map<String, String> env = strMap(cfg, "env");
                String command = str(cfg, "command");
                if (command == null || command.isBlank()) {
                    log.warn("mcp server '{}': stdio transport requires 'command', skipped", name);
                    return null;
                }
                b = args == null || args.isEmpty()
                        ? b.stdioTransport(command)
                        : (env == null || env.isEmpty())
                                ? b.stdioTransport(command, args.toArray(new String[0]))
                                : b.stdioTransport(command, args, env);
            }
            Map<String, String> headers = strMap(cfg, "headers");
            if (headers != null && !headers.isEmpty()) {
                b = b.headers(headers);
            }
            McpClientWrapper w = b.buildSync();
            log.info("MCP client '{}' built, transport={}", name, transport);
            return w;
        } catch (Exception e) {
            log.warn("MCP client '{}' build failed ({}), degraded: {}",
                    name, transport, e.toString());
            return null;
        }
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : String.valueOf(v);
    }

    @SuppressWarnings("unchecked")
    private static List<String> strList(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null) {
            return null;
        }
        if (v instanceof List<?> l) {
            List<String> out = new ArrayList<>(l.size());
            for (Object o : l) {
                out.add(String.valueOf(o));
            }
            return out;
        }
        return List.of(String.valueOf(v));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> strMap(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null) {
            return null;
        }
        if (v instanceof Map<?, ?> mm) {
            Map<String, String> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : mm.entrySet()) {
                out.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
            }
            return out;
        }
        return Collections.emptyMap();
    }
}