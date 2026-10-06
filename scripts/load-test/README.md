# 2 核 4 GB 本机隔离压力测试

用途：为购买小规格服务器提供实测依据。只限制 CPU/内存，不等同于腾讯云实际 CPU、公网带宽、磁盘性能或 DeepSeek 容量。

待测后端使用正常 Spring Security、验证码、CSRF、Redis Session、租户/工作区和文档所有权校验；验证码由测试生成器仅读取隔离 Redis 中的本轮 Session，不修改认证代码。合成账户不是管理员。

所有上游请求发送到本机延迟模拟器；语义检索、MapReduce 与邮件发送关闭。**不能据此证明模型质量、真实上游限流或收费业务容量。** 生成器运行于 Windows，后端、PostgreSQL、Redis 运行于同一个 2 CPU / 4 GB 的 Docker/WSL 环境。

## 原本机数据库的持久限制

本任务已对现有 PostgreSQL、Redis 容器应用 CPU/内存限制。后续重新创建容器时，使用新增覆盖文件保留限制：

```powershell
docker-compose -f infra/docker-compose.yml -f infra/docker-compose.resources-2c4g.yml up -d
```

覆盖文件只针对原本机数据库。正式 API 的 JVM 和容器内存也应单独限制；下面的独立测试已包含 API。

## 测试资源预算

| 服务 | CPU 上限 | 容器内存上限 | 额外限制 |
| --- | --- | --- | --- |
| API | 1.25 核 | 2 GiB | Java 21，堆 256 MiB–1 GiB，Hikari 10 |
| PostgreSQL | 0.5 核 | 768 MiB | 全新 `budget_load` 数据库 |
| Redis | 0.25 核 | 256 MiB | 数据上限 192 MiB，noeviction，Session/租约保留应用 TTL |

CPU 上限合计 2 核，内存上限合计 3 GiB，为 WSL 系统和 Docker 留空间。CPU 上限不是预留资源。没有新增付费软件或订阅。

## 复现

先确认 Docker Linux 引擎全局为 2 CPU / 4 GB，其他容器不运行。只使用新压测卷；不要修改 Compose 中的数据库名、数据卷挂载或模型地址。

1. 将当前 `services/api/pom.xml`、`src/main/java` 和非生产资源复制到 `target/budget-load-20261006/build`；不复制 `.env`、密钥文件、生产配置。在快照目录执行 `mvn -B -ntp '-Dmaven.test.skip=true' package`。源码和 Jar 哈希在准备时保存。
2. 在项目根目录执行 `python scripts/load-test/budget_load.py prepare`。随机测试凭据写入 Git 忽略的 `target`，不在命令参数、日志或报告中输出。
3. 执行 `docker build -f infra/load-test/Dockerfile.java21 --build-arg BUDGET_ALPINE_MIRROR=https://mirrors.tuna.tsinghua.edu.cn/alpine -t prompt-optimizer-budget-java21 infra/load-test`，构建仅供测试的 Java 21 运行镜像。软件包仍校验 Alpine 签名，镜像源遵循清华 TUNA 的 Alpine 使用说明。单独终端启动 `python scripts/load-test/budget_load.py serve`。这是无收费的模拟器。
4. 启动隔离环境：

```powershell
$env:BUDGET_LOAD_RUN_DIR='E:/MyProject/prompt-optimizer-platform/target/budget-load-20261006'
docker-compose -f infra/load-test/docker-compose.yml -p prompt-optimizer-budget up -d
```

5. 等待 API `/api/v1/health` 返回 200，再为新测试库写入合成账户：

```powershell
Get-Content -Raw scripts/load-test/seed.sql | docker exec -i prompt-optimizer-budget-postgres-1 psql -v ON_ERROR_STOP=1 -U budget_load -d budget_load
```

6. 执行 `python scripts/load-test/budget_load.py smoke` 验证正常、异常、边界与恢复；随后执行 `python scripts/load-test/budget_load.py full` 完成分级持续生成、大文档上传及解析测试，最后执行 `python scripts/load-test/budget_load.py contexts` 验证带已索引 10 MiB DOCX 的持续生成。最后一项应紧接本轮新数据集，约 511 MiB 的原始文档量已经接近 512 MiB 上限，不能反复追加新文档。结果、资源采样仅保留汇总，不保存 Cookie、CSRF、验证码、密码、授权头或请求正文。
7. 结束后停止专用测试项目与模拟器：

```powershell
docker-compose -f infra/load-test/docker-compose.yml -p prompt-optimizer-budget stop
```

数据卷保留，下一轮需明确是否继续该测试数据集；不要用 `down -v` 删除业务数据。当前报告说明每阶段数据集规模与测试长度。HTTP 200 还要通过四要素结果非空校验；大文档必须实际达到 READY，PARTIAL 不计入完整成功。
