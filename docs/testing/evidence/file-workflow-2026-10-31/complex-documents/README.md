# 复杂文档最终代码复验

`live-report.json` 记录基线提交与源码 SHA-256、文件摘要、真实 HTTP 状态、内容及错误断言。未保存登录密码、令牌或真实用户材料。7 份合成文件复用固定本机合成账号，经实际上传确认与解析；3 份异常文档经实际接口验证。模型调用为 0，视觉识别选择“仅本地提取”，没有上传合成材料到模型供应商。

`test-summary.json` 摘录最终五个测试类各自最近一次 Surefire 结果，共 53 项；共享四类与新兼容类分别运行，不是全仓库回归。`coverage-desktop.png`、`coverage-mobile.png` 展示未识别部分仍有提示；`files-desktop.png` 展示文档数量及中性索引徽标。

结构化夹具与断言在 `services/api/src/test/java/com/promptoptimizer/context/service/impl/ComplexDocumentCompatibilityTest.java`；部分 Word/ODF 夹具是最小 XML 包，验证提取机制，不认证所有办公应用的文件兼容性。真实脚本原件在本机 `tmp/file-workflow-20261031/complex-live.mjs`，后台使用隔离 19000 端口；原测试启动失败和第一次成功结果保留在同目录下，未覆盖。

证据只证明列出的正文、表格关系、页序、安全及显示路径；不证明未识别图片已被阅读，不等于 OCR、复杂版式或全部专有 WPS 格式已通过。
