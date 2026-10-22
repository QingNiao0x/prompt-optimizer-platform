这是您需要的浏览器离线小工具HTML文件。它能把粘贴的多行文字中的空行（包括只含空格或制表符的行）全部移除，同时保留非空行的首尾空格、行内字符和原有顺序。
```html
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>空行清理 · 离线小工具</title>
  <style>
    /* 全局重置与字体 */
    * {
      box-sizing: border-box;
      margin: 0;
      padding: 0;
    }

    body {
      background: #f5f7fb;
      font-family: system-ui, -apple-system, 'Segoe UI', Roboto, 'Helvetica Neue', sans-serif;
      display: flex;
      align-items: center;
      justify-content: center;
      min-height: 100vh;
      padding: 1.5rem;
      margin: 0;
    }

    /* 主卡片 */
    .tool-card {
      background: #ffffff;
      border-radius: 2rem;
      box-shadow: 0 20px 40px -12px rgba(0, 20, 30, 0.25), 0 8px 24px -8px rgba(0, 0, 0, 0.1);
      width: 100%;
      max-width: 1100px;
      padding: 2rem 2rem 2rem 2rem;
      transition: all 0.2s ease;
    }

    /* 标题区域 */
    .header {
      display: flex;
      align-items: center;
      gap: 0.75rem;
      margin-bottom: 1.75rem;
      flex-wrap: wrap;
    }

    .header h1 {
      font-size: 1.8rem;
      font-weight: 600;
      letter-spacing: -0.02em;
      color: #0b2b3b;
      display: flex;
      align-items: center;
      gap: 0.5rem;
    }

    .header h1 span {
      background: #e1f0fa;
      color: #0f5e7a;
      font-size: 0.9rem;
      font-weight: 500;
      padding: 0.2rem 0.9rem;
      border-radius: 40px;
      letter-spacing: 0.01em;
      margin-left: 0.5rem;
    }

    .badge-offline {
      background: #e6f7e6;
      color: #1e6b3b;
      font-size: 0.8rem;
      font-weight: 500;
      padding: 0.25rem 0.9rem;
      border-radius: 40px;
      display: inline-flex;
      align-items: center;
      gap: 0.3rem;
      border: 1px solid #b8e0c0;
      margin-left: auto;
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
      .header h1 {
        font-size: 1.5rem;
      }
    }

    /* 每个面板 */
    .pane {
      display: flex;
      flex-direction: column;
      background: #fafcff;
      border-radius: 1.5rem;
      border: 1px solid #dde7f0;
      overflow: hidden;
      transition: border-color 0.15s;
    }

    .pane:focus-within {
      border-color: #3b8fc2;
      box-shadow: 0 0 0 3px rgba(59, 143, 194, 0.15);
    }

    .pane-header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding: 0.9rem 1.2rem;
      background: #eef4fa;
      border-bottom: 1px solid #d2e0ec;
      font-weight: 500;
      color: #1a4b63;
      font-size: 0.95rem;
    }

    .pane-header .hint {
      font-weight: 400;
      font-size: 0.8rem;
      color: #4b6f82;
      background: #ffffffc0;
      padding: 0.2rem 0.7rem;
      border-radius: 30px;
    }

    textarea {
      width: 100%;
      min-height: 320px;
      padding: 1.2rem 1.2rem;
      font-family: 'SF Mono', 'Fira Code', 'Cascadia Code', 'Consolas', monospace;
      font-size: 0.95rem;
      line-height: 1.6;
      border: none;
      background: #ffffff;
      resize: vertical;
      outline: none;
      color: #0d2c3b;
      tab-size: 4;
      white-space: pre;
      overflow-wrap: normal;
      overflow-x: auto;
    }

    textarea::placeholder {
      color: #9bb7c9;
      font-family: system-ui, sans-serif;
      font-style: italic;
      opacity: 0.8;
    }

    /* 输出区域只读样式 */
    textarea[readonly] {
      background: #f9fcff;
      color: #1a3e4e;
      cursor: default;
    }

    /* 按钮栏 */
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
      border: 1px solid #cbdae6;
      padding: 0.7rem 1.5rem;
      border-radius: 60px;
      font-size: 0.95rem;
      font-weight: 500;
      color: #1a4b63;
      cursor: pointer;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      gap: 0.4rem;
      transition: all 0.15s ease;
      box-shadow: 0 1px 2px rgba(0, 0, 0, 0.02);
      font-family: inherit;
      line-height: 1.2;
      white-space: nowrap;
    }

    button:hover {
      background: #f0f7ff;
      border-color: #7fb4d4;
      color: #0a3b52;
    }

    button:active {
      transform: scale(0.97);
      background: #e2effa;
    }

    button.primary {
      background: #1a6e96;
      border-color: #1a6e96;
      color: white;
      box-shadow: 0 4px 10px -4px rgba(26, 110, 150, 0.4);
    }

    button.primary:hover {
      background: #0f5a7e;
      border-color: #0f5a7e;
      color: white;
    }

    button.primary:active {
      background: #0b4a68;
    }

    button:disabled {
      opacity: 0.5;
      pointer-events: none;
      filter: grayscale(0.3);
    }

    .status-message {
      font-size: 0.9rem;
      color: #3f6b80;
      background: #e9f2f9;
      padding: 0.4rem 1rem;
      border-radius: 40px;
      display: inline-flex;
      align-items: center;
      gap: 0.4rem;
      transition: opacity 0.2s;
      min-height: 2.4rem;
    }

    .status-message i {
      font-style: normal;
      font-size: 1.1rem;
    }

    /* 小提示 */
    .footnote {
      margin-top: 1.5rem;
      font-size: 0.8rem;
      color: #6b8b9c;
      text-align: center;
      border-top: 1px solid #d9e6f0;
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

    /* 隐藏辅助 */
    .sr-only {
      position: absolute;
      width: 1px;
      height: 1px;
      padding: 0;
      margin: -1px;
      overflow: hidden;
      clip: rect(0, 0, 0, 0);
      border: 0;
    }
  </style>
</head>
<body>
  <div class="tool-card">
    <div class="header">
      <h1>
        🧹 空行清理
        <span>离线版</span>
      </h1>
      <div class="badge-offline">
        <span>🔒</span> 不上传 · 不保存
      </div>
    </div>

    <div class="panes">
      <!-- 输入面板 -->
      <div class="pane">
        <div class="pane-header">
          <span>📋 原始文本</span>
          <span class="hint">粘贴多行文字</span>
        </div>
        <textarea id="inputArea" placeholder="在这里粘贴文本…&#10;&#10;空行（包括只含空格或制表符的行）会被移除。&#10;非空行的首尾空格、行内字符和顺序都会保留。"></textarea>
      </div>

      <!-- 输出面板 -->
      <div class="pane">
        <div class="pane-header">
          <span>✨ 清理结果</span>
          <span class="hint">只读 · 可复制</span>
        </div>
        <textarea id="outputArea" readonly placeholder="清理后的文本会显示在这里…"></textarea>
      </div>
    </div>

    <!-- 操作按钮与状态 -->
    <div class="actions">
      <div class="button-group">
        <button id="cleanBtn" class="primary" type="button">
          <span>🧽</span> 清理空行
        </button>
        <button id="copyBtn" type="button">
          <span>📋</span> 复制结果
        </button>
        <button id="clearBtn" type="button">
          <span>🗑️</span> 清空全部
        </button>
      </div>
      <div id="statusMessage" class="status-message" role="status" aria-live="polite">
        <i>⏳</i> 等待输入
      </div>
    </div>

    <!-- 底部说明 -->
    <div class="footnote">
      <span>🔹 空行定义：仅含空格、制表符或完全为空的行</span>
      <span>🔹 非空行首尾空格保留</span>
      <span>🔹 所有处理在浏览器本地完成</span>
    </div>
  </div>

  <script>
    (function() {
      'use strict';

      // DOM 元素
      const inputArea = document.getElementById('inputArea');
      const outputArea = document.getElementById('outputArea');
      const cleanBtn = document.getElementById('cleanBtn');
      const copyBtn = document.getElementById('copyBtn');
      const clearBtn = document.getElementById('clearBtn');
      const statusMessage = document.getElementById('statusMessage');

      // 状态更新辅助函数
      function setStatus(text, icon = '⏳', isTemporary = false) {
        statusMessage.innerHTML = `<i>${icon}</i> ${text}`;
        if (isTemporary) {
          // 如果之前有定时器，清除
          if (window._statusTimer) {
            clearTimeout(window._statusTimer);
          }
          window._statusTimer = setTimeout(() => {
            // 恢复为默认提示（根据当前输入/输出状态）
            updateDefaultStatus();
            window._statusTimer = null;
          }, 3000);
        }
      }

      // 根据当前输入/输出内容更新默认状态
      function updateDefaultStatus() {
        const inputText = inputArea.value;
        const outputText = outputArea.value;

        if (inputText.trim() === '' && outputText === '') {
          setStatus('等待输入', '⏳');
        } else if (outputText !== '') {
          // 统计输出行数
          const lines = outputText.split(/\r?\n/).filter(line => line !== '').length;
          setStatus(`已清理 · ${lines} 行非空内容`, '✅');
        } else if (inputText !== '') {
          setStatus('点击「清理空行」处理文本', '🧹');
        } else {
          setStatus('等待输入', '⏳');
        }
      }

      // 核心清理函数：移除空行（仅含空格/制表符/完全空）
      function removeEmptyLines(text) {
        if (text === '') return '';

        // 按行分割（兼容 \r\n, \n, \r）
        const lines = text.split(/\r?\n/);

        // 过滤：保留非空行（即不是仅由空格或制表符组成的行，也不是空字符串）
        const nonEmptyLines = lines.filter(line => {
          // 如果行完全为空字符串，视为空行
          if (line === '') return false;
          // 如果行只包含空格或制表符（一个或多个），视为空行
          if (/^[ \t]*$/.test(line)) return false;
          // 其他情况保留
          return true;
        });

        // 用换行符连接（使用 \n 作为标准换行，但保留原始行内容）
        return nonEmptyLines.join('\n');
      }

      // 执行清理
      function performClean() {
        const original = inputArea.value;

        // 如果输入为空，清空输出并提示
        if (original === '') {
          outputArea.value = '';
          setStatus('输入为空，没有可清理的内容', 'ℹ️', true);
          updateDefaultStatus(); // 但会覆盖，所以稍后调用
          // 但为了显示临时消息，我们延迟恢复
          if (window._statusTimer) clearTimeout(window._statusTimer);
          window._statusTimer = setTimeout(() => {
            updateDefaultStatus();
            window._statusTimer = null;
          }, 2500);
          return;
        }

        const cleaned = removeEmptyLines(original);
        outputArea.value = cleaned;

        // 统计信息
        const originalLineCount = original.split(/\r?\n/).length;
        const cleanedLineCount = cleaned === '' ? 0 : cleaned.split('\n').length;
        const removedCount = originalLineCount - cleanedLineCount;

        if (cleaned === '') {
          setStatus('所有行均为空行，结果为空', '🧹', true);
        } else {
          setStatus(`已移除 ${removedCount} 个空行，保留 ${cleanedLineCount} 行`, '✅', true);
        }

        // 如果临时消息超时，恢复默认状态
        if (window._statusTimer) clearTimeout(window._statusTimer);
        window._statusTimer = setTimeout(() => {
          updateDefaultStatus();
          window._statusTimer = null;
        }, 3000);
      }

      // 复制结果到剪贴板
      async function copyResult() {
        const textToCopy = outputArea.value;

        if (textToCopy === '') {
          setStatus('没有可复制的内容', '⚠️', true);
          if (window._statusTimer) clearTimeout(window._statusTimer);
          window._statusTimer = setTimeout(() => {
            updateDefaultStatus();
            window._statusTimer = null;
          }, 2000);
          return;
        }

        try {
          // 优先使用 Clipboard API
          await navigator.clipboard.writeText(textToCopy);
          setStatus('已复制到剪贴板', '📋', true);
        } catch (err) {
          // 降级方案：选中并 execCommand
          try {
            outputArea.select();
            outputArea.setSelectionRange(0, outputArea.value.length);
            const success = document.execCommand('copy');
            if (success) {
              setStatus('已复制到剪贴板', '📋', true);
            } else {
              setStatus('复制失败，请手动选择复制', '⚠️', true);
            }
          } catch (e) {
            setStatus('复制失败，请手动选择复制', '⚠️', true);
          }
        }

        // 清除临时消息定时器并设置新的
        if (window._statusTimer) clearTimeout(window._statusTimer);
        window._statusTimer = setTimeout(() => {
          updateDefaultStatus();
          window._statusTimer = null;
        }, 2500);
      }

      // 清空全部
      function clearAll() {
        inputArea.value = '';
        outputArea.value = '';
        setStatus('已清空', '🗑️', true);
        if (window._statusTimer) clearTimeout(window._statusTimer);
        window._statusTimer = setTimeout(() => {
          updateDefaultStatus();
          window._statusTimer = null;
        }, 2000);
        // 聚焦输入框
        inputArea.focus();
      }

      // 监听输入变化，更新默认状态（但不自动清理）
      function onInputChange() {
        // 如果输出区域有内容但输入变了，可以提示用户重新清理
        const inputText = inputArea.value;
        const outputText = outputArea.value;

        // 如果输入为空，且输出不为空，则清空输出？不自动清空，但可以提示
        // 但为了不干扰用户，只更新状态
        if (inputText === '' && outputText !== '') {
          // 不自动清空输出，但提示
          setStatus('输入已清空，点击「清理空行」更新结果', 'ℹ️');
        } else if (inputText !== '' && outputText === '') {
          setStatus('点击「清理空行」处理文本', '🧹');
        } else if (inputText !== '' && outputText !== '') {
          // 检查输出是否与当前输入匹配？不自动判断，只提示
          setStatus('输入已修改，可重新清理', '✏️');
        } else {
          setStatus('等待输入', '⏳');
        }

        // 清除任何临时状态定时器
        if (window._statusTimer) {
          clearTimeout(window._statusTimer);
          window._statusTimer = null;
        }
      }

      // 初始化事件监听
      function init() {
        // 清理按钮
        cleanBtn.addEventListener('click', performClean);

        // 复制按钮
        copyBtn.addEventListener('click', copyResult);

        // 清空按钮
        clearBtn.addEventListener('click', clearAll);

        // 输入变化时更新状态（不自动清理）
        inputArea.addEventListener('input', onInputChange);

        // 额外：当用户粘贴时，可以自动聚焦到清理按钮？不需要，但可以给个提示
        inputArea.addEventListener('paste', function() {
          // 粘贴后延迟一点点更新状态，因为此时value还没更新
          setTimeout(() => {
            if (inputArea.value.trim() !== '') {
              setStatus('已粘贴文本，点击「清理空行」', '📋');
            } else {
              setStatus('等待输入', '⏳');
            }
            if (window._statusTimer) {
              clearTimeout(window._statusTimer);
              window._statusTimer = null;
            }
          }, 10);
        });

        // 初始状态
        updateDefaultStatus();

        // 可选：如果用户按 Ctrl+Enter 触发清理
        inputArea.addEventListener('keydown', (e) => {
          if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') {
            e.preventDefault();
            performClean();
          }
        });

        // 输出区域点击时全选？不必要，但可以方便复制
        // 不做额外处理
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
### 核心功能与操作流程

您只需将文本粘贴到左侧输入区，点击“清理空行”按钮，右侧就会显示处理后的结果。它的核心逻辑是逐行检查，只移除那些完全为空或仅由空格、制表符组成的行。

- **清理规则**：工具会按行分割文本，并过滤掉所有空行（包括只含空格或制表符的行）。非空行的首尾空格、行内字符和顺序都会原样保留。
- **操作方式**：除了手动点击“清理空行”按钮，您也可以在输入框中按 `Ctrl+Enter`（或 `Cmd+Enter`）快速触发清理。处理后的结果会显示在右侧只读区域，方便您核对。
- **状态反馈**：界面底部的状态栏会实时显示操作结果，例如移除了多少个空行、保留了哪些内容，让您对处理情况一目了然。

---

**优化建议：** 您可以根据需要调整输入框的默认高度（`textarea` 的 `min-height` 属性值），或修改状态提示的显示时长（`setTimeout` 中的毫秒数）。