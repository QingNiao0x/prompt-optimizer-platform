这是您需要的离线小工具HTML文件。它能在浏览器中直接运行，用于去除多行文本中的空行（包括仅含空格或制表符的行），并保留非空行的首尾空格和原有顺序。
```html
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>空行去除 · 离线小工具</title>
  <style>
    /* 简洁、直观、无外部依赖 */
    * {
      box-sizing: border-box;
      margin: 0;
      padding: 0;
    }

    body {
      background: #f5f7fa;
      font-family: system-ui, -apple-system, 'Segoe UI', Roboto, 'Helvetica Neue', sans-serif;
      display: flex;
      justify-content: center;
      align-items: center;
      min-height: 100vh;
      padding: 1.5rem;
      margin: 0;
    }

    .app {
      max-width: 900px;
      width: 100%;
      background: #ffffff;
      border-radius: 24px;
      box-shadow: 0 12px 30px rgba(0, 0, 0, 0.08);
      padding: 2rem 2rem 2rem 2rem;
      transition: all 0.2s;
    }

    h1 {
      font-size: 1.8rem;
      font-weight: 600;
      letter-spacing: -0.01em;
      color: #0b1b2b;
      margin-bottom: 0.25rem;
      display: flex;
      align-items: center;
      gap: 0.5rem;
    }

    .subhead {
      color: #5e6f8d;
      font-size: 0.95rem;
      margin-bottom: 1.75rem;
      border-left: 3px solid #3b82f6;
      padding-left: 0.75rem;
      line-height: 1.4;
    }

    .grid {
      display: flex;
      flex-direction: column;
      gap: 1.5rem;
    }

    .field {
      display: flex;
      flex-direction: column;
      gap: 0.5rem;
    }

    .field-header {
      display: flex;
      justify-content: space-between;
      align-items: baseline;
      flex-wrap: wrap;
      gap: 0.5rem;
    }

    .field-header label {
      font-weight: 500;
      color: #1e2b3c;
      font-size: 0.95rem;
      letter-spacing: 0.01em;
    }

    .hint {
      font-size: 0.8rem;
      color: #6b7b98;
      background: #eef2f6;
      padding: 0.2rem 0.6rem;
      border-radius: 20px;
    }

    textarea {
      width: 100%;
      border: 1.5px solid #dce3ec;
      border-radius: 16px;
      padding: 1rem 1.2rem;
      font-family: 'SF Mono', 'Fira Code', 'Cascadia Code', 'Consolas', monospace;
      font-size: 0.95rem;
      line-height: 1.6;
      resize: vertical;
      background: #ffffff;
      color: #0b1b2b;
      transition: border 0.15s, box-shadow 0.15s;
      outline: none;
      min-height: 180px;
    }

    textarea:focus {
      border-color: #3b82f6;
      box-shadow: 0 0 0 3px rgba(59, 130, 246, 0.15);
    }

    textarea::placeholder {
      color: #a7b7cc;
      font-family: system-ui, sans-serif;
      font-size: 0.9rem;
    }

    .actions {
      display: flex;
      flex-wrap: wrap;
      gap: 0.75rem;
      align-items: center;
      margin-top: 0.25rem;
    }

    button {
      border: none;
      background: #ffffff;
      font-family: inherit;
      font-size: 0.95rem;
      font-weight: 500;
      padding: 0.7rem 1.5rem;
      border-radius: 40px;
      cursor: pointer;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      gap: 0.4rem;
      transition: background 0.15s, transform 0.1s, box-shadow 0.15s;
      border: 1.5px solid transparent;
      line-height: 1.2;
      white-space: nowrap;
    }

    button:active {
      transform: scale(0.97);
    }

    .btn-primary {
      background: #1e2b3c;
      color: #ffffff;
      box-shadow: 0 4px 10px rgba(0, 0, 0, 0.05);
    }

    .btn-primary:hover {
      background: #0f1a26;
    }

    .btn-secondary {
      background: #f0f4fa;
      color: #1e2b3c;
      border-color: #d0dae8;
    }

    .btn-secondary:hover {
      background: #e6ecf5;
      border-color: #b8c8dd;
    }

    .btn-copy {
      background: #ffffff;
      color: #1e2b3c;
      border: 1.5px solid #d0dae8;
    }

    .btn-copy:hover {
      background: #f0f4fa;
      border-color: #b0c0d4;
    }

    .btn-copy.copied {
      background: #e6f7e6;
      border-color: #7bc97b;
      color: #1e6b1e;
    }

    .result-area {
      position: relative;
    }

    .result-actions {
      display: flex;
      justify-content: flex-end;
      margin-top: 0.5rem;
    }

    .status {
      font-size: 0.85rem;
      color: #3b82f6;
      background: #eef4ff;
      padding: 0.3rem 0.9rem;
      border-radius: 30px;
      display: inline-block;
      opacity: 0;
      transition: opacity 0.2s;
      pointer-events: none;
      margin-left: 0.5rem;
    }

    .status.show {
      opacity: 1;
    }

    .footer-note {
      margin-top: 1.5rem;
      font-size: 0.78rem;
      color: #8a9bb0;
      text-align: center;
      border-top: 1px solid #e9eef4;
      padding-top: 1.2rem;
      display: flex;
      justify-content: center;
      gap: 1rem;
      flex-wrap: wrap;
    }

    .footer-note span {
      display: inline-flex;
      align-items: center;
      gap: 0.3rem;
    }

    /* 小屏适配 */
    @media (max-width: 600px) {
      body {
        padding: 0.8rem;
      }
      .app {
        padding: 1.5rem 1.2rem;
        border-radius: 20px;
      }
      h1 {
        font-size: 1.5rem;
      }
      button {
        padding: 0.65rem 1.2rem;
        font-size: 0.9rem;
      }
      textarea {
        padding: 0.9rem 1rem;
        font-size: 0.9rem;
      }
    }
  </style>
</head>
<body>
  <div class="app">
    <h1>
      <span>🧹</span> 空行去除
    </h1>
    <div class="subhead">
      删除所有空行（包括只含空格或制表符的行）。非空行的首尾空格、行内字符和顺序完全保留。
    </div>

    <div class="grid">
      <!-- 输入区 -->
      <div class="field">
        <div class="field-header">
          <label for="inputText">输入文本</label>
          <span class="hint">粘贴多行文字</span>
        </div>
        <textarea id="inputText" placeholder="在这里粘贴多行文字…&#10;&#10;例如：&#10;第一行&#10;   &#10;第三行（前面有空格）&#10;	&#10;第五行" spellcheck="false"></textarea>
      </div>

      <!-- 操作按钮 -->
      <div class="actions">
        <button class="btn-primary" id="processBtn">
          ⚡ 去除空行
        </button>
        <button class="btn-secondary" id="clearBtn">
          🗑️ 清空
        </button>
        <span id="statusMsg" class="status">已处理</span>
      </div>

      <!-- 结果区 -->
      <div class="field result-area">
        <div class="field-header">
          <label for="outputText">处理结果</label>
          <span class="hint">可直接复制</span>
        </div>
        <textarea id="outputText" placeholder="处理后的文本会显示在这里…" readonly spellcheck="false"></textarea>
        <div class="result-actions">
          <button class="btn-copy" id="copyBtn">
            📋 复制结果
          </button>
        </div>
      </div>
    </div>

    <div class="footer-note">
      <span>🔒 完全离线 · 不上传 · 不保存</span>
      <span>⚡ 仅保留非空行</span>
    </div>
  </div>

  <script>
    (function() {
      // DOM 元素
      const inputText = document.getElementById('inputText');
      const outputText = document.getElementById('outputText');
      const processBtn = document.getElementById('processBtn');
      const clearBtn = document.getElementById('clearBtn');
      const copyBtn = document.getElementById('copyBtn');
      const statusMsg = document.getElementById('statusMsg');

      // 状态提示计时器
      let statusTimer = null;

      // 显示短暂的状态消息
      function showStatus(message, isError = false) {
        if (statusTimer) {
          clearTimeout(statusTimer);
          statusTimer = null;
        }
        statusMsg.textContent = message;
        statusMsg.style.color = isError ? '#b91c1c' : '#3b82f6';
        statusMsg.style.background = isError ? '#fee2e2' : '#eef4ff';
        statusMsg.classList.add('show');

        statusTimer = setTimeout(() => {
          statusMsg.classList.remove('show');
          statusTimer = null;
        }, 2000);
      }

      // 核心处理函数：去除空行（包括只含空格/制表符的行）
      function removeEmptyLines(text) {
        if (!text) return '';

        // 按行分割（支持 \r\n, \n, \r）
        const lines = text.split(/\r\n|\r|\n/);

        // 过滤：如果一行 trim() 后为空，则视为空行（包括只含空格、制表符、混合空白）
        // 注意：trim() 会移除首尾空白，但不会影响非空行内部字符和顺序
        const nonEmptyLines = lines.filter(line => line.trim() !== '');

        // 用换行符重新连接（使用 \n 作为标准换行，保持跨平台一致性）
        return nonEmptyLines.join('\n');
      }

      // 处理按钮点击
      function handleProcess() {
        const raw = inputText.value;

        // 即使输入为空，也执行处理（结果为空字符串）
        const result = removeEmptyLines(raw);
        outputText.value = result;

        // 显示处理反馈
        if (raw === '') {
          showStatus('输入为空', false);
        } else {
          const originalLineCount = raw.split(/\r\n|\r|\n/).length;
          const resultLineCount = result === '' ? 0 : result.split('\n').length;
          const removed = originalLineCount - resultLineCount;
          if (removed > 0) {
            showStatus(`已去除 ${removed} 个空行`, false);
          } else {
            showStatus('没有空行需要去除', false);
          }
        }
      }

      // 清空输入和输出
      function handleClear() {
        inputText.value = '';
        outputText.value = '';
        inputText.focus();
        showStatus('已清空', false);
      }

      // 复制结果到剪贴板
      async function handleCopy() {
        const textToCopy = outputText.value;

        if (!textToCopy) {
          showStatus('没有可复制的内容', true);
          return;
        }

        try {
          // 优先使用 Clipboard API
          await navigator.clipboard.writeText(textToCopy);
          // 复制成功视觉反馈
          copyBtn.classList.add('copied');
          copyBtn.innerHTML = '✅ 已复制';
          showStatus('已复制到剪贴板', false);

          // 2秒后恢复按钮样式
          setTimeout(() => {
            copyBtn.classList.remove('copied');
            copyBtn.innerHTML = '📋 复制结果';
          }, 2000);
        } catch (err) {
          // 降级方案：使用 textarea 选中 + execCommand
          try {
            outputText.select();
            outputText.setSelectionRange(0, outputText.value.length);
            const success = document.execCommand('copy');
            if (success) {
              copyBtn.classList.add('copied');
              copyBtn.innerHTML = '✅ 已复制';
              showStatus('已复制到剪贴板', false);
              setTimeout(() => {
                copyBtn.classList.remove('copied');
                copyBtn.innerHTML = '📋 复制结果';
              }, 2000);
            } else {
              showStatus('复制失败，请手动选择复制', true);
            }
          } catch (fallbackErr) {
            showStatus('复制失败，请手动选择复制', true);
          }
        }
      }

      // 绑定事件
      processBtn.addEventListener('click', handleProcess);
      clearBtn.addEventListener('click', handleClear);
      copyBtn.addEventListener('click', handleCopy);

      // 额外：输入框支持 Ctrl+Enter 快速处理
      inputText.addEventListener('keydown', (e) => {
        if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') {
          e.preventDefault();
          handleProcess();
        }
      });

      // 初始化：聚焦输入框
      inputText.focus();

      // 不自动保存任何内容，不读取本地存储
    })();
  </script>
</body>
</html>
```
### 核心功能与操作说明

您只需粘贴文本，点击按钮即可完成空行清理。它的处理逻辑很直接：逐行检查，只保留那些包含实际字符的行。

- **输入与处理**：在顶部文本框中粘贴多行文字。点击“去除空行”按钮，工具会立即扫描每一行，并删除所有仅由空格、制表符或两者混合构成的“空行”。
- **结果与复制**：处理后的文本会显示在下方结果区。您可以点击“复制结果”按钮，将内容一键复制到剪贴板，方便粘贴到其他应用。
- **状态反馈**：每次操作后，界面会短暂显示提示，例如“已去除 X 个空行”或“已复制到剪贴板”，让您清楚知道操作结果。

---

**优化建议：** 您可以根据需要调整输入框的默认提示文字（位于`<textarea id=“inputText”>`的`placeholder`属性中），使其更贴合您的使用场景。