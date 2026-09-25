# Docker 入门与本项目本地环境

## 1. Docker 是什么

Docker 可以把一个服务和它需要的运行环境打包成一个可启动的容器。对于本项目，Docker 主要用来运行 PostgreSQL 和 Redis，Vue 与 Spring Boot 仍然在电脑上运行，方便修改代码和调试。

可以先这样理解几个词：

| 词语 | 直观理解 | 本项目中的例子 |
| --- | --- | --- |
| 镜像 Image | 服务的安装包模板 | `postgres:16-alpine`、`redis:7-alpine` |
| 容器 Container | 镜像启动后的运行实例 | 正在运行的 PostgreSQL 服务 |
| Volume | 容器外的持久化数据目录 | 保存数据库数据，即使容器停止也保留 |
| Port | 电脑与容器之间的端口映射 | `localhost:5432` 访问 PostgreSQL |
| Compose | 一次管理多个容器的配置文件 | `infra/docker-compose.yml` |

容器不是虚拟机。它共享 Docker 的底层系统资源，启动更快；删除容器也不会自动删除命名 Volume 中的数据。

## 2. 为什么项目需要它

Spring Boot 后端默认连接 PostgreSQL 和 Redis。没有它们时：

- 后端无法建立数据库连接；
- Flyway 无法执行建表迁移；
- 后续历史记录和 Redis 限流功能无法联调。

Docker 让团队使用相同的 PostgreSQL 16 和 Redis 7，避免每个人手动安装不同版本。生产环境不一定使用 Docker 内的数据库，通常会换成云厂商托管的 PostgreSQL 和 Redis。

## 3. Windows 第一次使用

本项目使用 Docker Desktop。安装并打开 Docker Desktop 后，等待状态显示 Docker Engine 正在运行，再在 PowerShell 检查：

```powershell
docker version
docker-compose --version
```

当前这台电脑安装的是独立命令 `docker-compose`，所以项目文档使用这个命令。如果你的环境支持新版子命令，也可以把 `docker-compose` 换成 `docker compose`。

## 4. 启动本项目基础设施

在项目根目录执行：

```powershell
cd <项目根目录>
docker-compose -f infra/docker-compose.yml up -d
docker-compose -f infra/docker-compose.yml ps
```

`up -d` 的含义是创建并后台启动 PostgreSQL 和 Redis。第一次启动如果本地没有镜像，Docker 会从镜像仓库下载；后续启动会复用已经下载的镜像。

看到两个服务状态正常后，再启动后端：

```powershell
cd <项目根目录>\services\api
$env:MODEL_PROVIDER_MODE="mock"
mvn -s ..\..\.mvn\settings.xml spring-boot:run
```

Mock Provider 不会调用外部模型，也不需要 DeepSeek API Key，适合先验证本地链路。

## 5. 停止、查看和清理

```powershell
# 查看服务状态
docker-compose -f infra/docker-compose.yml ps

# 查看 PostgreSQL 日志
docker-compose -f infra/docker-compose.yml logs -f postgres

# 查看 Redis 日志
docker-compose -f infra/docker-compose.yml logs -f redis

# 停止并删除容器，保留数据库 Volume
docker-compose -f infra/docker-compose.yml down
```

下面的命令会删除本地数据库和 Redis 数据，只能在确认不需要本地数据时执行：

```powershell
docker-compose -f infra/docker-compose.yml down -v
```

如果只是想重新启动服务，使用 `up -d` 即可，不要使用 `down -v`。

如果当前只想验证前端与后端的核心提示词流程，可以暂时不启动 Docker，使用后端的 `local-mock` 配置：

```powershell
cd <项目根目录>\services\api
mvn -s ..\..\.mvn\settings.xml spring-boot:run -Dspring-boot.run.profiles=local-mock
```

这个模式不会执行数据库迁移，也不会连接 Redis；需要测试历史记录、配置持久化或缓存功能时，仍需启动 Docker 基础设施。

## 6. 本项目的端口和数据

| 服务 | 宿主机地址 | 容器内端口 | 数据是否持久化 |
| --- | --- | --- | --- |
| PostgreSQL | `localhost:5432` | `5432` | 是，命名 Volume |
| Redis | `localhost:6379` | `6379` | 是，命名 Volume，但只存短时数据 |
| Spring Boot | `localhost:8080` | 由本机 Maven 进程提供 | 不适用 |
| Vue | `localhost:5173` | 由本机 npm 进程提供 | 不适用 |

Docker 只负责两个基础设施服务，不会自动启动前端和后端。

## 7. 常见问题

### `Cannot connect to the Docker daemon`

Docker Desktop 没有启动，或者 Docker Engine 还没有准备好。打开 Docker Desktop，等待它完成启动后重试。

### `docker compose` 不是有效命令

当前环境使用的是独立命令：

```powershell
docker-compose --version
```

请使用 `docker-compose -f infra/docker-compose.yml ...`。

### 端口已被占用

如果 5432 或 6379 已被其他 PostgreSQL/Redis 占用，可以在项目根目录的 `.env` 中修改 `POSTGRES_PORT` 或 `REDIS_PORT`，同时调整后端的 `DB_URL` 和 `REDIS_URL`。`.env` 不要提交到 GitHub。
