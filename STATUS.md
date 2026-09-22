# Anytouch 项目状态（总窗口读取点）

> 协议：任何上位总窗口读本文件即获得全局进度，无需读对话。每次里程碑由施工窗（Qoder）更新。
> 本文件 = 施工窗 → 总窗口的唯一汇报口。细证据不进此文件，只放指针。

## 一句话进度

S0 工程奠基已完成并 commit（2026-09-22，2 worker / 0 拒收）；等待 S1 开工指令与 T2/T3 资源配给。

## 阶段面板

| Stage | 状态 | 门禁 | 指针 |
|---|---|---|---|
| S0 奠基+契约+CI | ✅ 完成 | — | `docs/ANYTOUCH-S0-final-report.md`，commits de3d6b4→2c7ddf1 |
| S1 核心执行器 | ⏸ 待开工（框架部分无门禁，可随时开） | T1 已完成 | `orders/`（S1 军令未写） |
| S2 录制 | 🔒 门票门禁 | T2（需模型 Key） | `orders/RULINGS-20260922.md` P0-1 |
| S3 兜底 | 🔒 门票门禁 | T3（需真机，含 MediaProjection 判官项） | 同上 P0-3 |
| S4 上线 | 🔒 门票门禁 | T-US + 模板合规预检 | 同上 P0-4 |
| W 轨 Windows 先行验证 | 🔒 老板拍板人力 | — | RULINGS P1-2 |

## 老板待办（阻塞项）

1. T2 配给：BYOK 测试模型 Key（Gemini/GPT-4o-mini/Haiku 三档）
2. T3 配给：真机预算（三星×2/Pixel×2/摩托×1/小米国际×1，二手 ¥3–5k）
3. P0-2 裁决确认：免费/付费边界按冻结共识执行中，如需翻案须老板明示
4. 两个 S0 worker 会话可归档（UI 操作）

## 纪律数据

`orders/METRICS.md`：交付 2 / 拒收 0 / Retry 0；过程自纠 3 处均有留痕。
治理文件：军令协议 `orders/ANYTOUCH-S0-ORDER.md` 为范本；裁决案卷 `orders/RULINGS-20260922.md`。
