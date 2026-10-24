package com.promptoptimizer.analytics.infrastructure;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * 限定离线归属查询的输入为公网 IP 字面量，防止 SDK 的字符串入口意外触发 DNS。
 * 本类只决定是否适合定位，不改变审计中保留原始来源 IP 的规则。
 *
 * @author QingNiao
 * @since 0.1.0
 */
final class PublicGeoIpAddress {

    private PublicGeoIpAddress() { }

    /** 非法、私网、回环、组播和文档示例地址不具有可展示的公网归属，返回 null。 */
    static InetAddress parse(String supplied) {
        if (supplied == null) return null;
        String value = supplied.trim();
        if (value.isEmpty() || value.length() > 45) return null;
        // 仅包含 IP 字符还不够：IPv4 必须是四段，避免 Java 把短数字视为公网地址。
        boolean ipv6 = value.indexOf(':') >= 0;
        if (ipv6 ? !value.matches("[0-9a-fA-F:.]+") : !validIpv4(value)) return null;
        try {
            InetAddress address = InetAddress.getByName(value);
            if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                    || address.isSiteLocalAddress() || address.isMulticastAddress()) return null;
            byte[] bytes = address.getAddress();
            // Java 将 IPv4-mapped IPv6 归一为四字节；必须继续应用 IPv4 的保留范围判断。
            return isReserved(bytes) ? null : address;
        } catch (UnknownHostException invalidLiteral) {
            return null;
        }
    }

    /** 禁止歧义数字和非十进制写法；调用 InetAddress 前即可排除所有主机名。 */
    private static boolean validIpv4(String value) {
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) return false;
        for (String part : parts) {
            if (!part.matches("[0-9]{1,3}") || (part.length() > 1 && part.startsWith("0"))
                    || Integer.parseInt(part) > 255) return false;
        }
        return true;
    }

    /** 排除运营商共享、基准测试、文档和保留段，不能把这些地址误展示为真实用户所在地。 */
    private static boolean isReserved(byte[] bytes) {
        int first = Byte.toUnsignedInt(bytes[0]);
        int second = Byte.toUnsignedInt(bytes[1]);
        if (bytes.length == 4) {
            int third = Byte.toUnsignedInt(bytes[2]);
            return first == 0 || first >= 240
                    || (first == 100 && second >= 64 && second <= 127)
                    || (first == 192 && second == 0 && (third == 0 || third == 2))
                    || (first == 198 && (second == 18 || second == 19 || (second == 51 && third == 100)))
                    || (first == 203 && second == 0 && third == 113);
        }
        return (first & 0xfe) == 0xfc
                || (first == 0x3f && second == 0xff && (Byte.toUnsignedInt(bytes[2]) & 0xf0) == 0)
                || (first == 0x20 && second == 0x01 && bytes[2] == 0 && bytes[3] == 2
                    && bytes[4] == 0 && bytes[5] == 0)
                || (first == 0x20 && second == 0x01 && Byte.toUnsignedInt(bytes[2]) == 0x0d
                    && Byte.toUnsignedInt(bytes[3]) == 0xb8);
    }
}
