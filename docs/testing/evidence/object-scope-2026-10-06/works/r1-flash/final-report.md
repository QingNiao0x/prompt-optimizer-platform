# 实际执行质量对照

正式上线放行：**未判定**。未评审不等于通过，HTTP 成功不等于业务正确。

| 模型与组 | 计划执行 | 成功正文 | 已评审 | 严重业务错误 | 胜/负/平/均失败 | 可比较数量 |
| --- | ---: | ---: | ---: | ---: | --- | ---: |
| deepseek:deepseek-flash|raw | 6 | 6 | 6 | 2 | 0/0/0/0 | 0 |
| deepseek:deepseek-flash|direct | 6 | 6 | 6 | 2 | 1/0/3/2 | 6 |
| deepseek:deepseek-flash|plan | 6 | 6 | 6 | 1 | 2/0/3/1 | 6 |
| deepseek:deepseek-flash|raw_matched | 3 | 3 | 3 | 1 | 0/0/0/0 | 0 |
| deepseek:deepseek-flash|direct_matched | 3 | 3 | 3 | 1 | 1/0/1/1 | 3 |
| deepseek:deepseek-v4-pro|raw | 6 | 6 | 6 | 2 | 0/0/0/0 | 0 |
| deepseek:deepseek-v4-pro|direct | 6 | 6 | 6 | 0 | 3/1/2/0 | 6 |
| deepseek:deepseek-v4-pro|plan | 6 | 6 | 6 | 1 | 1/1/3/1 | 6 |
| deepseek:deepseek-v4-pro|raw_matched | 3 | 3 | 3 | 1 | 0/0/0/0 | 0 |
| deepseek:deepseek-v4-pro|direct_matched | 3 | 3 | 3 | 1 | 0/1/1/1 | 3 |

配对固定同一题、同一执行模型、同一重复轮次和同一信息轨道；缺少输出/评审保持可见。增益只限本批样本，不表示增强一定更好。
