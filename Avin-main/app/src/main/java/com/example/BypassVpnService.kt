package com.example

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class BypassVpnService : VpnService(), Runnable {

    companion object {
        const val ACTION_CONNECT = "com.example.BypassVpnService.CONNECT"
        const val ACTION_DISCONNECT = "com.example.BypassVpnService.DISCONNECT"
        
        const val EXTRA_DNS1 = "extra_dns1"
        const val EXTRA_DNS2 = "extra_dns2"
        const val EXTRA_SERVER_NAME = "extra_server_name"

        val isConnected = MutableStateFlow(false)
        val activeServerName = MutableStateFlow("Disconnected")
        val currentDns = MutableStateFlow("None")
        
        // Keep track of total DNS queries resolved during this session
        val queryCount = MutableStateFlow(0)
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var vpnThread: Thread? = null
    private var isRunning = false
    private var executor: ExecutorService? = null

    private var dns1 = "178.22.122.100" // Default Shecan
    private var dns2 = "185.51.200.2"
    private var serverName = "Finland (Helsinki) - Shecan Router"

    override fun onCreate() {
        super.onCreate()
        executor = Executors.newCachedThreadPool()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_DISCONNECT) {
            disconnectVpn()
            return START_NOT_STICKY
        }

        if (action == ACTION_CONNECT) {
            val newDns1 = intent.getStringExtra(EXTRA_DNS1) ?: "178.22.122.100"
            val newDns2 = intent.getStringExtra(EXTRA_DNS2) ?: "185.51.200.2"
            val newServerName = intent.getStringExtra(EXTRA_SERVER_NAME) ?: "Default Router"

            // If already running with same settings, do nothing
            if (isRunning && dns1 == newDns1 && dns2 == newDns2) {
                return START_STICKY
            }

            // Otherwise, disconnect first if running, and start with new settings
            if (isRunning) {
                disconnectVpn()
            }

            dns1 = newDns1
            dns2 = newDns2
            serverName = newServerName

            startVpn()
        }

        return START_STICKY
    }

    private fun startVpn() {
        isRunning = true
        setupNotification()
        
        vpnThread = Thread(this, "BypassVpnThread").apply {
            start()
        }
        
        isConnected.value = true
        activeServerName.value = serverName
        currentDns.value = "$dns1, $dns2"
        queryCount.value = 0
    }

    private fun disconnectVpn() {
        isRunning = false
        vpnInterface?.close()
        vpnInterface = null
        vpnThread = null
        
        isConnected.value = false
        activeServerName.value = "Disconnected"
        currentDns.value = "None"
        
        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        disconnectVpn()
        executor?.shutdownNow()
        super.onDestroy()
    }

    override fun run() {
        try {
            // 1. Establish the TUN Interface
            val builder = Builder()
                .setSession("Smart Bypass VPN")
                .setMtu(1500)
                .addAddress("10.0.0.2", 32) // Assign a local private IP inside the tunnel

            // Add the anti-filtering DNS servers to the configuration
            builder.addDnsServer(dns1)
            builder.addDnsServer(dns2)

            // CRITICAL STEP: Add routes ONLY for the DNS servers themselves!
            // This captures only DNS traffic, leaving all other app TCP/UDP data to stream
            // directly over the high-speed physical network without any VPN overhead, while
            // unblocking filtering using DNS resolution.
            builder.addRoute(dns1, 32)
            builder.addRoute(dns2, 32)

            vpnInterface = builder.establish()
            if (vpnInterface == null) {
                Log.e("BypassVpn", "Failed to establish VPN interface")
                disconnectVpn()
                return
            }

            val fileInputStream = FileInputStream(vpnInterface!!.fileDescriptor)
            val fileOutputStream = FileOutputStream(vpnInterface!!.fileDescriptor)
            val buffer = ByteBuffer.allocate(32768)

            Log.i("BypassVpn", "VPN Tunnel active. Intercepting DNS queries for $dns1 and $dns2")

            while (isRunning) {
                val length = fileInputStream.read(buffer.array())
                if (length <= 0) {
                    Thread.sleep(10)
                    continue
                }

                buffer.limit(length)
                buffer.rewind()

                val packet = buffer.array()
                val versionAndIhl = packet[0].toInt() and 0xFF
                val version = versionAndIhl ushr 4
                val ihl = (versionAndIhl and 0x0F) * 4

                if (version == 4 && length > ihl) {
                    val protocol = packet[9].toInt() and 0xFF
                    if (protocol == 17) { // UDP
                        val srcIp = packet.copyOfRange(12, 16)
                        val dstIp = packet.copyOfRange(16, 20)

                        // UDP Header starts at index 'ihl'
                        val srcPort = ((packet[ihl].toInt() and 0xFF) shl 8) or (packet[ihl + 1].toInt() and 0xFF)
                        val dstPort = ((packet[ihl + 2].toInt() and 0xFF) shl 8) or (packet[ihl + 3].toInt() and 0xFF)
                        val udpLen = ((packet[ihl + 4].toInt() and 0xFF) shl 8) or (packet[ihl + 5].toInt() and 0xFF)

                        if (dstPort == 53 && length >= ihl + 8) { // DNS Query
                            val dnsQueryPayload = packet.copyOfRange(ihl + 8, ihl + udpLen)

                            // Process the query on our thread pool
                            executor?.submit {
                                forwardDnsQuery(dnsQueryPayload, srcIp, srcPort, dstIp, fileOutputStream)
                            }
                        }
                    }
                }
                buffer.clear()
            }
        } catch (e: Exception) {
            Log.e("BypassVpn", "Error in packet loop: ${e.message}", e)
        } finally {
            disconnectVpn()
        }
    }

    private fun forwardDnsQuery(
        queryPayload: ByteArray,
        clientIp: ByteArray,
        clientPort: Int,
        dnsIp: ByteArray,
        tunnelOutputStream: FileOutputStream
    ) {
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket()
            protect(socket) // CRITICAL: Protect the socket so it bypasses the VPN tunnel

            val targetDnsServer = InetAddress.getByAddress(dnsIp)
            val sendPacket = DatagramPacket(queryPayload, queryPayload.size, targetDnsServer, 53)
            
            socket.soTimeout = 2500 // Timeout to prevent thread blocking
            socket.send(sendPacket)

            val receiveBuffer = ByteArray(4096)
            val receivePacket = DatagramPacket(receiveBuffer, receiveBuffer.size)
            socket.receive(receivePacket)

            val responseData = receivePacket.data.copyOfRange(0, receivePacket.length)
            
            // Build the standard IP/UDP packet for the response
            val replyPacket = buildUdpResponsePacket(
                srcIp = dnsIp,      // The IP of the DNS server resolving the query
                dstIp = clientIp,   // The local interface IP (e.g. 10.0.0.2)
                srcPort = 53,
                dstPort = clientPort,
                payload = responseData
            )

            synchronized(tunnelOutputStream) {
                tunnelOutputStream.write(replyPacket)
                tunnelOutputStream.flush()
            }
            
            // Increment the query counter safely
            queryCount.value += 1

        } catch (e: Exception) {
            Log.w("BypassVpn", "DNS resolution failed over physical link: ${e.message}")
        } finally {
            socket?.close()
        }
    }

    private fun buildUdpResponsePacket(
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcPort: Int,
        dstPort: Int,
        payload: ByteArray
    ): ByteArray {
        val ipHeaderLen = 20
        val udpHeaderLen = 8
        val totalLength = ipHeaderLen + udpHeaderLen + payload.size
        val packet = ByteArray(totalLength)

        // 1. Construct IP Header
        packet[0] = 0x45.toByte() // IPv4, Header Length = 5 dwords (20 bytes)
        packet[1] = 0x00.toByte() // Type of Service
        packet[2] = (totalLength ushr 8).toByte()
        packet[3] = (totalLength and 0xFF).toByte()
        packet[4] = 0x00.toByte() // Identification
        packet[5] = 0x00.toByte()
        packet[6] = 0x40.toByte() // Don't Fragment flag set
        packet[7] = 0x00.toByte()
        packet[8] = 64.toByte()   // Time to Live (TTL)
        packet[9] = 17.toByte()   // Protocol (UDP = 17)
        // Header checksum will be calculated at bytes 10-11

        // Source and Destination IPs
        System.arraycopy(srcIp, 0, packet, 12, 4)
        System.arraycopy(dstIp, 0, packet, 16, 4)

        // Calculate and write IP checksum
        val checksum = calculateChecksum(packet, ipHeaderLen)
        packet[10] = (checksum ushr 8).toByte()
        packet[11] = (checksum and 0xFF).toByte()

        // 2. Construct UDP Header
        packet[20] = (srcPort ushr 8).toByte()
        packet[21] = (srcPort and 0xFF).toByte()
        packet[22] = (dstPort ushr 8).toByte()
        packet[23] = (dstPort and 0xFF).toByte()
        
        val udpLen = udpHeaderLen + payload.size
        packet[24] = (udpLen ushr 8).toByte()
        packet[25] = (udpLen and 0xFF).toByte()
        packet[26] = 0x00.toByte() // UDP checksum is optional in IPv4 (0 = disabled)
        packet[27] = 0x00.toByte()

        // 3. Write UDP Payload
        System.arraycopy(payload, 0, packet, 28, payload.size)

        return packet
    }

    private fun calculateChecksum(buf: ByteArray, length: Int): Int {
        var sum = 0
        var i = 0
        while (i < length - 1) {
            sum += ((buf[i].toInt() and 0xFF) shl 8) or (buf[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < length) {
            sum += (buf[i].toInt() and 0xFF) shl 8
        }
        while (sum ushr 16 != 0) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        return (sum.inv()) and 0xFFFF
    }

    private fun setupNotification() {
        val channelId = "bypass_vpn_channel"
        val channelName = "Smart Bypass Status"
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_LOW)
            notificationManager.createNotificationChannel(channel)
        }

        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Smart Bypass Active")
            .setContentText("Connected to: $serverName. Unblocking AI & sites.")
            .setSmallIcon(android.R.drawable.ic_menu_share) // Using system icon for compatibility
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        startForeground(1001, notification)
    }
}
