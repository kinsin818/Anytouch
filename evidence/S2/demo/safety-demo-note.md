# S2 安全链 demo（anytouch-s2-safety-demo.mp4）

- 生成：`bash scripts/s2-killdemo-rec.sh`（09-22 10:40 UTC，模拟器 MarvisPhone，4,159,091 B，≤90s 双幕）
- 幕 A（0:00-0:40 段）：Settings 搜索链命中 PASSWORD 高危词表 → 二次确认面板弹出 → 点"确认执行"
  → 放行进入 Passwords 页。回执 `S1SMOKE ok=3 total=3 stopped=false`。
- 幕 B（0:40-1:05 段）：同链面板再次弹出 → 挂起期点右侧悬浮停止球 → 面板/球即刻撤净、高危步零派发。
  回执 `stop="user_stop"` + `S1SMOKE-DETAIL code=USER_STOP msg=用户在二次确认等待期按下停止`（点球后 ≤3s）。
- 回执全文：`safety-receipt.txt`（两幕三条 S1SMOKE 行）。
- 口径声明：面板按钮/停止球点击为 `adb input tap` 测试通道模拟手指（同 device-smoke C6/C7），
  产品路径零坐标注入（红线 D grep 持续 PASS）；执行期零网络、截图不出设备。
- 本机无 ffmpeg，未做帧级校验；过程真实性以同窗 logcat 回执与脚本轮询记录互证。
- 关联：本 demo 覆盖的两条路径各有一颗已修设备雷（touch-modal 吞触点、kill 等待环盲区），
  详见 `evidence/S2/kill-ball-device.md` 与 `orders/METRICS.md` 主窗自纠四批。
