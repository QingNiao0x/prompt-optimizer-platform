# 本轮证据说明

- `manifest.json`：冻结的 622 个输入路径、字节数及 SHA-256；仅仓库材料，不包含真实用户资料、密钥或浏览器历史。
- `comparison-msedge.json` / `comparison-chrome.json`：ABBA 顺序的完整阶段计时及完整性结果。
- `baseline.json`：早期单轮原件，尾部检查器曾误报，保留不覆盖；原因见上级报告。
- `before.js` / `after.js`、`bundle-hashes.json`：性能对照实际运行的基线和候选索引代码，不含测试输入正文。候选性能改动仅 `projectIndexer.ts`，其他多选/权限修改不计入性能结论。
- `run.mjs` / `entry.ts`：原执行脚本及打包入口。重跑时在仓库根将本目录的脚本、清单和两个 bundle 复制到 `tmp/index-perf-20261116`，执行 `node tmp/index-perf-20261116/run.mjs --compare`；通过 `INDEX_BROWSER=chrome` 或 `msedge` 选择浏览器。需要已安装前端依赖及对应浏览器，端口 5221 空闲。运行会创建隔离测试项目并在每轮结束删除该测试项目，不操作用户站点数据。输入变更时从基线 `e18a1171` 读取并核对哈希，无法一致则失败，不静默更换材料。
- `environment.json`：本机版本、基线和测量边界。
- `source-hashes.json`：最终产品改动文件的哈希；所属 Git 提交提供完整差异。
- `verification.json`：最终检查汇总，失败历史未以复跑成功覆盖。
- `folder-selection-desktop.png` / `folder-selection-mobile.png`：Chrome 实际界面截图；手机为设备尺寸模拟。顶部“该文件夹已在选择清单中”是重复选择的临时反馈。

身份及模型/分析回包使用合成夹具。本轮无付费模型调用，无后端真实文档解析验收。窗口选择使用 filechooser 或 OPFS 原生句柄夹具，未验证系统原生目录窗口中的多选（该接口一次返回一个目录）。
