# Applied Factory MCP 服务器设计

> 状态：**已实装（P0–P4 完成，编译通过）**。本文为设计参考；实现以源码为准：服务器侧 `factory/McpProbeManager`、`factory/McpProbeResult`、`script/McpProbeHost`、`script/JsValueSerializer`，客户端 `mcp/*`（HTTP/MCP 传输），中继 `network/*Mcp*Payload` + `NetworkHandler`，绑定 UI 在控制器程序界面与 `/appliedfactory mcp` 命令。
>
> 设计：v2，按"探针程序 = 普通 job、与生产被动产线对齐"重构。目标是为 AI agent 提供"执行探针代码 → 直接返回日志"的能力，让不熟悉整合包的 agent 通过试错探索工厂，并可直接把实验产物上传为生产程序。
>
> 相关代码参考：`FactoryProgram` / `FactoryJob` / `PassiveJob`（job 调度）、`RhinoScriptRuntime`（沙箱与指令预算）、`ScriptApi` / `JsBridgeBinder`（白名单桥接）、`FactoryControllerBlockEntity`（`FactoryProgram.Host` 实现）、`NetworkHandler` / 各 payload（网络中继）、`ExportCommand`（`appliedscripts` 目录约定）。

## 1. 需求与背景

AI agent 进入不熟悉的 Minecraft 整合包时，无法从提示词知道：某个方向挂着什么网络、某个熔炉能熔什么、哪里有多少铁。与其静态枚举知识，不如给它一个**可试错的运行时**：写探针程序、跑起来、拿回日志与返回值，据此迭代，最终把验证过的程序上传为生产程序。

需求要点：

- 客户端启动一个 **MCP 服务器**（Model Context Protocol，Anthropic 标准），主流 agent 工具开箱即用；
- 核心工具是 **execute：执行探针程序 → 直接返回日志**；另有 **upload：直接上传生产程序**；
- **探针程序与生产流程对齐**：语义就是现在 `go(function*(){...})` 生成的被动产线——同一个调度器、同一套等待语义（Action 等待/`sleep` 跨真实 tick）、同一个 `log()`；探针程序被视为一个**普通 job**；
- `log()` 输出**不写本地文件再读取，直接随响应返回**；
- 程序中途挂起（等资源、等容量、sleep）时**和被动产线一样等待**，直到全部执行完成后返回；工具调用允许**指定超时时间**来界定等待；
- **搬运资源是合理语义**：实验生产本身就是构建产线的一部分，探针可以真实搬动资源；
- **单 tick 耗时上限不在 MCP 这一层做**，而是对所有 job（生产 + 探针）统一约束（沿用 Rhino 指令预算），探针不享受额外豁免也不被额外限制；
- **玩家指定目标控制器**（当前正在操作的实例）；对 AI 而言控制器是**隐式选择**，工具参数里不出现控制器 id；
- MCP 用 **Java web 服务器**提供（HTTP 传输），配置落在 **`/appliedscripts`** 目录。

## 2. 可行性分析

### 2.1 现状基础（可复用资产）

| 资产 | 位置 | 对 MCP 的意义 |
| --- | --- | --- |
| job 调度器 | `FactoryProgram`（`step()`、`startMissingPassives()`、`advance()`、等待/完成/失败语义） | 探针程序**直接复用 `FactoryProgram`**，天然"与生产对齐" |
| Rhino 沙箱 + 单步指令预算 | `RhinoScriptRuntime`（`FactoryContextFactory`、`INSTRUCTION_COUNT`、500K/step） | 探针 job 与生产 job 共用同一上限，无需 MCP 层再设限制 |
| 白名单 JS 桥 | `ScriptApi`、`JsBridgeBinder` | `go/network/bus/resource/recipes/log/sleep` 全套 API 与生产完全一致 |
| 控制器宿主接口 | `FactoryControllerBlockEntity` 实现 `FactoryProgram.Host` | 包一层装饰器即可捕获 `log()`，其余委托给控制器 |
| 客户端→服务器中继 | `NetworkHandler` + 各 `*Payload` | 单人/联机统一分发已有先例 |
| 本地参考目录 | `ExportCommand` 写入 `<游戏根>/appliedscripts/` | MCP 配置与 agent 配置片段放这里 |

### 2.2 技术可行性

- **MCP 传输**：Streamable HTTP transport（2025-06-18）。JDK 内置 `com.sun.net.httpserver.HttpServer` 即可（零新依赖）；需要更强并发/SSE 时可换 Jetty（走现有 `jarJar` 机制，Rhino 已有先例）。
- **探针即 job**：探针程序 = 一次 `FactoryProgram.load(code, 探针Host)`，`go()` 注册的被动 handler 成为与生产同类的 `PassiveJob`，由服务器侧 `McpProbeManager` 在每个服务器 tick 调用其 `step()`。等待语义（资源不足、目标满、sleep 跨真实 tick）与生产完全一致。
- **完成判定**：所有被动 job 都 settle（完成或失败）即"全部执行完成"。`FactoryProgram` 增加 `activeJobCount()` 即可轮询判定；无限循环/无限等待的探针由 `timeoutTicks` 界定。
- **结果捕获**：`RhinoScriptRuntime.loadProgram` 求值一次，捕获最后一个表达式的值（新增 `lastValue()` 访问器），完成/超时时序列化为 JSON 返回。
- **开箱即用**：Streamable HTTP 已被 Claude Code、Cursor、Windsurf、LangChain MCP adapter 支持；Claude Desktop 用 `"type":"http"` 配置。

### 2.3 风险与缓解

| 风险 | 缓解 |
| --- | --- |
| AI 真实搬动资源（副作用） | 仅 `127.0.0.1` 监听 + 可选 Bearer token；玩家显式绑定控制器；每次 execute/upload 写服务器日志与聊天栏审计 |
| 无限循环 / 无限等待占住 job 槽 | 单 tick 指令预算（所有 job 共享）防 CPU 烧穿；`timeoutTicks` 由调用方界定；硬上限（默认 1 小时）+ 玩家 `/appliedfactory mcp stop` 可终止全部探针 |
| 探针与生产争抢同一资源 | 探针直接操作真实世界状态，与生产被动产线共享资源是**预期语义**（实验生产），不作隔离 |
| 世界访问必须在服务器主线程 | HTTP 线程只解析 JSON-RPC；payload handler 与 `ServerTickEvent` 均在服务器主线程 |
| Rhino 沙箱逃逸 | 沿用 class shutter + 白名单桥，不引入文件/网络/反射 API |
| 上传覆盖生产程序 | `upload` 与 GUI 保存完全同语义（先编译后替换），编译失败不覆盖；重型审计 |
| MCP 规范版本演进 | 固定广告 `protocolVersion`，只实现工具能力子集 |

### 2.4 结论

**可行，且比 v1 更简单**：不需要新执行引擎——探针就是 `FactoryProgram`，新增量主要是 HTTP/MCP 传输层（客户端）、`McpProbeManager`（服务器侧 tick 驱动 + 完成判定）、探针 Host 装饰器与一组 payload。核心 MVP 约 1000–1500 行 Java。

## 3. 总体架构

```
  Agent tool (Claude Code / Cursor / ...)
        │  HTTP JSON-RPC 2.0 (Streamable HTTP)
        ▼
┌──────────────────────────────────────────────────────────┐
│  MCP Server —— 客户端 JVM（com.sun.net.httpserver）          │
│  · McpHttpHandler：传输 + 会话 + JSON-RPC 分发               │
│  · McpTools：工具 schema 与调用入口（execute/upload/status）  │
│  · McpRequestRegistry：requestId → 待完成的 HTTP Future     │
└───────────────┬──────────────────────────────────────────┘
        │ ExecuteMcpCodePayload / UploadControllerProgramPayload / Bind… (playToServer)
        ▼
┌──────────────────────────────────────────────────────────┐
│  服务器主线程                                             │
│  · NetworkHandler：定位玩家绑定控制器 BE，安全校验           │
│  · McpProbeHost：FactoryProgram.Host 装饰器，捕获 log()     │
│  · McpProbeManager（ServerTickEvent）：tick 驱动探针 step() │
│    ├─ 探针程序 = FactoryProgram.load(code, probeHost)      │
│    ├─ go() 被动 = PassiveJob，与生产同调度                  │
│    └─ 全部 settle 或 timeout → 完成 Future                  │
│  · JsValueSerializer：返回值 → JSON                        │
│  · upload → controller.updateControllerProgram(source)     │
└───────────────────────────────┬──────────────────────────┘
        │ McpCodeResultPayload / UploadResultPayload (playToClient)
        ▼
  MCP Server 完成 HTTP 响应 ──────▶ Agent
```

要点：

- **MCP 服务器在客户端**：监听 `127.0.0.1:<port>`，`POST /mcp`（JSON-RPC），`GET /mcp`（SSE 日志流），`DELETE /mcp`。
- **探针执行在服务器**：探针程序拥有**独立 Rhino scope**（独立 `FactoryProgram`），不触碰控制器运行中的生产 `FactoryProgram`；但走完全相同的调度与等待语义。
- **控制器隐式**：所有工具作用于"玩家当前绑定"的控制器，工具参数无控制器 id。
- **探针无状态**：每次 execute 是一次独立程序求值（世界即持久状态），不做跨调用 scope 持久化——与"保存一份程序去运行"的心智一致。

## 4. MCP 协议设计

### 4.1 传输与端点

- **传输**：MCP Streamable HTTP，protocolVersion `2025-06-18`（兼容 `2025-03-26` 客户端）。
- **端点**：`http://127.0.0.1:<port>/mcp`
  - `POST /mcp`：JSON-RPC 请求/响应；响应类型按 `Accept`（`application/json` 或 `text/event-stream`）。
  - `GET /mcp`（`Accept: text/event-stream`）：打开服务器→客户端通知流（`notifications/message` 实时推控制器日志）。
  - `DELETE /mcp`：终结会话。
- **头**：`Mcp-Protocol-Version`、`Mcp-Session-Id`（未带时服务端生成并回填）、可选 `Authorization: Bearer <token>`。
- **绑定**：默认 `127.0.0.1`；跨机器需显式开启（写入 `mcp.json` 并提示风险）。

### 4.2 JSON-RPC 方法与能力

| 方法 | 说明 |
| --- | --- |
| `initialize` | 返回 `protocolVersion`、`capabilities:{tools:{listChanged:false}}`、`serverInfo:{name:"appliedfactory-mcp",version:<mod>}` |
| `notifications/initialized` | 握手完成 |
| `ping` | 心跳 |
| `tools/list` | 返回工具 schema（见 4.3） |
| `tools/call` | 执行 `appliedfactory_execute` / `appliedfactory_upload` / `appliedfactory_status` |
| `notifications/message` | 服务器→客户端；SSE 流上推送控制器 `log()`/错误实时事件 |

握手示例：

```jsonc
// → POST /mcp
{"jsonrpc":"2.0","id":1,"method":"initialize","params":{
  "protocolVersion":"2025-06-18","capabilities":{},
  "clientInfo":{"name":"claude-code","version":"1.0"}}}
// ← 200 application/json, Mcp-Session-Id: s_abc123
{"jsonrpc":"2.0","id":1,"result":{
  "protocolVersion":"2025-06-18",
  "capabilities":{"tools":{"listChanged":false}},
  "serverInfo":{"name":"appliedfactory-mcp","version":"0.1.0"}}}
```

### 4.3 工具定义（tools/list）

**1) `appliedfactory_execute`（核心：执行探针程序）**

```jsonc
{
  "name": "appliedfactory_execute",
  "description": "在已绑定控制器上执行探针程序并返回日志。探针就是一份与生产一致的" +
    "被动产线程序（go(function*(){...})）：同一套 API、同一调度器、同一等待语义。" +
    "所有 go() 注册的生成器全部执行完成后返回；挂起（等资源/容量/sleep）时与被动产线" +
    "一样真实等待，用 timeoutTicks 界定。log() 输出直接返回，不写文件。",
  "inputSchema": {
    "type": "object",
    "properties": {
      "code": {"type": "string", "maxLength": 32768,
               "description": "与控制器程序同语法。最后一个表达式的值作为 result 返回。"},
      "timeoutTicks": {"type": "integer", "minimum": 1, "maximum": 72000,
               "description": "最多等待多少游戏刻（20 tick/秒）。默认不设（等到全部完成），" +
               "但受服务端硬上限约束。0 表示只求值不推进任何 go 生成器。"}
    },
    "required": ["code"]
  }
}
```

**2) `appliedfactory_upload`（上传生产程序）**

```jsonc
{
  "name": "appliedfactory_upload",
  "description": "把 source 编译并替换为已绑定控制器的生产程序，语义与在控制器界面点" +
    "保存完全一致：先编译，编译失败不覆盖现有程序。建议先用 execute 实验验证再上传。",
  "inputSchema": {
    "type": "object",
    "properties": {
      "source": {"type": "string", "maxLength": 32768, "description": "完整控制器程序源码。"}
    },
    "required": ["source"]
  }
}
```

**3) `appliedfactory_status`**（只读、低风险）：返回绑定控制器位置/维度/标签、生产程序是否编译成功、6 方向在线网络、总线数量、活动 job 数、活动探针数、托管 escrow 数、服务器运行时长。

### 4.4 execute 返回协议与执行语义

`tools/call` 成功时 `content[0].type="text"`，文本为单行 JSON：

```jsonc
{
  "ok": true,
  "reason": "completed | timeout | error | eval_only",
  "message": "reason 详细文本",
  "logs": ["hello", "smelt started"],          // log()/console.log 直接返回，不写文件
  "result": <JSON 值：最后一个表达式；Undefined→null；截断上限>,
  "pending": [{"kind":"transfer","detail":"8x minecraft:iron_ore → bus@(12,64,-8) south", ...}],
  "elapsedTicks": 74,
  "steps": 42
}
```

执行语义（对齐生产）：

1. **求值**：`FactoryProgram.load(code, probeHost)`。求值一次，等价于生产程序加载：
   - `go(fn)` 注册的生成器 → 等待推进；
   - 顶层语句立即执行；**顶层 `.now()` 可用**（探针求值期间绑定一次性 `ScriptExecutionContext`）；
   - `registerProcessingPattern` 与生产 API 同语法允许调用，但探针程序的样板**不会**提供给 AE（只有控制器生产程序会提供）；文档注明，后续可加"自动触发探针样板做端到端测试"。
   - 若没有注册任何 `go` → `reason:"eval_only"`，立即返回日志 + 最后一个表达式值。
2. **推进**：由 `McpProbeManager` 在**每个服务器 tick** 调用 `program.step()`，与生产完全一致：
   - `FactoryTransferAction`：真实执行；资源不足/目标满 → job 等待（不失败）；
   - `FactorySleepAction`：跨真实 tick 等待；
   - 每个 job 的单 tick 指令预算沿用 `RhinoScriptRuntime` 全局约束（**对所有 job 统一，不在 MCP 层另设**）。
3. **完成**：`program.activeJobCount() == 0`（全部被动 job settle）→ `reason:"completed"`，返回累计日志与 result。
4. **超时**：调用方给 `timeoutTicks` 时，超过后返回 `reason:"timeout"` + 已产生的 logs + `pending`（当前等待中的 Action 描述，让 AI 知道卡在哪）；探针程序被丢弃（jobs 取消）。
5. **错误**：编译失败 / 运行时异常 → `isError:true` + 消息 + 已产生 logs；探针销毁。

- **日志**：`log()` 进 `logs[]` **直接返回**（不写本地文件），同时转发给真实控制器 `log()`（订阅者聊天可见，审计），SSE 流存在时实时推 `notifications/message`。
- **无状态**：每次 execute 都是独立程序，不保留跨调用变量；世界的网络/资源/机器状态就是持久状态。`timeoutTicks:0` 可用于"只做只读检查不求值生成器"。

### 4.5 请求关联与生命周期

- 客户端为每次 `tools/call` 生成 `requestId`，挂到 `McpRequestRegistry`（MCP sessionId → requestId → HTTP Future）；服务器完成时回传同一 `requestId`，客户端完成 Future。
- `Mcp-Session-Id` 仅用于请求关联与 SSE 流；**不承载脚本状态**。
- 服务器侧 `McpProbeManager` 按 `(playerUUID, requestId)` 跟踪活动探针；玩家下线/换服、客户端 `mcp stop` → 取消全部探针、关闭 SSE、停 HTTP 服务器。
- 探针程序仅内存、不序列化；服务器重启自然消失。

### 4.6 upload 语义

- `upload` → 服务器校验绑定 → `controller.updateControllerProgram(source)`（与 GUI 保存同路径）：
  - 编译成功 → 替换生产程序、丢弃旧 generator、旧托管 escrow 由新程序回收、AE 样板刷新；
  - 编译失败 → 返回 `{ok:false, message:<编译错误>}`，现有程序不受影响。
- 重型审计：聊天栏 + 服务器日志记录"玩家 X 通过 MCP 上传了生产程序到 (x,y,z)"。
- 风险提示在工具 description 中写明：上传即覆盖，AI 应先 execute 验证。

### 4.7 控制器绑定（隐式选择）

- 玩家在控制器程序界面点 **"绑定到 MCP"**（或 `/appliedfactory mcp bind` 瞄准控制器），客户端发 `BindMcpControllerPayload{pos}`；服务器校验为 `FactoryControllerBlockEntity` 且玩家距离 ≤64 后确认。
- 客户端进程内记录 `McpBinding{dimension, pos, label}`；MCP 服务器运行期间所有工具调用带上该绑定。
- 服务器每次执行校验：玩家在线、绑定维度 == 玩家当前维度、控制器区块已加载；否则返回友好错误。
- AI 参数里没有控制器字段——完全隐式。

### 4.8 配置与开箱即用（/appliedscripts）

`/appliedfactory export` 与 `/appliedfactory mcp config` 写入 `<游戏根>/appliedscripts/mcp.json`：

```jsonc
{
  "server": {"host": "127.0.0.1", "port": 39291, "token": "", "autoStart": true,
             "probeHardTimeoutTicks": 72000},
  "binding": {"dimension": "minecraft:overworld", "pos": [123, 64, -45], "label": "main-factory"},
  "clients": {
    "claude_code": "claude mcp add --transport http appliedfactory http://127.0.0.1:39291/mcp",
    "cursor": "Cursor Settings → MCP → Add → URL: http://127.0.0.1:39291/mcp",
    "claude_desktop_config": {
      "mcpServers": {"appliedfactory": {"type": "http", "url": "http://127.0.0.1:39291/mcp"}}
    }
  }
}
```

同时输出：
- `mcp_tools.md`：工具与 JS API 说明（镜像 `script-api/factory-controller.d.ts`）；
- `mcp_config.json`：纯数据版配置。

控制命令（客户端）：`/appliedfactory mcp start [port]`、`stop`、`status`、`bind`、`config`；启动后聊天栏打印 URL 与配置位置。

## 5. 实现方案

### 5.1 代码布局

```
com.fulent.appliedfactory.mcp                 // 新增（客户端 transport + 协议）
├── McpServer                 HttpServer 生命周期（start/stop/status）
├── McpHttpHandler            Streamable HTTP：POST/GET/DELETE，JSON-RPC 分发，SSE
├── McpTools                  工具 schema（tools/list、tools/call 分派）
├── McpRequestRegistry        (mcpSessionId, requestId) → 待完成 HTTP Future + SSE 流
├── McpConfig                 读写 appliedscripts/mcp.json，生成 agent 配置片段
├── McpBinding                record(dimension, pos, label)；客户端绑定状态
└── McpCommand                /appliedfactory mcp 子命令（客户端）

com.fulent.appliedfactory.script              // 新增/小幅改动（服务器侧执行）
├── McpProbeHost              FactoryProgram.Host 装饰器：捕获 log()/reportScriptFailure
│                             到 buffer 并转发控制器，其余委托给控制器 BE
├── McpProbeManager           ServerTickEvent 驱动：创建/step/完成/超时/取消探针程序
└── JsValueSerializer         JS 值 → JSON（facade 属性递归、深度/循环/长度上限）

com.fulent.appliedfactory.factory             // 小改动
└── FactoryProgram            + activeJobCount()（完成判定用）

com.fulent.appliedfactory.script              // 小改动
└── RhinoScriptRuntime        + lastValue()：loadProgram 时捕获最后一个表达式值

com.fulent.appliedfactory.network             // 新增 payload
├── ExecuteMcpCodePayload      requestId + code + binding(pos) + timeoutTicks
├── McpCodeResultPayload       requestId + ok/reason/logs/result/pending
├── UploadControllerProgramPayload  requestId + pos + source
├── UploadResultPayload        requestId + ok + message
├── BindMcpControllerPayload   pos
└── McpBindResultPayload       accepted + label
```

### 5.2 网络分发（统一单人/联机）

- 沿用 `NetworkHandler` 注册：`playToServer(ExecuteMcpCodePayload, ...)`、`playToClient(McpCodeResultPayload, ...)` 等。playToServer handler 在服务器主线程执行，天然满足世界访问约束。
- 客户端 `McpTools.execute`：生成 `requestId` → `sendToServer` → 挂 Future；收到结果 payload 完成 Future。
- 服务器 handler：按绑定定位 BE → `McpProbeHost` 包装 → 探针入 `McpProbeManager`（`requestId` 对应）→ 返回。完成/超时由 manager 回发 `McpCodeResultPayload`（按玩家 UUID 在线查 Player）。
- 断线：`ClientPlayerNetworkEvent.LoggingOut` → `McpServer.stop()`，取消全部探针与 SSE。

### 5.3 服务器侧执行（伪代码）

```java
// McpProbeManager.onServerTick()
for (var probe : List.copyOf(active)) {
    if (serverTick - probe.startedAt >= probe.hardTimeout) {
        settle(probe, timeout, pendingSummary(probe)); continue;
    }
    probe.program.step();
    if (probe.program.activeJobCount() == 0) {
        settle(probe, completed, null);          // 全部被动 job 执行完成
    }
}

// execute handler（服务器主线程）
McpProbeResult handle(McpProbeHost host, String code, long timeoutTicks) {
    var result = FactoryProgram.load(code, host);          // 编译 + 求值一次，捕获 lastValue
    if (!result.successful()) return compileError(result);
    var program = result.program();
    if (program.passiveHandlerCount() == 0)
        return evalOnly(host.logs(), program.runtime().lastValue());   // 纯求值，立即返回
    return McpProbeManager.start(host, program, timeoutTicks);         // 挂起，tick 推进
}
```

### 5.4 里程碑

| 阶段 | 内容 | 验收 |
| --- | --- | --- |
| P0 基础改动 | `FactoryProgram.activeJobCount()`、`RhinoScriptRuntime.lastValue()` | 原功能回归通过 |
| P1 探针执行 | `McpProbeHost`、`McpProbeManager`、`JsValueSerializer`；`ServerTickEvent` 驱动 | 纯求值/`go` 完成/挂起等待/超时/无限循环五类用例正确 |
| P2 传输+协议 | `McpHttpServer`、Streamable HTTP、JSON-RPC、工具 schema | `curl` 握手 + `tools/list` + `tools/call`；Claude Code 连接成功 |
| P3 网络中继 | 6 个 payload + `NetworkHandler` 注册 + 结果回填 | 单人/联机双跑通；断线自动停服 |
| P4 绑定 + upload | GUI 按钮、`/appliedfactory mcp` 子命令、upload 审计 | 绑定→execute→upload→控制器程序生效全链路 |
| P5 配置与开箱即用 | `mcp.json` + `mcp_tools.md` + agent 配置片段 | 按文档配置秒连 |
| P6 加固 | SSE 实时日志、token、返回长度上限、并发上限、游戏测试 | 压测多并发 execute 无卡死 |

### 5.5 关键取舍（MVP）

- **探针 = `FactoryProgram` 实例**，不做新执行引擎：与生产语义零偏差，维护面最小。
- **无状态**：每次 execute 独立程序，世界即持久状态；不做跨调用 scope 持久化。
- **真实 tick 等待**：`sleep`/transfer 等待跨真实 tick，与被动产线一致；`timeoutTicks` 是唯一界定手段（默认不设，硬上限防泄漏）。
- **单 tick 指令预算全 job 共享**：不在 MCP 层另设指令/步数上限。
- **`registerProcessingPattern` 在探针中允许但不生效**（样板不提供给 AE）；端到端样板测试留作扩展。
- **传输只做 HTTP**，不做 stdio shim；如需要可加 `appliedfactory-mcp.bat` 壳进程转发。

## 6. 安全与边界

- 仅监听回环地址；token 可选但推荐；token 不写进公开文档。
- 每次 execute/upload 写服务器日志与聊天栏审计（玩家、控制器坐标、代码长度、耗时）。
- 单 tick 指令预算（约 500K）对所有 job 统一生效；单次调用由 `timeoutTicks`（上限 72000 tick = 1 小时）界定；`mcp stop` 与断线可终止全部探针。
- 探针可真实搬动资源（与玩家手动产线同等能力）——由显式绑定 + 本地监听 + 审计共同约束，不承诺高于手动脚本的安全级别。
- `log()` 不写本地文件，直接随响应返回。
- 探针不落盘、重启即失效；会话无脚本状态残留。

## 7. 附：示例会话（agent 视角）

```
1. status
   → 绑定 main-factory (overworld 123,64,-45)，南向网络在线，3 个总线，程序已编译

2. execute（timeoutTicks 0，只读检查）:
     network('south').buses.map(b => ({id: b.target.id, face: b.targetFace, channels: b.channels}))
   → reason:"eval_only", result:[{id:'minecraft:furnace', face:'south', channels:['ae2:i','ae2:f']}, ...]

3. execute（timeoutTicks 0）:
     recipes().typesOfMachine('minecraft:furnace')
   → result:['minecraft:smelting']

4. execute（timeoutTicks 400）:            // 真实等待，模拟产线
     go(function* () {
       const iron = network('north').extract(item('minecraft:iron_ore', 8));
       if (iron !== null) { yield iron.to(bus); log('moved 8 ore'); }
       yield sleep(100);
       const ingot = network('north').extract(item('minecraft:iron_ingot', -1));
       log('ingot: ' + ingot.amount);
     });
   → reason:"completed", logs:["moved 8 ore","ingot: 8"], elapsedTicks:101

5. execute（timeoutTicks 60，故意试一次会等资源的程序）:
     go(function* () { yield furnace.extract().to(order.network); });
   → reason:"timeout", logs:[], pending:[{kind:"transfer", detail:"waiting: source short"}] 
     // AI 据此知道要先把原料送进去

6. upload:
     {source: "<第 4 步验证过的程序>"}
   → {ok:true, message:"saved"}；控制器聊天栏出现 MCP 上传审计
```

AI 在每一步从 `reason`/`logs`/`pending` 中读反馈，完成从"一无所知"到"看懂并操作工厂、最终落地生产程序"的试错闭环。
