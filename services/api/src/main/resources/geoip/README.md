# 随包 IP 归属数据库

本目录存放 Analytics 默认离线查询所需的 IPv4、IPv6 城市库。运行时读取本目录资源，不下载数据库，也不向上游发送待查询 IP。

## 来源与固定版本

- 上游项目：[lionsoul2014/ip2region](https://github.com/lionsoul2014/ip2region)。
- 数据发布版本：[v3.18.0](https://github.com/lionsoul2014/ip2region/releases/tag/v3.18.0)。
- 固定提交：`c1a1fc7d5941760db3f8431dc05c48cf7f0e30a1`。
- 配套 Java SDK：`org.lionsoul:ip2region:3.3.7`；SDK 版本与数据版本分别记录。

| 本地文件 | 上游原件 | 字节数 | SHA-256 |
| --- | --- | --- | --- |
| `ip2region_v4.xdb` | [固定提交 IPv4 XDB](https://raw.githubusercontent.com/lionsoul2014/ip2region/c1a1fc7d5941760db3f8431dc05c48cf7f0e30a1/data/ip2region_v4.xdb) | 11122036 | `8e31bbdccb5bf21028af10592d4312ec975da0bffa108c0c5d862a12190f9ad3` |
| `ip2region_v6.xdb` | [固定提交 IPv6 XDB](https://raw.githubusercontent.com/lionsoul2014/ip2region/c1a1fc7d5941760db3f8431dc05c48cf7f0e30a1/data/ip2region_v6.xdb) | 37277813 | `939f6b46bd2b8bec3cf7c5ceb8ba782266ae9b1f35b5ba7916700dec0b7506ed` |
| `LICENSE-ip2region.txt` | [固定提交 LICENSE.md](https://raw.githubusercontent.com/lionsoul2014/ip2region/c1a1fc7d5941760db3f8431dc05c48cf7f0e30a1/LICENSE.md) | 12976 | `fe01f2f8fcaafac539154e6aa80b0b7f8af54e01dc4d52322f72971991c6280e` |

以上为固定提交完整下载文件的实测大小和 SHA-256。更新数据时须同步更新此表，并检查最终 JAR 内的数据库与这些值一致。

## 许可与归属

保留固定提交的上游许可证全文于 `LICENSE-ip2region.txt`。上游根许可证给出 Apache-2.0 或 MIT 的许可选择；本项目保留该原文，不重写许可条款。Java SDK 的许可按其 Maven 制品及上游声明保留。更新或分发数据库时应一并保留来源、版本和许可，不将第三方数据标为本项目原创。

## 格式与运行边界

当前版本的记录格式为 `国家|省份|城市|ISP|国家代码`，解析器只投影国家、省份、城市。空值、`0` 及约定的未知／保留占位值不会被当作实际地理名称。一个返回位置来自一个数据库，不跨库拼接省、市。

上游公开数据并不承诺固定更新周期或每个 IP 的城市精度。结果表示 IP 归属估算，不能证明真实用户位置；无法取得的层级继续返回 `null`。本目录不包含用户查询记录、账号、凭据或用户提供的 IP 样本。

配置、来源优先级、安全诊断及部署步骤见仓库 `docs/development/离线IP归属解析与部署.md`。数据库更新须记录新的固定来源与校验值，并运行受影响离线验证；不能仅替换同名文件而保留旧的来源信息。
