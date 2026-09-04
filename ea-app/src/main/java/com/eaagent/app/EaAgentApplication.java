package com.eaagent.app;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * EA-Agent 应用入口：装配全部模块，开启调度（EA-Bus 消费者）与 Mapper 扫描。
 */
@SpringBootApplication(scanBasePackages = "com.eaagent")
@EnableScheduling
@MapperScan("com.eaagent.ontology.mapper")
public class EaAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(EaAgentApplication.class, args);
    }
}