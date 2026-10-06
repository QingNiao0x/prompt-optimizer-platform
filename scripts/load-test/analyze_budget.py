"""把本轮无敏感正文的压测记录汇总为可审阅证据与 Markdown 报告。"""
from __future__ import annotations

import hashlib
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
RUN = ROOT / 'target' / 'budget-load-20261006'
OUT = ROOT / 'docs' / 'testing' / 'evidence' / 'budget-load-2026-10-06'
REPORT = ROOT / 'docs' / 'testing' / '2核4GB-Docker压力测试-2026-10-06.md'


def load(name: str) -> object:
    return json.loads((RUN / name).read_text(encoding='utf-8'))


def mib(value: str) -> float:
    number, unit = re.match(r'([\d.]+)\s*(\w+)', value.strip()).groups()
    return float(number) * {'B': 1/1048576, 'kB': 1000/1048576, 'KiB': 1/1024,
                            'MB': 1000000/1048576, 'MiB': 1, 'GB': 1000000000/1048576,
                            'GiB': 1024}.get(unit, 1)


def resource_summary(samples: list[dict], phase: str | None = None) -> dict:
    selected = [sample for sample in samples if phase is None or sample.get('phase') == phase]
    peaks = {}
    total_peak, cpu_peak = 0.0, 0.0
    available = []
    for sample in selected:
        total, cpu = 0.0, 0.0
        for row in sample.get('containers', []):
            service = row['Name'].removeprefix('prompt-optimizer-budget-').removesuffix('-1')
            memory = mib(row['MemUsage'].split('/')[0])
            cores = float(row['CPUPerc'].strip('%')) / 100
            peak = peaks.setdefault(service, {'peak_mib': 0, 'peak_cpu_cores': 0, 'peak_pids': 0})
            peak['peak_mib'] = max(peak['peak_mib'], memory)
            peak['peak_cpu_cores'] = max(peak['peak_cpu_cores'], cores)
            peak['peak_pids'] = max(peak['peak_pids'], int(row['PIDs']))
            total += memory
            cpu += cores
        total_peak, cpu_peak = max(total_peak, total), max(cpu_peak, cpu)
        if sample.get('wsl_memory_kib', {}).get('MemAvailable'):
            available.append(sample['wsl_memory_kib']['MemAvailable'] / 1024)
    return {'samples': len(selected), 'services': peaks, 'simultaneous_container_peak_mib': round(total_peak, 2),
            'simultaneous_cpu_peak_cores': round(cpu_peak, 3),
            'minimum_wsl_available_mib': round(min(available), 2) if available else None}


def boundary_checks(rows: list[dict]) -> list[dict]:
    expected = {
        'empty_prompt': (400, 'INVALID_ARGUMENT'),
        'oversized_document': (400, 'INVALID_ARGUMENT'),
        'invalid_path': (400, 'DOCUMENT_PROCESSING_ERROR'),
        'cross_user_document': (404, 'DOCUMENT_PROCESSING_ERROR'),
        'negative_chunk_index': (400, 'DOCUMENT_PROCESSING_ERROR'),
        'cross_tenant_history': (404, None),
        'upstream_429': (503, 'PROVIDER_RATE_LIMITED'),
        'upstream_503': (502, 'PROVIDER_UNAVAILABLE'),
        'upstream_timeout': (504, 'PROVIDER_TIMEOUT'),
    }
    checks = []
    for row in rows:
        name = row['name']
        if name == 'per_user_limit_3':
            ok = row['successes'] == 3 and row['http'].get('429') == 1
            wanted = '3 successes + 1 HTTP 429'
        elif name == 'global_limit_100':
            ok = row['successes'] == 100 and row['http'].get('503') == 1
            wanted = '100 successes + 1 HTTP 503'
        elif name.endswith('_recovery'):
            ok = row['status'] == 200
            wanted = 'HTTP 200 and valid result'
        elif name in expected:
            status, code = expected[name]
            ok = row['status'] == status and (code is None or row['code'] == code)
            wanted = f'HTTP {status}' + (f' / {code}' if code else '')
        else:
            continue
        checks.append({'name': name, 'passed': ok, 'expected': wanted,
                       'actual': {k: v for k, v in row.items() if k != 'name'}})
    return checks


def main() -> None:
    results = load('results-full.json')
    smoke = load('results-smoke.json')
    resources = load('resource-samples.json')
    runtime = load('runtime-verification.json')
    context_results = load('results-contexts.json') if (RUN / 'results-contexts.json').is_file() else None
    if not results.get('completed'):
        raise SystemExit('压力测试未完成，不能生成完成报告')
    OUT.mkdir(parents=True, exist_ok=True)
    # 只复制已设计为无凭据/正文的白名单文件；不遍历 secrets 或日志。
    for name in ('results-full.json', 'results-smoke.json', 'results-contexts.json', 'resource-samples.json',
                 'runtime-verification.json', 'source-manifest.json', 'simulator-preflight.json',
                 'database-summary.json'):
        if (RUN / name).is_file():
            (OUT / name).write_bytes((RUN / name).read_bytes())
    checks = boundary_checks(results.get('boundaries', []))
    aggregate = resource_summary(resources)
    jar = RUN / 'build' / 'target' / 'prompt-optimizer-api-0.1.0-SNAPSHOT.jar'
    summary = {'resources': aggregate, 'boundaries': checks,
               'jar_sha256': hashlib.sha256(jar.read_bytes()).hexdigest()}
    (OUT / 'summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding='utf-8')
    phases = results['phases']
    generations = [row for row in phases if row['name'].startswith('generation-')]
    documents = [row for row in phases if row['name'].startswith('upload-')]
    mixed = next((row for row in phases if row['name'].startswith('mixed-')), None)
    all_generation_ok = all(row['successes'] == row['requests'] and
                            row['light_requests']['successes'] == row['light_requests']['requests']
                            for row in generations)
    lines = [
        '# 2 核 4 GB Docker 压力测试（2026-10-06）', '',
        '## 实测结论', '',
        ('本轮 5–100 个独立用户持续生成与同时进行的登录状态/历史查询全部成功。'
         if all_generation_ok else '本轮部分阶段存在失败，见下表，不应按最大用户数上线。'),
        '结果支持把 2 核 4 GB 作为初期有限流的部署候选。它不构成腾讯云实际机器、真实 DeepSeek、所有文档类型或任意工作负载的人数保证。',
        '上线初期建议全局生成上限先设 20、单用户仍为 3；大文档先控制在少量并行处理。20 是基于较低已测负载保留余量的运营建议，不是测试证明的安全临界值；本次测量配置保留全局 100，以验证容量与拒绝边界。', '',
        '## 环境与实际操作', '',
        '| 项目 | 实际条件 |', '| --- | --- |',
        '| 总资源 | Docker Linux 引擎：2 CPU；WSL 内存上限 4 GB；实际 MemTotal 约 3.83 GiB；无 Swap |',
        '| API | 1.25 CPU / 2 GiB；Java 21；JVM -Xms256m -Xmx1024m；ActiveProcessorCount=2；Hikari 10 |',
        '| PostgreSQL | 0.5 CPU / 768 MiB；PostgreSQL 16；全新 budget_load 库与新卷；现有 V1–V12 迁移用于新库初始化 |',
        '| Redis | 0.25 CPU / 256 MiB；Redis 7；maxmemory=192 MiB / noeviction；真实 Session 与并发租约；本轮 AOF 关闭 |',
        '| 模型 | Windows 本机 OpenAI 兼容延迟模拟器；10 秒 / 30 秒延迟；非内置即时 Mock；无付费调用 |',
        '| 文档 | 合成 UTF-8 TXT 和有效 DOCX（ZIP_STORED），按真实 1 MiB 分片上传、异步解析并达到 READY；附加带索引文档生成专项 |',
        '| 身份 | 110 个合成普通账户；实际 106 个独立登录会话；每账户独立租户/工作区；正常密码、图形验证码与 CSRF |',
        '| 生成器 | 在 Windows 宿主机运行，位于待测 Docker 资源限制之外 |', '',
        '原本机 PostgreSQL、Redis 容器已通过 docker update 应用同样的数据库上限；原数据卷未挂载到测试项目。新增资源覆盖文件可在容器重建时保留上限。API 容器的上限作用于本轮隔离测试；日常后端也必须放入相应资源范围才可复现。',
        '容器 CPU 上限合计 2 核、内存上限合计 3 GiB；CPU 上限不是预留资源。具体限制符合 [Docker Compose 服务资源配置](https://docs.docker.com/reference/compose-file/services/) 与 [Docker 资源限制说明](https://docs.docker.com/engine/containers/resource_constraints/)。',
        '原本机 Redis 配置开启 AOF；专用 Redis 未开启 AOF，因此结果没有覆盖生产 Redis 持久化写盘开销与重启恢复。该差异保留在 runtime-verification.json，不能把内存/并发限额相同解读为全部运行配置相同。',
        'Docker Hub / MCR 下载过慢，最终使用缓存的 Redis Alpine 基础镜像安装 Java 21 和字体；软件包通过 Alpine 签名验证，镜像源依照 [清华 TUNA Alpine 说明](https://mirrors.tuna.tsinghua.edu.cn/help/alpine/)。未升级项目 pom.xml 依赖。', '',
        f"测试开始：{results['started_at']}；结束：{results['ended_at']}（Asia/Shanghai）。", '',
        '## 持续生成与轻量查询', '',
        '每个用户保持一个未结束的生成请求，完成后立即继续；各阶段是持续闭环负载，不是只点击一次。HTTP 200 还要求优化正文非空及四要素完整。P95 表示 95% 的请求在该时间内完成。', '',
        '这一组采用固定简短需求、不携带文档上下文；文件上传和带文档生成另列，不能将 100 人这一项解释为 100 人同时处理复杂大文档。', '',
        '| 同时生成用户 | 设定模型等待 | 实际阶段时长 | 成功/请求 | 生成 P95 | 成功次数/分钟 | 登录状态/历史查询成功数 | 查询 P95 |',
        '| --- | --- | --- | --- | --- | --- | --- | --- |',
    ]
    for row in generations:
        probe = row['light_requests']
        lines.append(f"| {row['virtual_users']} | {row['upstream_delay_seconds']} 秒 | {row['elapsed_seconds']} 秒 | {row['successes']}/{row['requests']} | {row['p95_seconds']} 秒 | {row['successes_per_minute']} | {probe['successes']}/{probe['requests']} | {probe['p95_seconds']} 秒 |")
    lines += ['', '最长模型等待阶段持续约 5 分钟；这不是数小时/数天的稳定性或内存泄漏验证。成功次数受设定的模型等待控制，不能当作真实厂商的吞吐量。', '',
              '## 大文档上传与解析', '',
              '| 同时上传用户 | 文件类型 | 单文件实际 MiB | READY/总数 | 上传至可用 P95 | 全部文档提取字符总数 |',
              '| --- | --- | --- | --- | --- | --- |']
    for row in documents:
        language = 'DOCX' if '-docx-' in row['name'] else 'TXT'
        chars = sum(item.get('extracted_characters', 0) for item in row['details'])
        lines.append(f"| {row['virtual_users']} | {language} | {row['bytes_per_document']/1048576:.3f} | {row['successes']}/{row['requests']} | {row['p95_seconds']} 秒 | {chars:,} |")
    if mixed:
        row, doc = mixed['generating'], mixed['documents']
        lines += ['', f"混合场景：20 人生成（模型等待 30 秒）同时 5 人上传约 10 MiB DOCX；生成 {row['successes']}/{row['requests']} 成功，P95 {row['p95_seconds']} 秒；文档 {doc['successes']}/{doc['requests']} READY，P95 {doc['p95_seconds']} 秒；轻量查询 P95 {row['light_requests']['p95_seconds']} 秒。"]
    if context_results:
        lines += ['', '### 带已索引大文档的生成专项', '',
                  f"专项完成状态：{context_results.get('completed')}；开始 {context_results.get('started_at')}，结束 {context_results.get('ended_at')}。各用户引用自己的已索引文档，后端实际进行文档上下文检索与摘要组装；无需将整个文件再次作为 JSON 正文上传。"]
        for row in context_results.get('phases', []):
            lines.append(f"5 人各携带约 10 MiB DOCX 持续生成：{row['successes']}/{row['requests']} 成功；模型等待 {row['upstream_delay_seconds']} 秒；阶段 {row['elapsed_seconds']} 秒；生成 P95 {row['p95_seconds']} 秒；轻量查询 P95 {row['light_requests']['p95_seconds']} 秒。")
        if not context_results.get('completed'):
            lines.append('该专项没有完成，不可把前面的简短生成结果推广到带文档场景。')
    lines += ['', '当前代码的单文件上限为 50 MiB、活动文档原始体积总量上限 512 MiB、活动文档数量上限 100；2 CPU 下实际只有 2 个解析工作线程，其他任务会等待。单文件上限不等于可同时解析该数量的文件。见 TemporaryDocumentIndexService 与 StreamingDocumentExtractor。', '',
              '## 资源与恢复', '',
              '| 服务 | 采样内存峰值 MiB | 采样 CPU 峰值（核） | PIDs 峰值（含线程） |', '| --- | --- | --- | --- |']
    for name, peak in aggregate['services'].items():
        lines.append(f"| {name} | {peak['peak_mib']:.2f} | {peak['peak_cpu_cores']:.3f} | {peak['peak_pids']} |")
    lines += ['', f"三个容器同一采样时刻的内存合计峰值 {aggregate['simultaneous_container_peak_mib']} MiB；WSL 可用内存最低 {aggregate['minimum_wsl_available_mib']} MiB；CPU 合计采样峰值 {aggregate['simultaneous_cpu_peak_cores']} 核。采样间隔约 5 秒，可能遗漏短暂峰值；Docker Linux 内存统计扣除文件缓存，因此同时给出 WSL MemAvailable。", '',
              '收尾核验：专用测试项目三个容器均已停止，OOMKilled=false、RestartCount=0；API 的退出码 143 为本次主动发送 SIGTERM 停止，数据库与 Redis 退出码 0。见 runtime-verification.json。',
              '原本机两个数据库容器在收尾时已不在 Docker 列表中，但原 PostgreSQL、Redis 数据卷已核实仍保留。本轮执行未包含删除原容器/原数据卷的命令；容器消失原因尚未确认，因此不能声称原容器状态完全未变。没有自动重建它们，以免干扰其他会话的操作。新增原数据库资源覆盖文件用于后续正常重建时继续限制。', '',
              '## 正常、异常与边界核验', '',
              '| 场景 | 期望 | 实际（状态/代码或数量） | 结论 |', '| --- | --- | --- | --- |']
    for item in checks:
        actual = item['actual']
        value = (f"{actual['status']} / {actual['code']}" if 'status' in actual
                 else f"{actual['successes']}/{actual['requests']} 成功；HTTP {json.dumps(actual['http'])}")
        lines.append(f"| {item['name']} | {item['expected']} | {value} | {'通过' if item['passed'] else '未通过'} |")
    if (RUN / 'database-summary.json').is_file():
        database = load('database-summary.json')
        lines += ['', f"隔离数据库核验：成功生成响应 {database['successful_generation_responses']:,} 次，优化记录 {database['optimization_records']:,} 条，数量一致；操作审计 {database['audit_events']:,} 条，租户/工作区/用户归属不一致 {database['ownership_mismatch']} 条；迁移最大版本 V{database['flyway_max_version']}。本轮 usage_event 无记录，未验证 Token/费用入账。"]
    lines += ['', '已复现超时分类问题：模拟上游等待 65 秒，应用约 60 秒结束请求，但响应为 HTTP 502 / PROVIDER_UNAVAILABLE；按 GlobalExceptionHandler.mapProviderError 的超时契约应为 HTTP 504 / PROVIDER_TIMEOUT。后续请求可恢复。容量测试完成不代表该错误分类已修复。',
              '建议检查 OpenAiCompatiblePromptEnhancementProvider.requestEnhancement 中 RestClientException 的兜底分支是否覆盖响应读取阶段的超时原因链，并补充真实延迟响应的集成回归。本任务未修改这段业务代码，根因仍需针对异常链核实。', '',
              '## 验证边界与上线使用建议', '',
              '- 已测：直接增强的真实服务端认证、Redis 并发限制、PostgreSQL 历史持久化、文档分片上传及文本/DOCX 解析；合成文档只位于专用环境。',
              '- 未测：真实 DeepSeek 的速率、配额、费用、模型质量和长尾延迟；邮箱注册/发送验证码；Plan 多轮全流程；语义检索、MapReduce、OCR；PDF/XLSX/含大量图片文档；前端浏览器负载、HTTPS/Nginx 静态服务；公网带宽、云盘 IOPS、70 GB 盘的长期增长与真实云 CPU；大历史库和管理员复杂统计查询。',
              '- 文档内容为合成重复语料、DOCX 未压缩包，不能代表所有复杂文档；没有用户逐秒随机到达的开放模型，也没有数小时耐久测试。',
              '- 上线初期保留全局/单账户并发限制，控制大文档队列和上传字节总量；限流与排队提示应让用户明确知道等待或重试。',
              '- 若生成失败，分别核对平台并发拒绝、上游 429、超时、数据库/Redis 异常；不能只凭在线人数判断机器不足。',
              '- 成本与配额仍要按用户设置；本轮没有任何付费模型调用，不建议仅因注册用户达到 100 就立即升级机器。', '',
              '## 文件与复现', '',
              '- `infra/docker-compose.resources-2c4g.yml`：原数据库资源覆盖文件。',
              '- `infra/load-test/docker-compose.yml` 与 `Dockerfile.java21`：专用测试环境，未连接业务数据卷。',
              '- `scripts/load-test/README.md`：复现步骤；`budget_load.py`：测试生成器；`seed.sql`：仅允许 budget_load 的合成数据夹具。',
              '- `docs/testing/evidence/budget-load-2026-10-06/`：脱敏结果、实际资源采样、容器核验、数据库计数、源码快照哈希；不包含凭据、Cookie、验证码、CSRF、授权头、原始提示词或文件正文。',
              f"- 被测 Jar SHA-256：`{summary['jar_sha256']}`。当前工作区存在用户未提交修改，测试针对打包时的代码快照；源码清单记录对应候选，不能把其他会话的后续修改视为本轮已验证。", '']
    REPORT.write_text('\n'.join(lines), encoding='utf-8')
    print(json.dumps({'report': str(REPORT), 'generation_requests': sum(r['requests'] for r in generations),
                      'generation_successes': sum(r['successes'] for r in generations),
                      'resources': aggregate, 'boundary_failed': [i['name'] for i in checks if not i['passed']]}, ensure_ascii=False))


if __name__ == '__main__':
    main()
