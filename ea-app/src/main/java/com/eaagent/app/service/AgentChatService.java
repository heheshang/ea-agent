package com.eaagent.app.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.eaagent.common.BizException;
import com.eaagent.common.ErrorCode;
import com.eaagent.common.Texts;
import com.eaagent.ontology.mapper.AgentChatMapper;
import com.eaagent.ontology.model.AgentChatEntity;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 聊天（Chat）容器服务（V17，/api/agent/chats）：
 * 新建聊天产出唯一 id + 描述；run 与调用链明细按 chat_id 归属，可在 Ontology 调用链中跨 run 追踪。
 * 描述缺省「新对话」，首条 goal 到来时自动更新为 goal 截断——让描述有实际语义。
 */
@Service
public class AgentChatService {

    /** goal 截断为描述的字符上限。 */
    private static final int DESC_LIMIT = 64;

    private final AgentChatMapper chatMapper;

    public AgentChatService(AgentChatMapper chatMapper) {
        this.chatMapper = chatMapper;
    }

    /** 新建聊天：产出唯一 id（租户内 identity）+ 描述。 */
    public AgentChatEntity createChat(Long tenantId, Long userId, String description) {
        if (description != null && description.trim().length() > 256) {
            throw new BizException(ErrorCode.PARAM_ERROR, "聊天描述不能超过 256 字符");
        }
        AgentChatEntity c = new AgentChatEntity();
        c.setTenantId(tenantId);
        c.setUserId(userId);
        c.setDescription(description == null || description.isBlank()
                ? AgentChatEntity.DEFAULT_DESCRIPTION : description.trim());
        c.setStatus(AgentChatEntity.STATUS_ACTIVE);
        chatMapper.insert(c);
        return c;
    }

    /** 按 id 校验租户和用户归属，不存在或越权均返回 15004。 */
    public AgentChatEntity getChat(Long tenantId, Long userId, Long chatId) {
        AgentChatEntity c = chatMapper.selectOne(new QueryWrapper<AgentChatEntity>()
                .eq(AgentChatEntity.COL_TENANT_ID, tenantId)
                .eq(AgentChatEntity.COL_USER_ID, userId)
                .eq(AgentChatEntity.COL_ID, chatId));
        if (c == null) {
            throw new BizException(ErrorCode.CHAT_NOT_FOUND, "聊天不存在: " + chatId);
        }
        return c;
    }

    /** 当前用户聊天列表（新到旧）。 */
    public List<AgentChatEntity> listChats(Long tenantId, Long userId, int limit) {
        return chatMapper.selectList(new QueryWrapper<AgentChatEntity>()
                .eq(AgentChatEntity.COL_TENANT_ID, tenantId)
                .eq(AgentChatEntity.COL_USER_ID, userId)
                .orderByDesc(AgentChatEntity.COL_CREATED_AT)
                .last("LIMIT " + Math.max(1, Math.min(limit, 200))));
    }

    /** 默认描述聊天收到首条 goal 时更新描述为 goal 截断（描述有语义，便于调用链回放识别）；返回最新聊天。 */
    public AgentChatEntity describeFromGoal(Long tenantId, Long userId, Long chatId, String goal) {
        AgentChatEntity c = getChat(tenantId, userId, chatId);
        if (goal == null || goal.isBlank()
                || !AgentChatEntity.DEFAULT_DESCRIPTION.equals(c.getDescription())) {
            return c;
        }
        AgentChatEntity up = new AgentChatEntity();
        up.setId(c.getId());
        up.setDescription(Texts.truncate(goal.trim(), DESC_LIMIT));
        chatMapper.updateById(up);
        return chatMapper.selectById(c.getId());
    }
}