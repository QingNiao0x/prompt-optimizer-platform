这是一个可直接在浏览器中离线使用的HTML工具，用于删除多行文本中的空行，同时保留非空行的首尾空格和原有顺序。
```html
<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>空行清理 · 离线小工具</title>
    <style>
        * {
            box-sizing: border-box;
            margin: 0;
            padding: 0;
        }

        body {
            font-family: system-ui, -apple-system, 'Segoe UI', Roboto, 'Helvetica Neue', sans-serif;
            background: #f5f7fb;
            min-height: 100vh;
            display: flex;
            align-items: center;
            justify-content: center;
            padding: 1.5rem;
            margin: 0;
        }

        .app-container {
            max-width: 1200px;
            width: 100%;
            background: #ffffff;
            border-radius: 28px;
            box-shadow: 0 20px 40px -12px rgba(0, 0, 0, 0.2), 0 8px 24px -8px rgba(0, 0, 0, 0.1);
            padding: 2rem 2rem 2rem 2rem;
            transition: all 0.2s;
        }

        h1 {
            font-size: 1.9rem;
            font-weight: 600;
            letter-spacing: -0.02em;
            color: #0b1b32;
            display: flex;
            align-items: center;
            gap: 0.5rem;
            margin-bottom: 0.25rem;
        }

        .subhead {
            color: #4a5b6e;
            font-size: 0.95rem;
            margin-bottom: 1.75rem;
            border-left: 4px solid #3b82f6;
            padding-left: 1rem;
            background: #f0f4fe;
            border-radius: 0 8px 8px 0;
            line-height: 1.4;
            padding: 0.6rem 1rem;
        }

        .subhead code {
            background: #e2e8f0;
            padding: 0.15rem 0.4rem;
            border-radius: 6px;
            font-size: 0.85rem;
            color: #1e293b;
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
                gap: 1rem;
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
            background: #f9fafc;
            border-radius: 20px;
            border: 1px solid #e2e8f0;
            overflow: hidden;
            transition: border-color 0.15s, box-shadow 0.15s;
        }

        .panel:focus-within {
            border-color: #3b82f6;
            box-shadow: 0 0 0 3px rgba(59, 130, 246, 0.15);
        }

        .panel-header {
            display: flex;
            align-items: center;
            justify-content: space-between;
            padding: 0.9rem 1.2rem;
            background: #ffffff;
            border-bottom: 1px solid #e9edf2;
            font-weight: 500;
            color: #1e2b3c;
            font-size: 0.95rem;
        }

        .panel-header span {
            display: flex;
            align-items: center;
            gap: 0.4rem;
        }

        .badge {
            background: #eef2f6;
            color: #2c3e50;
            font-size: 0.7rem;
            font-weight: 500;
            padding: 0.2rem 0.6rem;
            border-radius: 30px;
            letter-spacing: 0.3px;
        }

        textarea {
            width: 100%;
            min-height: 320px;
            padding: 1.2rem 1.2rem;
            font-family: 'SF Mono', 'Fira Code', 'Cascadia Code', 'Consolas', 'Menlo', monospace;
            font-size: 0.95rem;
            line-height: 1.6;
            border: none;
            background: #ffffff;
            resize: vertical;
            outline: none;
            color: #0f1a2b;
            transition: background 0.1s;
            tab-size: 4;
            white-space: pre;
            overflow-wrap: normal;
            overflow-x: auto;
        }

        textarea::placeholder {
            color: #9aa9b9;
            font-family: system-ui, sans-serif;
            font-size: 0.9rem;
            white-space: normal;
        }

        textarea:read-only {
            background: #f8fafd;
            color: #1e2b3c;
        }

        .toolbar {
            display: flex;
            flex-wrap: wrap;
            align-items: center;
            justify-content: space-between;
            gap: 1rem;
            margin-top: 0.5rem;
        }

        .action-buttons {
            display: flex;
            flex-wrap: wrap;
            gap: 0.75rem;
        }

        button {
            display: inline-flex;
            align-items: center;
            justify-content: center;
            gap: 0.4rem;
            background: #ffffff;
            border: 1px solid #cfddee;
            color: #1e2b3c;
            font-weight: 500;
            font-size: 0.9rem;
            padding: 0.65rem 1.3rem;
            border-radius: 40px;
            cursor: pointer;
            transition: all 0.15s ease;
            box-shadow: 0 1px 2px rgba(0, 0, 0, 0.02);
            font-family: inherit;
            line-height: 1.2;
            white-space: nowrap;
        }

        button:hover {
            background: #f0f6ff;
            border-color: #3b82f6;
            color: #1e3a8a;
            box-shadow: 0 4px 8px -4px rgba(59, 130, 246, 0.2);
        }

        button:active {
            transform: scale(0.97);
            background: #e6efff;
        }

        button.primary {
            background: #1e3a8a;
            border-color: #1e3a8a;
            color: white;
            box-shadow: 0 4px 10px -4px rgba(30, 58, 138, 0.3);
        }

        button.primary:hover {
            background: #1e40af;
            border-color: #1e40af;
            color: white;
            box-shadow: 0 6px 14px -6px #1e3a8a;
        }

        button.primary:active {
            background: #172554;
        }

        button:disabled {
            opacity: 0.5;
            pointer-events: none;
            filter: grayscale(0.3);
        }

        .stats {
            font-size: 0.85rem;
            color: #4a5b6e;
            background: #f0f4fe;
            padding: 0.4rem 1rem;
            border-radius: 30px;
            display: flex;
            align-items: center;
            gap: 0.6rem;
            white-space: nowrap;
        }

        .stats strong {
            color: #1e3a8a;
            font-weight: 600;
        }

        .footnote {
            margin-top: 1.5rem;
            font-size: 0.8rem;
            color: #6b7b8f;
            display: flex;
            align-items: center;
            justify-content: center;
            gap: 0.5rem;
            border-top: 1px solid #e9edf2;
            padding-top: 1.2rem;
            text-align: center;
            flex-wrap: wrap;
        }

        .footnote svg {
            opacity: 0.7;
        }

        .clear-btn {
            background: transparent;
            border-color: #d0dae8;
            color: #4a5b6e;
        }

        .clear-btn:hover {
            background: #fee9e9;
            border-color: #f87171;
            color: #b91c1c;
        }

        .copy-btn {
            background: #ffffff;
        }

        .copy-btn.copied {
            background: #dcfce7;
            border-color: #22c55e;
            color: #166534;
        }

        .sr-only {
            position: absolute;
            width: 1px;
            height: 1px;
            padding: 0;
            margin: -1px;
            overflow: hidden;
            clip: rect(0, 0, 0, 0);
            white-space: nowrap;
            border-width: 0;
        }
    </style>
</head>
<body>
    <div class="app-container">
        <h1>
            <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="#1e3a8a" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="margin-right: 4px;">
                <path d="M4 6h16M4 12h16M4 18h10" />
                <path d="M18 18l2 2 4-4" stroke="#3b82f6" />
            </svg>
            空行清理
        </h1>
        <div class="subhead">
            ⚡ 删除所有空行（包括只含空格或制表符的行）。非空行的首尾空格、行内字符和顺序完全保留。
        </div>

        <div class="editor-grid">
            <!-- 输入面板 -->
            <div class="panel">
                <div class="panel-header">
                    <span>📥 原始文本</span>
                    <span class="badge" id="inputLineCount">0 行</span>
                </div>
                <textarea id="inputArea" placeholder="在此粘贴多行文本…&#10;&#10;空行、只含空格或制表符的行都会被移除。&#10;非空行原样保留（包括首尾空格）。" spellcheck="false"></textarea>
            </div>

            <!-- 输出面板 -->
            <div class="panel">
                <div class="panel-header">
                    <span>📤 清理结果</span>
                    <span class="badge" id="outputLineCount">0 行</span>
                </div>
                <textarea id="outputArea" readonly placeholder="清理后的文本将显示在这里…" spellcheck="false"></textarea>
            </div>
        </div>

        <div class="toolbar">
            <div class="action-buttons">
                <button class="primary" id="cleanBtn" title="删除所有空行（仅含空格/制表符的行也算空行）">
                    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                        <path d="M3 6h18M3 12h18M3 18h12" />
                        <path d="M19 18l2 2 4-4" stroke="currentColor" />
                    </svg>
                    清理空行
                </button>
                <button id="copyBtn" class="copy-btn" title="复制清理结果到剪贴板">
                    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                        <rect x="9" y="9" width="13" height="13" rx="2" ry="2"></rect>
                        <path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"></path>
                    </svg>
                    复制结果
                </button>
                <button id="clearBtn" class="clear-btn" title="清空输入和输出">
                    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                        <path d="M3 6h18M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2m3 0v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6h14z" />
                        <line x1="10" y1="11" x2="10" y2="17" />
                        <line x1="14" y1="11" x2="14" y2="17" />
                    </svg>
                    清空
                </button>
            </div>
            <div class="stats" id="statsDisplay">
                <span>📊 已移除 <strong id="removedCount">0</strong> 个空行</span>
            </div>
        </div>

        <div class="footnote">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <circle cx="12" cy="12" r="10" />
                <line x1="12" y1="8" x2="12" y2="12" />
                <line x1="12" y1="16" x2="12.01" y2="16" />
            </svg>
            完全离线 · 无网络请求 · 不自动保存 · 数据仅留在你的浏览器中
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
            const inputLineCount = document.getElementById('inputLineCount');
            const outputLineCount = document.getElementById('outputLineCount');
            const removedCountSpan = document.getElementById('removedCount');

            // 辅助：统计行数（按换行符分割，空字符串视为0行）
            function countLines(text) {
                if (text === '') return 0;
                // 使用 split(/\r?\n/) 兼容 \r\n 和 \n
                return text.split(/\r?\n/).length;
            }

            // 更新输入行数显示
            function updateInputStats() {
                const text = inputArea.value;
                const lines = countLines(text);
                inputLineCount.textContent = lines + ' 行';
            }

            // 更新输出行数显示
            function updateOutputStats() {
                const text = outputArea.value;
                const lines = countLines(text);
                outputLineCount.textContent = lines + ' 行';
            }

            // 核心清理函数：删除空行（包括只含空格或制表符的行）
            // 保留非空行的首尾空格、行内字符和顺序
            function removeEmptyLines(text) {
                if (text === '') return '';

                // 按行分割，保留所有行（包括空行）
                // 使用 /\r?\n/ 分割，兼容 Windows 和 Unix 换行
                const lines = text.split(/\r?\n/);

                // 过滤：如果一行 trim() 后为空字符串，则视为空行（包括只含空格/制表符）
                // 注意：trim() 会移除首尾空白，包括空格、制表符、换行等，但这里每行已经不含换行符
                const nonEmptyLines = lines.filter(line => line.trim() !== '');

                // 用 \n 连接（统一使用 Unix 换行，但保留原始内容）
                return nonEmptyLines.join('\n');
            }

            // 执行清理并更新界面
            function performClean() {
                const originalText = inputArea.value;
                const cleanedText = removeEmptyLines(originalText);

                // 计算移除的空行数量
                const originalLines = originalText === '' ? [] : originalText.split(/\r?\n/);
                const cleanedLines = cleanedText === '' ? [] : cleanedText.split('\n');
                const removed = originalLines.length - cleanedLines.length;

                // 更新输出区域
                outputArea.value = cleanedText;

                // 更新统计信息
                removedCountSpan.textContent = removed >= 0 ? removed : 0;
                updateInputStats();
                updateOutputStats();

                // 如果输出为空，可以给个提示（但保留占位符）
                // 复制按钮状态重置
                resetCopyButton();
            }

            // 重置复制按钮样式
            function resetCopyButton() {
                copyBtn.classList.remove('copied');
                copyBtn.innerHTML = `
                    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                        <rect x="9" y="9" width="13" height="13" rx="2" ry="2"></rect>
                        <path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"></path>
                    </svg>
                    复制结果
                `;
            }

            // 复制结果到剪贴板
            async function copyResult() {
                const textToCopy = outputArea.value;
                if (!textToCopy) {
                    // 如果输出为空，可以复制空字符串，但给个轻提示（不干扰）
                    // 但为了体验，仍然复制空字符串
                }

                try {
                    await navigator.clipboard.writeText(textToCopy);
                    // 成功反馈
                    copyBtn.classList.add('copied');
                    copyBtn.innerHTML = `
                        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                            <polyline points="20 6 9 17 4 12"></polyline>
                        </svg>
                        已复制
                    `;
                    // 2秒后恢复
                    setTimeout(() => {
                        resetCopyButton();
                    }, 2000);
                } catch (err) {
                    // 降级方案：选中输出区域并提示手动复制
                    outputArea.select();
                    outputArea.setSelectionRange(0, 99999);
                    try {
                        document.execCommand('copy');
                        copyBtn.classList.add('copied');
                        copyBtn.innerHTML = `
                            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                                <polyline points="20 6 9 17 4 12"></polyline>
                            </svg>
                            已复制
                        `;
                        setTimeout(resetCopyButton, 2000);
                    } catch (e) {
                        alert('复制失败，请手动选择输出内容复制。');
                    }
                }
            }

            // 清空所有内容
            function clearAll() {
                inputArea.value = '';
                outputArea.value = '';
                removedCountSpan.textContent = '0';
                updateInputStats();
                updateOutputStats();
                resetCopyButton();
                inputArea.focus();
            }

            // 输入时实时更新行数统计（不自动清理，保持用户控制）
            function onInputChange() {
                updateInputStats();
                // 如果输出区域有内容，但输入变了，输出区域保留旧结果，但统计不自动更新
                // 但为了清晰，可以保留输出不变，用户可再次点击清理
                // 这里不做自动清理，尊重用户操作
            }

            // 初始化事件监听
            function init() {
                // 清理按钮
                cleanBtn.addEventListener('click', performClean);

                // 复制按钮
                copyBtn.addEventListener('click', copyResult);

                // 清空按钮
                clearBtn.addEventListener('click', clearAll);

                // 输入区域监听
                inputArea.addEventListener('input', onInputChange);

                // 初始化统计
                updateInputStats();
                updateOutputStats();

                // 设置初始移除计数为0
                removedCountSpan.textContent = '0';

                // 可选：输入框支持 Ctrl+Enter 快速清理
                inputArea.addEventListener('keydown', (e) => {
                    if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') {
                        e.preventDefault();
                        performClean();
                    }
                });

                // 输出区域只读，但允许选中复制
                // 不需要额外处理
            }

            // 启动
            init();
        })();
    </script>
</body>
</html>
```
### 核心功能与操作说明

这个工具的核心是“清理空行”，操作非常直观。您只需将文本粘贴到左侧，点击按钮，右侧就会显示结果。

- **清理逻辑**：工具会逐行检查，只要一行在去除首尾空白后为空（包括只含空格或制表符的行），就会被删除。非空行的所有字符（包括首尾空格）和顺序都会原样保留。
- **操作方式**：界面分为左右两个面板。您可以在左侧“原始文本”区域粘贴内容，然后点击“清理空行”按钮，结果会立即显示在右侧“清理结果”区域。您也可以使用快捷键 `Ctrl+Enter`（或 `Cmd+Enter`）快速执行清理。
- **辅助功能**：顶部会实时显示输入和输出的行数。清理后，底部会统计并显示移除了多少个空行。您还可以一键复制结果或清空所有内容，方便重复使用。