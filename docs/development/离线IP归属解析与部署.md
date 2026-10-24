# 离线 IP 归属解析与部署

## 1. 目的与边界

统计审计事件需要保存操作 IP 及国家、省、市。原解析器仅在配置了可读取的 MaxMind 城市库时提供位置；未配置数据库时，合法公网 IP 也会得到空位置。

当前实现增加随应用打包的 ip2region IPv4、IPv6 城市库作为默认数据源，继续兼容已有 MaxMind 配置。查询、加载和回退均在本机完成，不向第三方发送待解析 IP，不在启动时下载数据库。审计事件、管理员接口及权限边界保持原有契约。

IP 归属是数据库对网络地址的估算，不能证明设备或人员的真实物理位置。网络运营商出口、代理、移动网络及数据库更新滞后都可能造成偏差；库中没有的省、市保留 `null`，不生成看似完整的位置。

本次实现不回填历史审计记录。已保存的空位置不会因安装新库自动改变；既有登录会话仍使用原登录位置快照，重新登录才会采集新的登录位置。历史展示补充、数据回填及 `APP_VISIT` 调整不属于本文已实现能力。

## 2. 解析路径

| 组件 | 职责 |
| --- | --- |
| `AnalyticsEventServiceImpl` | 通过原 `GeoLocationResolver` 接口为审计采集位置；解析不可用不阻断事件记录。 |
| `OfflineGeoLocationResolver` | Spring 默认注入入口；校验公网 IP，并按 MaxMind、ip2region 顺序选择结果。 |
| `PublicGeoIpAddress` | 只接受合法 IP 字面量，拒绝域名、端口、IPv6 zone 标识、私网、回环及已列出的保留地址。 |
| `MaxMindGeoLocationResolver` | 读取可选本机 City MMDB，优先使用中文位置名称。 |
| `Ip2RegionGeoLocationResolver` | 从 JAR 或受控本机文件加载 IPv4、IPv6 XDB，以已校验的地址字节查询。 |

来源选择规则：

1. 地址无效或属于排除范围时，直接返回空位置，不执行域名解析或外部请求。
2. MaxMind 返回国家、省、市中任一已知字段时，保留该来源的整个结果。只有国家、没有城市也属于有效部分结果。
3. MaxMind 三个字段均为空时，使用 ip2region。
4. 不跨库拼接国家、省、市，避免生成彼此矛盾的位置。ip2region 返回的占位值会转为 `null`；没有查询结果或查询失败时仍记录审计事件。

IPv4、IPv6 数据库独立加载，一种协议的库缺失或损坏不使另一种协议失效。正常加载后使用 SDK 的全内存模式，避免并发查询共享文件游标。查询不引入 Redis 缓存，也没有逐 IP 缓存失效流程。

## 3. 默认部署

固定的依赖与数据版本如下；Java SDK 版本与数据发布版本是两个独立版本号。

| 项目 | 版本或位置 |
| --- | --- |
| Java SDK | `org.lionsoul:ip2region:3.3.7` |
| 城市库发布版本 | `v3.18.0` |
| 上游固定提交 | `c1a1fc7d5941760db3f8431dc05c48cf7f0e30a1` |
| IPv4 资源 | `services/api/src/main/resources/geoip/ip2region_v4.xdb` |
| IPv6 资源 | `services/api/src/main/resources/geoip/ip2region_v6.xdb` |
| 来源、校验与许可 | 同目录 `README.md`、`LICENSE-ip2region.txt` |

默认构建会将两份 XDB 放入后端 JAR，无需新增服务器环境变量或手动挂载数据库文件。按原项目流程构建、发布并重启后端即可启用新解析器；本地修改代码不等于服务器已经部署。

完整数据库合计约 46 MiB。应用在启动时加载到内存，须为数据库内容、索引及 JVM 对象留出堆空间；约 46 MiB 是数据规模，不能直接当作进程增量内存上限。加载使用 1 MiB 分片控制临时分配，运行期不为每次查询重新加载库。后端制品也会包含这些二进制资源，制品下载与启动资源预算应计入它们。

## 4. 可选的本机数据库覆盖

默认随包库满足普通部署。需要按运维流程更新城市数据时，可使用以下配置覆盖单个协议的库；不会自动联网更新。

| Spring 属性 | 环境变量 | 默认值／用途 |
| --- | --- | --- |
| `app.analytics.geoip.database` | `APP_ANALYTICS_GEOIP_DATABASE` | 空；已有 MaxMind City MMDB 的本机文件路径，保持优先级。 |
| `app.analytics.geoip.ip2region.ipv4` | `APP_ANALYTICS_GEOIP_IP2REGION_IPV4` | `classpath:geoip/ip2region_v4.xdb` |
| `app.analytics.geoip.ip2region.ipv6` | `APP_ANALYTICS_GEOIP_IP2REGION_IPV6` | `classpath:geoip/ip2region_v6.xdb` |

XDB 允许 `classpath:`、绝对本机路径及本机 `file:` URI，例如 `file:/opt/prompt-optimizer/geoip/ip2region_v4.xdb` 或 `file:/E:/data/geoip/ip2region_v4.xdb`。HTTP、HTTPS、UNC 和带远端主机的 `file:` 地址会被拒绝。MaxMind 属性沿用本机文件路径语义，不接受 UNC 网络共享。

覆盖路径指向缺失或损坏的库时，该协议的 ip2region 数据源不可用，系统会输出诊断并保留空位置；不会静默切回被覆盖的随包文件。删除不需要的覆盖配置可恢复默认值。显式空值也会使对应协议的数据源不可用。

外部库必须保持当前五字段格式 `国家|省份|城市|ISP|国家代码`。解析器只使用前三个位置字段，不能直接把旧版四级行政字段格式或不同供应商的同名文件替换进来。资源需要可读、大小在 0 到 128 MiB 之间（不含 0），且协议类型和 SDK 格式兼容。

更新流程：

1. 从已核实、许可适用的上游版本取得完整库，记录发布版本、提交、SHA-256 和许可。
2. 在本地使用候选库验证 IPv4、IPv6、常见区域、异常输入以及原有审计采集；不向第三方上传用户 IP。
3. 如更新随包库，连同来源清单和许可重新构建制品；如使用外部本机库，将部署配置指向已验证的新文件。
4. 按已有生产变更授权流程发布并重启后端，检查两种协议的加载日志，再验证新产生的受控审计事件。

加载器在启动时创建数据库快照，替换磁盘文件不会使运行中的内存库自动更新。需要回退时恢复上一个已验证制品或配置并重启；不修改历史审计事实。

## 5. 诊断与验收

| 日志事件 | 含义与排查方向 |
| --- | --- |
| `analytics.geo_database_loaded`，`source=IP2REGION` | 对应 `protocol=IPV4/IPV6` 库已加载；包含字节数和库头创建时间。默认部署应分别出现两条。 |
| `analytics.geo_database_loaded`，`source=MAXMIND` | 可选 City 库已加载，包含构建时间。 |
| `analytics.geo_database_disabled`，`source=MAXMIND`，`reason=NOT_CONFIGURED` | 未配置可选 MaxMind，是允许的默认状态；继续检查 ip2region 是否加载成功。 |
| `analytics.geo_database_unavailable` | 配置、读取、大小、格式或版本检查失败；按 `source`、`protocol` 和安全的 `reason` 定位。 |
| `analytics.geo_lookup_unavailable` | 运行期查询失败。ip2region 每个解析器实例只报告一次，避免每个事件都刷屏。 |
| `analytics.geo_database_close_failure` | ip2region 关闭过程异常，需要结合应用停止过程排查。 |

解析器的新增诊断不记录查询 IP、用户身份、资源正文或敏感路径。国家、省、市缺失本身不证明解析器报错：先确认库已加载，再检查是否为被排除的地址、库覆盖不足或来源优先级导致的部分结果。

反向代理来源 IP 的可信链仍由 `ClientIpResolver` 决定；离线库只解析收到的地址。若应用收到的是代理内网 IP，应按已有代理信任规则修正实际部署链，不能通过信任任意 `X-Forwarded-For` 请求头来获得看似可解析的公网 IP。

验收应分别记录以下证据，不用编译成功代替业务验证：

- 默认未配置 MaxMind 时，真实随包 IPv4、IPv6 数据库能返回位置；同一地址经旧的无库 MaxMind 入口仍为空，用以验证修复命中原因。
- 截图样本仅在本机离线解析，记录位置结果，不将真实用户 IP 写入测试源码、文档或公开报告。
- 域名、非法地址、私网、回环、文档专用地址及 IPv6 zone 标识返回空位置，不发起网络请求。
- 缺库、损坏库、协议错配、远端资源配置和并发查询的行为符合上述降级规则；原有审计采集仍可用。
- 构建产物中存在两份完整 XDB，其大小和 SHA-256 与来源清单一致；重新启动后检查实际加载日志。

本文说明实现、部署条件和验证要求，不替代本轮实际回归结果，也不表示生产环境已经验收。

## 6. 本轮本地验证（2026-10-24）

在 Java 21 下执行了 10 个测试类，共 98 项，失败、错误、跳过均为 0。覆盖真实 IPv4/IPv6 库、同一地址的无库对照、并发读取、Spring 默认装配、异常输入、远端路径拒绝、单库缺失／损坏／协议错配，以及原有审计投递与恢复回归。

截图提供的两个样本仅作为本机 JVM 验收参数传入，未写入测试源码或本报告，也未发送给任何查询服务。固定城市库给出的归属结果如下；这是数据源查询结果，不是对实际人员所在地的独立核实。

| 样本 | 国家 | 省份 | 城市 |
| --- | --- | --- | --- |
| 截图第一个不同 IP | 中国 | 北京市 | 北京市 |
| 截图第二个不同 IP | 中国 | 云南省 | 曲靖市 |

`OfflineGeoLocationAuditCaptureTest` 使用真实解析器，验证解析结果进入 `PendingAuditEvent` 的操作位置和登录位置快照；同会话改变操作 IP 后登录快照保留，私网地址仍产生审计事件且位置为空。该测试替换身份与投递出口，未连接真实 PostgreSQL，不能表述为数据库及浏览器全链路验收。

本轮测试类：

```text
Ip2RegionGeoLocationResolverTest                 15
OfflineGeoLocationResolverTest                  40
OfflineGeoLocationConfigurationTest              2
OfflineGeoLocationAuditCaptureTest               2
ClientIpResolverTest                             3
AnalyticsEventServiceTest                        6
AnalyticsClientEventServiceTest                  7
AuditEventDatabaseWriterTest                     3
AuditEventDeliveryTest                          17
AuditFailureDiagnosticsTest                      3
```

执行入口为 Maven 的 `-Dtest=<上述类名逗号分隔> test`。截图样本验收可通过本机 `geoip.acceptance.addresses` 系统属性启用；属性不存在时该样本用例跳过，其余公共夹具仍执行。验收日志仅输出样本序号与国省市；Surefire XML 可能保留启动参数，含样本属性的原始报告留在忽略的 `target` 内，不作为公开证据提交。

后端 `-DskipTests package` 成功，已直接读取最终 JAR 的 `BOOT-INF/classes/geoip/` 条目，核对两份 XDB 和许可证的字节数、SHA-256 与来源清单完全一致，并确认 `BOOT-INF/lib/ip2region-3.3.7.jar` 存在。没有启动生产服务、执行数据库迁移或修改生产配置；历史空位置展示未包含在本轮修复中。
