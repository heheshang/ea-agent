package com.eaagent.app.config;

import com.eaagent.common.TenantContext;
import org.springframework.core.task.TaskDecorator;

/**
 * 跨线程租户上下文传递：SSE/异步线程池任务继承调用方 TenantContext，执行后还原。
 */
public class TenantContextTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable task) {
        TenantContext.Context snapshot = TenantContext.snapshot();
        return () -> {
            TenantContext.restore(snapshot);
            try {
                task.run();
            } finally {
                TenantContext.clear();
            }
        };
    }
}