这是一个可直接在浏览器中离线使用的单文件 HTML 工具，用于删除多行文本中的空行（包括仅含空格或制表符的行），并保留非空行原样。
```html
<!DOCTYPE html>
<html lang="zh-CN">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>空行删除工具 · 离线版</title>
  <style>
    /* 全局重置与字体 —— 不依赖任何外部资源 */
    * {
      box-sizing: border-box;
      margin: 0;
      padding: 0;
    }

    body {
      background: #f5f7fa;
      font-family: system-ui, -apple-system, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif;
      display: flex;
      justify-content: center;
      align-items: center;
      min-height: 100vh;
      padding: 1.5rem;
      margin: 0;
      line-height: 1.5;
      color: #1e293b;
    }

    .app-container {
      max-width: 1100px;
      width: 100%;
      background: #ffffff;
      border-radius: 24px;
      box-shadow: 0 20px 35px -8px rgba(0, 0, 0, 0.1), 0 8px 12px -6px rgba(0, 0, 0, 0.05);
      padding: 2rem 2rem 2.2rem;
      transition: all 0.2s;
    }

    h1 {
      font-size: 1.9rem;
      font-weight: 600;
      letter-spacing: -0.02em;
      margin-bottom: 0.25rem;
      color: #0f172a;
      display: flex;
      align-items: center;
      gap: 0.5rem;
    }

    .subhead {
      font-size: 0.95rem;
      color: #475569;
      margin-bottom: 1.8rem;
      border-left: 4px solid #3b82f6;
      padding-left: 1rem;
      background: #f8fafc;
      border-radius: 0 8px 8px 0;
      padding: 0.6rem 1rem;
    }

    .subhead code {
      background: #e2e8f0;
      padding: 0.15rem 0.4rem;
      border-radius: 6px;
      font-size: 0.9em;
      font-family: ui-monospace, 'SF Mono', 'Cascadia Code', 'Consolas', monospace;
      color: #0f172a;
    }

    .editor-grid {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: 1.5rem;
      margin-bottom: 1.5rem;
    }

    @media (max-width: 700px) {
      .editor-grid {
        grid-template-columns: 1fr;
        gap: 1.2rem;
      }
      .app-container {
        padding: 1.5rem;
      }
      h1 {
        font-size: 1.6rem;
      }
    }

    .panel {
      display: flex;
      flex-direction: column;
      min-width: 0;
    }

    .panel-header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      margin-bottom: 0.6rem;
      flex-wrap: wrap;
      gap: 0.5rem;
    }

    .panel-title {
      font-weight: 600;
      font-size: 1rem;
      color: #1e293b;
      display: flex;
      align-items: center;
      gap: 0.4rem;
    }

    .panel-title span {
      font-weight: 400;
      font-size: 0.8rem;
      color: #64748b;
      background: #f1f5f9;
      padding: 0.15rem 0.6rem;
      border-radius: 20px;
    }

    textarea {
      width: 100%;
      min-height: 280px;
      padding: 1rem 1.2rem;
      font-family: ui-monospace, 'SF Mono', 'Cascadia Code', 'Consolas', 'Liberation Mono', monospace;
      font-size: 0.95rem;
      line-height: 1.6;
      border: 1.5px solid #e2e8f0;
      border-radius: 16px;
      background: #ffffff;
      resize: vertical;
      transition: border-color 0.15s, box-shadow 0.15s;
      outline: none;
      color: #0f172a;
      tab-size: 4;
      white-space: pre;
      overflow-wrap: normal;
      overflow-x: auto;
    }

    textarea:focus {
      border-color: #3b82f6;
      box-shadow: 0 0 0 4px rgba(59, 130, 246, 0.15);
    }

    textarea::placeholder {
      color: #94a3b8;
      font-family: system-ui, -apple-system, sans-serif;
      font-size: 0.9rem;
      white-space: normal;
    }

    /* 结果区域只读，但可选中复制 */
    #outputArea {
      background: #f8fafc;
      border-color: #cbd5e1;
      color: #0f172a;
      cursor: text;
    }

    #outputArea:focus {
      border-color: #3b82f6;
      box-shadow: 0 0 0 4px rgba(59, 130, 246, 0.1);
    }

    .action-bar {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      justify-content: space-between;
      gap: 1rem;
      margin-top: 0.5rem;
    }

    .btn-group {
      display: flex;
      flex-wrap: wrap;
      gap: 0.8rem;
      align-items: center;
    }

    button {
      font-family: inherit;
      font-size: 0.95rem;
      font-weight: 500;
      padding: 0.7rem 1.6rem;
      border-radius: 40px;
      border: none;
      cursor: pointer;
      transition: background 0.15s, transform 0.1s, box-shadow 0.15s;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      gap: 0.4rem;
      background: #ffffff;
      color: #1e293b;
      border: 1.5px solid #e2e8f0;
      box-shadow: 0 1px 2px rgba(0, 0, 0, 0.02);
      white-space: nowrap;
    }

    button:active {
      transform: scale(0.97);
    }

    button.primary {
      background: #3b82f6;
      border-color: #3b82f6;
      color: white;
      box-shadow: 0 4px 10px -2px rgba(59, 130, 246, 0.3);
    }

    button.primary:hover {
      background: #2563eb;
      border-color: #2563eb;
    }

    button.secondary {
      background: #ffffff;
      border-color: #cbd5e1;
      color: #1e293b;
    }

    button.secondary:hover {
      background: #f8fafc;
      border-color: #94a3b8;
    }

    button:disabled {
      opacity: 0.5;
      cursor: not-allowed;
      transform: none;
      box-shadow: none;
    }

    .copy-feedback {
      font-size: 0.85rem;
      color: #16a34a;
      font-weight: 500;
      display: inline-flex;
      align-items: center;
      gap: 0.3rem;
      opacity: 0;
      transition: opacity 0.2s;
      margin-left: 0.5rem;
    }

    .copy-feedback.show {
      opacity: 1;
    }

    .stats {
      font-size: 0.85rem;
      color: #64748b;
      background: #f1f5f9;
      padding: 0.4rem 1rem;
      border-radius: 30px;
      white-space: nowrap;
    }

    .stats strong {
      color: #0f172a;
      font-weight: 600;
    }

    .footnote {
      margin-top: 1.8rem;
      font-size: 0.8rem;
      color: #94a3b8;
      text-align: center;
      border-top: 1px solid #e2e8f0;
      padding-top: 1.2rem;
    }

    /* 自定义滚动条（不影响功能） */
    textarea::-webkit-scrollbar {
      width: 10px;
      height: 10px;
    }
    textarea::-webkit-scrollbar-track {
      background: #f1f5f9;
      border-radius: 10px;
    }
    textarea::-webkit-scrollbar-thumb {
      background: #cbd5e1;
      border-radius: 10px;
      border: 2px solid #f1f5f9;
    }
    textarea::-webkit-scrollbar-thumb:hover {
      background: #94a3b8;
    }
  </style>
</head>
<body>
  <div class="app-container">
    <h1>
      <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="#3b82f6" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="display:inline-block; vertical-align:middle;">
        <path d="M4 6h16M4 12h16M4 18h10" />
        <path d="M18 18l2 2 4-4" stroke="#3b82f6" />
      </svg>
      空行删除工具
    </h1>
    <div class="subhead">
      删除所有空行（包括仅含空格或制表符的行）。非空行保持原样：首尾空格、行内字符、顺序均不变。
    </div>

    <div class="editor-grid">
      <!-- 输入面板 -->
      <div class="panel">
        <div class="panel-header">
          <div class="panel-title">
            📥 输入文本
            <span>粘贴区域</span>
          </div>
        </div>
        <textarea id="inputArea" placeholder="在此粘贴多行文本……&#10;&#10;空行、仅含空格或制表符的行都会被删除。&#10;非空行会原样保留（包括首尾空格）。" spellcheck="false"></textarea>
      </div>

      <!-- 输出面板 -->
      <div class="panel">
        <div class="panel-header">
          <div class="panel-title">
            📤 处理结果
            <span>可直接复制</span>
          </div>
          <div class="stats" id="statsDisplay">等待处理</div>
        </div>
        <textarea id="outputArea" readonly placeholder="处理后的文本会显示在这里……" spellcheck="false"></textarea>
      </div>
    </div>

    <div class="action-bar">
      <div class="btn-group">
        <button class="primary" id="processBtn" type="button">
          ✨ 删除空行
        </button>
        <button class="secondary" id="copyBtn" type="button">
          📋 复制结果
        </button>
        <button class="secondary" id="clearBtn" type="button">
          🧹 清空
        </button>
        <span class="copy-feedback" id="copyFeedback">✓ 已复制</span>
      </div>
      <div class="stats" id="lineStats">输入 0 行 · 输出 0 行</div>
    </div>

    <div class="footnote">
      ⚡ 完全离线 · 无网络请求 · 不保存任何内容
    </div>
  </div>

  <script>
    (function() {
      'use strict';

      // DOM 元素
      const inputArea = document.getElementById('inputArea');
      const outputArea = document.getElementById('outputArea');
      const processBtn = document.getElementById('processBtn');
      const copyBtn = document.getElementById('copyBtn');
      const clearBtn = document.getElementById('clearBtn');
      const copyFeedback = document.getElementById('copyFeedback');
      const lineStats = document.getElementById('lineStats');
      const statsDisplay = document.getElementById('statsDisplay');

      // 用于复制反馈的定时器
      let feedbackTimer = null;

      /**
       * 核心处理函数：删除空行（包括仅含空格/制表符的行）
       * 非空行原样保留，包括首尾空格、行内字符、顺序。
       * @param {string} text 原始文本
       * @returns {string} 处理后的文本
       */
      function removeEmptyLines(text) {
        if (!text) return '';

        // 按行分割（保留所有行，包括末尾空行）
        const lines = text.split(/\r?\n/);

        // 过滤：如果一行 trim() 后为空字符串，则视为空行（含仅空格/制表符）
        // 注意：trim() 会移除所有空白字符（空格、制表符、换行等），
        // 但这里我们只关心行内是否只有空白字符。
        // 非空行原样保留（不 trim，不修改任何字符）。
        const nonEmptyLines = lines.filter(line => line.trim() !== '');

        // 用换行符重新连接（使用 \n 作为标准换行，但保留原始内容）
        // 注意：原始文本中的 \r\n 会被统一为 \n，但这是可接受的，
        // 因为要求是“非空行原样保留”，换行符统一不影响行内容。
        // 如果需要严格保留原始换行符，可以更复杂，但通常 \n 即可。
        return nonEmptyLines.join('\n');
      }

      /**
       * 更新统计信息
       * @param {string} inputText 原始文本
       * @param {string} outputText 处理后文本
       */
      function updateStats(inputText, outputText) {
        // 计算输入行数（空字符串视为0行）
        const inputLines = inputText === '' ? 0 : inputText.split(/\r?\n/).length;
        // 输出行数
        const outputLines = outputText === '' ? 0 : outputText.split(/\r?\n/).length;

        lineStats.textContent = `输入 ${inputLines} 行 · 输出 ${outputLines} 行`;

        // 更新结果面板上的小标签
        if (outputText === '') {
          statsDisplay.textContent = '无内容';
        } else {
          statsDisplay.textContent = `已删除 ${inputLines - outputLines} 个空行`;
        }
      }

      /**
       * 处理按钮点击
       */
      function handleProcess() {
        const rawText = inputArea.value;
        const result = removeEmptyLines(rawText);
        outputArea.value = result;
        updateStats(rawText, result);

        // 如果结果为空，给出提示（但不清空输入）
        if (result === '' && rawText.trim() !== '') {
          // 所有行都是空行，结果为空
          statsDisplay.textContent = '所有行均为空行';
        } else if (rawText === '') {
          statsDisplay.textContent = '等待处理';
        }

        // 隐藏复制反馈（如果之前显示）
        hideCopyFeedback();
      }

      /**
       * 复制结果到剪贴板
       */
      async function handleCopy() {
        const textToCopy = outputArea.value;

        if (!textToCopy) {
          // 没有内容可复制，短暂提示
          copyFeedback.textContent = '✗ 无内容';
          copyFeedback.style.color = '#dc2626';
          copyFeedback.classList.add('show');
          clearTimeout(feedbackTimer);
          feedbackTimer = setTimeout(() => {
            copyFeedback.classList.remove('show');
            copyFeedback.style.color = '#16a34a';
            copyFeedback.textContent = '✓ 已复制';
          }, 1500);
          return;
        }

        try {
          // 优先使用 Clipboard API
          if (navigator.clipboard && navigator.clipboard.writeText) {
            await navigator.clipboard.writeText(textToCopy);
          } else {
            // 降级方案：选中并复制
            outputArea.select();
            outputArea.setSelectionRange(0, textToCopy.length);
            document.execCommand('copy');
            // 取消选中（可选）
            outputArea.setSelectionRange(0, 0);
            outputArea.blur();
          }

          // 显示成功反馈
          copyFeedback.textContent = '✓ 已复制';
          copyFeedback.style.color = '#16a34a';
          copyFeedback.classList.add('show');
          clearTimeout(feedbackTimer);
          feedbackTimer = setTimeout(() => {
            copyFeedback.classList.remove('show');
          }, 1800);
        } catch (err) {
          // 复制失败（可能用户拒绝权限或浏览器限制）
          copyFeedback.textContent = '✗ 复制失败';
          copyFeedback.style.color = '#dc2626';
          copyFeedback.classList.add('show');
          clearTimeout(feedbackTimer);
          feedbackTimer = setTimeout(() => {
            copyFeedback.classList.remove('show');
            copyFeedback.style.color = '#16a34a';
            copyFeedback.textContent = '✓ 已复制';
          }, 2000);
        }
      }

      /**
       * 隐藏复制反馈
       */
      function hideCopyFeedback() {
        copyFeedback.classList.remove('show');
        clearTimeout(feedbackTimer);
      }

      /**
       * 清空输入和输出
       */
      function handleClear() {
        inputArea.value = '';
        outputArea.value = '';
        lineStats.textContent = '输入 0 行 · 输出 0 行';
        statsDisplay.textContent = '等待处理';
        hideCopyFeedback();
        inputArea.focus();
      }

      /**
       * 输入框内容变化时，重置统计（但不清空输出，让用户知道需要重新处理）
       */
      function handleInputChange() {
        // 如果输入框为空，同时清空输出和统计
        if (inputArea.value === '') {
          outputArea.value = '';
          lineStats.textContent = '输入 0 行 · 输出 0 行';
          statsDisplay.textContent = '等待处理';
        } else {
          // 提示用户需要重新处理
          statsDisplay.textContent = '内容已修改，请点击处理';
        }
        hideCopyFeedback();
      }

      // 绑定事件
      processBtn.addEventListener('click', handleProcess);
      copyBtn.addEventListener('click', handleCopy);
      clearBtn.addEventListener('click', handleClear);
      inputArea.addEventListener('input', handleInputChange);

      // 可选：Ctrl+Enter 快捷处理
      inputArea.addEventListener('keydown', (e) => {
        if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') {
          e.preventDefault();
          handleProcess();
        }
      });

      // 初始化统计
      lineStats.textContent = '输入 0 行 · 输出 0 行';
      statsDisplay.textContent = '等待处理';

      // 页面加载后聚焦输入框（方便粘贴）
      window.addEventListener('load', () => {
        inputArea.focus();
      });
    })();
  </script>
</body>
</html>
```
### 功能与操作说明

这个工具围绕“删除空行”这个核心动作，提供了清晰的输入、处理和复制流程。

- **输入与处理**：左侧输入框用于粘贴多行文本。点击“删除空行”按钮后，工具会逐行检查，过滤掉所有空行（包括仅含空格或制表符的行），非空行则原样保留。
- **结果与复制**：右侧结果区域会显示处理后的文本，并自动统计输入和输出的行数。您可以直接点击“复制结果”按钮，将内容复制到剪贴板。
- **辅助操作**：界面还提供了“清空”按钮，方便快速重置。输入框支持 Ctrl+Enter（或 Cmd+Enter）快捷键触发处理。