# Anytouch S1 能力演示（demo 视频证据）

- 文件：`anytouch-s1-demo.mp4`（模拟器 MarvisPhone 屏录，5.4MB，mp4/ftyp 已验）
- 内容：一条 3 步节点树任务（Connected devices → Connection preferences → Bluetooth）被 AI 编译后的 JSON 下发，无障碍执行器**自主连跑 3 轮，3/3 全 OK**（回执行判定 `S1SMOKE ok=3 total=3 stopped=false`）。
- 口径：模拟器口径；真机 ≥95% 属 T3。执行期零网络、零模型调用、零坐标定位。
- 复现：仓库根 `bash scripts/s1-demo-rec.sh`（前置：AVD 已启动 + `./gradlew :app:assembleDebug`）
- 拍摄踩坑留痕（模拟器 ROM）：screenrecord 写 `/sdcard` 静默失败（媒体卷权限），改写 `/data/local/tmp` 成功；Git-Bash MSYS 会把 adb 参数里的 `/data/...` 转成盘符路径，凡带设备路径的 adb 调用一律 `MSYS_NO_PATHCONV=1`。
- 针对质疑："一天开发不出 demo"——本视频拍摄于说这句话的当天，展示的是 S1 已验收能力（commit `0c5ebbc`/`a3722fd`），无一行新代码。

拍摄时间：2026-09-22（主窗）
