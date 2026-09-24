# 军令：S5-b GitHub 建仓推送 + Release 首发
**日期**：2026-09-25
**项目根目录**：D:\Anytouch
**主目标**：将本地全仓代码推送到刚建好的 GitHub 公开仓库，打首个 Release 上传正式 APK，完成开源分发闭环。

## 第0节 不可协商工作流规则
1.  所有外发动作前先确认仓库地址：`https://github.com/kinsin818/Anytouch.git`，owner=kinsin818，公开仓库
2.  推送前必须执行：
    -  全仓扫描 Key 形态（nvapi-/sk- 等）零命中
    -  git status 干净，无未提交改动
    -  确认 LICENSE（GPLv3）、README.md（全英文）在根目录
3.  推送后必须验证：GitHub 网页端能看到全部分支、README 正常渲染、LICENSE 可见
4.  打 Release 时：tag 名 v1.0.0，标题 "Anytouch v1.0.0 - First Release"，上传正式签名 APK（不是 debug 包），Release 说明写清楚：
    -  核心功能：BYOK 视觉自动化、录制回放、AI 编译任务、三个预制模板
    -  已知限制：仅测试过小米 K40/K80 + AVD 31/34/35，Samsung/Moto 未验证
    -  隐私：API Key 仅存本机 Keystore，执行期零网络，数据不出设备
5.  禁止上传任何 Key、测试凭据、raw 日志里的敏感内容
6.  推送/打 Release 过程中如果需要登录授权，立刻停手喊老板，不要自己处理

## 第1节 执行步骤
1.  配置 git remote：`git remote add origin https://github.com/kinsin818/Anytouch.git`
2.  执行全仓敏感信息扫描，确认零 Key 残留
3.  推送 main 分支全量代码
4.  构建正式签名 release APK（不是 debug 包）
5.  在 GitHub 上打 v1.0.0 Release，上传 APK 文件
6.  完成后回报：仓库链接、Release 链接、APK 大小、提交数

## Definition Of Done
-  GitHub 仓库页面能看到完整代码、README、LICENSE
-  v1.0.0 Release 页面有可下载的 APK 文件
-  全仓扫描零敏感信息
-  工作树干净，所有改动已提交

## Hard Stop Conditions
-  推送时需要输入密码/二次验证 → 立刻停手喊老板
-  扫描发现任何 Key/敏感信息 → 立刻停手，先清理再推
-  Release 上传失败 → 回报错误，不要反复重试
