# yotoo-mcp

基于 **Spring Boot 3** 与 **Spring AI MCP Server**（Streamable HTTP）的服务：从 **MySQL** 读取业务 API 定义（`skill_api_def` / `skill_api_param`），动态注册为 MCP 工具；**GET** 类工具经网关真实转发下游 HTTP，**非 GET** 不发起下游请求，仅返回带 `json_schema_form` 的结构供前端表单渲染。可选将当前工具列表同步到 **Dify** 的 PostgreSQL（`tool_mcp_providers`）。

## 项目功能

- **MCP Server（同步 + Streamable HTTP）**：工具能力、工具变更通知能力在配置中开启（见 `application.yml`）。
- **动态工具（唯一生效来源）**：`DynamicToolCallbackProvider` 根据内存中的 `ApiDef` + `ApiParam` 生成工具名（`operationId`）、描述与输入 JSON Schema，执行时统一走 `ApiInvoker`。
- **API 定义加载**：
  - 启动及刷新时通过 `ApiDatabaseService` 从 MySQL 加载 `skill_api_def` 全表，以及 `skill_api_param` 中 `api_edition = 2` 的参数，并按 `api_id` 关联到各 API。
  - 若数据库不可用或查询结果为空，`ApiBeanCache` 回退到内置三条模拟定义（`getWeather`、`calculate`、`getId`），与 `TestController` 中的示例路由对应。
- **调用语义（`ApiInvoker`）**：
  - `spring.ai.mcp.simulate-invoke=true`：不发起真实 HTTP；非 GET 仍返回表单结构；GET 返回固定模拟文案。
  - `simulate-invoke=false` 且请求方法为 **GET**：向 `http://{api.gateway}{apiPath}` 发起请求（`apiPath` 若已含 `http://` 或 `https://` 则整段当作完整 URL）。路径占位符 `{param}` 从参数替换；未写入路径的参数作为 **query**；参数名 `token` 会作为 **Bearer** 放入 `Authorization`。
  - **非 GET**：**不调用下游**，返回统一 JSON 信封，`uiKind` 为 `json_schema_form`，`data` 内含 `jsonSchema`（各字段带 `value`）与 `aiNotice`，供界面渲染与模型话术约束。
  - 每次真实/模拟调用路径上会尝试递增 `skill_api_def.used_count`（`api_id` 为空时跳过）。
- **定时与手动刷新**：`ApiCacheRefreshService` 按 `api.cache.refresh.cron` 定时从 MySQL 刷新缓存；`GET /refresh/tools` 手动刷新。刷新成功后会调用 `McpToolService.updateDifyMcpTools()`，将当前缓存中的工具列表写入 Dify 库表（需配置 `dify.mcp.*`）。*注：向已连接 MCP 客户端发送 `tools/list_changed` 的 `refreshTools()` 在当前实现中被注释，若需客户端自动拉新列表可自行恢复调用。*
- **Dify 工具元数据同步**：`McpToolService` 连接 PostgreSQL，按 `dify.mcp.provider-id` 更新 `dify.mcp.db.table`（默认 `tool_mcp_providers`）中的 `tools`（jsonb），并兼容既有 JSON 中的 `input_schema` / `inputSchema` 字段命名。

## 代码中与 Dify 不一致的说明

- `McpService.livingCity`（`@Tool`）及对应的 `MethodToolCallbackProvider` Bean 在 `ToolCallbackProviderConfig` 中**已注释**，目的是与「通过数据库维护工具列表」的 Dify 用法一致；当前 MCP 暴露的工具**全部**来自 MySQL（或回退的模拟数据），不包含 `livingCity`。

## 主要类职责

| 类 | 作用 |
|----|------|
| `ApiDatabaseService` | 从 MySQL 读取 `skill_api_def`、`skill_api_param`，以及 `incrementApiDefUsedCount` |
| `ApiBeanCache` | 内存中的 `apiDefList`，启动加载与 `refreshCache()` |
| `DynamicToolCallbackProvider` | 将 `apiDefList` 转为 Spring AI `ToolCallback` |
| `ApiInvoker` | 工具执行：GET 下游调用 / 非 GET 表单 JSON / 模拟模式 |
| `ApiCacheRefreshService` | 定时与 `/refresh/tools` 触发缓存刷新与 Dify 写库 |
| `McpToolService` | `updateDifyMcpTools()`、（可选）`notifyToolsListChanged` |
| `TestController` | 示例 HTTP：`/get/weather/{city}`、`/get/id`、`POST /calculate` |

## 运行要求

- **JDK 17**（Spring Boot 3.x）
- **Maven**（建议 3.6.3+；可使用仓库内 JDK17 脚本）
- 可选：**MySQL**（业务 API 定义）、**PostgreSQL**（Dify 侧工具同步）

## 快速启动

### 配置说明（`src/main/resources/application.yml`）

| 配置项 | 含义 |
|--------|------|
| `server.port` | HTTP 端口，默认 `10087` |
| `spring.ai.mcp.server.*` | MCP 名称、版本、`protocol: STREAMABLE`、`streamable-http.mcp-endpoint`（默认 `/mcp`） |
| `spring.ai.mcp.simulate-invoke` | 是否模拟调用（不发起真实 GET） |
| `api.gateway` | GET 请求网关主机（`host:port`，代码中拼接为 `http://` 前缀） |
| `api.cache.db.*` | MySQL：JDBC URL、用户名、密码 |
| `api.cache.refresh.enabled` / `cron` | 是否启用定时刷新及 Cron 表达式 |
| `api.invoke.connect-timeout-ms` / `read-timeout-ms` | GET 下游连接与读取超时 |
| `dify.mcp.provider-id` | Dify MCP Provider 记录主键（UUID） |
| `dify.mcp.db.*` | PostgreSQL 连接与表名（默认 `tool_mcp_providers`） |

环境变量覆盖示例：`API_CACHE_DB_URL`、`API_CACHE_REFRESH_ENABLED`、`DIFY_MCP_DB_URL` 等（见 yml 中 `${...}`）。

### 构建与运行（Windows 推荐使用项目内 JDK17）

```powershell
.\mvn17.ps1 -DskipTests install
.\mvn17.ps1 spring-boot:run
```

或 cmd：`mvn17.cmd`。

### Toolchains（与其它 Java 8 项目共存）

`pom.xml` 启用了 `maven-toolchains-plugin`，需在 `~/.m2/toolchains.xml` 配置 JDK 17（可参考仓库内 `toolchains.xml.example`）。实际打包/运行仍建议用 `mvn17` 脚本，避免 Maven 进程 JDK 与项目不一致。

## MCP 客户端连接

服务启动后（默认 `http://127.0.0.1:10087`），使用 **Streamable HTTP**，端点示例：

- `http://127.0.0.1:10087/mcp`

**Cursor 等（仅需 URL）示例：**

```json
{
  "mcpServers": {
    "yotoo-mcp-local": {
      "url": "http://127.0.0.1:10087/mcp"
    }
  }
}
```

工具列表内容由当前 MySQL（或回退模拟数据）决定，**不是**固定为 `livingCity`、`getWeather` 等；仅当库不可用时才会看到三条示例工具。

**旧版独立 SSE + POST 消息端点**与本项目当前服务端不兼容。

## HTTP 辅助接口

- `GET /refresh/tools`：重新加载 MySQL 缓存并尝试同步 Dify `tools` 字段；响应形如 `refresh success, dataSource=mysql` 或 `mock`。
- `GET /get/weather/{city}`、`GET /get/id`、`POST /calculate`：与回退模拟 API 定义配套的本地示例（真实环境请以自己的库表为准）。

`POST /calculate` 请求体示例：

```json
{
  "a": 12,
  "b": 3,
  "op": "div"
}
```

## 工具返回约定（前端 / 模型）

- **GET 成功或错误**：`uiKind` 一般为 `standard`，信封字段含 `success`、`message`、`status`、`operationId`、`url`、`data`（下游 body 或错误信息）等。
- **非 GET**：`uiKind` 为 `json_schema_form`，`data` 为 `{ "jsonSchema": { ... }, "aiNotice": "..." }`，表示未调用下游，需在页面侧继续提交。
