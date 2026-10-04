package pl.siren.mobile.mesh

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import kotlin.concurrent.thread

/**
 * Local mesh hop over Wi-Fi broadcast (UDP 47474). Works with no Internet and no Siren server — only
 * phones on the same network. The message is the signed alarm as-is; receivers verify it themselves.
 * Stands in for the BLE / Wi-Fi Direct / LoRa bearers of the target design.
 */
class MeshRelay(context: Context, private val onMessage: (payloadB64: String, signatureB64: String, hop: Int, from: InetAddress) -> Unit) {
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private var lock: WifiManager.MulticastLock? = null
    @Volatile private var socket: DatagramSocket? = null

    fun start() {
        if (socket != null) return
        lock = wifi.createMulticastLock("siren-mesh").apply { setReferenceCounted(false); acquire() }
        val s = DatagramSocket(null).apply {
            reuseAddress = true
            broadcast = true
            bind(InetSocketAddress(PORT))
        }
        socket = s
        thread(name = "siren-mesh", isDaemon = true) {
            val buf = ByteArray(8192)
            while (!s.isClosed) {
                try {
                    val packet = DatagramPacket(buf, buf.size)
                    s.receive(packet)
                    if (isOwnAddress(packet.address)) continue
                    val json = JSONObject(String(packet.data, 0, packet.length))
                    if (json.optInt("siren") != 1) continue
                    onMessage(json.getString("payload"), json.getString("signature"), json.optInt("hop", 1), packet.address)
                } catch (e: Exception) {
                    if (!s.isClosed) Log.w(TAG, "mesh receive: ${e.message}")
                }
            }
        }
    }

    fun stop() {
        socket?.close()
        socket = null
        lock?.release()
        lock = null
    }

    /** Broadcast to every phone in reach. [hop] is the hop count receivers will see. */
    fun broadcast(payloadB64: String, signatureB64: String, hop: Int) {
        val s = socket ?: return
        val bytes = JSONObject().put("siren", 1).put("payload", payloadB64).put("signature", signatureB64).put("hop", hop)
            .toString().toByteArray()
        thread(isDaemon = true) {
            for (target in targets()) {
                runCatching { s.send(DatagramPacket(bytes, bytes.size, target, PORT)) }
                    .onFailure { Log.w(TAG, "mesh send to $target: ${it.message}") }
            }
        }
    }

    private fun targets(): List<InetAddress> {
        val out = mutableListOf(InetAddress.getByName("255.255.255.255"))
        runCatching {
            NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }.forEach { nif ->
                nif.interfaceAddresses.mapNotNullTo(out) { it.broadcast }
            }
        }
        return out.distinct()
    }

    private fun isOwnAddress(addr: InetAddress): Boolean = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().any { nif -> nif.inetAddresses.toList().any { it == addr } }
    }.getOrDefault(false)

    companion object {
        const val PORT = 47474
        private const val TAG = "SirenMesh"
    }
}
