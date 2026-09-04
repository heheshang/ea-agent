package com.eaagent.app.config;

import com.eaagent.ontology.rule.RuleEngine;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 全局 Bean：口令哈希（BCrypt，运行时生成，无固定盐）+ 规则引擎实例
 * （RuleEngine 为无状态编译器，ObjectApiService/AudienceResolver 注入使用）。
 */
@Configuration
public class AppConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public RuleEngine ruleEngine() {
        return new RuleEngine();
    }
}