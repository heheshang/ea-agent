-- 客户数据初始化（供 scripts/init-customers.sh 调用；也可直连 psql 手动执行）。
-- 语义：确保 demo 租户存在 seed-1..seed-:count 这 N 个客户；已存在则跳过（解锁支持补齐尾部）。
-- 幂等：customer 有 UNIQUE (tenant_id, external_id)，ON CONFLICT DO NOTHING → 重复执行不重复插入。
-- psql 变量：:tenant_id（目标租户 id）、:count（目标数量）。
-- 直连用法：psql -h localhost -p 5433 -U eaagent -d eaagent \
--            -v tenant_id=1 -v count=500 -f scripts/seed-customers.sql
--
-- 数据形态对齐 SeedDataInitializer.seedCustomers（attributes.preferred_channel=console、
-- tags 数组、status ACTIVE），另加分布：10% VIP、~14% 沉睡、每 12 个 1 个 INACTIVE，
-- 供客户列表分页 / DSL 筛选（tags CONTAINS、attributes.* EXISTS）/ 人群 / 灰度 / Agent 查询演示。
-- 手机段 1381xxxxxxx 与种子客户 1380000000x、旧 seed 批次 1390xxxxxxx 均不冲突。

INSERT INTO customer (tenant_id, external_id, phone, email, wechat_openid,
                      attributes, tags, status, created_at, updated_at)
SELECT
    :tenant_id,
    'seed-' || n,
    '138' || lpad((10000000 + n)::text, 8, '0'),
    'cust' || n || '@example.com',
    NULL,
    jsonb_build_object(
        'preferred_channel', 'console',
        'name', '客户' || n,
        'gender', CASE WHEN n % 2 = 0 THEN 'male' ELSE 'female' END,
        'hobby', (ARRAY['跑步', '读书', '摄影', '旅行', '烹饪'])[1 + (n % 5)]
    ),
    CASE
        WHEN n % 10 = 0 THEN '["示例","VIP"]'::jsonb
        WHEN n % 7 = 0 THEN '["示例","沉睡"]'::jsonb
        ELSE '["示例"]'::jsonb
    END,
    CASE WHEN n % 12 = 0 THEN 'INACTIVE'::text ELSE 'ACTIVE'::text END,
    now(), now()
FROM generate_series(1, :count) AS n
ON CONFLICT (tenant_id, external_id) DO NOTHING;