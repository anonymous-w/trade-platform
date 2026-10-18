# 数据管理平台 / 販売・輸出管理システム

**代码版本：v0.2（2026-10-13 优化版，可运行开发原型）** · 根据 `docs/system-design-v0.7.md` 构建。

> 这是面向需求确认与开发的原型，不是已经验证的生产交付系统。尤其销售工作流、五年监视、历史数据字段映射、集装箱实际箱号关联等仍待日本公司确认。

## 1. 实现范围

| 模块 | 实现状态 |
|---|---|
| Spring Security 登录、会话、CSRF、退出 | 已实现；首个管理员从环境变量初始化 |
| 輸出管理：查询、分页、CSV导出 | 已实现 |
| 輸出詳細：设备、包装件、发票、箱型、船期 | 已实现；从关联表实时读取 |
| 原票プレビュー：PDF预览、下载、打印 | 已实现；已有 PDF 可继续使用，新上传暂时停用 |
| コンテナ実績：按型号/箱型/依据查询 | 已实现；区分 `PLANNED` / `DOCUMENTED` / `ACTUAL` |
| データ取込み：包装集计 Excel 原资料、限定 CSV | Excel 支持预览、确认保存和查询；PDF 上传暂时停用 |
| 販売管理：列表、详情、PDF | **只读基础版**；不包含创建/审批流程 |
| 5年間監視 | **只读参考列表**，不自动判定监视义务 |
| 窄屏界面 | 基础响应式适配；没有独立手机 App |

## 2. 运行环境

推荐 Docker Engine + Docker Compose v2。在代码所在机器需要能够下载 Docker images、Maven 和 npm 依赖。首次安装可能耗时较长。

```bash
cp .env.example .env
# 编辑 .env 中的 DB_PASSWORD 和 ADMIN_PASSWORD，使用不同的强密码
# 开发环境可保持 DEMO_DATA_ENABLED=true
# Windows PowerShell 可用 Copy-Item .env.example .env

docker compose -p trade-platform-v02 up --build -d
docker compose -p trade-platform-v02 ps
```

打开 **http://localhost:8088**，登录账号是 `.env` 中的 `ADMIN_USER` 与 `ADMIN_PASSWORD`。

演示记录：`DEMO-EXP-001` / `DEMO-INV-001` / `SPG-X`，装箱数据（4 件、5110kg、9.266m³、20DRY×1）源自设计讨论的示例，但**客户和编号是合成演示数据**，绝不代表实际商业记录；该集装箱证据类别为 `DOCUMENTED`。

取消模拟数据：将 `DEMO_DATA_ENABLED=false`，并使用全新空数据库启动。**关闭该选项不会删除之前已写入的演示数据。**

停止运行：

```bash
docker compose -p trade-platform-v02 down
```

注意：`docker compose -p trade-platform-v02 down -v` **会永久删除数据库和上传的文件卷**；如需保留历史数据，请不要添加 `-v`。

## 3. 开发模式（不使用 Docker 构建）

本地需要 Java 21、Maven 3.9+、Node 22、PostgreSQL 17。首先启动数据库或运行 `docker compose -p trade-platform-v02 up -d db`。

```bash
# 终端 A：后端（确保数据库及环境变量已配置）
cd backend
mvn spring-boot:run

# 终端 B：前端
cd frontend
npm ci
npm run dev
```

Vite 默认 `http://localhost:5173`，通过代理将 `/api` 转发到 `localhost:8080`。数据库密码和管理员密码必须通过环境变量设置，不能提交到 Git。

## 4. 目录说明

```text
trade-platform-v0.2/
├── frontend/           Vue 3 + TypeScript + Element Plus
├── backend/            Spring Boot 3 + JDBC + Spring Security + Flyway
│   └── src/main/resources/db/migration/V1__unified_schema.sql
├── docs/               平台 v0.7 设计书及原候选数据库 DDL
├── samples/            CSV 导入格式示例
├── deploy/             Nginx 配置
├── docker-compose.yml
└── .env.example
```

**数据库表：** 使用统一数据库设计 v0.1 的 15 张候选表做初版 Flyway 基线。应用本次主要写入 10 张核心表；销售及监视候选表预留，但其业务规则尚未确认。不要将当前 DDL 当作生产模型已批准。

## 5. 页面操作流程

- **輸出管理**：填写 Invoice No.、设备、型号、客户或日期 → `検索` → `詳細` → 查看包装明细与集装箱来源 → `原票を見る` → 选择 Invoice/Packing List → `ダウンロード` 或 `印刷`。
- **コンテナ実績**：按设备型号、箱型、证据类别过滤历史记录，然后点击 `詳細` 返回出口主记录。
- **データ取込み**：管理员可预览并保存包装集计 Excel 原资料；所有登录用户可查询保存记录、原始行号及疑点。PDF 新上传暂停，已有文档仍可查看。旧 CSV 模板入口折叠保留。
- **販売管理**：只读列表与详情，数据需另行导入；销售审批未实现。

## 6. 重要的数据约定

- 一个 `T_EXPORT` 可以对应多个 `T_EXPORT_ITEM`、Invoice、Packing List 和容器记录；设备规格是历史快照，不能以商品主数据覆盖。
- `T_PACKING_LIST_HEADER` 保留原单据汇总，`T_PACKING_LIST` 保留明细。来源差异不自动覆盖。
- `T_EXPORT_CONTAINER.RECORD_BASIS` 的含义：`PLANNED` 是计划、`DOCUMENTED` 是文件记载、`ACTUAL` 必须有实际出运依据。系统不会根据字段自动推断核实等级。
- `T_DOCUMENT` 只存储私有文件的元数据，PDF 实体保存在 `document_data` 容器卷，不直接向公网开放。
- CSV 导入是**严格限制的演示型流程**：单个 `export_no` 只允许一个设备、一个 Invoice、一个箱型配置；金额默认为 0、客户地址和出货地是待核实占位值，导入的出口状态为 DRAFT。这不是原 Excel 的全面迁移工具。
- SYN26 附带明确标记为合成数据的演示 PDF；没有关联文件时显示空状态。

## 7. API 概览

| 接口 | 方法 | 功能 |
|---|---|---|
| `/api/auth/csrf` | GET | 获取 CSRF token |
| `/api/auth/login` | POST (form) | 登录 |
| `/api/auth/me` | GET | 当前用户 |
| `/api/auth/logout` | POST | 退出 |
| `/api/exports` | GET | 出口分页查询 |
| `/api/exports/{id}` | GET | 出口详情及关联对象 |
| `/api/exports/export.csv` | GET | 导出筛选记录，上限1万行 |
| `/api/containers` | GET | 集装箱历史查询 |
| `/api/sales`, `/api/sales/{id}` | GET | 只读销售查询 |
| `/api/documents` | POST | PDF 上传暂时停止（管理员请求返回 410） |
| `/api/documents/{id}/content` | GET | 内联 PDF 预览 |
| `/api/documents/{id}/download` | GET | 下载原始 PDF |
| `/api/imports/csv` | POST | 管理员导入受限 CSV |
| `/api/imports/packing/preview` | POST | 管理员预览包装集计 XLSX，不写数据库 |
| `/api/imports/packing` | POST | 管理员确认保存原资料，需 previewHash、acknowledgeReview |
| `/api/imports/packing/batches` | GET | 最近 50 个导入批次 |
| `/api/imports/packing/batches/{id}/rows` | GET | 分页、关键词、要确认项筛选 |

## 8. 测试与上线前检查

在可联网的开发环境执行：

```bash
cd backend && mvn test package
cd ../frontend && npm ci && npm run build
cd .. && docker compose -p trade-platform-v02 up --build -d
```

1. 用不同浏览器访问系统，匿名请求受保护 API 应返回 401。
2. 登录后查询 `DEMO-EXP-001`，确认四条包装明细和 `DOCUMENTED` 的 20DRY×1。
3. 在データ取込み选择包装集计 Excel，预览后确认保存；检查合同搜索、分页和要确认项。已有 PDF 仍可查看、下载、打印，新上传已停用。
4. 使用 `samples/export-import-template.csv` 导入测试记录，确认明细和容器可查；再次导入应拒绝重复。
5. 通过无管理员权限账户验证不能执行文件上传或 CSV 导入（需要另行建立 USER 角色账户）。
6. 在测试数据库上执行数据备份和恢复演练。

**部署生产前仍需完成：** HTTPS/TLS 和 `COOKIE_SECURE=true`、受控反向代理、独立备份与恢复验证、实际用户和角色管理、进一步的登录限流策略、监控告警、漏洞扫描、敏感数据脱敏、权限需求确认、多客户访问隔离评估、真实业务数据迁移验证及与日本公司的验收。

## 9. 当前验证说明

2026-10-13 已在本机 Docker 环境完成前后端构建、Java 单元测试、隔离 PostgreSQL 恢复与迁移、40 项 HTTP 回归检查，以及浏览器登录、搜索、详情、PDF预览、上传关联记录选择和窄屏布局检查。具体修复、测试边界及回退方法见 [审计报告](docs/AUDIT-2026-10-13.md)。这不等同于生产业务验收。

## 10. 更多关联测试数据（SYN26）

新版本附带额外的**合成测试数据**，以 72 笔出口及 32 笔销售订单为中心，关联设备、发票、Packing List 明细、集装箱和演示 PDF。全部使用 `SYN26-` 前缀与原始示例隔离。详见 [`samples/synthetic-demo/README.md`](samples/synthetic-demo/README.md)。

在**本地开发**环境将 `.env` 设置为 `DEMO_DATA_ENABLED=true`，并启动服务后运行：

```bash
bash samples/synthetic-demo/install_demo.sh
```

这条命令会复制 32 份演示 PDF 到私有文档卷，并向 PostgreSQL 添加合成记录。**请勿在生产库使用，不会覆盖已有记录，也不会自动删除数据**。首次运行成功后不需要反复执行。

补充的数据可通过 `samples/synthetic-demo/SYN26_演示数据汇总.xlsx` 阅读；SQL 数据对应 `samples/synthetic-demo/seed_demo.sql`，并附验证脚本 `verify_import.sql`。

- 2026-10-13 已增加统一关键词搜索，并对普通搜索中的 `%`、`_` 做字面匹配。
- 五年监视页和数据导入页增加了**只读示例列表**，其他页面由新增数据库记录自动丰富。
- 已实现的搜索行为及型号规范化后续建议，请阅读 [`docs/SEARCH_IMPROVEMENT_NOTES.md`](docs/SEARCH_IMPROVEMENT_NOTES.md)。

## 11. 日常启动、停止与数据库连接

在终端执行：

```bash
cd /Users/ying/Downloads/trade-platform-v0.2
docker compose -p trade-platform-v02 up -d      # 启动
docker compose -p trade-platform-v02 stop       # 停止，保留容器和数据
# 修改代码后：
docker compose -p trade-platform-v02 up --build -d
```

本机入口为 `http://localhost:8088`（以 `.env` 的 `WEB_PORT` 为准）。DBeaver 使用 `127.0.0.1:5433`，数据库 `trade_platform`、用户 `trade_user`，密码取 `.env` 的 `DB_PASSWORD`。连接仅映射到本机回环地址。

始终使用相同项目名 `trade-platform-v02`，避免因文件夹改名而连到另一套空数据卷。现有 `.env` 不要用示例文件覆盖。管理员仅在空库首次初始化；修改 `.env` 的管理员密码不会自动修改已存在用户的数据库密码。

连续输错密码 5 次会将该账号暂时锁定 5 分钟。成功登录会重置失败次数。CSV 要求 UTF-8（支持 BOM）、2MB 以下、最多300行；同一出口号的客户、设备、日期及箱型信息必须一致，任一错误都会回滚整份导入。


## 包装集计 Excel 原资料导入（2026-10-18）

进入 `データ取込み`，选择 `.xlsx` → `プレビュー` → 检查行数和「要確認」 → 勾选确认 → `原資料として保存`。保存后在同页下方按合同号、设备、型号或分解部名称查询，点击「原文／要確認」查看对应 Excel 行及原始单元格。

- 支持本项目核对的包装集计格式：单工作表、3〜4 行为表头、B:P 列、从第 5 行开始；上限 10MB / 10,000 行。不是任意 Excel 的通用识别器。
- 识别合并单元格、同合同多设备、D:E 合并时 F/G 为型号/部件的变体，以及明确的 G/H 数量与包装编号互换。列变化会标为要确认；没有合并关系的空白不会擅自向下填充。
- 数量、尺寸、重量、体积采用保存值。保留原有小数，不改写原值、不把共享数值复制到各行、不自动换算疑似厘米尺寸或重算体积。公式不执行、外部工作簿不读取，外部引用产生的设备信息保留原文并标为未确定。
- 容器类型和数量保留原文，暂不推断实绩；客户、发票、日期缺失不会编造，因此记录只进入原资料库，不生成正式出口单，不进入正式集装箱统计。
- 文件 SHA-256 和解析内容 SHA-256 都有唯一约束；相同文件改名或仅重新压缩后上传也不会重复。修改业务内容的工作簿属于新批次，不自动覆盖旧资料；可能有重复的合同，正式业务整合前需核对。
- 数据库新增 Flyway V3：`t_packing_import_batch` 保存批次、校验摘要、行数和导入人；`t_packing_source_row` 保存可检索字段与 JSONB 原始/解析内容、数式、合并范围和疑点。不修改 V1/V2 或现有业务记录。
- PDF POST 接口默认关闭（`app.pdf-upload-enabled=false`），新上传入口移除；恢复功能需明确重新启用配置和界面，已有私有文档访问不受影响。
- 真实 Excel、真实数据导出和环境密码不得提交 GitHub。解析器测试使用生成的测试工作簿；完整文件的本地验证通过 `PACKING_TEST_FILE` 指定路径。

DBeaver 查询原资料示例：

```sql
SELECT batch_id, file_name, row_count, contract_count, review_rows, created_at
FROM t_packing_import_batch ORDER BY batch_id DESC;

SELECT source_row, contract_no, equipment_name, model_no, part_name,
       data->>'quantity' AS quantity, data->>'quantityUnit' AS quantity_unit,
       data->>'widthMm' AS width_mm, data->>'depthMm' AS depth_mm,
       data->>'heightMm' AS height_mm, data->>'weightKg' AS weight_kg,
       data->>'volumeM3' AS volume_m3, data->'warnings' AS warnings
FROM t_packing_source_row
WHERE batch_id = 1 ORDER BY source_row;
```

数式读取使用 [Apache POI 的保存结果机制](https://poi.apache.org/components/spreadsheet/eval.html)，不执行外部引用或宏。详见 [Excel 导入验证记录](docs/EXCEL-IMPORT-2026-10-18.md)。


## 管理页面中的 Excel 资料检索

保存后进入「輸出管理」或「コンテナ実績」→「Excel取込資料」，可跨文件查询合同、设备、型号、部件、包装编号、集装箱及港口，并组合筛选型号、箱型、出发港、目的港和要确认项。没有正式记录时默认显示此标签。现有资料无需重新导入，点击「原文／要確認」可查看来源文件、行号和原始单元格。

原有正式记录位于「登録済み輸出／登録済みコンテナ」标签。原始包装资料不会自动生成缺失的客户、发票、日期或销售记录。集装箱原文可能在多个包装行重复，不应相加作为实际箱数；销售与五年监视仍需相应业务数据。

接口：`GET /api/packing-sources`，所有登录用户可访问；参数为 `keyword/model/container/departurePort/destinationPort/reviewOnly/page/size`，分页大小 1–100。

## 通知中心

顶部铃铛提供个人操作通知、未读数量、单条/全部已读和分页历史。Excel 预览上传、Excel/CSV 导入成功及失败由后端生成通知，持久化保存；每 30 秒自动更新。首次升级不生成历史通知，现有 PDF 上传暂停状态不变。修改、删除类型已预留，等待相应业务接口实现。详见 [通知中心说明](docs/NOTIFICATIONS.md)。

## 期限管理表导入

支持 Excel 预览、确认、重复处理和历史附件，并在販売管理及5年間監視共享查询。使用步骤与解析边界见 [期限管理表导入说明](docs/DEADLINE-IMPORT.md)。
