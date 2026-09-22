# S2 demo 录屏说明（type_text 输入链）

- 文件：`anytouch-s2-type-demo.mp4`（3,002,777 字节，adb screenrecord 于模拟器 API-34 一次连续录制，未剪辑）
- 脚本：`scripts/s2-demo-rec.sh`（可重跑；证据只追加不覆盖，重跑前请先归档本目录）
- 链路（3 步，全程零坐标、纯节点引用，执行期零网络）：
  1. click 节点 `Search settings`（Settings 首页搜索框）
  2. type_text 向同一节点写入 `wifi`（SET_TEXT 主通道 + 落字活读复核）
  3. click 搜索结果节点 `Wi-Fi`
- 机读回执：`receipt.txt` — `09-22 09:30:59.020 AnytouchRun: S1SMOKE ok=3 total=3 stopped=false`
  （录制起于 ~09:30:26，回执落在录制窗口内；录屏即该轮真实过程）
- 口径说明：本机无 ffmpeg/ffprobe，未做帧级校验；时长以 `--time-limit 90` 上限 + 回执时间戳交叉印证（实测录制约 33s + 停录收尾）。
