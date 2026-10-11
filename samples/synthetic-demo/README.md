# SYN26 开发演示数据（仅用于测试）

本目录的数据 **全部人工合成**，不是日本公司真实订单，也不代表已核实的出口或法定监视记录。

## 记录规模

- 客户 16、设备型号 18
- 销售订单 32、销售明细 32
- 出口业务 72、出口设备 82、发票 72
- Packing List 单据头 72、包装件明细 343
- 集装箱配置 80（PLANNED、DOCUMENTED、ACTUAL 均只是模拟业务状态）
- 监视周期 20、检查事件 47（**模拟测试记录，不具有法律效力**）
- 演示 PDF 32 份（Invoice、Packing List、销售订单；不可作为真实凭证）

所有编号以 `SYN26-` 开头，客户标记 `【DEMO】`，PDF 上带醒目的 `SYNTHETIC DEMO`。示例数据与原有 `DEMO-EXP-001` 独立。文件包括可读 CSV、单据 PDF 与最终导入 SQL。

## 如何安装到已有项目（不会覆盖现有订单）

1. **只在本地测试环境使用**。备份你的开发数据库，确保 `.env` 中有 `DEMO_DATA_ENABLED=true`；不要用生产数据库。
2. 在项目根目录先运行 `docker compose up --build -d` 并确认数据库和后端正常。
3. macOS/Linux 运行：

```bash
bash samples/synthetic-demo/install_demo.sh
```

Windows PowerShell 可在项目根目录依次运行：

```powershell
docker compose cp samples/synthetic-demo/documents/. backend:/data/documents/
docker compose cp samples/synthetic-demo/seed_demo.sql db:/tmp/synthetic_seed.sql
docker compose exec -T db sh -c 'psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -f /tmp/synthetic_seed.sql'
```

PowerShell 方法请同样先确认 `.env` 中 `DEMO_DATA_ENABLED=true`。

**重要：** 导入脚本采取一次性安装模式。检测到 `SYN26-EXP-0001` 已存在时会停止，不会插入重复数据。数据库插入在事务中完成，如果遇到约束错误会整体回滚。PDF 会在 SQL 导入之前复制到文件卷；如果 SQL 出错，可先根据日志排查后重试。不会自动删除任何已有数据。卸载测试数据应在**单独的测试数据库**中恢复预先制作的快照，不建议手工 `DELETE`。

## 建议测试

| 页面 | 测试内容 |
|---|---|
| 輸出管理 | 型番输入 `SPG`、`MC`，Invoice No. 输入 `SYN26-INV`，验证多条结果及分页 |
| 輸出詳細 | 打开任意 `SYN26-EXP`，验证 1～2 台设备与包装行、箱型及依据 |
| コンテナ実績 | 输入 `20DRY`，按 PLANNED / DOCUMENTED / ACTUAL 模拟状态筛选 |
| 原票プレビュー | 打开前 12 笔出口，切换模拟 Invoice 和 Packing List，测试 PDF 下载/打印 |
| 販売管理 | 受注No. 输入 `SYN26-SO`，查看 32 笔只读销售订单（每页 20 条） |
| 帳票プレビュー | 前 8 笔销售订单含有 DEMO ORDER PDF |
| データ取込み | 查看“登録済み書類”；可使用单独的 `samples/export-import-template.csv` 测试新记录导入，注意不要重复使用已存在的编号 |
| 5年間監視 | 只读显示 20 条合成监视周期；不自动执行任何监管判断 |

**当前搜索代码没有修改。** 现有 `ExportController` / `ContainerController` 已用 `ILIKE '%关键字%'` 进行大小写不敏感的子串匹配；详见根目录的 `docs/SEARCH_IMPROVEMENT_NOTES.md`。
