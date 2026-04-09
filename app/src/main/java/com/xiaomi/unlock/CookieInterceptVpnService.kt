package com.xiaomi.unlock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import kotlin.concurrent.thread
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

class CookieInterceptVpnService : VpnService() {

    companion object {
        const val TAG              = "CookieVPN"
        const val ACTION_START     = "START"
        const val ACTION_STOP      = "STOP"
        const val NOTIF_ID         = 9001
        const val CHANNEL_ID       = "vpn_channel"
        const val ACTION_COOKIE_FOUND = "com.xiaomi.unlock.COOKIE_FOUND"
        const val EXTRA_COOKIE     = "cookie"
        const val ACTION_LOG       = "com.xiaomi.unlock.VPN_LOG"
        const val EXTRA_LOG        = "log_msg"

        const val TARGET_HOST      = "sgp-api.buy.mi.com"
        const val TARGET_PORT      = 443
        const val PROXY_PORT       = 8899

        var isRunning = false

        // Target IP resolved at runtime
        var targetIp   = ""
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var running = false
    private var proxyThread: Thread? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopVpn(); return START_NOT_STICKY }
        startVpn()
        return START_STICKY
    }

    private fun startVpn() {
        if (isRunning) return
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("Waiting for Xiaomi traffic..."))

        // Resolve target IP
        thread(isDaemon = true) {
            try {
                targetIp = InetAddress.getByName(TARGET_HOST).hostAddress ?: ""
                sendLog("Resolved $TARGET_HOST → $targetIp")
            } catch (e: Exception) {
                sendLog("DNS error: ${e.message}")
            }
        }

        // Start local MITM proxy first
        proxyThread = thread(isDaemon = true, name = "ProxyThread") {
            runMitmProxy()
        }

        // Give proxy time to start
        Thread.sleep(300)

        // Build VPN interface
        val builder = Builder()
            .setSession("XiaomiCookieCapture")
            .addAddress("10.99.0.1", 24)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("8.8.8.8")
            .setMtu(1500)

        try { builder.addDisallowedApplication(packageName) } catch (_: Exception) {}

        vpnInterface = builder.establish()
        isRunning = true
        running = true

        sendLog("VPN started. Monitoring traffic...")

        thread(isDaemon = true, name = "PacketLoop") {
            runPacketLoop()
        }
    }

    private fun stopVpn() {
        running = false
        isRunning = false
        proxyThread?.interrupt()
        try { vpnInterface?.close() } catch (_: Exception) {}
        vpnInterface = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ── Packet loop: intercept TCP packets to TARGET_HOST:443
    // and redirect them to our local proxy at 10.99.0.1:PROXY_PORT
    private fun runPacketLoop() {
        val vpnIn  = FileInputStream(vpnInterface!!.fileDescriptor)
        val vpnOut = FileOutputStream(vpnInterface!!.fileDescriptor)
        val buf    = ByteBuffer.allocate(32767)

        while (running) {
            try {
                buf.clear()
                val len = vpnIn.read(buf.array())
                if (len <= 0) { Thread.sleep(5); continue }
                buf.limit(len)

                val ipVer = (buf.get(0).toInt() shr 4) and 0xF
                if (ipVer != 4) { vpnOut.write(buf.array(), 0, len); continue }

                val proto = buf.get(9).toInt() and 0xFF
                if (proto != 6) { vpnOut.write(buf.array(), 0, len); continue } // not TCP

                val ipHdrLen  = (buf.get(0).toInt() and 0xF) * 4
                val tcpOffset = ipHdrLen

                val dstPort = ((buf.get(tcpOffset + 2).toInt() and 0xFF) shl 8) or
                              (buf.get(tcpOffset + 3).toInt() and 0xFF)

                val dstIp = "${buf.get(16).toInt() and 0xFF}.${buf.get(17).toInt() and 0xFF}" +
                            ".${buf.get(18).toInt() and 0xFF}.${buf.get(19).toInt() and 0xFF}"

                // Is this packet for our target?
                val isTarget = dstPort == TARGET_PORT &&
                               (dstIp == targetIp || targetIp.isEmpty() ||
                                dstIp.startsWith("103.") || dstIp.startsWith("120."))

                if (isTarget) {
                    // Rewrite destination to our local proxy (10.99.0.1:PROXY_PORT)
                    // Dst IP → 10.99.0.1
                    buf.put(16, 10); buf.put(17, 99); buf.put(18, 0); buf.put(19, 1)
                    // Dst port → PROXY_PORT
                    buf.put(tcpOffset + 2, (PROXY_PORT shr 8).toByte())
                    buf.put(tcpOffset + 3, (PROXY_PORT and 0xFF).toByte())
                    // Recalculate IP checksum
                    recalcIpChecksum(buf, ipHdrLen)
                    // Recalculate TCP checksum
                    recalcTcpChecksum(buf, ipHdrLen, len)
                }

                vpnOut.write(buf.array(), 0, len)

            } catch (e: InterruptedException) { break
            } catch (e: Exception) {
                if (running) Log.e(TAG, "Packet error: ${e.message}")
            }
        }
    }

    // ── Local MITM proxy: accepts redirected connections,
    // does TLS to real server, inspects headers
    private fun runMitmProxy() {
        val serverSocket = java.net.ServerSocket()
        serverSocket.reuseAddress = true
        serverSocket.bind(InetSocketAddress("10.99.0.1", PROXY_PORT))
        sendLog("Local proxy on 10.99.0.1:$PROXY_PORT")

        while (running) {
            try {
                val client = serverSocket.accept()
                thread(isDaemon = true) { handleClient(client) }
            } catch (e: Exception) {
                if (running) sendLog("Proxy accept error: ${e.message}")
                break
            }
        }
        serverSocket.close()
    }

    private fun handleClient(clientSocket: Socket) {
        try {
            // Connect to real server with TLS
            val realSocket = Socket()
            protect(realSocket)
            realSocket.connect(InetSocketAddress(TARGET_HOST, TARGET_PORT), 10000)

            val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val sslSocket  = sslFactory.createSocket(
                realSocket, TARGET_HOST, TARGET_PORT, true
            ) as SSLSocket
            sslSocket.startHandshake()

            val clientIn  = clientSocket.getInputStream()
            val clientOut = clientSocket.getOutputStream()
            val serverIn  = sslSocket.getInputStream()
            val serverOut = sslSocket.getOutputStream()

            // Read client request (HTTP headers)
            val reqBuf = ByteArray(16384)
            val reqLen = clientIn.read(reqBuf)
            if (reqLen > 0) {
                val reqStr = String(reqBuf, 0, reqLen, Charsets.UTF_8)

                // Extract Cookie header
                for (line in reqStr.split("\r\n", "\n")) {
                    if (line.startsWith("Cookie:", ignoreCase = true)) {
                        val cookie = line.substringAfter("Cookie:").trim()
                        if (cookie.contains("new_bbs_serviceToken") ||
                            cookie.contains("serviceToken")) {
                            sendLog("✅ Cookie found!")
                            broadcastCookie(cookie)
                        }
                        break
                    }
                }

                // Forward request to real server
                serverOut.write(reqBuf, 0, reqLen)
                serverOut.flush()
            }

            // Pipe response back to client
            val fwd = thread(isDaemon = true) {
                try { serverIn.copyTo(clientOut) } catch (_: Exception) {}
            }
            try { clientIn.copyTo(serverOut) } catch (_: Exception) {}
            fwd.join(3000)

            sslSocket.close()
        } catch (e: Exception) {
            sendLog("Client error: ${e.message}")
        } finally {
            try { clientSocket.close() } catch (_: Exception) {}
        }
    }

    private fun broadcastCookie(cookie: String) {
        sendBroadcast(Intent(ACTION_COOKIE_FOUND).apply {
            putExtra(EXTRA_COOKIE, cookie)
            setPackage(packageName)
        })
        stopVpn()
    }

    // ── Checksum helpers ─────────────────────────────────────────────────────

    private fun recalcIpChecksum(buf: ByteBuffer, ipHdrLen: Int) {
        buf.put(10, 0); buf.put(11, 0)
        var sum = 0L
        for (i in 0 until ipHdrLen / 2) {
            val word = ((buf.get(i * 2).toInt() and 0xFF) shl 8) or
                       (buf.get(i * 2 + 1).toInt() and 0xFF)
            sum += word
        }
        while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        val checksum = sum.inv() and 0xFFFF
        buf.put(10, (checksum shr 8).toByte())
        buf.put(11, (checksum and 0xFF).toByte())
    }

    private fun recalcTcpChecksum(buf: ByteBuffer, ipHdrLen: Int, totalLen: Int) {
        val tcpOffset = ipHdrLen
        val tcpLen    = totalLen - ipHdrLen
        buf.put(tcpOffset + 16, 0); buf.put(tcpOffset + 17, 0)

        var sum = 0L
        // Pseudo-header
        for (i in 12..15) sum += (buf.get(i).toInt() and 0xFF) shl (if (i % 2 == 0) 8 else 0)
        for (i in 16..19) sum += (buf.get(i).toInt() and 0xFF) shl (if (i % 2 == 0) 8 else 0)
        sum += 6 // protocol TCP
        sum += tcpLen
        // TCP segment
        var i = 0
        while (i < tcpLen - 1) {
            val word = ((buf.get(tcpOffset + i).toInt() and 0xFF) shl 8) or
                       (buf.get(tcpOffset + i + 1).toInt() and 0xFF)
            sum += word; i += 2
        }
        if (tcpLen % 2 != 0) sum += (buf.get(tcpOffset + tcpLen - 1).toInt() and 0xFF) shl 8
        while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        val checksum = sum.inv() and 0xFFFF
        buf.put(tcpOffset + 16, (checksum shr 8).toByte())
        buf.put(tcpOffset + 17, (checksum and 0xFF).toByte())
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun sendLog(msg: String) {
        Log.d(TAG, msg)
        sendBroadcast(Intent(ACTION_LOG).apply {
            putExtra(EXTRA_LOG, msg)
            setPackage(packageName)
        })
    }

    private fun createNotificationChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Cookie Interceptor", NotificationManager.IMPORTANCE_LOW)
            )
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Xiaomi Cookie Interceptor")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() { stopVpn(); super.onDestroy() }
}
