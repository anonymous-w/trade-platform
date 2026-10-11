# 数据管理平台 / 販売・輸出管理システム

**代码版本：v0.1（可运行开发原型）** · 根据 `docs/system-design-v0.7.md` 构建。

> 这是面向需求确认与开发的原型，不是已经验证的生产交付系统。尤其销售工作流、五年监视、历史数据字段映射、集装箱实际箱号关联等仍待日本公司确认。

## 1. 实现范围

| 模块 | 实现状态 |
|---|---|
| Spring Security 登录、会话、CSRF、退出 | 已实现；首个管理员从环境变量初始化 |
| 輸出管理：查询、分页、CSV导出 | 已实现 |
| 輸出詳細：设备、包装件、发票、箱型、船期 | 已实现；从关联表实时读取 |
| 原票プレビュー：PDF预览、下载、打印 | 已实现；上传真实 PDF 后可使用 |
| コンテナ実績：按型号/箱型/依据查询 | 已实现；区分 `PLANNED` / `DOCUMENTED` / `ACTUAL` |
| データ取込み：PDF 上传、限定格式 CSV 导入 | 已实现；PDF不做OCR；CSV要求一笔出口只有一款设备 |
| 販売管理：列表、详情、PDF | **只读基础版**；不包含创建/审批流程 |
| 5年間監視 | **预留页面**，不擅自定义合规义务 |
| 自动装箱推荐、AI、移动端 | 不在本版范围 |

## 2. 运行环境

推荐 Docker Engine + Docker Compose v2。在代码所在机器需要能够下载 Docker images、Maven 和 npm 依赖。首次安装可能耗时较长。

```bash
cp .env.example .env
# 编辑 .env 中的 DB_PASSWORD 和 ADMIN_PASSWORD，使用不同的强密码
# 开发环境可保持 DEMO_DATA_ENABLED=true
# Windows PowerShell 可用 Copy-Item .env.example .env

docker compose up --build -d
docker compose ps
```

打开 **http://localhost:8088**，登录账号是 `.env` 中的 `ADMIN_USER` 与 `ADMIN_PASSWORD`。

演示记录：`DEMO-EXP-001` / `DEMO-INV-001` / `SPG-X`，装箱数据（4 件、5110kg、9.266m³、20DRY×1）源自设计讨论的示例，但**客户和编号是合成演示数据**，绝不代表实际商业记录；该集装箱证据类别为 `DOCUMENTED`。

取消模拟数据：将 `DEMO_DATA_ENABLED=false`，并使用全新空数据库启动。**关闭该选项不会删除之前已写入的演示数据。**

停止运行：

```bash
docker compose down
```

注意：`docker compose down -v` **会永久删除数据库和上传的文件卷**；如需保留历史数据，请不要添加 `-v`。

## 3. 开发模式（不使用 Docker 构建）

本地需要 Java 21、Maven 3.9+、Node 22、PostgreSQL 17。首先启动数据库或运行 `docker compose up -d db`。

```bash
# 终端 A：后端（确保数据库及环境变量已配置）
cd backend
mvn spring-boot:run

# 终端 B：前端
cd frontend
npm install
npm run dev
```

Vite 默认 `http://localhost:5173`，通过代理将 `/api` 转发到 `localhost:8080`。数据库密码和管理员密码必须通过环境变量设置，不能提交到 Git。

## 4. 目录说明

```text
trade-platform-v0.1/
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
- **データ取込み**：管理员可上传 PDF 到指定出口或销售记录；CSV 模板可导入一笔出口/一款设备的多包装行。
- **販売管理**：只读列表与详情，数据需另行导入；销售审批未实现。

## 6. 重要的数据约定

- 一个 `T_EXPORT` 可以对应多个 `T_EXPORT_ITEM`、Invoice、Packing List 和容器记录；设备规格是历史快照，不能以商品主数据覆盖。
- `T_PACKING_LIST_HEADER` 保留原单据汇总，`T_PACKING_LIST` 保留明细。来源差异不自动覆盖。
- `T_EXPORT_CONTAINER.RECORD_BASIS` 的含义：`PLANNED` 是计划、`DOCUMENTED` 是文件记载、`ACTUAL` 必须有实际出运依据。系统不会根据字段自动推断核实等级。
- `T_DOCUMENT` 只存储私有文件的元数据，PDF 实体保存在 `document_data` 容器卷，不直接向公网开放。
- CSV 导入是**严格限制的演示型流程**：单个 `export_no` 只允许一个设备、一个 Invoice、一个箱型配置；金额默认为 0、客户地址和出货地是待核实占位值，导入的出口状态为 DRAFT。这不是原 Excel 的全面迁移工具。
- 本示例没有为销售记录制作虚构合同；没有原件的单据预览会显示空状态。

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
| `/api/documents` | POST | 管理员 PDF 上传 |
| `/api/documents/{id}/content` | GET | 内联 PDF 预览 |
| `/api/documents/{id}/download` | GET | 下载原始 PDF |
| `/api/imports/csv` | POST | 管理员导入受限 CSV |

## 8. 测试与上线前检查

在可联网的开发环境执行：

```bash
cd backend && mvn test package
cd ../frontend && npm install && npm run build
cd .. && docker compose up --build -d
```

1. 用不同浏览器访问系统，匿名请求受保护 API 应返回 401。
2. 登录后查询 `DEMO-EXP-001`，确认四条包装明细和 `DOCUMENTED` 的 20DRY×1。
3. 上传一个真实 PDF，确认在预览页能查看、下载、打印。
4. 使用 `samples/export-import-template.csv` 导入测试记录，确认明细和容器可查；再次导入应拒绝重复。
5. 通过无管理员权限账户验证不能执行文件上传或 CSV 导入（需要另行建立 USER 角色账户）。
6. 在测试数据库上执行数据备份和恢复演练。

**部署生产前仍需完成：** HTTPS/TLS 和 `COOKIE_SECURE=true`、受控反向代理、独立备份与恢复验证、实际用户和角色管理、登录暴力破解防护/锁定策略、监控告警、漏洞扫描、敏感数据脱敏、权限需求确认、多客户访问隔离评估、真实业务数据迁移验证及与日本公司的验收。

## 9. 当前验证说明

本源代码包已执行文件完整性和静态约束检查；由于生成环境无法访问 npm/Maven Registry，并且没有可运行的 Docker/数据库服务，**未能在此环境完成 `mvn test`、`npm run build` 或真实 PostgreSQL 联调**。第一次构建应在联网环境完成；出现版本/编译问题时，以命令行真实输出为依据修正，而不是将其视为通过全部验收。

## 10. 更多关联测试数据（SYN26）

新版本附带额外的**合成测试数据**，以 72 笔出口及 32 笔销售订单为中心，关联设备、发票、Packing List 明细、集装箱和演示 PDF。全部使用 `SYN26-` 前缀与原始示例隔离。详见 [`samples/synthetic-demo/README.md`](samples/synthetic-demo/README.md)。

在**本地开发**环境将 `.env` 设置为 `DEMO_DATA_ENABLED=true`，并启动服务后运行：

```bash
bash samples/synthetic-demo/install_demo.sh
```

这条命令会复制 32 份演示 PDF 到私有文档卷，并向 PostgreSQL 添加合成记录。**请勿在生产库使用，不会覆盖已有记录，也不会自动删除数据**。首次运行成功后不需要反复执行。

补充的数据可通过 `samples/synthetic-demo/SYN26_演示数据汇总.xlsx` 阅读；SQL 数据对应 `samples/synthetic-demo/seed_demo.sql`，并附验证脚本 `verify_import.sql`。

- 本次不改动 `ExportController.java`、`ContainerController.java`、`SalesController.java` 的现有搜索逻辑。
- 五年监视页和数据导入页增加了**只读示例列表**，其他页面由新增数据库记录自动丰富。
- 查询是否需要统一关键词、多字段 OR、型号规范化等优化，请阅读 [`docs/SEARCH_IMPROVEMENT_NOTES.md`](docs/SEARCH_IMPROVEMENT_NOTES.md)。
