# Bukkit 并发示例

NovaLang 的并发原语在 Bukkit 插件开发中非常实用。核心原则：**数据库/网络操作异步执行不卡服，写回游戏状态时用 `sync` 回到主线程保证安全**。

> 所有并发函数均为全局内置，无需 import。

---

## 注册 Bukkit 调度器

`scope`、`sync`、`launch`、`parallel`、`withTimeout`、`schedule`、`scheduleRepeat`、`delay` 和 `Dispatchers.Main` 都依赖宿主调度器。引入 `nova-bukkit` 模块后一行注册：

### 快速注册（推荐）

```java
import nova.bukkit.BukkitSchedulers;

public class MyPlugin extends JavaPlugin {
    @Override
    public void onEnable() {
        // 一行注册，解锁所有并发函数对接 Bukkit 调度器
        BukkitSchedulers.register(this);

        // 然后加载 Nova 脚本...
    }
}
```

> **Gradle 依赖**: `implementation project(':nova-bukkit')` 或发布后 `implementation 'com.novalang:nova-bukkit:版本号'`

### 自定义实现（高级）

如需自定义调度逻辑，可手动实现 `NovaScheduler` 接口：

<details>
<summary>展开查看完整实现</summary>

```java
import nova.runtime.NovaScheduler;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public class CustomNovaScheduler implements NovaScheduler {

    private final JavaPlugin plugin;
    private final java.util.concurrent.Executor mainExec;
    private final java.util.concurrent.Executor asyncExec;

    public CustomNovaScheduler(JavaPlugin plugin) {
        this.plugin = plugin;
        this.mainExec = task -> Bukkit.getScheduler().runTask(plugin, task);
        this.asyncExec = task -> Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
    }

    @Override
    public java.util.concurrent.Executor mainExecutor() { return mainExec; }

    @Override
    public java.util.concurrent.Executor asyncExecutor() { return asyncExec; }

    @Override
    public boolean isMainThread() { return Bukkit.isPrimaryThread(); }

    @Override
    public Cancellable scheduleLater(long delayMs, Runnable task) {
        long ticks = Math.max(1, delayMs / 50);
        var bt = Bukkit.getScheduler().runTaskLater(plugin, task, ticks);
        return new Cancellable() {
            public void cancel() { bt.cancel(); }
            public boolean isCancelled() { return bt.isCancelled(); }
        };
    }

    @Override
    public Cancellable scheduleRepeat(long delayMs, long periodMs, Runnable task) {
        long delayTicks = Math.max(1, delayMs / 50);
        long periodTicks = Math.max(1, periodMs / 50);
        var bt = Bukkit.getScheduler().runTaskTimer(plugin, task, delayTicks, periodTicks);
        return new Cancellable() {
            public void cancel() { bt.cancel(); }
            public boolean isCancelled() { return bt.isCancelled(); }
        };
    }
}
```

```java
// 手动注册
nova.runtime.SchedulerHolder.set(new CustomNovaScheduler(this));
```

</details>

注册后，Nova 脚本中的并发函数自动对接到 Bukkit 调度器：`launch`/`parallel`/`withTimeout` 提交到 Bukkit 异步线程池，`sync` 回到 Bukkit 主线程。

---

## scope / sync — 异步与主线程切换

- **`scope { }`** — 在异步线程执行 block，**阻塞调用者**直到完成。block 内可用 `delay()`
- **`sync { }`** — 将 block 提交到 Bukkit 主线程执行并等待结果。已在主线程时直接执行

> **警告**：`scope` 会阻塞当前线程！在 Bukkit 主线程（事件处理器、命令等）中**不要使用 `scope`**，否则会卡服。主线程应使用 `launch`（见下节）。`scope` 适合在已经处于异步线程时需要等待子任务完成的场景。

```nova
// ❌ 错误 — 主线程调用 scope 会卡服！
fun onPlayerJoin(player: Player) {
    scope {
        val data = db.loadPlayerData(player.uuid)  // 主线程阻塞等待查库完成
        sync { player.sendMessage("§a已加载") }
    }
}

// ✅ 正确 — launch 不阻塞，立即返回
fun onPlayerJoin(player: Player) {
    launch {
        val data = db.loadPlayerData(player.uuid)  // 异步线程查库
        sync {
            // 回到 Bukkit 主线程，安全操作实体
            player.setHealth(data["health"])
            player.setLevel(data["level"])
            player.sendMessage("§a数据已加载")
        }
    }
}
```

> **注意**：Bukkit API（操作实体、方块、发包等）必须在主线程调用，所以异步操作完后必须 `sync` 回去。

### scope 的正确用法 — 在异步线程中等待

```nova
// scope 适合在 launch 内部使用，阻塞的是异步线程不影响主线程
launch {
    val result = scope {
        // 这里已经在异步线程，scope 阻塞异步线程没问题
        val a = async { queryA() }
        val b = async { queryB() }
        merge(a.await(), b.await())
    }
    sync { applyResult(result) }
}
```

### launch 内使用 delay

```nova
fun countdown(player: Player) {
    launch {
        for (i in 3.downTo(1)) {
            sync { player.sendMessage("§e倒计时: $i") }
            delay(1000)  // 异步线程安全休眠 1 秒
        }
        sync { player.sendMessage("§a开始！") }
    }
}
```

> `delay()` 不能在主线程调用（会抛异常），只能在 `scope`/`launch` 的异步上下文中使用。主线程延迟应使用 `schedule()`。

---

## launch — Bukkit 主线程的首选异步方式

`launch` 不阻塞调用者，是 Bukkit 事件处理器、命令回调等主线程场景的首选：

```nova
fun onPlayerJoin(player: Player) {
    launch {
        val data = db.loadPlayerData(player.uuid)
        sync {
            player.setHealth(data["health"])
            player.setLevel(data["level"])
            player.sendMessage("§a数据已加载")
        }
    }
    // 不等待，立即返回，不卡主线程
}
```

### launch vs scope

| | `launch` | `scope` |
|---|----------|---------|
| 是否阻塞调用者 | 否 | **是** |
| 返回值 | Job（可 join/cancel） | block 的返回值 |
| 主线程可用 | **是（推荐）** | 否（会卡服） |
| 适用场景 | 事件处理、命令、定时任务 | launch 内部等待子任务 |
| 适用场景 | 后台任务、事件处理 | 需要等结果时 |

---

## async / await — 带返回值的异步

`async` 在后台执行 block 并返回 Future，通过 `.await()` 获取结果：

```nova
fun onPlayerJoin(player: Player) {
    // 异步加载，不阻塞
    val economyFuture = async { db.query("SELECT * FROM economy WHERE uuid=?", player.uuid) }
    val homeFuture = async { db.query("SELECT * FROM homes WHERE uuid=?", player.uuid) }

    // 需要时再 await
    launch {
        val economy = economyFuture.await()
        val homes = homeFuture.await()
        sync {
            applyPlayerData(player, economy, homes)
        }
    }
}
```

---

## schedule / scheduleRepeat — 调度器定时任务

通过 Bukkit 调度器在主线程执行定时任务，返回可取消的 Task：

```nova
// 5 秒后在主线程执行（单次）
val task = schedule(5000) {
    server.broadcastMessage("§6活动即将开始！")
}

// 取消任务
task.cancel()
```

```nova
// 每 60 秒刷新一次记分板（主线程）
val refreshTask = scheduleRepeat(0, 60000) {
    for (player in server.onlinePlayers) {
        updateScoreboard(player)
    }
}

// 插件卸载时取消
fun onDisable() {
    refreshTask.cancel()
}
```

### 自动清理 AFK 玩家

```nova
val afkChecker = scheduleRepeat(60000, 30000) {
    val now = now()
    for (player in server.onlinePlayers) {
        val lastActive = getLastActivity(player)
        if (now - lastActive > 600000) {  // 10 分钟未活动
            player.kick("§7因长时间未活动被踢出")
        }
    }
}
```

---

## delay — 异步延迟

`delay` 在异步线程中休眠，不卡主线程：

```nova
// 延迟重生效果
fun onPlayerDeath(player: Player) {
    launch {
        delay(3000)  // 等待 3 秒
        sync {
            player.teleport(spawnPoint)
            player.setHealth(20)
            player.sendMessage("§a你已重生")
        }
    }
}
```

> 主线程调用 `delay()` 会抛异常（防止意外卡服）。主线程延迟应使用 `schedule(ms) { }`。

---

## Dispatchers — 执行器

NovaLang 提供 4 个内置 Dispatcher：

| Dispatcher | 说明 |
|------------|------|
| `Dispatchers.IO` | I/O 密集型线程池（CachedThreadPool） |
| `Dispatchers.Default` | CPU 密集型（ForkJoinPool） |
| `Dispatchers.Unconfined` | 在调用者线程直接执行 |
| `Dispatchers.Main` | Bukkit 主线程（注册 NovaScheduler 后可用） |

### withContext 切换执行器

```nova
fun loadAndApply(player: Player) {
    launch {
        // 在 IO 线程池查库
        val data = withContext(Dispatchers.IO) {
            db.heavyQuery(player.uuid)
        }

        // 回到 Bukkit 主线程
        withContext(Dispatchers.Main) {
            player.setLevel(data["level"])
            player.sendMessage("§a加载完成")
        }
    }
}
```

---

## coroutineScope / supervisorScope — 结构化并发

### coroutineScope

子任务全部成功才返回，任一子任务失败则取消其余并传播异常：

```nova
fun loadWorldData(worldName: String) {
    coroutineScope { s ->
        val chunks = s.async { db.loadChunks(worldName) }
        val entities = s.async { db.loadEntities(worldName) }
        val metadata = s.async { db.loadMetadata(worldName) }

        // 三个查询并行执行，任一失败则全部取消
        applyWorldData(chunks.await(), entities.await(), metadata.await())
    }
}
```

### supervisorScope

子任务互相独立，一个失败不影响其他：

```nova
fun broadcastDailyRewards() {
    supervisorScope { s ->
        for (player in server.onlinePlayers) {
            s.launch {
                // 每个玩家独立处理，某个玩家失败不影响其他人
                val reward = db.getDailyReward(player.uuid)
                sync {
                    giveReward(player, reward)
                    player.sendMessage("§a领取每日奖励: $reward")
                }
            }
        }
    }
    // 所有玩家处理完毕后才到这里
    println("每日奖励发放完成")
}
```

### 指定 Dispatcher

```nova
// 在 IO 线程池中运行结构化并发
coroutineScope(Dispatchers.IO) { s ->
    val a = s.async { readFile("config1.yml") }
    val b = s.async { readFile("config2.yml") }
    mergeConfigs(a.await(), b.await())
}
```

---

## parallel — 简单并行执行

不需要结构化并发时，`parallel` 更简洁：

```nova
fun onPlayerJoin(player: Player) {
    launch {
        val results = parallel(
            { db.query("SELECT * FROM economy WHERE uuid=?", player.uuid) },
            { db.query("SELECT * FROM homes WHERE uuid=?", player.uuid) },
            { httpGet("https://api.example.com/skin/${player.uuid}").json() }
        )
        val economy = results[0]
        val homes = results[1]
        val skin = results[2]

        sync {
            applyPlayerData(player, economy, homes, skin)
        }
    }
}
```

---

## withTimeout — 超时保护

防止外部服务挂掉拖慢服务器：

```nova
fun fetchPlayerRank(uuid: String): String {
    return try {
        withTimeout(3000) {
            httpGet("https://api.example.com/rank/$uuid").body
        }
    } catch (e) {
        "默认段位"  // 超时 3 秒后回退
    }
}
```

---

## Channel — 线程间通信

### 异步日志队列

高频事件解耦，主线程投递不阻塞，后台消费者写入：

```nova
val logChannel = Channel(1000)

// 启动后台消费者
launch {
    for (msg in logChannel) {
        appendFile("logs/game.log", "$msg\n")
    }
}

// 主线程随时投递
fun logAction(player: Player, action: String) {
    logChannel.send("[${now()}] ${player.name}: $action")
}
```

### 生产者-消费者：区块扫描

```nova
val blockChannel = Channel(500)

// 生产者：扫描区块中的矿石
launch {
    for (block in chunk.getBlocks()) {
        if (block.type == "DIAMOND_ORE") {
            blockChannel.send(mapOf("x" to block.x, "y" to block.y, "z" to block.z))
        }
    }
    blockChannel.close()
}

// 消费者：逐个入库
launch {
    for (pos in blockChannel) {
        db.execute("INSERT INTO ores VALUES (?, ?, ?)", pos["x"], pos["y"], pos["z"])
    }
    sync { player.sendMessage("§a矿石扫描完成") }
}
```

### 带超时接收

```nova
val ch = Channel(10)
ch.send(42)
val v = ch.receiveTimeout(1000)  // 1 秒内等待，超时抛异常
```

---

## AtomicInt / AtomicLong / AtomicRef — 原子变量

### 线程安全统计

```nova
val totalKills = AtomicInt(0)
val totalDeaths = AtomicInt(0)

fun onPlayerKill(killer: Player, victim: Player) {
    totalKills.incrementAndGet()
    totalDeaths.incrementAndGet()

    launch {
        db.execute("UPDATE stats SET kills=kills+1 WHERE uuid=?", killer.uuid)
    }
}

fun updateScoreboard(player: Player) {
    player.sendMessage("全服击杀: ${totalKills.get()}")
}
```

### AtomicRef 缓存热数据

```nova
val leaderboard = AtomicRef(emptyList())

// 每 5 分钟异步刷新排行榜
scheduleRepeat(0, 300000) {
    launch {
        val top10 = db.query("SELECT name, score FROM rankings ORDER BY score DESC LIMIT 10")
        leaderboard.set(top10)  // 原子替换，读取方无需加锁
    }
}

// 主线程读取，零开销
fun showLeaderboard(player: Player) {
    val top = leaderboard.get()
    for ((i, entry) in top.withIndex()) {
        player.sendMessage("§6#${i + 1} §f${entry["name"]} §7- §e${entry["score"]}")
    }
}
```

---

## Mutex — 互斥锁

### 经济系统转账

```nova
val economyLock = Mutex()

fun transfer(from: Player, to: Player, amount: Int) {
    launch {
        economyLock.withLock {
            val fromBalance = db.getBalance(from.uuid)
            if (fromBalance < amount) {
                sync { from.sendMessage("§c余额不足") }
                return
            }
            db.setBalance(from.uuid, fromBalance - amount)
            db.setBalance(to.uuid, db.getBalance(to.uuid) + amount)
        }
        sync {
            from.sendMessage("§a已转账 $amount 给 ${to.name}")
            to.sendMessage("§a收到 ${from.name} 转账 $amount")
        }
    }
}
```

### 按玩家粒度加锁

```nova
val playerLocks = mutableMapOf()

fun getPlayerLock(uuid: String): Mutex {
    return playerLocks.getOrPut(uuid) { Mutex() }
}

fun modifyPlayerData(player: Player, action: () -> Unit) {
    launch {
        getPlayerLock(player.uuid).withLock {
            action()
        }
    }
}
```

---

## awaitAll / awaitFirst

### 批量保存

```nova
fun onServerShutdown() {
    val futures = server.onlinePlayers.map { player ->
        launch { db.savePlayerData(player.uuid, collectData(player)) }
    }
    awaitAll(futures)
    println("所有玩家数据已保存")
}
```

### 竞速查询

```nova
fun getPlayerSkin(uuid: String): Any {
    val f1 = launch { httpGet("https://api-1.example.com/skin/$uuid").json() }
    val f2 = launch { httpGet("https://api-2.example.com/skin/$uuid").json() }
    return awaitFirst([f1, f2])
}
```

---

## 综合示例：异步副本系统

```nova
val activeRuns = AtomicInt(0)
val resultChannel = Channel(100)

// 后台记录副本结果
launch {
    for (result in resultChannel) {
        db.execute(
            "INSERT INTO dungeon_log VALUES (?, ?, ?, ?)",
            result["player"], result["dungeon"], result["time"], result["success"]
        )
    }
}

fun startDungeon(player: Player, dungeonName: String) {
    if (activeRuns.get() >= 10) {
        player.sendMessage("§c副本已满，请稍后再试")
        return
    }

    activeRuns.incrementAndGet()
    player.sendMessage("§a正在加载副本...")

    launch {
        // 并行加载副本资源
        val resources = parallel(
            { db.query("SELECT * FROM dungeon_config WHERE name=?", dungeonName) },
            { db.query("SELECT * FROM dungeon_mobs WHERE dungeon=?", dungeonName) },
            { db.query("SELECT * FROM dungeon_rewards WHERE dungeon=?", dungeonName) }
        )
        val config = resources[0]
        val mobs = resources[1]
        val rewards = resources[2]

        sync {
            val world = createDungeonWorld(config, mobs)
            player.teleport(world.spawnLocation)
            player.sendMessage("§a副本已就绪，限时 ${config["timeLimit"]} 秒！")
        }

        try {
            withTimeout(config["timeLimit"] * 1000) {
                waitForCompletion(player, dungeonName)
            }
            resultChannel.send(mapOf(
                "player" to player.name, "dungeon" to dungeonName,
                "time" to config["timeLimit"], "success" to true
            ))
            sync { giveRewards(player, rewards) }
        } catch (e) {
            resultChannel.send(mapOf(
                "player" to player.name, "dungeon" to dungeonName,
                "time" to config["timeLimit"], "success" to false
            ))
            sync { player.sendMessage("§c副本超时，挑战失败") }
        } finally {
            activeRuns.decrementAndGet()
        }
    }
}
```

---

## 速查表

| 场景 | 推荐方案 |
|------|----------|
| 注册 Bukkit 调度器 | `BukkitSchedulers.register(plugin)` |
| 异步执行 + 等结果（会阻塞） | `scope { ... sync { } }`（仅限异步线程内） |
| 异步执行不阻塞（主线程推荐） | `launch { ... sync { } }` |
| 异步线程安全休眠 | `delay(ms)`（不可在主线程） |
| 主线程延迟执行 | `schedule(ms) { }` |
| 主线程定时重复 | `scheduleRepeat(delay, period) { }` |
| 带返回值的异步 | `async { } → .await()` |
| 切换执行器 | `withContext(Dispatchers.IO) { }` |
| 并行加载多种数据 | `parallel({ }, { }, { })` |
| 结构化并发（全成功） | `coroutineScope { s -> s.async { } }` |
| 结构化并发（独立容错） | `supervisorScope { s -> s.launch { } }` |
| 外部 API 超时保护 | `withTimeout(ms) { }` |
| 高频事件解耦 | `Channel` + 后台消费者 |
| 线程安全计数器 | `AtomicInt` / `AtomicLong` |
| 无锁缓存热数据 | `AtomicRef` |
| 保护共享数据一致性 | `Mutex.withLock { }` |
| 批量等待所有任务 | `awaitAll(futures)` |
| 多源竞速取最快 | `awaitFirst(futures)` |
