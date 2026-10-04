import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;

/**
 * 在停掉本地后端前，验证启动环境能建立 Java NIO 选择器和本机回环连接。
 * 不读取项目配置，不访问外部网络；失败保留原始异常，供启动脚本判断退出码。
 *
 * @author QingNiao
 * @since 0.1.0
 */
public final class BackendNioPreflight {
    private BackendNioPreflight() {
    }

    /** 使用临时端口核对 NIO 回环连接，资源随作用域关闭，不启动项目服务。 */
    public static void main(String[] args) throws IOException {
        try (Selector selector = Selector.open();
             ServerSocketChannel server = ServerSocketChannel.open()) {
            server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            try (SocketChannel client = SocketChannel.open(server.getLocalAddress());
                 SocketChannel accepted = server.accept()) {
                if (!client.isConnected() || !accepted.isConnected() || !selector.isOpen()) {
                    throw new IOException("本机 NIO 回环连接未就绪");
                }
            }
        }
    }
}
