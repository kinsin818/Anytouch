package com.anytouch.app

import com.anytouch.app.platform.RecordGate
import com.anytouch.app.recorder.SavedTask
import com.anytouch.app.recorder.SavedTaskGate
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 一次任务注入请求：id 用纳秒保证同内容重复提交也是新请求。
 *
 * [plan] 是 S5-d 的重复执行计划：随请求一起进总线，**不由服务侧再去读界面**（界面随时可变，
 * 请求一旦派发就得钉死当时用户填的那一份）。缺省 = 单发，与旧口径逐字同义。
 */
data class TaskRequest(
    val id: Long,
    val json: String,
    val submittedAtMs: Long = System.currentTimeMillis(),
    val plan: RepeatPlan = RepeatPlan(),
)

/** 任务总线策略常量。 */
object TaskPolicy {
    /**
     * 注入到执行的存活窗口：超时未被消费即作废。
     * 无 TTL 时设备实测复现"注入 4 分钟无人值守 → 服务重绑后旧任务自动开跑"（StateFlow 头重放），
     * 违背"用户意图驱动执行"的红线语义，宁可丢单不偷跑。
     */
    const val TTL_MS = 60_000L
}

/** 无障碍服务连接状态与任务总线的唯一事实源，UI 与执行器都从这里读写。 */
object AppState {
    val serviceConnected = MutableStateFlow(false)

    /** UI/adb 写入，服务收集执行。null = 无待执行请求。 */
    val taskRequests = MutableStateFlow<TaskRequest?>(null)

    val running = MutableStateFlow(false)

    /**
     * AI 编译这一跑是否在路上（裁决 S3-R4-1 的状态真值，见 `orders/ANYTOUCH-S3-byok-ORDER.md` §0.1）：
     * 录制面（开录 / 停止并编译）与面板按钮读的都是这一格，编译侧 `beginCompile` 翻 true、
     * `endCompile` 翻 false——"编译中"若面板一份、录制面一份，就是两套真值（雷 18 同族）。
     */
    val compileBusy = MutableStateFlow(false)

    /** 最近一次执行的 RunReport JSON（人读 + 归因数据源）。 */
    val lastRunReport = MutableStateFlow<String?>(null)

    /**
     * 最近一次被拒的派发话术（准入见 [taskAdmission]）：与开录/编辑拒因同律——**必须显示**，
     * 静默吞掉一次"点了没反应"就是黑洞。新一次派发（成功或失败）即覆盖。
     */
    val taskRejection = MutableStateFlow<String?>(null)

    /**
     * 预制模板装载被拒的话术（S5-a）：与派发/开录/编辑拒因同律——**必须显示**，
     * "点了模板没反应"不许成黑洞。新一次装载（成功或失败）即覆盖。
     * 单独一格不复用 [taskRejection]：两件事同时红时不许互相盖（同 record/record_stop 分格先例）。
     */
    val templateRejection = MutableStateFlow<String?>(null)

    /**
     * 「我的任务」操作被拒的话术（S5-e 要求 2）：与上面几格同一条律——静默"点了没反应"=黑洞。
     * 仍是独立一格：存一条的同时派发正被 V-3 拦下，两句话不许互相盖。
     */
    val savedRejection = MutableStateFlow<String?>(null)

    /**
     * 那条存档红字**当时判的是哪一档**（与 [savedRejection] 必须同写，同 `taskRejectionGate` 那条纪律）。
     * 需要它的是三档**纯状态档**：[com.anytouch.app.recorder.SavedTaskGate.READ_FAILED]（盘现在读不出）、
     * [com.anytouch.app.recorder.SavedTaskGate.COMPILING]（编译那一跑在路上）、
     * [com.anytouch.app.recorder.SavedTaskGate.RUNNING]（有任务正在跑）——状态一归位，这三句话就失去依据，
     * 留在屏上就是假红（假红与假绿同罪）。其余档都是请求绑定的（空名／重名／太长／空账／找不到／写不进），
     * 由下一次操作覆盖；词表档那条存档脱钩的红字也存 null——它跟 V-3 一样绑用户那一次点击。
     * 判据住在纯函数 `savedRejectionAfterChange`，这里只存身份。
     */
    @Volatile
    var savedRejectionGate: SavedTaskGate? = null

    /** 存档拒因成对上写：档位与话术同生同灭（漏一处=过期边认不出该作废哪条）。 */
    fun setSavedRejection(gate: SavedTaskGate?, copy: String?) {
        savedRejectionGate = gate
        savedRejection.value = copy
    }

    /**
     * 屏上「我的任务」列表：磁盘件的**镜像**，不是第二份真值——每次写操作后由 `SavedTaskStore` 从盘重读再发布，
     * 首进界面也重读一次（与 `ByokGateway.refreshFromVault` 同一形态）。判据一律在磁盘＋纯函数那一侧，
     * 这一格只负责"让 Compose 在增删后重画"。
     */
    val savedTasks = MutableStateFlow<List<SavedTask>>(emptyList())

    /**
     * 那次派发被拒时门禁判的是哪一档（S3-F/F1-2、F1-3）。与 [taskRejection] **必须同写**：
     * 过期边要认"这条红字是不是纯状态档"（RUNNING/COMPILING 都是），拿文本比对就是字符串当身份
     * （同 `RecorderStore.editRejectionGate` / `stopRejectionGate` 那条纪律）。
     *
     * 两种情形存 null，各自都有理由：
     * - 派发成功（撤红字）；
     * - V-3 那类**请求绑定**的拒（框内是作废的机器建议）：它绑用户那一次点击，由下一次派发覆盖，
     *   不许被状态跃迁悄悄抹掉。
     */
    @Volatile
    var taskRejectionGate: RecordGate? = null

    /** 派发拒因成对上写：档位与话术同生同灭（漏一处=过期边认不出该作废哪条）。 */
    fun setTaskRejection(gate: RecordGate?, copy: String?) {
        taskRejectionGate = gate
        taskRejection.value = copy
    }

    fun submit(json: String, plan: RepeatPlan = RepeatPlan()) {
        taskRequests.value = TaskRequest(System.nanoTime(), json, plan = plan)
    }

    fun isExpired(request: TaskRequest, nowMs: Long = System.currentTimeMillis()): Boolean =
        nowMs - request.submittedAtMs > TaskPolicy.TTL_MS

    /** 执行完毕/过期/忙中丢弃时作废请求（仅当队列头仍是该请求）：StateFlow 重放语义会在服务重绑时把旧任务再执行一次。 */
    fun consume(request: TaskRequest) {
        if (taskRequests.value?.id == request.id) taskRequests.value = null
    }
}
