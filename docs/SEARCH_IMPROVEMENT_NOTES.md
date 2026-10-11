# 搜索体验优化建议（本次仅说明，不修改搜索源码）

## 现状核查：不是完全精准查询

现有后端 `backend/src/main/java/jp/co/tradeplatform/export/ExportController.java` 的 `criteria(...)` 已采用 `ILIKE :model` 并传入 `%用户输入%`。同样，`invoiceNo`、`equipment`、`customer` 也是子串模糊匹配。例如型番 `SPG` 应匹配 `SPG-X`、`SPG-X2`、`SPG-XL`。`ContainerController.java` 对型号、箱型也做模糊匹配；`SalesController.java` 的 `q` 只匹配订单号或客户名。

感觉必须输入精确型号的常见原因：只有一个示例记录、输入包含不同连接符/全半角、把型号填进了设备名搜索框、多个搜索条件以 AND 同时过滤、前端或后端仍运行旧版本、未触发「検索」按钮。

## 先检查是否正常（不用改代码）

1. 打开浏览器开发者工具 Network，登录后在「輸出管理」的**型番**输入 `SPG` 点击「検索」。
2. 请求应类似 `/api/exports?model=SPG&page=0&size=20`；在这批 SYN26 数据中应该匹配多个型号。
3. 若 `SPG` 不匹配，查看响应内容和已部署的源代码版本；不要在没有确认原因时重写查询。
4. 清空其他检索条件，因为当前条件是 AND；例如同时设置 `model=SPG` 和 `customer=ABC`，结果必须同时满足。

## 将来推荐的改进次序

### P1：一个统一关键字搜索框

- 前端 `ExportList.vue` 增加 `keyword` 输入框，保留日期等高级筛选。
- 后端 `ExportController.criteria` 增加 `keyword` 参数；对发票号、出口业务号、设备名称、型号、客户的日文/英文名称做 OR 子查询；高级筛选条件仍以 AND 拼接。
- 使用参数绑定（`NamedParameterJdbcTemplate`）而不是手工拼 SQL。
- 前端每次改变关键词时重置页码到第一页，并显示总命中数。

结构示意（不是完整可替换源码）：

```sql
AND (
  e.export_no ILIKE :keyword
  OR c.customer_name ILIKE :keyword
  OR c.customer_name_en ILIKE :keyword
  OR EXISTS (SELECT 1 FROM t_invoice i
             WHERE i.export_id=e.export_id AND i.invoice_no ILIKE :keyword)
  OR EXISTS (SELECT 1 FROM t_export_item x
             WHERE x.export_id=e.export_id
               AND (x.model_no ILIKE :keyword OR x.equipment_name ILIKE :keyword))
)
```

`:keyword` 参数传 `%输入内容%`；注意对用户输入的 `%`、`_` 等通配符进行转义，使普通用户输入以字面字符匹配。

### P2：型号规范化与别名

`SPG X`、`SPGX`、`SPG-X`、`ＳＰＧ－Ｘ` 可能被视为同一型号。可以新增规范化检索字段 `MODEL_SEARCH_KEY`，统一做全半角转换、大小写标准化、去除空格和连字符；**但保留原始 `MODEL_NO` 原样展示**。设备名称如“プロファイル研削盤 / Profile Grinding Machine / 磨床”可引入经审核的同义词字典，不要未经确认就自动混并不同设备。

### P3：更快的模糊检索

数据量增长后可使用 PostgreSQL `pg_trgm` 的 GIN 索引辅助 `ILIKE` / 相似度排序，但必须先用 `EXPLAIN ANALYZE` 验证实际查询计划。日语多字节分词及只有一两个字符的检索需要另行测试；不要一开始引入 Elasticsearch。

### P4：交互细节

- 搜索关键词建议使用 300～400ms debounce，或保留明确“検索”按钮，避免每次键入发出大量请求。
- 检索结果分页、过滤状态、清空按钮和空结果提示统一。
- 添加后端测试：`SPG` 匹配 SPG-X 系列、大小写不敏感、混合设备记录、多个筛选条件、空关键词、恶意 `%` 和 `_` 字符。

以上仅为下一轮修改建议，本次**未改动现有出口/销售/集装箱搜索控制器代码**。
