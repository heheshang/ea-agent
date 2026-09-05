package com.eaagent.common;

/**
 * Actor 类型常量（action_log.actor_type / act_log actor 身份）。
 * HUMAN = 管理员/运营等带 tokenId 的真实用户；SYSTEM = 系统/定时触发；AGENT = Agent 引擎代践。
 */
public final class Actors {
    public static final String SYSTEM = "SYSTEM";
    public static final String USER = "USER";
    public static final String AGENT = "AGENT";

    private Actors() {
    }
}
