这个附件是我们社区器材借用台的完整合成 Vue 3 + TypeScript 工程，没有后端。请修复“新建借用”和“修改借用”两个现有入口共用的余量与提交校验，保持现有页面和错误码。

业务口径已经定好：同一地点、同一器材，只有 CONFIRMED 和 COLLECTED 借用单占用库存；DRAFT、RETURNED、CANCELLED 都不占用，别的地点和器材也不能算进来。新建可借数量＝该地点器材总库存－上述占用，显示至少为 0。修改时输入的是整单的新数量，不是增量；算上限时要排除当前这张单，只扣其他有效占用。

两个页面显示的数字和直接调用业务服务都要遵守这一个口径。预览后其他人可能又确认了借用，点击确认或保存时必须重新读取仓库并检查，不能用先前预览放行。数量必须是 number 类型的正安全整数，小数、非有限值等都用现有 INVALID_QUANTITY 错误。数量超过最新余量时用现有 INSUFFICIENT_STOCK；没有配置库存、借用单不存在及非 CONFIRMED 状态只读的现有边界保留。

保存失败不应改仓库、借用状态或版本，也别清掉页面里用户刚输入的数量和用途；成功后保留既有的用途 trim、版本递增和余量刷新。请检查两个实际页面入口，不只修一份脱离调用链的函数。

只修改当前器材借用链路中确实必要的文件：src/domain/availability.ts、src/domain/quantity.ts、src/application/loanDeskService.ts、src/composables/useNewLoan.ts、src/composables/useLoanEdit.ts、src/components/AvailabilityPanel.vue、src/components/LoanDraftForm.vue、src/views/NewLoanPage.vue、src/views/EditLoanPage.vue。旧台账 src/legacy、活动报名 src/events、押金预览、合成数据、类型与错误码、依赖和其他文件都保持不变，不增删文件或安装依赖。

交付一个 JSON 对象，格式为 {"files":[{"path":"src/实际路径","content":"该文件完整替换内容"}]}。files 只列本次真的修改的文件，路径相对附件工程根目录。不要输出 Markdown 围栏、说明、命令或额外脚本；我会把这些完整文件应用到同一份工程后验收。
