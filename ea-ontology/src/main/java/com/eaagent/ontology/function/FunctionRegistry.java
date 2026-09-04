package com.eaagent.ontology.function;

import com.eaagent.common.BizException;
import com.eaagent.common.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Function 注册表（4.2 Call Function）：按名称路由 callFunction；未注册抛 E-17001。
 * 注册方式与 ActionRegistry 一致 —— Spring 构造注入收集全部 Function bean，
 * 新增预测 / 优化函数只需实现 Function 并声明 @Component，无需改路由代码。
 */
@Component
public class FunctionRegistry {

    private static final Logger log = LoggerFactory.getLogger(FunctionRegistry.class);

    private final Map<String, Function> functions = new ConcurrentHashMap<>();

    public FunctionRegistry(List<Function> discovered) {
        for (Function f : discovered) {
            functions.put(f.name(), f);
        }
        log.info("FunctionRegistry initialized: {}", functions.keySet());
    }

    public Function get(String name) {
        Function f = functions.get(name);
        if (f == null) {
            throw new BizException(ErrorCode.FUNCTION_NOT_REGISTERED);
        }
        return f;
    }

    /** 全部函数（按名排序，供 callFunction 工具描述与链路图使用）。 */
    public List<Function> all() {
        return functions.values().stream().sorted(Comparator.comparing(Function::name)).toList();
    }
}