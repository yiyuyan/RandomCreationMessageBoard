# RandomCreationMessageBoard

Fabric 模组（Minecraft **26.1.2**，Java 25），添加「留言板」与「时空胶囊」两个物品，数据存放在 Cloudflare Workers + D1。

## 物品与配方

### 留言板 `crc_msg_board:msg_board`

右键打开留言板界面。

```
铁锭   红石   铁锭
红石   告示牌 红石
铁锭   红石   铁锭
```

告示牌使用物品标签 `#minecraft:signs`，任意木质告示牌均可。

### 时空胶囊 `crc_msg_board:copper_time_capsules`

右键打开时空胶囊界面：写入留言 → 选择开启日期 → 点击「完成」封存。物品 NBT 记录留言 id 与开启时间；未到时间无法打开，到时间后右键显示留言。

```
       铜锭
铜锭   海洋之心  铜锭
       铜锭
```

两个物品都已加入创造模式「工具与实用物品」标签页。

## 界面

**留言板**：左侧日历（默认折叠，`历` 按钮展开），中间显示所选日期的留言，右侧时间列（默认折叠，`时` 按钮展开），底部输入框（上限 200 字，不得为空，每 3 秒一条）+「发送」+「刷新」。每次发送无论成功与否都会弹出 toast；成功时清空输入框并刷新列表。

**时空胶囊**：中间留言输入框（上限 500 字，不得为空），下方「选择时间」按钮展开日历，最下方「完成」按钮提交。提交成功后 id 通过自定义网络包写回**服务端**物品栈（否则客户端改动会被同步覆盖）。

**开发环境（`FabricLoader.isDevelopmentEnvironment()`）额外行为**，便于调试：

- 允许把开启时间设到过去，但**最多只能回拨 30 分钟**（`DEV_MAX_BACKDATE_MS`），三层同时校验：客户端、服务端 C2S 包、Worker；
- 多了两个快捷按钮：`10秒后`（openAt = 现在 + 10s）和`立刻`（openAt = 现在 − 1s），跳过等待直接验证「锁定 → 可打开」；
- 界面显示绿色`开发模式：可回拨 30 分钟`提示；时间超界时`打开时间`会变红；
- **后端开关 `ALLOW_PAST_CAPSULE_OPEN` 当前为 `"false"`**：Worker 只接受未来的 `openAt`。因此 dev 下把时间设到过去会被后端以 `400 bad_open_at` 拒绝——`立刻`（现在 − 1s）同样会被拒，实际可用的是 `10秒后` 或日历里的未来日期。要让 dev 回拨真正生效，把该变量改回 `"true"` 再 deploy。

生产环境（正式安装的 jar）不受影响：仍然只能选未来日期，Worker 端也仍然拒绝过去时间。

## 胶囊自动清理

胶囊被**打开（读取）后满 30 分钟**，由 Worker 自动删除，避免已读内容长期占存储。

- 清理**内联在 POST 请求里**（发新留言时顺带删），另有一个每小时 cron 作为兜底；不再需要 5 分钟一轮的定时任务。
- 只在**读取那一刻**记录 `read_at`（`COALESCE` 保证只写第一次），未读的胶囊永远保留 —— 不会出现"还没看就被删"。
- 判定条件是 `type='capsule' AND read_at IS NOT NULL AND read_at <= now - 30min`。
- 若你想要的是"到点后 30 分钟无论是否读过都删"，把上面条件改成 `open_at <= now - 30min` 即可（一行改动）。

## 成本控制

- **客户端 15 秒缓存**：同一日期在 15 秒内重复打开留言板不再发请求（「刷新」按钮强制刷新）。
- **增量拉取**：客户端记住该日期已取到的最新时间戳，刷新时带 `since=`，服务端只返回新增行。
- **限流与写入合并为一条 SQL**：每次发帖少一次读，并且限流变成原子操作（并发双发不会再都通过）。
- **去掉未被任何查询使用的 `open_at` 索引**：实测每行省 14.4 B。
- **cron 从每 5 分钟改为每小时**：定时调用 288 次/天 → 24 次/天。

## 配置

首次运行会生成 `config/crc_msg_board.json`：

```json
{
  "baseUrl": "https://crc-msg-board.YOUR-SUBDOMAIN.workers.dev",
  "timeoutSeconds": 10
}
```

把 `baseUrl` 改成你部署后的 Worker 地址即可，无需重启（下一次请求生效）。

## 后端

**已部署：**

| 项 | 值 |
|---|---|
| Worker URL | `https://crc-msg-board.179544143.workers.dev` |
| D1 数据库 | `crc_msg_board` / `bd898f90-31d0-457e-b2c8-d39d52117695`（APAC） |
| 认证方式 | `wrangler login`（OAuth，凭据在 `~/.wrangler`） |

开发环境已写入 `run/config/crc_msg_board.json`，直接指向上面这个 URL；正式客户端把同样的内容放进取到的 `config/crc_msg_board.json` 即可。

重新部署（改了 `backend/src` 之后）：

```powershell
cd backend
npx.cmd wrangler deploy
```

改 schema 后需要重跑：

```powershell
npx.cmd wrangler d1 execute crc_msg_board --file=schema.sql --remote --yes
```

细节见 [`backend/README.md`](backend/README.md)。

接口：

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/v1/messages` | 提交留言，`type` 为 `normal`/`capsule` |
| GET | `/v1/messages?date=&tzOffset=&type=` | 按日期（含时区偏移）列出留言 |
| GET | `/v1/messages/{id}` | 读取单条；胶囊未到时间返回 403 `not_yet_open` |
| GET | `/v1/health` | 健康检查 |

服务端校验：`type` 枚举、长度（普通 200 / 胶囊 500 **按 Unicode 码点**计算，emoji 记 1）、非空、UUID 格式、`openAt` 必须在未来、**每人 3 秒一条**（返回 429 + `retryAfterMs`）。正文用 `deflate-raw` 压缩后以 BLOB 存 D1，省存储。

## 实现要点

- 模组侧只做界面与 HTTP，**不在 Minecraft 服务端存储消息**；数据全部在 D1。
- 26.1.2 的 GUI 渲染管线是新的 `extractRenderState(GuiGraphicsExtractor, ...)`，输入是 `MouseButtonEvent` / `KeyEvent` 记录类型；日历是手绘组件（原版没有可复用日期选择器）。
- **边框用 4 条 `fill` 手绘**（`Ui.border`）。`GuiGraphicsExtractor.outline` 的右/下边线会画到矩形外一像素，直接用会导致边框出界。
- **物品必须有 item definition**（`assets/<ns>/items/<id>.json`）。只有 `models/item/*.json` 会显示成黑紫方块 —— 这是 1.21.4+ 引入的双文件机制。
- 创造标签页：`CreativeModeTabs.TOOLS_AND_UTILITIES` 在该版本是 `private`，因此用注册表 id 重建等价 key：`ResourceKey.create(Registries.CREATIVE_MODE_TAB, Identifier.fromNamespaceAndPath("minecraft","tools_and_utilities"))`。
- 胶囊封存用自定义 C2S 包 `crc_msg_board:capsule_write`，服务端二次校验：id 长度 26、`openAt` 必须在未来、手上确实是胶囊物品、且尚未封存。
- 开发用冒烟测试 `client/SmokeTest.java`：仅当 `config/crc_msg_board_smoke.flag` 存在时启用，会依次打开三个界面、模拟日历点击、把两个物品放进快捷栏、校验创造标签页内容并截图到 `run/screenshots/`。正常游玩不受影响。

## 数据生成（datagen）

配方、进度、物品模型、item definition、中英文语言文件全部由 datagen 生成，不再手写：

```powershell
& "D:\.gradle\wrapper\dists\gradle-9.8.1-bin\a2l6ltp3k1vth6lwipzm5p2jp\gradle-9.8.1\bin\gradle.bat" runDatagen
```

产物在 `src/main/generated/`（已随仓库提交，Loom 会自动并入主资源集）：

| Provider | 产物 |
|---|---|
| `MsgBoardRecipeProvider` | `data/crc_msg_board/recipe/*.json` + 解锁进度 |
| `ItemAssetProvider` | `assets/crc_msg_board/models/item/*.json` 与 `assets/crc_msg_board/items/*.json` |
| `MsgBoardLangProvider` | `assets/crc_msg_board/lang/{en_us,zh_cn}.json` |

`ItemAssetProvider` 是自定义 `DataProvider`：26.1.2 的 `ItemModelGenerators` 只公开 `run()`，没有给模组用的逐物品生成方法，所以两个文件直接按规范写出。

## 已知限制

- **限流可绕过**：`playerUuid` 由客户端提供，恶意客户端可伪造不同 UUID。若要真正防刷，需要接入 Minecraft 服务端会话校验或在 Worker 增加按 IP 的限制。
- Worker 的限流是「先查后写」两条 D1 语句，非原子；同一 UUID 的并发请求理论上可能都通过（README 里给了 Durable Object 方案）。
- 无按 IP 限流（schema 里没有 IP 列）。
- 日历选择日期时使用本地时区（`atStartOfDay`），与列表查询用的 `tzOffset` 一致。

## 构建

需要 JDK 25（loom 1.18.2 要求）：

```powershell
$env:JAVA_HOME = "D:\.gradle\jdks\eclipse_adoptium-25-amd64-windows\jdk-25.0.3+9"
# 注意：本机 npm/cf 的 .ps1 垫片被执行策略拦截，用 gradle.bat / npm.cmd
& "D:\.gradle\wrapper\dists\gradle-9.8.1-bin\a2l6ltp3k1vth6lwipzm5p2jp\gradle-9.8.1\bin\gradle.bat" build
```

产物：`build/libs/randomcreationmessageboard-0.1.jar`
