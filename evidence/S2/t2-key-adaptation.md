# T2 首批：NVIDIA NIM Key 适配 + 端到端"AI 编译 → 设备执行"（2026-09-22）

口径：本批属**创建期**（host 侧编译），与"执行期零网络"红线不冲突——设备侧执行链一行未动，
编译产物仍是纯节点 Action JSON，注入通道与手搓 task_json 完全同形。
`tools/compiler` 位于 ci-local 红线 A（core/）与红线 C（app/src/main）的扫描路径之外，
豁免口径已写入 `scripts/ci-local.sh` 头注；Key 只从环境变量或仓库外文件读入，任何路径不打印，
错误信息经 `nvapi-[A-Za-z0-9_-]+` → `nvapi-***` 脱敏后截断 200 字符。

## 1. Key 与模型目录核验

- 老板配给 15 把 nvapi Key（`D:\新建文件夹\key.txt`，仓库外）：**15/15 全部有效**（`GET /v1/models` 均 200）。
- **目录勘误（重要，需回禀老板）**：Key 文件附带的"角色→模型"映射表 7 个模型名
  （`z-ai/glm-5.2`、`qwen/qwen3-next-80b-a3b-instruct`、`minimaxai/minimax-m3`、
  `mistralai/mistral-medium-3.5-128b`、`qwen/qwen3.5-122b-a10b`、`deepseek-ai/deepseek-v4-pro`、
  `mistralai/mistral-large-3-675b-instruct-2512`）**在本账号目录中全部不存在**。
  实际可用（与本任务相关部分）：`z-ai/glm-5.3`、`z-ai/glm-5.3-flash`、
  `deepseek-ai/deepseek-v4.1-flash`、`deepseek-coder-6.7b`、`openai/gpt-oss-20b`、
  `mistral-large-2-instruct`、llama 系列 15 款；**无 qwen / minimax 任何条目**。
  定性：映射表来自另一账号/区域或已过期，不是 Key 失效。任何按表调用都会 4xx。
- 模型行为坑：`deepseek-ai/deepseek-v4.1-flash` 与 `glm-5.3` 均为推理模型——
  `max_tokens` 给小了 `content` 直接为 `None`（思考吃满预算，`reasoning_content` 是独立字段）；
  本工具固定 `max_tokens=2000`。`glm-5.3` 单次 >90s，编译探针默认改用 `deepseek-v4.1-flash`。

## 2. 编译链实现与 fail-closed 校验

`tools/compiler`（新增 Gradle 模块，host 侧 only，不进 APK）：
`LlmTransport` 接口 + `NvidiaNimTransport`（OpenAI 兼容 `/v1/chat/completions`，`temperature=0.0`）
+ `DslCompiler` + `ProbeMain` 探针入口。

`CompileResult` 只有两型：`Ok(actions, actionsJson)` / `Reject(stage, detail)`，**没有第三种"大概能用"**。
`Reject.stage` 覆盖 transport 异常、JSON 不可解析、校验不过三段。

校验跑在**原始 JSON 文本**上（不是解码后的对象），因为 `ContractJson` 带 `ignoreUnknownKeys=true`，
坐标字段解码后会被静默丢弃——只有查原文才能发现模型偷塞了 `target`：
非空数组、无 `target` 键、`value` 键黑名单（x/y/point/coordinate(s)/bounds/offset…）、
`action_id` 唯一非空、`type ∈ {click,type_text,scroll,key}`、`source=="node"`、
`safety.viewport_ok=true`（click 另需 `click_enabled=true`）、按类型校 `value` 必填项与枚举域。
JVM 12 例全绿，含"合法 JSON 但带坐标"的反例。

## 3. 本批挖到的雷：同一 prompt 两条路径两种结果（工具链编码盲区）

初版探针 `--intent <中文>` 走命令行，Kotlin 路径**稳定**返回
`REJECT stage=validate detail=空动作数组`；同期 python 从 `DslCompiler.kt` 里正则抽出**完全相同**的
SYSTEM 串直调同一模型，3/3 得到正确的 412 字符动作数组。
排错顺序正确（先证自家代码再疑平台）：先复核 prompt 内容一致 → 排除 trimIndent/温度/序列化，
定位到根因是**中文意图经 Git Bash → gradle `--args` 分词 → JVM 参数解码两段转码后成乱码**，
模型收到垃圾输入，按 prompt 的 fail-closed 语义输出 `[]`——即"拒编"是正确行为，错在输入通道。
修复：`ProbeMain` 增 `--intent-file`（`readText(Charsets.UTF_8)` 直读，优先于 `--intent`），
把命令行编码这个变量一次消掉；探针另在 stderr 回显 `PROBE model=… intent=…` 以便核对真实输入。

教训并入盲区清单：**"单测绿≠能看见"在跨语言/跨进程边界上同样成立——探针的输入通道本身是待验对象，
同一 prompt 两条投递路径必须对拍，否则会把工具链编码问题误诊成模型或 prompt 缺陷。**

prompt 迭代另记一坑：初版规则 7 写"目标文本不确定时宁可少输出，可输出 []"，模型据此对
"进入 Connected devices/Connection preferences/Bluetooth"这种**英文界面词已明示**的意图整链拒编；
改为规则 7"意图中出现的英文界面词就是屏幕上的可见文本，直接使用它们" + 规则 8 一条 few-shot 示例后归正。

## 4. 端到端设备实证（创建期 AI → 执行期零模型）

```
# host 编译（Key 经环境变量，不落盘）
export ANYTOUCH_NVIDIA_KEY=$(sed -n '1p' /d/新建文件夹/key.txt | tr -d '\r\n ')
./gradlew :tools:compiler:run --console=plain \
  --args="--intent-file D:/Anytouch/intent.tmp.txt"
# 意图：在英文界面的设置首页，依次进入 Connected devices、Connection preferences、Bluetooth 三个入口
# stdout（已过 12 项校验，可直接注入）：
[{"action_id":"cd","type":"click","source":"node","value":{"text":"Connected devices"},"safety":{"viewport_ok":true,"click_enabled":true}},
 {"action_id":"cp","type":"click","source":"node","value":{"text":"Connection preferences"},"safety":{"viewport_ok":true,"click_enabled":true}},
 {"action_id":"bt","type":"click","source":"node","value":{"text":"Bluetooth"},"safety":{"viewport_ok":true,"click_enabled":true}}]

# device 执行（与手搓 task_json 同一条注入通道）
adb shell am force-stop com.android.settings && adb shell am start -a android.settings.SETTINGS
adb logcat -c
adb shell "am start -f 536870912 -n com.anytouch.app/.MainActivity --es task_json '<上列压缩 JSON>'"
```

回执（09-22 12:23:42.677，pid 15827）：
`S1SMOKE ok=3 total=3 stopped=false stop=-`
截图 `t2-e2e-bluetooth-page.png`：落在 Bluetooth 二级页（Use Bluetooth 开启、Device name sdk_gphone64_x86_64），
三步导航真实落地，非假绿。**执行全程设备侧零网络、零模型调用、零坐标。**

## 5. 回归与门禁

- `:app` 238（=119×2 变体）、`:tools:compiler` 12、`:core:contracts` 19，全绿。
- `scripts/ci-local.sh` PASS：红线 A/B/C/D/E 全 clean（A/C 已确认未把 `tools/` 纳入扫描范围，豁免口径见脚本头注）。
- `scripts/device-smoke.sh` 9/9（本批未改设备侧代码，E2E 为增量证据）。
- Key 材料核验：本文件、git 索引、`build/` 输出中均无 `nvapi-` 串；`intent.tmp.txt`/`compiled-task.tmp.json`
  为探针脚手架临时件，随本批删除，重跑命令已在上文固化为可复制形态。

## 6. 待办（T2 余量）

- 把 `DslCompiler` 从"探针"接进真正的创建期 UI（S2 录制→编译→执行主链）。
- BYOK 三档实测（Gemini/GPT-4o-mini/Haiku）仍缺 Key，本批只覆盖 NVIDIA 单通道。
- 老板侧：确认映射表勘误来源（另一账号还是区域差异），否则角色分配表要按实际目录重写。
