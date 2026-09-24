# S5-R4 · NIM 角色→模型映射表重写（实测明细，2026-09-24 晚）

> 老板令原文（逐字，见 `orders/RULINGS-20260922.md` S5 批表）：
> **"之前那 7 个错的 NIM 模型名映射表，让 Qoder 直接按咱们实测可用的 8 个模型改了"**
> 靶文件：`D:\新建文件夹\key.txt`（**仓库外**，Key 永不入 git——本文件与 git 全域只出现模型名与 HTTP 码，无 Key 字面）。
> 原表备份：`D:\新建文件夹\key.txt.bak-20260924`（改前整文件复制，可回退）。

## 1. 实测方法（只读探针，零产品额度）

- `GET /v1/models`：账号目录 82 款在册（key#1，HTTP 200）。
- 逐款 `POST /v1/chat/completions`（`max_tokens=1`，"say ok"）坐实"可调用"——**目录在册 ≠ 可调用**，
  本晚去重实探 **29 款：200 计 10、404 计 17、503 计 1（ultra-550b 容量）、超时未坐实 1（kimi-k3）**——
  404 的 17 款全部目录在册（含 `deepseek-ai/deepseek-coder-6.7b-instruct`、`mistralai/mistral-large-2-instruct`、
  `mistralai/mistral-large` 等），即 09-22 那张坏表的同族形态：**光对目录写表还会再坏一次**。故全表只用 200 实测过的名字。
- 探针属 host 侧诊断（同 09-22 的 15/15 验真口径），**不占 S4 批账（6/8 定格不动）、不动产品代码**。

## 2. 实测结果账（本晚 key#1）

| 模型 | HTTP | 处置 |
|---|---|---|
| z-ai/glm-5.3 | 200 | **入表**（GOLink二审） |
| z-ai/glm-5.3-flash | 200 | **入表**（左舵观察） |
| deepseek-ai/deepseek-v4.1-flash | 200 | **入表**（左舵兜底诊断） |
| openai/gpt-oss-20b | 200 | **入表**（GOLink初审） |
| mistralai/mistral-nemotron | 200（首探 000 超时，170s 复探成） | **入表**（右书指挥摘要） |
| google/gemma-4-31b-it | 200（首探 000，复探成） | **入表**（左舵页面分类） |
| nvidia/nemotron-3-super-120b-a12b | 200 | **入表**（GOLink写代码） |
| nvidia/nemotron-3.5-lightning-30b-a3b | 200 | **备用第 8 款**（未写入表，注释会破坏老板侧解析器，故只登记于此） |
| meta/muse-glimmer-30b | 200 | 实测可用，领域未知未选 |
| nvidia/nemotron-3-nano-omni-30b-a3b-reasoning | 200 | 实测可用，推理档未选 |
| moonshotai/kimi-k3 | 000（170s 超时两次） | 未坐实，不选 |
| nvidia/nemotron-3-ultra-550b-a55b | 503（容量不足，复探仍 503） | 不选 |
| nvidia/nemotron-4-340b-instruct、llama-3.1-nemotron 51b/70b/ultra、mixtral-8x22b、yi-large、jamba-1.5-large、gemma-3-12b、phi-3.5-moe、granite-3.0-8b、zamba2、codestral-22b、mistral-large、mistral-large-2-instruct、deepseek-coder-6.7b-instruct、codellama-70b | 404 | 在册不可调用，一律不选 |

**与老板"8 个"的口径差（照实不糊）**：本窗实测 200 共 **10 款**；表用 7 行 + 备用 1 款 = 8 款——
"8"按"表可用池"落齐，10 款全量登记在上表，谁多谁少以本表为准，不倒推口径。

## 3. 新表磁盘现值（key.txt 第 18~24 行，逐字）

```
左舵观察      z-ai/glm-5.3-flash
左舵兜底诊断  deepseek-ai/deepseek-v4.1-flash
左舵页面分类  google/gemma-4-31b-it
右书指挥摘要  mistralai/mistral-nemotron
GOLink写代码  nvidia/nemotron-3-super-120b-a12b
GOLink初审    openai/gpt-oss-20b
GOLink二审    z-ai/glm-5.3
```

- 角色名逐字未动（那是老板侧项目的分配面）；**角色→能力档位为本次分配，老板可一句话重排**。
- 改后复点：`grep -c nvapi` = **15**（Key 行零改动）；旧 7 错名（glm-5.2/qwen×2/minimax/medium-3.5/v4-pro/large-3-675b）盘上残留=**0**。

## 4. STATUS 待办 1 结案注

"需老板回禀：表来自另一账号/区域还是已过期？"——**不再需要考据来源**（S5-R4 直接令改表）；
定性维持 09-22 判断（表非本账号目录），勘误回禀随本文件+RULINGS S5-R4 行**结案**。
