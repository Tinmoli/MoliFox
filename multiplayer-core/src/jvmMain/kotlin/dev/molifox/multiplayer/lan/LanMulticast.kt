// 组播相关小工具：发现侧加入组播组，广播侧按本机 IPv4 网络接口发送。
package dev.molifox.multiplayer.lan

import dev.molifox.core.i18n.I18n
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.UnknownHostException

internal object LanMulticast {
    /** 尽力加入 IPv4 组播组；失败只记录原因，调用方仍可用回环单播。 */
    fun join(
        listener: DatagramSocket,
        groupAddress: String,
        listenPort: Int,
        onError: (Throwable) -> Unit,
    ) {
        val group =
            try {
                InetAddress.getByName(groupAddress)
            } catch (failure: UnknownHostException) {
                onError(IOException(I18n.t("multiplayer.lanMulticast.1", groupAddress), failure))
                return
            }
        val interfaces = usableInterfaces(onError) ?: return
        var joined = false
        interfaces
            .filter { networkInterface ->
                canJoin(networkInterface)
            }.forEach { networkInterface ->
                try {
                    listener.joinGroup(InetSocketAddress(group, listenPort), networkInterface)
                    joined = true
                } catch (failure: IOException) {
                    onError(failure)
                }
            }
        if (!joined) {
            onError(IOException(I18n.t("multiplayer.lanMulticast.2")))
        }
    }

    /** 轮流在每个可用 IPv4 接口上向组播目标发送一帧；单个接口失败不影响其它接口。 */
    fun send(
        socket: MulticastSocket,
        target: InetSocketAddress,
        payload: ByteArray,
        onError: (Throwable) -> Unit,
    ) {
        val interfaces = usableInterfaces(onError) ?: return
        interfaces
            .filter { networkInterface ->
                canSend(networkInterface)
            }.forEach { networkInterface ->
                try {
                    socket.networkInterface = networkInterface
                    socket.send(DatagramPacket(payload, payload.size, target))
                } catch (failure: IOException) {
                    onError(failure)
                }
            }
    }

    private fun usableInterfaces(onError: (Throwable) -> Unit): List<NetworkInterface>? =
        try {
            NetworkInterface.getNetworkInterfaces()?.toList() ?: emptyList()
        } catch (failure: IOException) {
            onError(IOException(I18n.t("multiplayer.lanMulticast.3", failure.message), failure))
            null
        }

    private fun canJoin(networkInterface: NetworkInterface): Boolean =
        eagerlyUsable(networkInterface) && networkInterface.inetAddresses.toList().any(::isIpv4)

    private fun canSend(networkInterface: NetworkInterface): Boolean =
        eagerlyUsable(networkInterface) && networkInterface.inetAddresses.toList().any(::isIpv4)

    private fun eagerlyUsable(networkInterface: NetworkInterface): Boolean =
        networkInterface.isUp &&
            networkInterface.supportsMulticast() &&
            !networkInterface.isLoopback

    private fun isIpv4(address: InetAddress): Boolean = address is Inet4Address
}
