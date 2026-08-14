# 本地基础设施

本目录目前只负责本地开发需要的 PostgreSQL 和 Redis。前端与 Spring Boot 后端仍然在宿主机上运行，方便断点调试和查看日志。

## 第一次启动

先启动 Docker Desktop，确认下面命令能看到 Docker Server 信息：

```powershell
docker version
```

在项目根目录执行：

```powershell
docker-compose -f infra/docker-compose.yml up -d
docker-compose -f infra/docker-compose.yml ps
```

看到 `postgres` 和 `redis` 的状态为 `healthy` 后，再启动后端。

## 查看和停止

```powershell
docker-compose -f infra/docker-compose.yml logs -f postgres
docker-compose -f infra/docker-compose.yml logs -f redis
docker-compose -f infra/docker-compose.yml down
```

`down` 只停止并删除容器，命名卷中的数据库数据会保留。除非确认要删除本地数据，否则不要执行 `down -v`。

## 默认本地连接

| 服务 | 地址 | 默认值 |
| --- | --- | --- |
| PostgreSQL | `localhost:5432` | 数据库 `prompt_optimizer`，用户/密码 `prompt_optimizer` |
| Redis | `localhost:6379` | 无密码 |

生产环境不使用这组默认密码，改用托管 PostgreSQL、托管 Redis 和密钥管理服务。
