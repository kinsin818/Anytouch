# S7-T1 · Gumroad 封面上传：只读侦察结果（未保存，公开页零变化）

日期：2026-09-26（本机 UTC+8 / 页面与服务器侧记 UTC）
执行窗：Anytouch 施工总窗（`D:\Anytouch`）
军令：`D:\Qoder\ops\orders\ANYTOUCH-S7-跨窗-封面上传与8443定性-ORDER-20260926.md` T1
老板裁决（本批约束，优先于军令原判据）：**"先只读侦察，不保存"** —— 进商家后台、定位 Cover 文件输入、
实测 `uid + filePath` 这条签名到底收不收文件，**绝不点 Save changes**。

## 0. 一句话结论

军令指定的第六路（`mcp__user-browser-use__upload_file` + `{uid, filePath}`）**在工具桥层就被拒**，
报错原文三次一字不差：`Invalid arguments for file_upload: name is not supported`。
该报错与我传的 uid 和参数组合无关（换两枚不同 uid、加/不加 `includeSnapshot` 均为同一句），
而 `additionalProperties:false` 又不允许我补任何字段 ⇒ 本窗**没有第六条路**，按军令"当场停下上报"处置。
公开页封面**仍为空**，本窗一个字节也没保存进去。

## 1. 素材字节核对（与军令所列逐枚对齐）

```
D:\Qoder\ops\evidence\s6-cover\cover-1.png  118,523 B  sha256 14b105df92f819da...  1280x720
D:\Qoder\ops\evidence\s6-cover\cover-2.png  112,230 B  sha256 ec6f8ce405e526ff...  1280x720
D:\Qoder\ops\evidence\s6-cover\cover-3.png  123,481 B  sha256 515c5f87cade2c86...  1280x720
```
尺寸取自 PNG IHDR 字节（`struct.unpack('>II', data[16:24])`），非文件名推断。三枚皆横向、≥1280x720，
满足后台 Cover 区自己写的规格（原文见 §3）。

## 2. 会话与路由事实（可复现路径）

- 登录态：真实 Chrome（`user-browser-use` 通道）已登录 Gumroad 商家，账号显示为 **"Xin Jin"**。
- 产品在册：`Anytouch - Android Accessibility Automation Tool`，permalink `lzecov`，
  公开页 `https://5787773983017.gumroad.com/l/lzecov`，状态 published，`0 sales`，价格 `6.99`。
- **直连 `/l/lzecov/edit` 不会进编辑器**：服务端把该 URL 渲染成**公开页**（页顶只给一枚 `link "Edit product"`），
  编辑器是点进去之后由 Inertia 客户端装载的。本窗第一次抓到的完整编辑器快照，正是走
  公开页 → `Edit product`（点击落点 449,88）→ 编辑器 这条路。
- 军令里那串 `X-54Z_vwekYz_wuexC8kSA==` 是 **API 形态的 product id**，不是 URL 路径；
  按它拼 `/products/<base64>/edit` 与 `/l/<base64>` 均 404（本窗与主窗各自踩过，口径一致）。

## 3. Cover 区当前状态：空（截图入档）

编辑器无障碍树原文（节选）：

```
[ref_101] header  -> [ref_103] heading "Cover"
                  -> [ref_104] link "Learn more"
[ref_105] div  "Upload images or videosImages should be horizontal, at least 1280x720px, and 72 DPI (dots per inch)."
[ref_106] button "Upload images or videos"
```

渲染证据：`raw/editor-cover-empty.png`（16,223 B，按 `uid=ref_100` 的 section 元素单独截图）——
只有一块虚线投放区 + 那枚按钮，**没有任何已上传缩略图** ⇒ 后台侧封面槽与公开页 `covers:[]` 一致。

## 4. 已试路径与逐条报错原文（本窗新增三次，全部未落盘任何字节）

| # | 动作 | 结果原文 |
|---|---|---|
| U1 | `upload_file {uid:"ref_106"(button), filePath:"D:\\...\\cover-1.png"}` | `Invalid arguments for file_upload: name is not supported` |
| U2 | `upload_file {uid:"ref_105"(wrapper div), filePath:同上}` | `Invalid arguments for file_upload: name is not supported` |
| U3 | `upload_file {uid:"ref_106", filePath:同上, includeSnapshot:false}` | `Invalid arguments for file_upload: name is not supported` |

三点判读：
1. 报错点名的是 **`name` 这个字段"not supported"** —— 本窗三次都**没有**传 `name`（schema 只允许 `uid`/`filePath`/`includeSnapshot`），
   所以这句是桥内部自己拼参数拼坏了，不是"我参数写错"。主窗上一轮的归因（"参数写错"）在此**更正**。
2. 换 uid（按钮 / 其父 div）与增减可选字段都不改变报错 ⇒ 与目标元素无关。
3. 即便桥修好，**当前也没有可寻址的文件输入 uid**：两次快照（`verbose:true` 315 元素全量 / 交互过滤 347 元素）
   里 Cover 区**不存在任何 `input[type=file]` 节点**——Gumroad 把它做成 `display:none`，永不进无障碍树。
   后台只暴露那枚 `button "Upload images or videos"`，点它 = 弹操作系统文件框 = 军令已排除的第 5 条死路
   （Computer Use 原生对话框撞 `browser_url_policy`），且与老板"只读侦察"不冲突但**本窗没点**，不制造挂起的原生框。

## 5. 为什么不能用 DOM 探针补一刀（工具通道实测）

- `mcp__user-browser-use__evaluate_script` 对**任何**函数都返回 `{}`：连 `() => document.title` 也是 `{}`；
  带 `filePath` 时落盘文件内容就是字面 `{}`。⇒ 该通道的 JS 求值不可用，无法用 `querySelectorAll('input[type=file]')`
  自证/自取 uid。（同一工具在 check-host.net 上曾正常回读过节点元数据，说明不是"页面问题"而是这条通道本身。）
- 内置浏览器（`browser-use` 通道，Playwright 系，其 `setInputFiles` 能吃隐藏 input）作为第六条路的替代：
  **对本靶完全不可达** —— 两次导航 `https://5787773983017.gumroad.com/l/lzecov/edit` 与 `/l/lzecov` 均
  `网页加载失败：ERR_CONNECTION_RESET`。且它没有商家登录态，即便可达也进不去后台。

## 6. 零保存的正证（公开页复拉对拍）

```
curl -sS https://5787773983017.gumroad.com/l/lzecov
HTTP=200 SIZE=32043
&quot;covers&quot;:[]            （1 处，仍为空数组）
&quot;thumbnail_url&quot;:null
```
与本批基线件（侦察前拉的 32,043 B）逐字节 `cmp`：唯一差异在第 **735** 字节，内容是
`<meta name="csrf-token" content="...">` 的值换了（每次请求都换）。把 csrf 归一化后两文件**完全等价**。
⇒ 价格、文案、封面、缩略图、`permalink` 等**一个字段都没动**。本窗全程未点 `Save changes`、未点 `Unpublish`。

## 7. 一处如实登记、不洗的观察

编辑器无障碍树里 `main` 元素带着一段横幅文案：`"Changes saved! Would you like to notify your customers about those changes? Skip for now / Send notification"`。
- 本窗**没有**点过任何保存类按钮（含该横幅里的两个按钮），这段是页面装载时就在那儿的文案；
- §6 的逐字节对拍证明：无论这段是哪一次留下的，**公开记录与侦察前完全一致**（只差 csrf），
  所以它没有代表本批产生的任何对外变更。"要不要通知客户"那两个按钮本窗一律未碰，属老板面。

## 8. 处置与请求（交回老板/主窗）

军令 T1 的判据是"三枚封面进 Cover 槽 + Save + 公开页 `covers` 非空"。**本窗做不到，且已按军令停在上报**：
主窗列出的 1–5 条死路本窗逐条复核为真，第 6 条（`uid+filePath`）经三次实测在桥层即拒，报错原文见 §4。
剩下唯一可行路径只有两条，都不在本窗权限内：
- **(a) 人的手**：老板（或有登录态的人）在后台 Cover 区把 `cover-1/2/3.png` 三枚**拖进虚线投放区**并点 Save ——
  素材已按后台规格（横向 ≥1280x720、72 DPI）备好，字节与 sha256 见 §1，直接可用；
- **(b) 修桥**：`user-browser-use` 的 `file_upload` 动作内部多塞了 `name` 字段，需该 MCP 服务端修；
  修好后仍需后台把 `input[type=file]` 暴露进无障碍树（或改用 CDP `DOM.setFileInputFiles` 白名单放行）才有 uid 可指。

本窗**没有**采用军令明令禁止的旁路（外链图片、base64 塞 textarea、直接 POST 商家接口）——一条都没有。
