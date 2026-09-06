package com.eaagent.agent.storage;

import com.eaagent.ontology.mapper.AgentScopeFileMapper;
import com.eaagent.ontology.mapper.AgentScopeStateMapper;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.harness.agent.DistributedStore;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * agentscope {@link DistributedStore} 的 PostgreSQL 聚合 Bean：统一提供
 * {@link AgentStateStore}（会话状态）与 {@link BaseStore}（workspace 文件）。
 * sandbox/messageBus/asyncToolRegistry 全部走默认（不启用 sandbox、不启用 subagents）。
 *
 * <p>仅 {@code ea.agentscope.file-store=postgres} 时装配；默认 filesystem 时不创建该 Bean
 * （引擎据此分支，零回归）。多租户隔离见 {@link PgAgentStateStore}/{@link PgBaseStore}。
 */
@Component
@ConditionalOnProperty(value = "ea.agentscope.file-store", havingValue = "postgres")
public class PgDistributedStore implements DistributedStore {

    private final AgentStateStore agentStateStore;
    private final BaseStore baseStore;

    public PgDistributedStore(AgentScopeStateMapper stateMapper, AgentScopeFileMapper fileMapper) {
        this.agentStateStore = new PgAgentStateStore(stateMapper);
        this.baseStore = new PgBaseStore(fileMapper);
    }

    @Override
    public AgentStateStore agentStateStore() {
        return agentStateStore;
    }

    @Override
    public BaseStore baseStore() {
        return baseStore;
    }
}