这是一个可直接在浏览器中离线使用的单文件HTML工具。它能把您粘贴的多行文字中的空行（包括只含空格或制表符的行）去掉，同时保留非空行的首尾空格、行内字符和原有顺序。
```html
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>空行清除 · 离线小工具</title>
  <style>
    /* 全局重置与字体 */
    * {
      box-sizing: border-box;
      margin: 0;
      padding: 0;
    }

    body {
      background: #f7f9fc;
      font-family: system-ui, -apple-system, 'Segoe UI', Roboto, 'Helvetica Neue', sans-serif;
      display: flex;
      justify-content: center;
      align-items: center;
      min-height: 100vh;
      padding: 1.5rem;
      margin: 0;
    }

    .tool-card {
      max-width: 1200px;
      width: 100%;
      background: #ffffff;
      border-radius: 28px;
      box-shadow: 0 20px 40px -12px rgba(0, 20, 30, 0.25), 0 8px 24px -8px rgba(0, 0, 0, 0.1);
      padding: 2rem 2rem 2rem 2rem;
      transition: all 0.2s ease;
    }

    h1 {
      font-size: 1.9rem;
      font-weight: 600;
      letter-spacing: -0.02em;
      color: #0b2b3c;
      display: flex;
      align-items: center;
      gap: 0.5rem;
      margin-bottom: 0.25rem;
    }

    .subhead {
      color: #4a6572;
      font-size: 0.95rem;
      margin-bottom: 1.75rem;
      border-left: 4px solid #a0c4d8;
      padding-left: 1rem;
      line-height: 1.5;
    }

    .subhead strong {
      font-weight: 600;
      color: #1e4a5f;
    }

    /* 双栏布局 */
    .panes {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: 1.5rem;
      margin-bottom: 1.5rem;
    }

    @media (max-width: 700px) {
      .panes {
        grid-template-columns: 1fr;
        gap: 1rem;
      }
      .tool-card {
        padding: 1.5rem;
      }
      h1 {
        font-size: 1.6rem;
      }
    }

    .pane {
      display: flex;
      flex-direction: column;
    }

    .pane-header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      margin-bottom: 0.5rem;
      padding: 0 0.25rem;
    }

    .pane-header label {
      font-weight: 600;
      font-size: 0.95rem;
      color: #1e4a5f;
      letter-spacing: 0.3px;
      display: flex;
      align-items: center;
      gap: 0.4rem;
    }

    .pane-header label span {
      background: #e3eef5;
      border-radius: 30px;
      padding: 0.15rem 0.6rem;
      font-size: 0.75rem;
      font-weight: 500;
      color: #1e4a5f;
    }

    .badge {
      font-size: 0.75rem;
      background: #eef3f7;
      color: #2e5a6e;
      padding: 0.2rem 0.7rem;
      border-radius: 30px;
      font-weight: 500;
    }

    textarea {
      width: 100%;
      min-height: 280px;
      padding: 1rem 1.2rem;
      font-family: 'SF Mono', 'Fira Code', 'Cascadia Code', 'Consolas', 'Courier New', monospace;
      font-size: 0.95rem;
      line-height: 1.6;
      border: 1.5px solid #d9e4ec;
      border-radius: 18px;
      background: #ffffff;
      resize: vertical;
      transition: border 0.15s, box-shadow 0.15s;
      color: #0b2b3c;
      box-shadow: inset 0 2px 4px rgba(0, 0, 0, 0.01);
      outline: none;
      white-space: pre;
      overflow-wrap: normal;
      overflow-x: auto;
    }

    textarea:focus {
      border-color: #6fa3c0;
      box-shadow: 0 0 0 4px rgba(111, 163, 192, 0.15);
    }

    textarea[readonly] {
      background: #f9fcff;
      border-color: #cbdae6;
      color: #1a3e4f;
      cursor: default;
    }

    /* 按钮区域 */
    .actions {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      justify-content: space-between;
      gap: 1rem;
      margin-top: 0.5rem;
    }

    .button-group {
      display: flex;
      flex-wrap: wrap;
      gap: 0.75rem;
    }

    button {
      background: #ffffff;
      border: 1.5px solid #cbdae6;
      color: #1e4a5f;
      font-weight: 500;
      font-size: 0.95rem;
      padding: 0.7rem 1.4rem;
      border-radius: 40px;
      cursor: pointer;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      gap: 0.5rem;
      transition: all 0.15s ease;
      box-shadow: 0 2px 4px rgba(0, 0, 0, 0.02);
      font-family: inherit;
      line-height: 1.2;
      white-space: nowrap;
    }

    button:hover {
      background: #f2f8fd;
      border-color: #8bb3cc;
      box-shadow: 0 4px 8px rgba(0, 40, 60, 0.05);
    }

    button:active {
      transform: scale(0.97);
      background: #e6f0f7;
    }

    button.primary {
      background: #1e4a5f;
      border-color: #1e4a5f;
      color: white;
      box-shadow: 0 6px 12px -4px rgba(20, 60, 80, 0.3);
    }

    button.primary:hover {
      background: #123b4d;
      border-color: #123b4d;
    }

    button.primary:active {
      background: #0b2e3d;
    }

    button:disabled {
      opacity: 0.45;
      pointer-events: none;
      filter: grayscale(0.3);
    }

    .stats {
      font-size: 0.85rem;
      color: #4a6572;
      background: #f0f6fb;
      padding: 0.4rem 1rem;
      border-radius: 40px;
      display: flex;
      align-items: center;
      gap: 0.5rem;
      white-space: nowrap;
    }

    .stats strong {
      font-weight: 700;
      color: #1e4a5f;
    }

    .footnote {
      margin-top: 1.5rem;
      font-size: 0.8rem;
      color: #6b8a9c;
      text-align: center;
      border-top: 1px solid #e2edf5;
      padding-top: 1.2rem;
      display: flex;
      justify-content: center;
      gap: 1.5rem;
      flex-wrap: wrap;
    }

    .footnote span {
      display: inline-flex;
      align-items: center;
      gap: 0.3rem;
    }

    /* 小图标用字符代替，无外部依赖 */
    .icon {
      font-size: 1.1rem;
      line-height: 1;
    }

    /* 复制成功临时反馈 */
    .copy-feedback {
      position: fixed;
      bottom: 2rem;
      left: 50%;
      transform: translateX(-50%);
      background: #1e4a5f;
      color: white;
      padding: 0.6rem 1.5rem;
      border-radius: 40px;
      font-size: 0.9rem;
      box-shadow: 0 8px 20px rgba(0, 0, 0, 0.2);
      opacity: 0;
      transition: opacity 0.2s;
      pointer-events: none;
      z-index: 100;
    }

    .copy-feedback.show {
      opacity: 1;
    }
  </style>
</head>
<body>
  <div class="tool-card">
    <h1>
      <span class="icon">🧹</span> 空行清除
    </h1>
    <div class="subhead">
      <strong>只含空格或制表符的行</strong>也会被移除 · 非空行首尾空格、行内字符与顺序<strong>完全保留</strong>
    </div>

    <div class="panes">
      <!-- 输入区 -->
      <div class="pane">
        <div class="pane-header">
          <label>
            <span class="icon">📋</span> 原始文本
          </label>
          <span class="badge" id="inputLineCount">0 行</span>
        </div>
        <textarea id="inputArea" placeholder="在此粘贴多行文字…&#10;&#10;空行（包括只含空格/制表符的行）会被移除。&#10;非空行原样保留。"></textarea>
      </div>

      <!-- 输出区 -->
      <div class="pane">
        <div class="pane-header">
          <label>
            <span class="icon">✨</span> 清理结果
          </label>
          <span class="badge" id="outputLineCount">0 行</span>
        </div>
        <textarea id="outputArea" readonly placeholder="清理后的文本将显示在这里…"></textarea>
      </div>
    </div>

    <!-- 操作栏 -->
    <div class="actions">
      <div class="button-group">
        <button id="clearBtn" title="清空输入和输出">
          <span class="icon">🗑️</span> 清空
        </button>
        <button id="copyBtn" class="primary" title="复制清理结果到剪贴板">
          <span class="icon">📄</span> 复制结果
        </button>
      </div>
      <div class="stats" id="statsDisplay">
        <span class="icon">📊</span> 移除 <strong id="removedCount">0</strong> 个空行
      </div>
    </div>

    <div class="footnote">
      <span>🔒 完全离线 · 不上传 · 不保存</span>
      <span>⚡ 实时处理</span>
    </div>
  </div>

  <!-- 复制反馈浮层 -->
  <div id="copyFeedback" class="copy-feedback">已复制到剪贴板</div>

  <script>
    (function() {
      'use strict';

      // DOM 元素
      const inputArea = document.getElementById('inputArea');
      const outputArea = document.getElementById('outputArea');
      const inputLineCount = document.getElementById('inputLineCount');
      const outputLineCount = document.getElementById('outputLineCount');
      const removedCountSpan = document.getElementById('removedCount');
      const clearBtn = document.getElementById('clearBtn');
      const copyBtn = document.getElementById('copyBtn');
      const copyFeedback = document.getElementById('copyFeedback');

      // 工具函数：判断一行是否为空行（只含空格或制表符，或完全为空）
      function isEmptyLine(line) {
        // 使用正则：行内只包含空格(\x20)或制表符(\t)或为空
        // 注意：\s 会包含换行等，但这里 line 不包含换行符，所以用 /^[ \t]*$/ 更精确
        return /^[ \t]*$/.test(line);
      }

      // 核心处理：按行分割，过滤空行，保留非空行原样（包括首尾空格）
      function removeEmptyLines(text) {
        if (text === '') {
          return { result: '', removed: 0, totalLines: 0, keptLines: 0 };
        }

        // 按换行符分割。注意：需要处理 \r\n 和 \n 以及 \r
        // 为了保留原始行内容（包括可能的 \r），我们使用 split(/\r?\n|\r/) 但这样会丢失 \r 信息
        // 但需求是“非空行的首尾空格、行内字符和原有顺序都要保留”，换行符本身不是行内容的一部分。
        // 通常粘贴的文本换行符为 \n 或 \r\n，我们统一按行分割，保留每行的内容（不含换行符）。
        // 使用 split(/\r\n|\r|\n/) 可以兼容所有换行风格，但会丢失原始换行符类型。
        // 但输出时我们使用 \n 连接，这是合理的，因为工具目的是清理空行，换行符统一为 \n 不影响内容。
        // 如果用户希望保留原始换行风格，可以更复杂，但通常不需要。这里采用 \n 连接。
        const lines = text.split(/\r\n|\r|\n/);
        const totalLines = lines.length;

        // 过滤：保留非空行（即不是只含空格或制表符的行）
        const keptLines = lines.filter(line => !isEmptyLine(line));
        const removed = totalLines - keptLines.length;

        // 用换行符连接保留的行（使用 \n 作为标准换行）
        const result = keptLines.join('\n');

        return {
          result,
          removed,
          totalLines,
          keptLines: keptLines.length
        };
      }

      // 更新界面：处理输入并显示结果
      function updateOutput() {
        const rawText = inputArea.value;

        // 计算输入行数（用于显示）
        let inputLines = 0;
        if (rawText !== '') {
          // 同样按换行分割，但注意末尾换行会产生一个空行，这是符合直觉的
          inputLines = rawText.split(/\r\n|\r|\n/).length;
        } else {
          inputLines = 0;
        }
        inputLineCount.textContent = `${inputLines} 行`;

        // 处理空行
        const { result, removed, keptLines } = removeEmptyLines(rawText);

        // 更新输出区域
        outputArea.value = result;

        // 更新输出行数显示
        // 注意：如果 result 为空字符串，行数应为 0；否则按 \n 分割计算行数
        let outputLines = 0;
        if (result !== '') {
          outputLines = result.split('\n').length;
        } else {
          outputLines = 0;
        }
        outputLineCount.textContent = `${outputLines} 行`;

        // 更新移除的空行数量
        removedCountSpan.textContent = removed;

        // 复制按钮状态：如果结果为空，可以禁用复制（但也可以复制空字符串，这里保留可用，但视觉上提示）
        // 不强制禁用，因为复制空字符串也是合理操作。但为了体验，如果结果为空且输入也为空，可禁用？
        // 保持可用，用户可能想复制空内容。
      }

      // 清空所有内容
      function clearAll() {
        inputArea.value = '';
        outputArea.value = '';
        inputLineCount.textContent = '0 行';
        outputLineCount.textContent = '0 行';
        removedCountSpan.textContent = '0';
        inputArea.focus();
      }

      // 复制结果到剪贴板
      async function copyResult() {
        const textToCopy = outputArea.value;

        // 如果结果为空，仍然可以复制空字符串，但给出提示更友好
        if (textToCopy === '') {
          // 可以复制空字符串，但用户可能期望有内容。这里仍然复制，但反馈提示“结果为空”
          // 为了不混淆，我们仍然复制空字符串，但显示不同反馈？简单起见，统一显示“已复制”
          // 但若结果为空，复制空字符串可能让用户困惑，所以显示“结果为空，未复制”？
          // 更合理：如果结果为空，不执行复制，提示“没有可复制的内容”
          if (inputArea.value === '') {
            showFeedback('没有可复制的内容');
          } else {
            // 输入非空但结果为空（所有行都是空行），复制空字符串也是合理的，但提示一下
            try {
              await navigator.clipboard.writeText('');
              showFeedback('已复制空结果');
            } catch (err) {
              fallbackCopy('');
            }
          }
          return;
        }

        try {
          await navigator.clipboard.writeText(textToCopy);
          showFeedback('已复制到剪贴板');
        } catch (err) {
          // 降级方案
          fallbackCopy(textToCopy);
        }
      }

      // 降级复制方法（针对旧浏览器或权限问题）
      function fallbackCopy(text) {
        try {
          // 创建临时 textarea
          const tempTextArea = document.createElement('textarea');
          tempTextArea.value = text;
          tempTextArea.style.position = 'fixed';
          tempTextArea.style.opacity = '0';
          tempTextArea.style.pointerEvents = 'none';
          document.body.appendChild(tempTextArea);
          tempTextArea.select();
          tempTextArea.setSelectionRange(0, 99999); // 移动端兼容
          const success = document.execCommand('copy');
          document.body.removeChild(tempTextArea);
          if (success) {
            showFeedback('已复制到剪贴板');
          } else {
            showFeedback('复制失败，请手动选择复制');
          }
        } catch (e) {
          showFeedback('复制失败，请手动选择复制');
        }
      }

      // 显示临时反馈
      let feedbackTimer = null;
      function showFeedback(message) {
        copyFeedback.textContent = message;
        copyFeedback.classList.add('show');
        if (feedbackTimer) {
          clearTimeout(feedbackTimer);
        }
        feedbackTimer = setTimeout(() => {
          copyFeedback.classList.remove('show');
          feedbackTimer = null;
        }, 1800);
      }

      // 初始化事件监听
      function init() {
        // 输入时实时更新
        inputArea.addEventListener('input', updateOutput);

        // 清空按钮
        clearBtn.addEventListener('click', clearAll);

        // 复制按钮
        copyBtn.addEventListener('click', copyResult);

        // 初始更新（处理可能存在的浏览器自动填充或初始值）
        updateOutput();

        // 可选：输入框获得焦点时，如果为空，不自动填充
        // 无其他操作
      }

      // 页面加载后初始化
      if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
      } else {
        init();
      }
    })();
  </script>
</body>
</html>
```
### 核心功能与操作说明

您只需在左侧输入区粘贴文本，右侧就会实时显示清理后的结果。它的处理逻辑非常直接：

- **空行判定**：工具会逐行检查，如果一行完全为空，或者只包含空格（` `）或制表符（`\t`），就会被视为空行并移除。
- **内容保留**：所有非空行都会原样保留，包括行首和行尾的空格、行内的所有字符以及它们之间的顺序。
- **实时反馈**：输入时，右侧结果和顶部的行数统计会立即更新，同时会显示移除了多少个空行。

### 交互与反馈设计

界面分为左右两栏，操作按钮集中在底部，方便您快速使用：

- **清空按钮**：一键清除输入和输出区域的内容。
- **复制结果按钮**：将右侧清理后的文本复制到剪贴板，并会有短暂的“已复制”提示。
- **状态提示**：每个区域上方都会显示当前的行数，底部则会统计移除的空行数量，让您对处理结果一目了然。