# 医保清算韧性服务（clearing-resilience-service）

面向院内环境部署的医保日清算韧性服务。每天接收医院结算清单、药店购药记录与异地结算回传，
在源系统重传、目录缺失、规则冲突、接口降级与网络故障同时发生时，保证：

- 少数故障接口**不会**耗尽连接与并发配额（依赖本地令牌桶 + 故障隔离）；
- 同一清单**不会**重复记账（幂等账本：清单版本 + 清算操作号唯一约束）；
- 每一次等待 / 限流 / 重投 / 升级人工都有**可复核的解释**（决策事件时间线）。

技术栈：Java 21+ / Spring Boot 3.5 / Spring Data JPA / PostgreSQL / springdoc-openapi（Swagger UI）。

---

## 1. 组件与需求映射

| 需求组件 | 实现 | 说明 |
| --- | --- | --- |
| 任务登记器 | `TaskRegistrar` | 记录输入批次、清算日、清单版本、业务键、依赖版本（目录/规则版本快照）、内容哈希 |
| 失败判定器 | `FailureClassifier` | 技术瞬时故障 / 目录等待 / 规则冲突 / 内容不一致 / 人工裁定，五类可复核状态 |
| 依赖状态图 | `DependencyGraphService` | 目录、待遇规则、基金网关、医院/药店批次节点 + 依赖边 + 恢复条件 |
| 幂等账本 | `LedgerService` | 唯一约束 `(bill_version, operation_no)` 与 `(operation_no)`；回传待确认只查不扣 |
| 预算分配器 | `BudgetAllocator` + `TokenBucketService` | **依赖本地令牌桶**；协调器按依赖 × 来源 × 金额优先级写配额证据（`quota_grant`） |
| 调度器 | `ClearingScheduler` | 仅当 令牌 + 退避时间 + 依赖健康 三者同时满足才投放 |
| 风暴观测器 | `StormObserver` | 按依赖检测队列增长、令牌消耗、失败聚集，输出 NORMAL/WATCH/STORM |
| 恢复解释器 | `RecoveryExplainer` | `GET /api/tasks/{id}/explanation` 给出人话结论 + 完整决策时间线 |
| **规则快照库** | `RuleSnapshotService` + `rule_snapshot` | 每次规则发布固化不可变快照；`previousVersion` 维护版本链；未来生效日入 `DRAFT`，跨午夜自动激活，旧版本 `SUPERSEDED`，支持人工 `WITHDRAWN` |
| **任务绑定器** | `TaskRuleBinder` + `task_rule_binding` | 入队时点绑定生效规则版本（`INITIAL` 永不删除）；策略 A 追加 `REBIND` 轨迹；更正任务 `CORRECTION` 绑定 |
| **影子复算器** | `ShadowRecalculator` + `shadow_recalc` | 用新快照对同一报文重算，**只算不写账**，原金额/影子金额/差额完整留痕 |
| **差异裁决队列** | `RuleGovernanceService` + `rule_diff_case` | 差额非零立案 `OPEN`，人工 `APPROVE/WITHDRAW/REJECT/ISSUE_CORRECTION`；冻结案件的覆盖批准返回 409 |
| **结果发布册** | `ResultPublicationService` + `result_publication` | 只追加不改写：原始行 `ORIGINAL` 冻结，更正追加 `CORRECTION` 行、原行转 `SUPERSEDED` 但金额保留 |
| **影响通知** | `impact_notice` | 更正任务向基金侧/医疗机构侧各发一条，载明新旧版本、金额与差额（补付/追回） |

### 规则版本治理的两条策略（题目要求的架构选型）

规则服务发布新目录后，队列中旧清单与新清单可能按同一任务重试，却适用不同待遇规则。系统同时支持：

- **策略 A（口径统一）**：`POST /api/rule-governance/policy/rebind-latest` 把**未执行**任务改绑最新生效规则，
  改绑以追加 `REBIND` 绑定方式留痕、初始绑定保留；代价是改变了尚未冻结清单的计算依据。
  **已扣款/已回传（冻结）任务不在此列**，其结果拒绝任何静默覆盖。
- **策略 B（历史可复现）**：入队时点规则版本绑定保持不动，裁决 `ISSUE_CORRECTION` **另发更正任务**
  （独立清单版本 `v1-r1`、独立操作号、独立账本分录），原任务/原分录/原发布行全部保留，
  并向影响方发出通知；代价是维护双版本账本与通知闭环。

### 四条规则治理不变量（均有测试与场景断言）

1. **规则跨午夜生效**：未来生效日的快照为 `DRAFT`，逻辑时钟到日后调度周期自动激活，版本链可追溯；
2. **人工裁决撤回**：未执行任务恢复入队时点规则版本，绑定轨迹 `INITIAL→REBIND→恢复` 完整可复现；
3. **两批同项清单补正日期不同**：更正任务携带各自补正日期，双版本金额独立入账；
4. **不能静默覆盖已回传结果**：冻结案件的覆盖批准被拒绝（REST 返回 409、事件留痕
   `RESULT_FROZEN_REJECTED`），只能追加更正发布行。

### 关键设计取舍（题目要求的架构选型）

采用**依赖本地令牌桶 + 协调器配额证据**：

- 每个依赖（`RULE_ENGINE`、`FUND_GW`）一个本地令牌桶，按调度周期定额补充；
- 依赖 `DOWN` 时**冻结**对应桶（不补充、不放行），故障只收紧其自身队列 —— 基金回传故障不影响
  目录已恢复的医院清单按预算继续推进；
- 恢复后**不补发**故障期间错过的补充，只从冻结余额按周期渐进投放；
- 协调器（`BudgetAllocator`）每周期按依赖健康度折算补充速率（降级减半、DOWN 置零），并把
  来源 × 金额优先级的配额授予写入 `quota_grant` 表，作为跨队列公平性的审计证据；
- 代价：需维护多个桶与回收账（本实现以 `total_consumed` / `total_refilled` 记账）。

### 三条核心不变量（均有测试与场景断言）

1. **同业务键不同内容** → 登记器生成 `CONTENT_CONFLICT`，不发放任何令牌；
2. **待遇规则服务不可用** → 规则依赖桶停止放行；恢复后仅按剩余预算渐进投放，不补发；
3. **基金回传已受理但回执延迟** → 账本保持 `PENDING_CONFIRM`，按操作号查询，不取得新的扣款令牌。

---

## 2. 快速开始

### 方式 A：Docker Compose 一键启动（推荐）

```bash
docker compose up --build
```

启动后访问：

- 服务：`http://localhost:8080`
- Swagger UI：`http://localhost:8080/swagger-ui.html`
- OpenAPI 文档：`http://localhost:8080/v3/api-docs`

### 方式 B：本地开发启动

前置：PostgreSQL 已运行（本环境为 `127.0.0.1:5432`，库/用户/密码均为 `ccdev`）。

```bash
# 构建（含测试）
mvn package

# 运行
java -jar target/clearing-resilience-service.jar
```

可用环境变量覆盖数据源：`DB_HOST` / `DB_PORT` / `DB_NAME` / `DB_USER` / `DB_PASSWORD`。

### 测试账号（HTTP Basic，仅用于本地/演示）

| 账号 | 密码 | 权限 |
| --- | --- | --- |
| `admin` | `admin123` | 读写（登记、调度、演练、健康度变更） |
| `viewer` | `viewer123` | 只读（查询、解释、风暴观测） |

### 调度模式

默认**手动调度**：通过 `POST /api/scheduler/tick` 逐周期驱动，保证验收确定性。
需要持续自动调度时设置环境变量 `CLEARING_SCHEDULER_AUTO=true`
（间隔 `CLEARING_SCHEDULER_INTERVAL_MS`，默认 3000ms）。

---

## 3. 验收方式（本题验证）

### 3.1 一键验收场景（10 个样本）

覆盖题目要求的医院批次、药店回传、目录冲突、接口降级与配额恢复等样本，
以及规则版本治理的三个专项样本。每个场景通过真实服务执行并返回步骤轨迹与 PASS/FAIL 断言：

```bash
# 医院结算批次正常清算
curl -u admin:admin123 -X POST http://127.0.0.1:8080/api/demo/scenario/hospital-batch

# 药店购药回传
curl -u admin:admin123 -X POST http://127.0.0.1:8080/api/demo/scenario/pharmacy-return

# 目录缺失 → 目录等待 → 目录同步后自动恢复
curl -u admin:admin123 -X POST http://127.0.0.1:8080/api/demo/scenario/catalog-conflict

# 接口降级 → 桶冻结 → 恢复后渐进投放、配额恢复（含配额证据断言）
curl -u admin:admin123 -X POST http://127.0.0.1:8080/api/demo/scenario/rule-degradation

# 基金回执延迟 → 账本待确认、按操作号查询、不重复扣款
curl -u admin:admin123 -X POST http://127.0.0.1:8080/api/demo/scenario/fund-pending-confirm

# 同业务键不同内容 → 内容冲突、不发放令牌、人工裁定
curl -u admin:admin123 -X POST http://127.0.0.1:8080/api/demo/scenario/content-conflict

# 连续超时 → 失败聚集触发 STORM → 恢复后消化积压
curl -u admin:admin123 -X POST http://127.0.0.1:8080/api/demo/scenario/storm-watch

# 规则跨午夜生效：旧清单 700 冻结、新清单按新规则 750，差额立案且静默覆盖被拒
curl -u admin:admin123 -X POST http://127.0.0.1:8080/api/demo/scenario/rule-midnight-effective

# 人工裁决撤回：改绑任务恢复入队时点规则，绑定版本链 2026.11→2026.12→2026.11
curl -u admin:admin123 -X POST http://127.0.0.1:8080/api/demo/scenario/rule-diff-withdraw

# 两批同项清单补正日期不同：另发更正任务、双版本账本、发布册版本链、4 条影响通知
curl -u admin:admin123 -X POST http://127.0.0.1:8080/api/demo/scenario/two-batch-correction

# 演练复位（清空业务数据、恢复依赖健康与令牌桶、重建规则快照基线）
curl -u admin:admin123 -X POST http://127.0.0.1:8080/api/demo/reset
```

返回示例（节选）：

```json
{
  "name": "rule-degradation",
  "ok": true,
  "steps": ["登记批次 B1 共 5 笔医院清单并执行 1 个调度周期（RULE 桶容量 6，消耗 5）", "..."],
  "assertions": [
    "PASS: 故障期间桶冻结：余额保持 1、零补充（不放行、不补充），实际余额=1 累计补充=0",
    "PASS: 恢复后仅按剩余预算渐进补充（+2/周期），不补发故障期间尝试：累计补充=2（故障前 0）"
  ]
}
```

### 3.2 手工 API 流程（可对照 Swagger UI 操作）

```bash
# 1. 登记任务（payload 为结算报文 JSON）
curl -u admin:admin123 -H 'Content-Type: application/json' -X POST http://127.0.0.1:8080/api/tasks \
  -d '{"batchId":"B-001","clearingDate":"2026-11-23","businessKey":"HOSP-001","billVersion":"v1",
       "sourceType":"HOSPITAL","amount":"12000.00",
       "payload":"{\"items\":[{\"catalogCode\":\"A-001\",\"qty\":2,\"price\":100}]}"}'

# 2. 执行一个调度周期
curl -u admin:admin123 -X POST http://127.0.0.1:8080/api/scheduler/tick

# 3. 查看任务恢复解释（为何等待/限流/重投/升级人工）
curl -u viewer:viewer123 http://127.0.0.1:8080/api/tasks/1/explanation

# 4. 依赖降级/恢复（DOWN 冻结桶，HEALTHY 解冻不补发）
curl -u admin:admin123 -H 'Content-Type: application/json' \
  -X POST http://127.0.0.1:8080/api/dependencies/RULE_ENGINE/health \
  -d '{"health":"DOWN","reason":"规则服务不可用演练"}'

# 5. 查看令牌桶 / 配额证据 / 风暴观测 / 账本
curl -u viewer:viewer123 http://127.0.0.1:8080/api/buckets
curl -u viewer:viewer123 http://127.0.0.1:8080/api/quota/evidence
curl -u viewer:viewer123 http://127.0.0.1:8080/api/storm/latest
curl -u viewer:viewer123 http://127.0.0.1:8080/api/ledger

# 6. 基金回执延迟演练：网关设为 DELAYED 后登记+调度，任务进入 PENDING_CONFIRM；
#    回执到达后按操作号确认
curl -u admin:admin123 -H 'Content-Type: application/json' \
  -X POST http://127.0.0.1:8080/api/simulation/fund-gateway \
  -d '{"mode":"DELAYED","delaySeconds":300}'
curl -u admin:admin123 -X POST http://127.0.0.1:8080/api/ledger/FR-HOSP-001-v1/confirm
```

### 3.2.1 规则版本治理手工流程（对照 Swagger UI「规则快照与差异治理」）

```bash
# 1. 发布次日生效的新规则（跨午夜生效；effectiveFrom 为当日则立即生效并触发影子复算）
curl -u admin:admin123 -H 'Content-Type: application/json' \
  -X POST http://127.0.0.1:8080/api/rule-governance/snapshots \
  -d '{"version":"2026.12","reimbursementRate":0.75,"effectiveFrom":"2026-11-24","description":"比例上调"}'

# 2. 推进逻辑时钟到生效日，再调度（或直接 POST /api/rule-governance/activate）
curl -u admin:admin123 -X POST 'http://127.0.0.1:8080/api/rule-governance/clock/advance?days=1'
curl -u admin:admin123 -X POST http://127.0.0.1:8080/api/scheduler/tick

# 3. 查看影子复算留痕与差异裁决队列（frozen=true 即已扣款/回传，禁止覆盖）
curl -u viewer:viewer123 http://127.0.0.1:8080/api/rule-governance/shadow
curl -u viewer:viewer123 http://127.0.0.1:8080/api/rule-governance/diffs

# 4a. 策略 A：未执行任务统一改绑最新规则（冻结任务不受影响）
curl -u admin:admin123 -X POST 'http://127.0.0.1:8080/api/rule-governance/policy/rebind-latest?reason=统一口径'

# 4b. 策略 B：对冻结案件另发更正任务（原结果保留，自动发影响通知）
curl -u admin:admin123 -H 'Content-Type: application/json' \
  -X POST http://127.0.0.1:8080/api/rule-governance/diffs/1/adjudicate \
  -d '{"action":"ISSUE_CORRECTION","note":"按新规则补正"}'

# 5. 核验版本链：任务绑定轨迹、结果发布册、影响通知
curl -u viewer:viewer123 http://127.0.0.1:8080/api/rule-governance/tasks/1/bindings
curl -u viewer:viewer123 'http://127.0.0.1:8080/api/rule-governance/publications?businessKey=HOSP-001'
curl -u viewer:viewer123 http://127.0.0.1:8080/api/rule-governance/notices
```

### 3.3 单元/集成测试

```bash
mvn test
```

19 个测试覆盖：令牌桶冻结/解冻不补发、内容冲突不发令牌、重复登记幂等吸收、
账本操作号幂等、回执延迟不重复扣款、依赖降级恢复渐进投放、目录等待恢复、
规则冲突升级人工、安全边界（401/403/200），以及规则治理四项不变量
（跨午夜生效金额差异、人工撤回版本链、两批补正双版本、冻结结果禁止静默覆盖）。

---

## 4. API 一览（详见 Swagger UI）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/tasks` | 登记清算任务（幂等：重复吸收 / 内容冲突） |
| GET | `/api/tasks` | 任务列表（可按状态/依赖过滤） |
| GET | `/api/tasks/{id}/explanation` | 恢复解释（结论 + 决策时间线 + 失败记录） |
| POST | `/api/tasks/{id}/manual-resolve` | 人工裁定（REQUEUE/CANCEL/KEEP_ORIGINAL/OVERRIDE） |
| GET/POST | `/api/dependencies[/{code}/health]` | 依赖状态图 / 健康度变更 |
| GET | `/api/buckets`、`/api/quota/evidence` | 本地令牌桶 / 协调器配额证据 |
| GET/POST | `/api/ledger[/{operationNo}[/confirm]]` | 幂等账本查询 / 回执确认 |
| POST | `/api/scheduler/tick` | 手动调度周期 |
| GET | `/api/storm/latest`、`/api/storm/reports` | 风暴观测 |
| GET/POST | `/api/catalog/items` | 医保目录查询 / 同步 |
| GET/POST | `/api/simulation[...]` | 下游模拟器（规则超时、基金延迟/宕机） |
| GET/POST | `/api/rule-governance/snapshots[/{version}/withdraw]` | 规则快照库版本链 / 发布 / 人工撤回 |
| POST | `/api/rule-governance/activate` | 手动执行跨午夜激活（调度周期已自动执行） |
| GET | `/api/rule-governance/tasks/{taskId}/bindings` | 任务规则绑定轨迹（INITIAL/REBIND/CORRECTION） |
| POST | `/api/rule-governance/policy/rebind-latest` | 策略 A：未执行任务随最新规则统一重算 |
| GET/POST | `/api/rule-governance/shadow`、`/tasks/{taskId}/recalc` | 影子复算留痕 / 人工单笔复算立案 |
| GET/POST | `/api/rule-governance/diffs[/{caseId}/adjudicate]` | 差异裁决队列 / 人工裁决（冻结覆盖返回 409） |
| GET | `/api/rule-governance/publications` | 结果发布册（追加式版本链，不可静默覆盖） |
| GET/POST | `/api/rule-governance/notices[/{noticeId}/ack]` | 影响通知 / 相关方确认 |
| GET/POST | `/api/rule-governance/clock[/advance]` | 逻辑时钟（跨午夜演练，不影响真实时间） |
| POST | `/api/demo/reset`、`/api/demo/scenario/{name}` | 验收演练（10 个场景） |

---

## 5. 配置项（`application.yml`，均可环境变量覆盖）

| 配置 | 默认 | 说明 |
| --- | --- | --- |
| `clearing.buckets.RULE_ENGINE.capacity/refill-per-tick` | 6 / 2 | 待遇规则依赖桶 |
| `clearing.buckets.FUND_GW.capacity/refill-per-tick` | 3 / 1 | 基金网关依赖桶（容量更小，保护基金接口） |
| `clearing.backoff.*` | 1000ms 起，指数，上限 10s，最多 5 次 | 瞬时故障退避 |
| `clearing.storm.*` | 窗口 300s，失败≥5 STORM，≥3 或队列增长≥5 WATCH | 风暴阈值 |
| `clearing.coordinator.ticks-per-period` | 10 | 配额周期折算 |
| `clearing.scheduler.auto-enabled` | false | 自动调度开关 |

---

## 6. 已实际执行的验证（本环境）

以下验证均在本工作区真实执行通过：

1. `mvn test`：19 个测试全部通过（H2 内存库），含新增 `RuleGovernanceTest` 4 项；
2. `mvn package` 构建成功，产出 `target/clearing-resilience-service.jar`；
3. 以本地 PostgreSQL 18（`ccdev`）启动服务，10 个验收场景（hospital-batch、pharmacy-return、
   catalog-conflict、rule-degradation、fund-pending-confirm、content-conflict、storm-watch、
   rule-midnight-effective、rule-diff-withdraw、two-batch-correction）全部返回 `ok: true`，断言全 PASS；
4. 规则治理专项核验：
   - 跨午夜生效：旧清单 700.00（2026.11）冻结保留，新清单按 2026.12 计算 750.00，差额 50.00 立案；
   - 人工撤回：绑定版本链 `2026.11(INITIAL)→2026.12(REBIND)→2026.11(撤回)`，撤回后按原口径 1400.00 清算；
   - 两批补正：B1 补正日 2026-11-24、B2 补正日 2026-11-25，原扣款 700.00 与更正扣款 750.00 双版本并存，
     发布册每键两行版本链，影响通知 4 条（2 案件 × 基金/医疗机构）；
   - 冻结保护：对冻结案件的覆盖批准 REST 返回 `409 CONFLICT`，事件留痕 `RESULT_FROZEN_REJECTED`，案件保持 OPEN；
5. 安全边界：未认证 401、viewer 写治理接口 403、admin 读写 200；
6. Swagger UI `/swagger-ui.html` 与 OpenAPI `/v3/api-docs` 可访问，含 16 条 `/api/rule-governance/**` 路径。

说明：本环境未安装 Docker CLI，Docker 镜像构建未在本环境执行；
`Dockerfile` 与 `docker-compose.yml` 已提供并静态核对（多阶段构建：Maven 构建 → JRE 运行，
Compose 含 postgres:18 健康检查与依赖编排）。

## 7. 已知边界

- 下游系统（目录/规则/基金网关）由内置模拟器承载，便于院内联调前演练；接入真实接口时
  替换 `DownstreamSimulator` 的调用实现即可，令牌桶、账本与调度逻辑不变；
- 规则生效日期由内置 `LogicalClock` 逻辑时钟驱动（默认固定演练基准日 2026-11-23），
  用于确定性演示“跨午夜生效”；生产环境应替换为真实时钟并由调度周期扫描到期快照；
- 规则快照当前固化的口径参数为统筹支付比例（报文总额 × 比例），真实规则正文以 JSON 存入
  `rule_body` 字段，扩展更多待遇参数时在 `RuleCalculationService` 增加计算分支即可；
- 认证为演示级 HTTP Basic 内存账号，生产应对接院内统一认证；
- 令牌桶按调度周期补充（非时间窗滑动），周期长度即补发粒度，故障恢复后的渐进速率由
  `refill-per-tick` 控制。
