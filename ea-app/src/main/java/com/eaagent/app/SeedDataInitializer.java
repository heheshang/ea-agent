package com.eaagent.app;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.eaagent.common.CryptoService;
import com.eaagent.ontology.mapper.AudienceMapper;
import com.eaagent.ontology.mapper.CampaignMapper;
import com.eaagent.ontology.mapper.ChannelConfigMapper;
import com.eaagent.ontology.mapper.CustomerMapper;
import com.eaagent.ontology.mapper.TemplateMapper;
import com.eaagent.ontology.mapper.TenantMapper;
import com.eaagent.ontology.mapper.TenantUserMapper;
import com.eaagent.ontology.mapper.UnsubscribeMapper;
import com.eaagent.ontology.model.AudienceEntity;
import com.eaagent.ontology.model.CampaignEntity;
import com.eaagent.ontology.model.ChannelConfigEntity;
import com.eaagent.ontology.model.CustomerEntity;
import com.eaagent.ontology.model.TemplateEntity;
import com.eaagent.ontology.model.TenantEntity;
import com.eaagent.ontology.model.TenantUserEntity;
import com.eaagent.ontology.model.UnsubscribeEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * 演示数据（ea.seed.enabled，幂等：已存在 demo 租户则跳过）：
 * demo 租户 + 双角色用户（BCrypt 运行时生成）+ DYNAMIC 人群 + 已审核模板 + RUNNING 活动
 * （触发规则 event_type=order_placed, cooldown=PT1H）+ 3 客户 + console 通道 + 一条退订。
 */
@Component
public class SeedDataInitializer implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(SeedDataInitializer.class);

    private final TenantMapper tenantMapper;
    private final TenantUserMapper tenantUserMapper;
    private final ChannelConfigMapper channelConfigMapper;
    private final AudienceMapper audienceMapper;
    private final TemplateMapper templateMapper;
    private final CampaignMapper campaignMapper;
    private final CustomerMapper customerMapper;
    private final UnsubscribeMapper unsubscribeMapper;
    private final PasswordEncoder encoder;
    private final CryptoService cryptoService;
    private final boolean enabled;

    public SeedDataInitializer(TenantMapper tenantMapper, TenantUserMapper tenantUserMapper,
                               ChannelConfigMapper channelConfigMapper, AudienceMapper audienceMapper,
                               TemplateMapper templateMapper, CampaignMapper campaignMapper,
                               CustomerMapper customerMapper, UnsubscribeMapper unsubscribeMapper,
                               PasswordEncoder encoder, CryptoService cryptoService,
                               @Value("${ea.seed.enabled:true}") boolean enabled) {
        this.tenantMapper = tenantMapper;
        this.tenantUserMapper = tenantUserMapper;
        this.channelConfigMapper = channelConfigMapper;
        this.audienceMapper = audienceMapper;
        this.templateMapper = templateMapper;
        this.campaignMapper = campaignMapper;
        this.customerMapper = customerMapper;
        this.unsubscribeMapper = unsubscribeMapper;
        this.encoder = encoder;
        this.cryptoService = cryptoService;
        this.enabled = enabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            log.info("seed disabled");
            return;
        }
        TenantEntity demo = tenantMapper.selectOne(new QueryWrapper<TenantEntity>()
                .eq(TenantEntity.COL_NAME, "demo").last("LIMIT 1"));
        if (demo != null) {
            seedSmsChannel(demo.getId());
            seedEmailChannel(demo.getId());
            log.info("demo tenant already seeded, skip core seed");
            return;
        }
        Long tenantId = seedTenant();
        Long adminId = seedUser(tenantId, "admin", "admin123", "管理员", "OPERATOR");
        seedUser(tenantId, "reviewer", "reviewer123", "审核员", "REVIEWER");
        seedChannel(tenantId);
        seedSmsChannel(tenantId);
        seedEmailChannel(tenantId);
        Long audienceId = seedAudience(tenantId, adminId);
        Long templateId = seedTemplate(tenantId);
        seedCampaign(tenantId, adminId, audienceId, templateId);
        seedCustomers(tenantId);
        seedUnsubscribe(tenantId);
        log.info("demo seed done: tenant={} admin={}", tenantId, adminId);
    }

    private Long seedTenant() {
        TenantEntity t = new TenantEntity();
        t.setName("demo");
        t.setDomain("demo.local");
        t.setPlan("trial");
        t.setStatus("ACTIVE");
        t.setQuota("{\"max_daily_touch\":1000}");
        t.setCreatedAt(Instant.now());
        tenantMapper.insert(t);
        return t.getId();
    }

    private Long seedUser(Long tenantId, String login, String rawPassword, String name, String role) {
        TenantUserEntity u = new TenantUserEntity();
        u.setTenantId(tenantId);
        u.setLoginName(login);
        u.setName(name);
        u.setPasswordHash(encoder.encode(rawPassword));
        u.setRole(role);
        u.setStatus("ACTIVE");
        u.setCreatedAt(Instant.now());
        tenantUserMapper.insert(u);
        return u.getId();
    }

    private void seedChannel(Long tenantId) {
        ChannelConfigEntity c = new ChannelConfigEntity();
        c.setTenantId(tenantId);
        c.setChannel("console");
        c.setConfigEncrypted(cryptoService.encrypt(tenantId, "{}"));
        c.setCallbackSecret(cryptoService.encrypt(tenantId, "console-dev-secret"));
        c.setEnabled(true);
        c.setFrequencyLimit(Map.of("max_per_day", 10, "quiet_hours", List.of("23:00-07:00")));
        c.setCreatedAt(Instant.now());
        channelConfigMapper.insert(c);
    }

    /** 短信真实通道（mock 网关联调）：endpoint 由 EA_MOCK_GW_URL 指定（容器内默认 compose 网络名，本地启动指 localhost:8090）；幂等，已有配置则跳过。 */
    private void seedSmsChannel(Long tenantId) {
        Long exists = channelConfigMapper.selectCount(new QueryWrapper<ChannelConfigEntity>()
                .eq(ChannelConfigEntity.COL_TENANT_ID, tenantId)
                .eq(ChannelConfigEntity.COL_CHANNEL, "sms"));
        if (exists != null && exists > 0) {
            return;
        }
        ChannelConfigEntity c = new ChannelConfigEntity();
        c.setTenantId(tenantId);
        c.setChannel("sms");
        c.setConfigEncrypted(cryptoService.encrypt(tenantId,
                "{\"endpoint\":\"" + mockGwUrl() + "/sms\",\"apiKey\":\"test-api-key\","
                        + "\"apiSecret\":\"test-api-secret\",\"signName\":\"EA运营\"}"));
        c.setCallbackSecret(cryptoService.encrypt(tenantId, "sms-callback-secret-1"));
        c.setEnabled(true);
        c.setFrequencyLimit(Map.of("max_per_day", 10, "quiet_hours", List.of("23:00-07:00")));
        c.setCreatedAt(Instant.now());
        channelConfigMapper.insert(c);
        log.info("sms channel config seeded: tenant={}", tenantId);
    }

    /** 邮件真实通道（mock 网关联调）：endpoint 由 EA_MOCK_GW_URL 指定；幂等，已有配置则跳过。 */
    private void seedEmailChannel(Long tenantId) {
        Long exists = channelConfigMapper.selectCount(new QueryWrapper<ChannelConfigEntity>()
                .eq(ChannelConfigEntity.COL_TENANT_ID, tenantId)
                .eq(ChannelConfigEntity.COL_CHANNEL, "email"));
        if (exists != null && exists > 0) {
            return;
        }
        ChannelConfigEntity c = new ChannelConfigEntity();
        c.setTenantId(tenantId);
        c.setChannel("email");
        c.setConfigEncrypted(cryptoService.encrypt(tenantId,
                "{\"endpoint\":\"" + mockGwUrl() + "/email\",\"apiKey\":\"test-api-key\","
                        + "\"apiSecret\":\"test-api-secret\"}"));
        c.setCallbackSecret(cryptoService.encrypt(tenantId, "sms-callback-secret-1"));
        c.setEnabled(true);
        c.setCreatedAt(Instant.now());
        channelConfigMapper.insert(c);
        log.info("email channel config seeded: tenant={}", tenantId);
    }

    /** mock 网关地址：本地启动（应用宿主进程）传 http://localhost:8090，容器内默认 compose 网络名 mock-gw。 */
    private String mockGwUrl() {
        String url = System.getenv("EA_MOCK_GW_URL");
        if (url == null || url.isBlank()) {
            url = "http://mock-gw:8090";
        }
        return url.replaceAll("/+$", "");
    }

    private Long seedAudience(Long tenantId, Long adminId) {
        AudienceEntity a = new AudienceEntity();
        a.setTenantId(tenantId);
        a.setName("活跃客户");
        a.setMode("DYNAMIC");
        a.setRule("status == 'ACTIVE'");
        a.setOwnerId(adminId);
        a.setStatus("ACTIVE");
        a.setCreatedAt(Instant.now());
        audienceMapper.insert(a);
        return a.getId();
    }

    private Long seedTemplate(Long tenantId) {
        TemplateEntity t = new TemplateEntity();
        t.setTenantId(tenantId);
        t.setChannel("console");
        t.setTitle("新人首单提醒");
        t.setContent("您好 {{name}}，您的首单专属优惠已到账。");
        t.setVars(List.of("name"));
        t.setReviewStatus("APPROVED");
        t.setCreatedAt(Instant.now());
        templateMapper.insert(t);
        return t.getId();
    }

    private void seedCampaign(Long tenantId, Long adminId, Long audienceId, Long templateId) {
        CampaignEntity c = new CampaignEntity();
        c.setTenantId(tenantId);
        c.setName("新人复购提醒");
        c.setAudienceId(audienceId);
        c.setChannel("console");
        c.setTemplateId(templateId);
        c.setGrayRatio(100);
        c.setAbMode("NONE");
        c.setTriggerRule(Map.of("event_type", "order_placed", "window", "1d", "cooldown", "PT1H"));
        c.setStatus("RUNNING");
        c.setOwnerId(adminId);
        c.setCreatedAt(Instant.now());
        c.setUpdatedAt(Instant.now());
        campaignMapper.insert(c);
    }

    private void seedCustomers(Long tenantId) {
        String[][] data = {
                {"13800000001", "alice@demo.com", "张三"},
                {"13800000002", "bob@demo.com", "李四"},
                {"13800000003", "carol@demo.com", "王五"},
        };
        for (String[] row : data) {
            CustomerEntity c = new CustomerEntity();
            c.setTenantId(tenantId);
            c.setPhone(row[0]);
            c.setEmail(row[1]);
            c.setAttributes(Map.of("preferred_channel", "console", "name", row[2]));
            c.setStatus("ACTIVE");
            c.setCreatedAt(Instant.now());
            c.setUpdatedAt(Instant.now());
            customerMapper.insert(c);
        }
    }

    private void seedUnsubscribe(Long tenantId) {
        UnsubscribeEntity u = new UnsubscribeEntity();
        u.setTenantId(tenantId);
        u.setCustomerKey(sha256Hex("13800000003")); // 王五退订 console，触发时跳过该客户
        u.setChannel("console");
        u.setReason("演示退订");
        u.setCreatedAt(Instant.now());
        unsubscribeMapper.insert(u);
    }

    static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}