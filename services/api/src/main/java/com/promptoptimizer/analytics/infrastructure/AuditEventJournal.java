package com.promptoptimizer.analytics.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.promptoptimizer.analytics.domain.PendingAuditEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 将事件与提交确认保存为不可变文件。重启时只重放没有 ack 的事件，成功记录也不自动删除。
 * 目录是服务端配置，必须使用每实例的持久卷并限制操作系统访问权限；不向 HTTP 暴露路径或内容。
 *
 * @author QingNiao
 * @since 0.1.0
 */
@Component
public class AuditEventJournal {
    private static final int MAX_EVENT_BYTES = 64 * 1024;
    private final Path directory;
    private final ObjectMapper json;

    /** 使用专用持久目录；默认相对路径便于本地启动，生产需显式绑定持久卷。 */
    public AuditEventJournal(@Value("${app.analytics.delivery.journal-directory:./data/analytics-journal}") String directory,
                             ObjectMapper json) {
        this.directory = Path.of(directory).toAbsolutePath().normalize();
        this.json = json;
    }

    /**
     * 先 force 文件内容再原子发布；重复 ID 返回第一份快照，不能用重试时的位置或时间覆盖原始事实。
     * @throws IOException 目录不可写、磁盘满或文件损坏；调用方必须告警，不能伪造已接收
     */
    public synchronized PendingAuditEvent append(PendingAuditEvent event) throws IOException {
        prepareDirectory();
        Path file = eventFile(event.id());
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            return read(file);
        }
        byte[] bytes = json.writeValueAsBytes(event);
        if (bytes.length > MAX_EVENT_BYTES) {
            throw new IOException("Audit event exceeded journal size limit");
        }
        // 只发布完整 JSON。进程中途退出留下的 .part 保留供运维核查，不被当成已接收事件。
        Path staging = directory.resolve(event.id() + "." + UUID.randomUUID() + ".part");
        writeNew(staging, bytes);
        try {
            Files.move(staging, file, StandardCopyOption.ATOMIC_MOVE);
        } catch (FileAlreadyExistsException conflict) {
            return read(file);
        }
        forceDirectory();
        return event;
    }

    /** 仅在数据库事务已提交后追加确认；数据库提交与 ack 之间退出时重放由主键保证幂等。 */
    public synchronized void acknowledge(UUID eventId) throws IOException {
        prepareDirectory();
        Path ack = directory.resolve(eventId + ".ack");
        if (Files.exists(ack, LinkOption.NOFOLLOW_LINKS)) {
            if (!acknowledged(eventId)) throw new IOException("Invalid journal acknowledgment");
        } else {
            try {
                writeNew(ack, new byte[0]);
                forceDirectory();
            } catch (FileAlreadyExistsException ignored) {
                // 并发重放可重复确认；已存在的不可变 ack 代表同一事件已经提交。
            }
        }
    }

    /** 检查已有提交确认；拒绝符号链接，防止运维目录被替换后读取越界文件。 */
    public synchronized boolean acknowledged(UUID id) throws IOException {
        Path ack = directory.resolve(id + ".ack");
        if (Files.isSymbolicLink(ack)) throw new IOException("Invalid journal acknowledgment");
        if (!Files.exists(ack, LinkOption.NOFOLLOW_LINKS)) return false;
        if (!Files.isRegularFile(ack, LinkOption.NOFOLLOW_LINKS) || Files.size(ack) != 0) {
            throw new IOException("Invalid journal acknowledgment");
        }
        return true;
    }

    /** 扫描未确认的不可变事件；损坏文件独立报告并保留，不能让一个坏文件阻断其他事件恢复。 */
    public synchronized Recovery recover() throws IOException {
        prepareDirectory();
        List<PendingAuditEvent> pending = new ArrayList<>();
        long corrupt = 0;
        try (var files = Files.newDirectoryStream(directory, "*.json")) {
            for (Path file : files) {
                try {
                    PendingAuditEvent event = read(file);
                    if (!file.getFileName().toString().equals(event.id() + ".json")) {
                        throw new IOException("Journal identity mismatch");
                    }
                    try {
                        if (!acknowledged(event.id())) pending.add(event);
                    } catch (IOException invalidAck) {
                        // 损坏确认不能假装已提交；保留原文件并继续幂等入库，但确认失败会持续告警，需人工修复。
                        pending.add(event);
                        corrupt++;
                    }
                } catch (IOException | RuntimeException failure) {
                    corrupt++;
                }
            }
        }
        try (var incomplete = Files.newDirectoryStream(directory, "*.part")) {
            // 发布前退出留下的片段从未得到接收确认，保留并告警，不能默默丢弃或当作完整事实重放。
            for (Path ignored : incomplete) corrupt++;
        }
        pending.sort(java.util.Comparator.comparing(PendingAuditEvent::occurredAt));
        return new Recovery(List.copyOf(pending), corrupt);
    }

    /** 文件大小和链接校验在反序列化前完成，防止损坏文件导致无界内存分配。 */
    private PendingAuditEvent read(Path file) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > MAX_EVENT_BYTES) {
            throw new IOException("Invalid journal event");
        }
        return json.readValue(Files.readAllBytes(file), PendingAuditEvent.class);
    }

    /** 文件名仅来自 UUID，不把用户输入、邮箱或请求路由拼成路径。 */
    private Path eventFile(UUID id) {
        return directory.resolve(id + ".json");
    }

    /** 目录从可信配置产生；POSIX 环境限制为服务账户可读写，Windows 沿用服务目录 ACL。 */
    private void prepareDirectory() throws IOException {
        for (Path ancestor = directory; ancestor != null; ancestor = ancestor.getParent()) {
            if (Files.isSymbolicLink(ancestor)) throw new IOException("Symbolic journal directory is not supported");
        }
        Files.createDirectories(directory);
        if (Files.getFileStore(directory).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
        }
    }

    /** CREATE_NEW 保证不覆盖已落盘的原始事件或确认文件；force(true) 在响应前提交文件内容。 */
    private void writeNew(Path target, byte[] bytes) throws IOException {
        try (FileChannel channel = FileChannel.open(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            if (Files.getFileStore(directory).supportsFileAttributeView("posix")) {
                Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-------"));
            }
            ByteBuffer data = ByteBuffer.wrap(bytes);
            while (data.hasRemaining()) channel.write(data);
            channel.force(true);
        }
    }

    /** POSIX 同时刷新目录项；Windows 不支持目录 FileChannel，原子重命名仍保留完整文件。 */
    private void forceDirectory() throws IOException {
        if (Files.getFileStore(directory).supportsFileAttributeView("posix")) {
            try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
                channel.force(true);
            }
        }
    }

    /** 未确认快照与损坏数量；不包含磁盘路径或原始 JSON，供投递服务构建安全状态。 */
    public record Recovery(List<PendingAuditEvent> events, long corruptFiles) { }
}
