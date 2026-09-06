package com.eaagent.app;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.eaagent.agent.service.KnowledgeBaseService;
import com.eaagent.common.CryptoService;
import com.eaagent.common.Roles;
import com.eaagent.common.Texts;
import com.eaagent.ontology.mapper.AudienceMapper;
import com.eaagent.ontology.mapper.CampaignMapper;
import com.eaagent.ontology.mapper.ChannelConfigMapper;
import com.eaagent.ontology.mapper.CustomerMapper;
import com.eaagent.ontology.mapper.KnowledgeLinkMapper;
import com.eaagent.ontology.mapper.KnowledgeMapper;
import com.eaagent.ontology.mapper.TemplateMapper;
import com.eaagent.ontology.mapper.TenantMapper;
import com.eaagent.ontology.mapper.TenantUserMapper;
import com.eaagent.ontology.mapper.UnsubscribeMapper;
import com.eaagent.ontology.model.AudienceEntity;
import com.eaagent.ontology.model.CampaignEntity;
import com.eaagent.ontology.model.ChannelConfigEntity;
import com.eaagent.ontology.model.CustomerEntity;
import com.eaagent.ontology.model.KnowledgeEntity;
import com.eaagent.ontology.model.KnowledgeLinkEntity;
import com.eaagent.ontology.model.TemplateEntity;
import com.eaagent.ontology.model.TenantEntity;
import com.eaagent.ontology.model.TenantUserEntity;
import com.eaagent.ontology.model.UnsubscribeEntity;
import com.eaagent.ontology.service.AudienceSnapshotService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 演示数据（ea.seed.enabled，幂等：已存在 demo 租户则跳过）：
 * demo 租户 + 双角色用户（BCrypt 运行时生成）+ DYNAMIC 人群 + 已审核模板 + RUNNING 活动
 * （触发规则 event_type=order_placed, window=1d）+ 3 客户 + console 通道 + 一条退订。
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
    private final KnowledgeMapper knowledgeMapper;
    private final KnowledgeLinkMapper knowledgeLinkMapper;
    private final KnowledgeBaseService knowledgeService;
    private final AudienceSnapshotService snapshotService;
    private final PasswordEncoder encoder;
    private final CryptoService cryptoService;
    private final boolean enabled;

    public SeedDataInitializer(TenantMapper tenantMapper, TenantUserMapper tenantUserMapper,
                               ChannelConfigMapper channelConfigMapper, AudienceMapper audienceMapper,
                               TemplateMapper templateMapper, CampaignMapper campaignMapper,
                               CustomerMapper customerMapper, UnsubscribeMapper unsubscribeMapper,
                               KnowledgeMapper knowledgeMapper, KnowledgeLinkMapper knowledgeLinkMapper,
                               KnowledgeBaseService knowledgeService,
                               AudienceSnapshotService snapshotService,
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
        this.knowledgeMapper = knowledgeMapper;
        this.knowledgeLinkMapper = knowledgeLinkMapper;
        this.knowledgeService = knowledgeService;
        this.snapshotService = snapshotService;
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
            seedKnowledge(demo.getId());
            seedKnowledgeLinks(demo.getId());
            log.info("demo tenant already seeded, skip core seed");
            return;
        }
        Long tenantId = seedTenant();
        Long adminId = seedUser(tenantId, "admin", "admin123", "管理员", Roles.OPERATOR);
        seedUser(tenantId, "reviewer", "reviewer123", "审核员", Roles.REVIEWER);
        seedChannel(tenantId);
        seedSmsChannel(tenantId);
        seedEmailChannel(tenantId);
        Long audienceId = seedAudience(tenantId, adminId);
        Long templateId = seedTemplate(tenantId);
        // 客户先于活动：活动创建时固化人群快照（member_count/customer_ids 需客户已存在）
        seedCustomers(tenantId);
        seedCampaign(tenantId, adminId, audienceId, templateId);
        seedUnsubscribe(tenantId);
        seedKnowledge(tenantId);
        seedKnowledgeLinks(tenantId);
        log.info("demo seed done: tenant={} admin={}", tenantId, adminId);
    }

    private Long seedTenant() {
        TenantEntity t = new TenantEntity();
        t.setName("demo");
        t.setDomain("demo.local");
        t.setPlan("trial");
        t.setStatus(TenantEntity.STATUS_ACTIVE);
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
        a.setStatus(AudienceEntity.STATUS_ACTIVE);
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
        t.setReviewStatus(TemplateEntity.REVIEW_APPROVED);
        t.setCreatedAt(Instant.now());
        templateMapper.insert(t);
        return t.getId();
    }

    private void seedCampaign(Long tenantId, Long adminId, Long audienceId, Long templateId) {
        CampaignEntity c = new CampaignEntity();
        c.setTenantId(tenantId);
        c.setName("新人复购提醒");
        c.setAudienceId(audienceId);
        // 活动创建即固化人群快照；存量/新建活动发送一律以快照为准（不与人群规则实时漂移）
        c.setAudienceSnapshot(snapshotService.build(tenantId, audienceId));
        c.setChannel("console");
        c.setTemplateId(templateId);
        c.setTriggerRule(Map.of("event_type", "order_placed", "window", "1d"));
        c.setStatus(CampaignEntity.STATUS_RUNNING);
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
            c.setTags(List.of("示例", row[2]));
            c.setStatus(CustomerEntity.STATUS_ACTIVE);
            c.setCreatedAt(Instant.now());
            c.setUpdatedAt(Instant.now());
            customerMapper.insert(c);
        }
    }

    private void seedUnsubscribe(Long tenantId) {
        UnsubscribeEntity u = new UnsubscribeEntity();
        u.setTenantId(tenantId);
        u.setCustomerKey(Texts.sha256Hex("13800000003")); // 王五退订 console，触发时跳过该客户
        u.setChannel("console");
        u.setReason("演示退订");
        u.setCreatedAt(Instant.now());
        unsubscribeMapper.insert(u);
    }

    /**
     * 演示知识库条目（与系统真实行为一致：退订/频控/快照；覆盖 V14 本体：7 类 record_type、
     * active/superseded/obsolete 生命周期与取代链）。
     * 幂等：逐条按 (tenant_id, title) 检查，已存在标题跳过——存量条目不重插、新条目可增量补入
     * （演示库已种过旧条目的场景也能拿到新增测试数据）。
     */
    private void seedKnowledge(Long tenantId) {
        Instant now = Instant.now();
        // 列: {title, content, tags, record_type, lifecycle, supersedesTitle(被本条取代的旧条目标题，须同批先插)}
        Object[][] rows = {
                {"触达退订规范", "触达前必须核对客户退订状态（unsubscribe 表）：已退订客户禁止任何渠道触达；"
                        + "客户在任一渠道退订即视为全局退订，其他渠道也不得再触达。违规触达会直接发失败。",
                        List.of("退订", "触达", "合规"), "rule", "active", null},
                {"频道频控", "频道级频控按每天上限（频道配置 max_per_day）限制同一客户在该频道的触达次数；"
                        + "触达前可调用 frequencyCheck 查询客户历史发送总量。",
                        List.of("频控", "触发规则"), "rule", "active", null},
                {"客户画像常用字段", "客户 attributes 为 jsonb 键值对，常用键：preferred_channel（偏好触达通道）、name（姓名）、"
                        + "gender（性别）、hobby（兴趣）；tags 为字符串数组，可用 DSL 过滤（如 tags CONTAINS 'VIP'）。"
                        + "queryCustomers 返回完整画像，供人群圈选与发送前核对。",
                        List.of("客户", "画像", "属性"), "fact", "active", null},
                {"静默时段触达约束", "触达时间受频道配置 quiet_hours 静默时段约束（演示默认 23:00-07:00）；"
                        + "静默时段内禁止发起触达，bestSendTime 评分也会回避静默时段。",
                        List.of("约束", "静默时段", "触达"), "constraint", "active", null},
                {"新客首单触发窗（v1）", "新客首单活动触发规则：event_type=order_placed、window=7d，"
                        + "即客户下单后 7 天内触发首单提醒；已被 v2 取代（window 缩短为 1d，避免优惠过期）。",
                        List.of("触发规则", "窗口"), "rule", "superseded", null},
                {"新客首单触发窗（v2）", "新客首单活动触发规则：event_type=order_placed、window=1d，"
                        + "下单后次日触发提醒，节奏更紧凑；取代 v1 的 7d 窗口，与种子活动「新人复购提醒」一致。",
                        List.of("触发规则", "窗口"), "rule", "active", "新客首单触发窗（v1）"},
                {"短信通道接入决策", "短信触达经配置驱动网关适配器：channel_config 配置存在则走 HTTP 网关"
                        + "（EA_MOCK_GW_URL 指定 endpoint），未配置降级 console。接入真实短信厂商只需改配置不换业务代码，"
                        + "mock 网关可离线联调。",
                        List.of("短信", "通道", "网关"), "decision", "active", null},
                {"触达频控上限取值的理由", "频道频控 max_per_day 上限（演示默认 10 次/天）在触达前经 frequencyCheck 校验："
                        + "上限兼顾留存触达频率与用户打扰，超限则本次触达被拒绝，避免同一客户被活动反复轰炸。",
                        List.of("频控", "理由"), "rationale", "active", null},
                {"人群规则过宽的教训", "建活动前须用 queryAudience 核对人群规模：DYNAMIC 人群规则过宽会圈中全量客户，"
                        + "导致整批误触达；已建活动发送以创建时的 audience_snapshot 快照为准，后续改人群规则不影响存量活动。",
                        List.of("人群", "教训", "快照"), "lesson", "active", null},
                {"忽略退订名单群发", "反模式：触达前不核对 unsubscribe 退订状态，已退订客户仍被发送——既浪费额度又引发客诉。"
                        + "正确做法：sendTouch 前核对退订与频控（SendTouchAction 发送前校验），再决定是否投递。",
                        List.of("退订", "反模式"), "anti_pattern", "active", null},
                {"灰度/AB 实验机制决策", "早期灰度比例推送与 AB 分流实验机制已下线（V11/V12 迁移删除 gray_ratio/ab_split/ab_variants "
                        + "等字段），当前发送为全量投递；实验验证改由人群对比完成。本条仅作历史记录保留（obsolete，检索默认不命中）。",
                        List.of("灰度", "实验", "历史"), "decision", "obsolete", null},
        };
        Map<String, Long> inserted = new HashMap<>();
        for (Object[] row : rows) {
            String title = (String) row[0];
            Long exists = knowledgeMapper.selectCount(new QueryWrapper<KnowledgeEntity>()
                    .eq(KnowledgeEntity.COL_TENANT_ID, tenantId)
                    .eq(KnowledgeEntity.COL_TITLE, title));
            if (exists != null && exists > 0) {
                continue;
            }
            KnowledgeEntity k = new KnowledgeEntity();
            k.setTenantId(tenantId);
            k.setTitle(title);
            k.setContent((String) row[1]);
            k.setTags((List<String>) row[2]);
            k.setEnabled(true);
            k.setRecordType((String) row[3]);
            k.setLifecycle((String) row[4]);
            String prevTitle = (String) row[5];
            if (prevTitle != null && inserted.containsKey(prevTitle)) {
                k.setSupersedesId(inserted.get(prevTitle));
            }
            k.setCreatedAt(now);
            k.setUpdatedAt(now);
            knowledgeMapper.insert(k);
            inserted.put(title, k.getId());
        }
        if (!inserted.isEmpty()) {
            knowledgeService.backfillEmbeddings();
            log.info("knowledge seeded: tenant={} inserted={}", tenantId, inserted.size());
        }
    }

    /**
     * 演示知识图谱类型化关系边（V15，幂等：按 (tenant_id, source, target, type) 查重，可增量补入）。
     * 边语义与种子条目内容一致：理由支撑规则、反模式冲突规范、画像支撑教训、
     * 频道频控与静默时段相关、静默时段细化触达规范；取代链（supersedes）由条目 supersedes_id 表达。
     */
    private void seedKnowledgeLinks(Long tenantId) {
        // {sourceTitle, targetTitle, relationType}
        String[][] links = {
                {"触达频控上限取值的理由", "频道频控", KnowledgeLinkEntity.REL_SUPPORTS},
                {"忽略退订名单群发", "触达退订规范", KnowledgeLinkEntity.REL_CONFLICTS},
                {"客户画像常用字段", "人群规则过宽的教训", KnowledgeLinkEntity.REL_SUPPORTS},
                {"频道频控", "静默时段触达约束", KnowledgeLinkEntity.REL_RELATED},
                {"静默时段触达约束", "触达退订规范", KnowledgeLinkEntity.REL_REFINES},
        };
        int inserted = 0;
        for (String[] l : links) {
            KnowledgeEntity src = knowledgeMapper.selectOne(new QueryWrapper<KnowledgeEntity>()
                    .eq(KnowledgeEntity.COL_TENANT_ID, tenantId)
                    .eq(KnowledgeEntity.COL_TITLE, l[0])
                    .last("LIMIT 1"));
            KnowledgeEntity dst = knowledgeMapper.selectOne(new QueryWrapper<KnowledgeEntity>()
                    .eq(KnowledgeEntity.COL_TENANT_ID, tenantId)
                    .eq(KnowledgeEntity.COL_TITLE, l[1])
                    .last("LIMIT 1"));
            if (src == null || dst == null) {
                continue; // 条目被删/不存在则跳过，不阻塞其余边
            }
            Long dup = knowledgeLinkMapper.selectCount(new QueryWrapper<KnowledgeLinkEntity>()
                    .eq(KnowledgeLinkEntity.COL_TENANT_ID, tenantId)
                    .eq(KnowledgeLinkEntity.COL_SOURCE_ID, src.getId())
                    .eq(KnowledgeLinkEntity.COL_TARGET_ID, dst.getId())
                    .eq(KnowledgeLinkEntity.COL_RELATION_TYPE, l[2]));
            if (dup != null && dup > 0) {
                continue;
            }
            KnowledgeLinkEntity link = new KnowledgeLinkEntity();
            link.setTenantId(tenantId);
            link.setSourceId(src.getId());
            link.setTargetId(dst.getId());
            link.setRelationType(l[2]);
            link.setCreatedAt(Instant.now());
            knowledgeLinkMapper.insert(link);
            inserted++;
        }
        if (inserted > 0) {
            log.info("knowledge links seeded: tenant={} inserted={}", tenantId, inserted);
        }
    }

    }