package com.example.pockettool

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.*
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.ConsumerIrManager
import android.net.wifi.ScanResult as WifiResult
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pInfo
import java.net.ServerSocket
import java.io.DataInputStream
import java.io.DataOutputStream
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.*
 
@SuppressLint("MissingPermission")
class MainActivity : Activity() {
    private lateinit var body: LinearLayout
    private lateinit var status: TextView
    private lateinit var address: EditText
    private lateinit var command: EditText
    private val ble = linkedMapOf<String, ScanResult>()
    private val gatts = mutableListOf<BluetoothGatt>()
    private var scanning = false
    private var receiver: BroadcastReceiver? = null
    private var importedPasswords: Set<String> = emptySet()
    private val passwordBundleRequest = 7401
    private lateinit var p2pManager: WifiP2pManager
    private var p2pChannel: WifiP2pManager.Channel? = null
    private var p2pReceiver: BroadcastReceiver? = null
    private val nearbyPeers = linkedMapOf<String, WifiP2pDevice>()
    private val sharePickRequest = 7402
    private var shareSocket: java.net.Socket? = null
    private var shareServerSocket: ServerSocket? = null
    private var shareInput: DataInputStream? = null
    private var shareOutput: DataOutputStream? = null
    @Volatile private var p2pSocketReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.hide()
        val permissions = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= 33) permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        requestPermissions(permissions.toTypedArray(), 10)
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(245,247,250)) }
        page.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(12))
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.rgb(17, 42, 76), Color.rgb(31, 105, 170))).apply { cornerRadius = dp(0).toFloat() }
            addView(TextView(this@MainActivity).apply {
                text = "PocketTool"; textSize = 26f; setTypeface(null, Typeface.BOLD)
                setTextColor(Color.WHITE)
            })
            addView(TextView(this@MainActivity).apply {
                text = "SMART DEVICE UTILITIES  •  VERSION 2.1"; textSize = 10f
                setTextColor(Color.rgb(220, 235, 250)); setPadding(0, dp(3), 0, 0)
            })
        })
        val scroll = ScrollView(this)
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16),dp(10),dp(16),dp(22)) }
        scroll.addView(body); page.addView(scroll); setContentView(page)

        heading("Infrared remote • NEC")
        val ir = card()
        address = EditText(this).apply { hint = "NEC address (hex)"; setText("04"); setSingleLine(true) }
        command = EditText(this).apply { hint = "Command (hex)"; setText("08"); setSingleLine(true) }
        ir.addView(address); ir.addView(command)
        val irButtons = row()
        irButtons.addView(button("POWER") { sendIr("08") }, weight())
        irButtons.addView(button("VOL +") { sendIr("02") }, weight())
        irButtons.addView(button("VOL −") { sendIr("03") }, weight())
        ir.addView(irButtons)
        val irButtons2 = row()
        irButtons2.addView(button("MUTE") { sendIr("09") }, weight())
        irButtons2.addView(button("SEND CUSTOM") { sendIr(command.text.toString()) }, weight())
        ir.addView(irButtons2)
        ir.addView(label("NEC command codes vary by TV model; edit address/command if these presets do not match."))
        body.addView(ir)

        heading("Bluetooth LE scanner")
        val bc = card()
        val br = row()
        br.addView(button("START / STOP SCAN") { toggleBle() }, weight())
        br.addView(button("CLEAR") { ble.clear(); renderBle() }, weight())
        bc.addView(br); bc.addView(label("Connect tries a BLE GATT connection; some devices do not accept connections."))
        bc.addView(LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; tag = "bleList" })
        body.addView(bc)

        heading("PocketTool Nearby Share")
        val nearby = card()
        nearby.addView(label("PHONE-TO-PHONE • NO URL • NO INTERNET"))
        val nearbyButtons = row()
        nearbyButtons.addView(button("FIND DEVICES") { discoverNearbyDevices() }, weight())
        nearbyButtons.addView(button("HOW TO CONNECT") { details("Nearby Share", "Dono phones par PocketTool kholen. Wi-Fi ON rakhein. FIND DEVICES dabayein, phir device ke saamne CONNECT karein. Bluetooth LE scan file transfer nahi karta; Wi-Fi Direct local transfer ke liye use hota hai.") }, weight())
        nearby.addView(nearbyButtons)
        nearby.addView(label("Dono phones par PocketTool khula hona chahiye. Device discovery aur Wi-Fi Direct connection yahan se shuru hota hai."))
        nearby.addView(LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; tag = "nearbyPeerList" })
        nearby.addView(button("SELECT FILE & SEND") { chooseNearbyFile() })
        nearby.addView(LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; tag = "nearbyShareStatus" })
        body.addView(nearby)

        heading("Wi-Fi network scanner")
        val wc = card()
        wc.addView(button("MY CONNECTED WI-FI DETAILS") { showConnectedWifi() })
        wc.addView(button("OPEN WI-FI SETTINGS / SHARE QR") {
            startActivity(Intent(android.provider.Settings.ACTION_WIFI_SETTINGS))
        })
        wc.addView(button("OPEN ROUTER ADMIN PAGE") { openRouterPage() })
        wc.addView(button("SCAN NEARBY WI-FI") { scanWifi() })
        wc.addView(button("DISCOVER SHARED FILE SERVERS") { discoverFileServices() })
        wc.addView(button("START PHONE FILE SERVER") { startPhoneFileServer() })
        wc.addView(button("STOP PHONE FILE SERVER") { stopPhoneFileServer() })
        wc.addView(button("START FTP SERVER") { startFtpServer() })
        wc.addView(button("STOP FTP SERVER") { stopFtpServer() })
        wc.addView(LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; tag = "ftpServerInfo" })
        wc.addView(label("Share folder: PocketToolShare • Same Wi-Fi par apne PC/phone ke browser se link kholo. Upload/download max 50 MB per file."))
        wc.addView(LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; tag = "phoneShareInfo" })
        wc.addView(label("Finds FTP / SFTP / SMB services advertised by your own phones and computers on the same Wi-Fi. A device must enable file sharing first."))
        wc.addView(LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; tag = "fileServiceList" })
        wc.addView(button("WI-FI SECURITY LAB • EDUCATION") { showWifiSecurityLab() })
        wc.addView(button("PASSWORD SECURITY LAB • 1–15 DIGITS") { showPasswordGuessDemo() })
        wc.addView(button("WI-FI PASSWORD AUDIT") { showPasswordAudit("Wi-Fi") })
        wc.addView(label("Router/gateway IP, phone IP, subnet, DNS, link speed, channel, security and nearby network details."))
        wc.addView(LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; tag = "wifiList" })
        body.addView(wc)

        heading("Cybersecurity & device tools")
        val tools = card()
        tools.addView(button("WI-FI ANALYZER") { showWifiAnalyzer() })
        tools.addView(button("NETWORK DIAGNOSTICS") { showNetworkDiagnostics() })
        tools.addView(button("BLE GATT EXPLORER") { showBleGuide() })
        tools.addView(button("BLUETOOTH PAIRING PASSWORD AUDIT") { showPasswordAudit("Bluetooth") })
        tools.addView(button("DEVICE SECURITY CHECK") { showDeviceSecurity() })
        tools.addView(button("NFC TAG READER CHECK") { showNfcInfo() })
        tools.addView(button("SUB-GHz RADIO LEARNING") { showSubGhzLab() })
        tools.addView(button("OPEN NFC SETTINGS") { startActivity(Intent(android.provider.Settings.ACTION_NFC_SETTINGS)) })
        tools.addView(label("NFC reading needs NFC hardware. Sub-GHz tools are educational only; phone hardware may not support radio transmission."))
        body.addView(tools)
        heading("Ethical Hacker Lab • Authorized testing")
        val ethical = card()
        ethical.addView(button("SECURITY AUDIT • MY WI-FI") { showMyNetworkAudit() })
        ethical.addView(button("DNS LOOKUP • DOMAIN RECON") { showDnsLookup() })
        ethical.addView(button("SHA-256 HASH LAB") { showHashLab() })
        ethical.addView(button("WEB APP SECURITY CHECKLIST") { showWebSecurityChecklist() })
        ethical.addView(button("ETHICAL HACKING ROADMAP") { showEthicalRoadmap() })
        ethical.addView(button("IMPORT PASSWORD BUNDLE • TXT") { importPasswordBundle() })
        ethical.addView(button("CHECK MY PASSWORD IN BUNDLE") { checkPasswordAgainstBundle() })
        ethical.addView(button("PASSWORD TEST SIMULATOR • OFFLINE") { runPasswordBundleSimulator() })
        ethical.addView(label(if (importedPasswords.isEmpty()) "No password bundle loaded. Import your own .txt wordlist to check whether your own password appears in it." else "Loaded locally: ${importedPasswords.size} unique entries. File is not uploaded or sent to any network."))
        ethical.addView(label("Safe learning tools: own devices, own network and explicitly authorized systems only. No password cracking, stealth scanning or exploitation."))
        body.addView(ethical)

        status = label("Ready • Offline utility")
        body.addView(status)
    }



    private var phoneShareServer: PocketShareServer? = null
    private var ftpServer: PocketFtpServer? = null
    private var ftpPassword: String = ""

    @Suppress("DEPRECATION")
    private fun startPhoneFileServer() {
        if (phoneShareServer != null) { toast("File server already running"); return }
        try {
            val ip = getLocalShareIp()
            if (ip == null) { toast("Wi-Fi ya doosre phone ka Hotspot connect karein"); status.text = "Wi-Fi / Hotspot connection required"; return }
            val folder = java.io.File(getExternalFilesDir(null), "PocketToolShare")
            val server = PocketShareServer(folder)
            server.start(10000, false)
            phoneShareServer = server
            val url = server.shareUrl(ip)
            val panel = body.findViewWithTag<LinearLayout>("phoneShareInfo") ?: return
            panel.removeAllViews()
            panel.addView(label("SERVER RUNNING\\n$url\\nFolder: ${folder.absolutePath}\\nConnect the other phone to this phone's hotspot or the same Wi-Fi, then open this link."))
            panel.addView(button("COPY SHARE LINK") { copy(url) })
            status.text = "Phone file server running • $ip:8765"
            toast("File sharing started")
        } catch (e: Exception) { phoneShareServer = null; toast("Server start nahi hua: ${e.message}") }
    }

    private fun stopPhoneFileServer() {
        try { phoneShareServer?.stop() } catch (_: Exception) {}
        phoneShareServer = null
        body.findViewWithTag<LinearLayout>("phoneShareInfo")?.apply {
            removeAllViews()
            addView(label("File server stopped"))
        }
        status.text = "Phone file server stopped"
    }

    private fun startFtpServer() {
        if (ftpServer != null) { toast("FTP server already running"); return }
        try {
            val ip = getLocalShareIp()
            if (ip == null) { toast("Wi-Fi ya doosre phone ka Hotspot connect karein"); status.text = "Wi-Fi / Hotspot connection required"; return }
            val folder = java.io.File(getExternalFilesDir(null), "PocketToolFTP")
            ftpPassword = (100000..999999).random().toString()
            val server = PocketFtpServer(folder, "pocket", ftpPassword)
            server.start()
            ftpServer = server
            val info = body.findViewWithTag<LinearLayout>("ftpServerInfo") ?: return
            info.removeAllViews()
            info.addView(label("FTP SERVER RUNNING\\nHost: $ip\\nPort: 2121\\nUsername: pocket\\nPassword: $ftpPassword\\nFolder: ${folder.absolutePath}\\nConnect the other phone to this phone's hotspot or the same Wi-Fi."))
            info.addView(button("COPY FTP ADDRESS") { copy("ftp://pocket:$ftpPassword@$ip:2121/") })
            status.text = "FTP server running • $ip:2121"
            toast("FTP server started")
        } catch (e: Exception) { ftpServer = null; toast("FTP start failed: ${e.message}") }
    }

    private fun stopFtpServer() {
        try { ftpServer?.stop() } catch (_: Exception) {}
        ftpServer = null
        body.findViewWithTag<LinearLayout>("ftpServerInfo")?.apply { removeAllViews(); addView(label("FTP server stopped")) }
        status.text = "FTP server stopped"
    }

    // Discover private LAN IP; this works for Wi-Fi clients and hotspot hosts.
    private fun getLocalShareIp(): String? {
        return try {
            val interfaces = java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces()).filter { it.isUp && !it.isLoopback }
            val candidates = interfaces.flatMap { network ->
                java.util.Collections.list(network.inetAddresses)
                    .filterIsInstance<java.net.Inet4Address>()
                    .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress && it.isSiteLocalAddress }
                    .map { address -> Triple(address.hostAddress ?: "", network.name.lowercase(), address) }
            }
            candidates.sortedWith(compareBy<Triple<String, String, java.net.Inet4Address>>(
                { if (it.first == "192.168.43.1") 0 else 1 },
                { if (it.second.contains("wlan") || it.second.contains("ap") || it.second.contains("swlan")) 0 else 1 }
            )).firstOrNull()?.first
        } catch (_: Exception) { null }
    }

    private val nsdManager by lazy { getSystemService(Context.NSD_SERVICE) as android.net.nsd.NsdManager }
    private val nsdListeners = mutableListOf<android.net.nsd.NsdManager.DiscoveryListener>()
    private val discoveredServices = linkedMapOf<String, String>()

    private fun discoverFileServices() {
        val list = body.findViewWithTag<LinearLayout>("fileServiceList") ?: return
        list.removeAllViews()
        discoveredServices.clear()
        nsdListeners.forEach { try { nsdManager.stopServiceDiscovery(it) } catch (_: Exception) {} }
        nsdListeners.clear()
        list.addView(label("Searching your local Wi-Fi for advertised FTP / SFTP / SMB shares…"))
        val types = listOf("_ftp._tcp.", "_sftp-ssh._tcp.", "_smb._tcp.")
        types.forEach { type ->
            val listener = object : android.net.nsd.NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) {}
                override fun onServiceFound(service: android.net.nsd.NsdServiceInfo) {
                    if (!service.serviceType.contains(type.removeSuffix("."))) return
                    try {
                        nsdManager.resolveService(service, object : android.net.nsd.NsdManager.ResolveListener {
                            override fun onResolveFailed(info: android.net.nsd.NsdServiceInfo, errorCode: Int) {}
                            override fun onServiceResolved(info: android.net.nsd.NsdServiceInfo) {
                                val host = info.host?.hostAddress ?: return
                                val port = info.port
                                val key = "${info.serviceName} • $host:$port"
                                runOnUiThread {
                                    discoveredServices[key] = type
                                    renderFileServices()
                                }
                            }
                        })
                    } catch (_: Exception) {}
                }
                override fun onServiceLost(service: android.net.nsd.NsdServiceInfo) {}
                override fun onDiscoveryStopped(serviceType: String) {}
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    try { nsdManager.stopServiceDiscovery(this) } catch (_: Exception) {}
                }
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            }
            nsdListeners.add(listener)
            try { nsdManager.discoverServices(type, android.net.nsd.NsdManager.PROTOCOL_DNS_SD, listener) }
            catch (_: Exception) {}
        }
        status.text = "Searching for shared file services on local Wi-Fi…"
    }

    private fun renderFileServices() {
        val list = body.findViewWithTag<LinearLayout>("fileServiceList") ?: return
        list.removeAllViews()
        if (discoveredServices.isEmpty()) {
            list.addView(label("No advertised FTP / SFTP / SMB services found yet. Enable file sharing on your own PC/mobile and scan again."))
            return
        }
        discoveredServices.forEach { (endpoint, type) ->
            val item = card()
            item.addView(label("$endpoint\nProtocol: ${type.removeSuffix(".").uppercase()}"))
            item.addView(button("COPY ADDRESS") {
                val endpointPart = endpoint.substringAfter("•").trim()
                copy(endpointPart)
            })
            list.addView(item)
        }
        status.text = "Found ${discoveredServices.size} shared file service(s)"
    }

    private fun discoverNearbyDevices() {
        try {
            p2pManager = getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
            p2pChannel = p2pManager.initialize(this, mainLooper, null)
            if (p2pReceiver == null) {
                p2pReceiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        when (intent.action) {
                            WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION ->
                                p2pManager.requestPeers(p2pChannel) { peers -> renderNearbyPeers(peers) }
                            WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION ->
                                p2pManager.requestConnectionInfo(p2pChannel) { info -> handleP2pConnection(info) }
                        }
                    }
                }
                val filter = IntentFilter().apply { addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION); addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION) }
                if (Build.VERSION.SDK_INT >= 33) registerReceiver(p2pReceiver, filter, Context.RECEIVER_NOT_EXPORTED) else registerReceiver(p2pReceiver, filter)
            }
            val list = body.findViewWithTag<LinearLayout>("nearbyPeerList")
            list?.removeAllViews(); list?.addView(label("Searching for nearby PocketTool devices…"))
            p2pManager.discoverPeers(p2pChannel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { status.text = "Nearby device discovery started" }
                override fun onFailure(reason: Int) { status.text = "Discovery failed: $reason" }
            })
        } catch (e: Exception) { toast("Wi-Fi Direct unavailable: ${e.message}") }
    }

    private fun renderNearbyPeers(peers: WifiP2pDeviceList) {
        nearbyPeers.clear(); peers.deviceList.forEach { nearbyPeers[it.deviceAddress] = it }
        val list = body.findViewWithTag<LinearLayout>("nearbyPeerList") ?: return
        list.removeAllViews()
        if (nearbyPeers.isEmpty()) { list.addView(label("No devices found yet. Dono phones par PocketTool kholkar FIND DEVICES dabayein.")); return }
        nearbyPeers.values.forEach { device ->
            val line = row()
            line.addView(label("${device.deviceName}\n${device.deviceAddress}"), LinearLayout.LayoutParams(0, -2, 1f))
            line.addView(button("CONNECT") { connectNearbyDevice(device) })
            list.addView(line)
        }
    }

    private fun connectNearbyDevice(device: WifiP2pDevice) {
        val config = android.net.wifi.p2p.WifiP2pConfig().apply { deviceAddress = device.deviceAddress }
        val channel = p2pChannel ?: return
        p2pManager.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { status.text = "Connection request sent to ${device.deviceName}" }
            override fun onFailure(reason: Int) { toast("Connection failed: $reason") }
        })
    }
    private fun handleP2pConnection(info: WifiP2pInfo) {
        if (!info.groupFormed || info.groupOwnerAddress == null || p2pSocketReady) return
        p2pSocketReady = true
        updateNearbyStatus("Wi-Fi Direct connected. Preparing file channel…")
        Thread {
            try {
                val socket = if (info.isGroupOwner) {
                    val server = ServerSocket(8988); shareServerSocket = server
                    server.accept().also { server.close(); shareServerSocket = null }
                } else java.net.Socket(info.groupOwnerAddress, 8988)
                shareSocket = socket
                shareInput = DataInputStream(socket.getInputStream())
                shareOutput = DataOutputStream(socket.getOutputStream())
                runOnUiThread { updateNearbyStatus("Connected • Ready to send and receive files") }
                receiveNearbyFiles()
            } catch (e: Exception) {
                p2pSocketReady = false
                runOnUiThread { updateNearbyStatus("Connection ended: ${e.message}") }
            }
        }.start()
    }

    private fun receiveNearbyFiles() {
        val input = shareInput ?: return
        try {
            while (shareSocket?.isClosed == false) {
                val name = input.readUTF()
                val size = input.readLong()
                if (size < 0L || size > 5L * 1024L * 1024L * 1024L) throw IllegalStateException("Invalid file size")
                val folder = java.io.File(getExternalFilesDir(null), "PocketToolReceived").apply { mkdirs() }
                val safeName = java.io.File(name).name
                var target = java.io.File(folder, safeName)
                if (target.exists()) target = java.io.File(folder, "${System.currentTimeMillis()}_$safeName")
                target.outputStream().buffered().use { output ->
                    val buffer = ByteArray(64 * 1024); var remaining = size
                    while (remaining > 0) {
                        val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        if (count < 0) throw java.io.EOFException("Transfer interrupted")
                        output.write(buffer, 0, count); remaining -= count
                    }
                }
                runOnUiThread { updateNearbyStatus("Received: ${target.name}\nSaved in PocketToolReceived") }
            }
        } catch (e: Exception) {
            runOnUiThread { updateNearbyStatus("Receive channel closed: ${e.message}") }
        }
    }

    private fun chooseNearbyFile() {
        if (shareSocket?.isConnected != true || shareOutput == null) {
            toast("Pehle nearby device connect karein"); return
        }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }
        startActivityForResult(intent, sharePickRequest)
    }

    private fun sendNearbyFile(uri: android.net.Uri) {
        Thread {
            try {
                val name = contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (cursor.moveToFirst() && index >= 0) cursor.getString(index) else "shared_file"
                } ?: "shared_file"
                val size = contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
                if (size < 0L || size > 5L * 1024L * 1024L * 1024L) throw IllegalStateException("File size unavailable or over 5 GB")
                val output = shareOutput ?: throw IllegalStateException("Not connected")
                synchronized(output) {
                    output.writeUTF(java.io.File(name).name); output.writeLong(size)
                    contentResolver.openInputStream(uri)?.use { stream ->
                        val buffer = ByteArray(64 * 1024); var count: Int
                        while (stream.read(buffer).also { count = it } != -1) if (count > 0) output.write(buffer, 0, count)
                    } ?: throw IllegalStateException("Cannot read selected file")
                    output.flush()
                }
                runOnUiThread { updateNearbyStatus("Sent: $name") }
            } catch (e: Exception) { runOnUiThread { updateNearbyStatus("Send failed: ${e.message}") } }
        }.start()
    }

    private fun updateNearbyStatus(message: String) {
        val panel = body.findViewWithTag<LinearLayout>("nearbyShareStatus") ?: return
        panel.removeAllViews(); panel.addView(label(message))
    }

    private fun importPasswordBundle() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
        }
        try { startActivityForResult(intent, passwordBundleRequest) }
        catch (_: Exception) { toast("File picker open nahi hua") }
    }

    @Deprecated("Deprecated by Android; retained for broad device compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == sharePickRequest && resultCode == RESULT_OK) { data?.data?.let { sendNearbyFile(it) }; return }
        if (requestCode != passwordBundleRequest || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            val loaded = linkedSetOf<String>()
            var totalChars = 0
            contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    totalChars += line.length
                    if (totalChars > 2_000_000 || loaded.size >= 50_000) break
                    if (line.isNotBlank()) loaded.add(line.trim())
                }
            } ?: throw IllegalStateException("File read nahi ho paayi")
            importedPasswords = loaded
            status.text = "Password bundle loaded • ${loaded.size} unique entries"
            details("Password Bundle Imported", "Entries loaded: ${loaded.size}\nMaximum: 50,000 lines / 2 MB\n\nThe list stays in app memory only. It is not uploaded and is cleared when the app process closes. Use only your own password list.")
        } catch (e: Exception) {
            toast("TXT file read nahi hui: ${e.message ?: "unknown error"}")
        }
    }

    private fun runPasswordBundleSimulator() {
        if (importedPasswords.isEmpty()) {
            toast("Pehle apni TXT password bundle import karein")
            return
        }
        val input = EditText(this).apply {
            hint = "Apna test password enter karein"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        android.app.AlertDialog.Builder(this).setTitle("Offline Password Test Simulator")
            .setMessage("Simulator imported list ko local memory mein line-by-line compare karega. Koi Wi-Fi/router connection ya login attempt nahi hoga.")
            .setView(input).setNegativeButton("CANCEL", null)
            .setPositiveButton("START SIMULATION") { _, _ ->
                val target = input.text.toString()
                if (target.isEmpty()) { toast("Test password enter karein"); return@setPositiveButton }
                val entries = importedPasswords.toList()
                val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = entries.size
                    progress = 0
                }
                val message = label("Starting local comparison…")
                val panel = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(20), dp(12), dp(20), dp(8))
                    addView(message)
                    addView(progress)
                }
                val dialog = android.app.AlertDialog.Builder(this)
                    .setTitle("Password Test Simulator")
                    .setView(panel)
                    .setNegativeButton("CLOSE", null)
                    .create()
                dialog.show()
                Thread {
                    var foundAt = -1
                    for (i in entries.indices) {
                        if (entries[i] == target) { foundAt = i; break }
                        if (i % 100 == 0 || i == entries.lastIndex) {
                            val done = i + 1
                            runOnUiThread {
                                if (dialog.isShowing) {
                                    progress.progress = done
                                    message.text = "Local comparisons: $done / ${entries.size}"
                                }
                            }
                            try { Thread.sleep(8) } catch (_: InterruptedException) { break }
                        }
                    }
                    val tested = if (foundAt >= 0) foundAt + 1 else entries.size
                    runOnUiThread {
                        if (dialog.isShowing) dialog.dismiss()
                        details("Simulation Complete",
                            "Entries checked locally: $tested / ${entries.size}\nResult: ${if (foundAt >= 0) "MATCH FOUND" else "NO MATCH"}\n\n${if (foundAt >= 0) "This test password exists in your imported bundle. Do not use it." else "No exact match in this list. This does not prove the password is secure."}\n\nSIMULATION ONLY • No Wi-Fi packets sent • No router login attempts.")
                    }
                }.start()
            }.show()
    }

    private fun checkPasswordAgainstBundle() {
        if (importedPasswords.isEmpty()) {
            toast("Pehle apni TXT password bundle import karein")
            return
        }
        val input = EditText(this).apply {
            hint = "Apna password enter karein"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        android.app.AlertDialog.Builder(this).setTitle("Check My Password")
            .setMessage("Ye check sirf phone par imported list se exact match karta hai. Kisi Wi-Fi/router par login attempt nahi hota.")
            .setView(input).setNegativeButton("CANCEL", null)
            .setPositiveButton("CHECK") { _, _ ->
                val candidate = input.text.toString()
                val found = importedPasswords.contains(candidate)
                details("Offline Bundle Check",
                    if (found) "MATCH FOUND\n\nYe password tumhari imported list mein maujood hai. Is password ko use na karein; ek unique passphrase banayein."
                    else "NO EXACT MATCH\n\nYe password imported list mein exact match nahi hua. Iska matlab password guaranteed secure nahi hai; length, uniqueness aur reuse bhi matter karte hain.\n\nNo network or router was contacted.")
            }.show()
    }

    private fun showMyNetworkAudit() {
        try {
            @Suppress("DEPRECATION")
            val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val info = wm.connectionInfo
            val dhcp = wm.dhcpInfo
            if (info == null || info.networkId == -1) {
                details("My Network Security Audit", "Wi-Fi connected nahi hai. Apne Wi-Fi se connect karke dobara check karein.")
                return
            }
            fun ip(v: Int) = "%d.%d.%d.%d".format(v and 255, v shr 8 and 255, v shr 16 and 255, v shr 24 and 255)
            val findings = mutableListOf<String>()
            if (info.rssi < -70) findings.add("• Signal weak hai; router placement check karein.")
            if (dhcp.gateway == 0) findings.add("• Gateway details unavailable.")
            findings.add("• Router admin password default se alag rakhein.")
            findings.add("• WPA3-Personal prefer karein; warna WPA2-AES use karein.")
            findings.add("• WPS disable, firmware update aur guest network review karein.")
            details("My Network Security Audit",
                "Connected SSID: ${info.ssid}\nPhone IP: ${ip(info.ipAddress)}\nRouter IP: ${if (dhcp.gateway != 0) ip(dhcp.gateway) else "Unavailable"}\nSignal: ${info.rssi} dBm\nFrequency: ${info.frequency} MHz\n\nSecurity review:\n" + findings.joinToString("\n") +
                "\n\nNote: Android does not expose the router's full security configuration here. Verify encryption and WPS in your router admin settings.")
        } catch (_: Exception) { toast("Network audit unavailable; Wi-Fi permission check karein.") }
    }

    private fun showDnsLookup() {
        val input = EditText(this).apply { hint = "example.com"; setSingleLine(true) }
        android.app.AlertDialog.Builder(this).setTitle("DNS Lookup")
            .setMessage("Domain ka DNS address resolve karein. Sirf public DNS lookup; no port scan.")
            .setView(input).setNegativeButton("CANCEL", null)
            .setPositiveButton("LOOK UP") { _, _ ->
                val host = input.text.toString().trim().removePrefix("https://").removePrefix("http://").substringBefore("/")
                if (host.isBlank() || host.contains(" ")) { toast("Valid domain enter karein"); return@setPositiveButton }
                status.text = "DNS lookup running…"
                Thread {
                    val result = try {
                        java.net.InetAddress.getAllByName(host).map { it.hostAddress }.distinct().joinToString("\n")
                    } catch (e: Exception) { "Lookup failed: ${e.message ?: "Domain resolve nahi hua"}" }
                    runOnUiThread { details("DNS: $host", result) }
                }.start()
            }.show()
    }

    private fun showHashLab() {
        val input = EditText(this).apply { hint = "Text to hash"; minLines = 2 }
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(8), dp(18), 0); addView(label("SHA-256 hash locally on this phone. Do not enter real passwords or secrets.")); addView(input) }
        android.app.AlertDialog.Builder(this).setTitle("SHA-256 Hash Lab").setView(panel)
            .setNegativeButton("CANCEL", null).setPositiveButton("GENERATE") { _, _ ->
                val bytes = java.security.MessageDigest.getInstance("SHA-256").digest(input.text.toString().toByteArray(Charsets.UTF_8))
                val hash = bytes.joinToString("") { "%02x".format(it) }
                details("SHA-256 Result", hash)
                copy(hash)
            }.show()
    }

    private fun showWebSecurityChecklist() {
        details("Web App Security Checklist",
            "AUTHORIZED REVIEW ONLY\n\n1. Authentication: MFA, secure password reset and rate limits.\n2. Authorization: verify server-side access checks for every object.\n3. Input handling: validate input and use parameterized queries.\n4. Sessions: Secure, HttpOnly, SameSite cookies; rotate session IDs.\n5. Transport: HTTPS everywhere; renew certificates.\n6. Configuration: remove debug pages, defaults and exposed secrets.\n7. Dependencies: patch vulnerable libraries and frameworks.\n8. Data: minimize collection; encrypt sensitive data at rest.\n9. Logging: record security events without logging passwords/tokens.\n10. Recovery: tested backups and incident response plan.\n\nThis is a manual checklist, not an automated vulnerability scanner.")
    }

    private fun showEthicalRoadmap() {
        details("Ethical Hacking Roadmap",
            "BEGINNER → PRACTICAL\n\n1. Networking: TCP/IP, DNS, HTTP/HTTPS, ports and subnetting.\n2. Linux: terminal, files, permissions and processes.\n3. Programming: Python basics, shell and JavaScript fundamentals.\n4. Web: requests, cookies, sessions, APIs and OWASP Top 10.\n5. Security: threat modelling, authentication and cryptography basics.\n6. Practice only in legal labs: OWASP Juice Shop, PortSwigger Web Security Academy and TryHackMe beginner rooms.\n7. Document scope, evidence, impact and remediation in every report.\n\nNever test public systems without written authorization.")
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun weight() = LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(5); bottomMargin = dp(5) }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    private fun heading(s: String) {
        body.addView(TextView(this).apply {
            text = s.uppercase(); textSize = 13f; letterSpacing = 0.06f
            setTypeface(null, Typeface.BOLD); setTextColor(Color.rgb(35, 73, 112))
            setPadding(dp(3), dp(18), dp(3), dp(9))
        })
    }
    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(13), dp(12), dp(13), dp(12))
        background = GradientDrawable().apply {
            setColor(Color.WHITE); cornerRadius = dp(16).toFloat()
            setStroke(dp(1), Color.rgb(226, 233, 241))
        }
        elevation = dp(2).toFloat()
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(7) }
    }
    private fun label(s: String) = TextView(this).apply {
        text = s; textSize = 12f; setTextColor(Color.rgb(86, 99, 116))
        setPadding(dp(4), dp(6), dp(4), dp(6)); setLineSpacing(dp(2).toFloat(), 1f)
    }
    private fun button(s: String, action: () -> Unit) = Button(this).apply {
        text = s; textSize = 10f; letterSpacing = 0.01f; isAllCaps = false
        setTypeface(null, Typeface.BOLD); setTextColor(Color.WHITE)
        minHeight = dp(38); minimumHeight = dp(38); setPadding(dp(10), 0, dp(10), 0)
        background = GradientDrawable().apply {
            setColor(Color.rgb(35, 104, 190)); cornerRadius = dp(10).toFloat()
        }
        stateListAnimator = null
        setOnClickListener { action() }
    }
    private fun sendIr(cmdText: String) {
        val a=address.text.toString().trim().toIntOrNull(16)
        val c=cmdText.trim().toIntOrNull(16)
        if (a==null || c==null || a !in 0..255 || c !in 0..255) { toast("Address/command 00–FF hex mein daalein"); return }
        val ir=getSystemService(CONSUMER_IR_SERVICE) as? ConsumerIrManager
        if (ir==null || !ir.hasIrEmitter()) { status.text="Is phone mein IR blaster nahi mila"; toast(status.text.toString()); return }
        val bits=(a and 255) or ((a.inv() and 255) shl 8) or ((c and 255) shl 16) or ((c.inv() and 255) shl 24)
        val p=ArrayList<Int>(); p.add(9000); p.add(4500)
        for (i in 0 until 32) { p.add(560); p.add(if (((bits shr i) and 1)==1) 1690 else 560) }
        p.add(560); p.add(20000); ir.transmit(38000,p.toIntArray())
        status.text="IR sent • address %02X • command %02X".format(a,c)
    }

    private val callback = object : ScanCallback() {
        override fun onScanResult(type: Int, result: ScanResult) { ble[result.device.address]=result; runOnUiThread { renderBle() } }
        override fun onScanFailed(code: Int) { runOnUiThread { status.text="Bluetooth scan failed: $code" } }
    }
    private fun toggleBle() {
        val scanner=(getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter?.bluetoothLeScanner
        if (scanner==null) { toast("Bluetooth ON karein"); return }
        try {
            if (scanning) { scanner.stopScan(callback); scanning=false; status.text="Bluetooth scan stopped" }
            else { ble.clear(); renderBle(); scanner.startScan(callback); scanning=true; status.text="Scanning BLE devices…" }
        } catch (_: SecurityException) { toast("Bluetooth permission allow karein") }
    }
    private fun renderBle() {
        val list=body.findViewWithTag<LinearLayout>("bleList") ?: return
        list.removeAllViews()
        if (ble.isEmpty()) { list.addView(label("No BLE devices yet. Start scan.")); return }
        ble.values.sortedByDescending { it.rssi }.forEach { result ->
            val d=result.device; val item=card()
            item.addView(label("${result.scanRecord?.deviceName ?: "Unknown device"}\n${d.address} • ${result.rssi} dBm"))
            val actions=row()
            actions.addView(button("DETAILS") { details("BLE device","Name: ${result.scanRecord?.deviceName ?: "Unknown"}\nMAC: ${d.address}\nSignal: ${result.rssi} dBm\nConnectable: ${result.isConnectable}") },weight())
            actions.addView(button("COPY MAC") { copy(d.address) },weight())
            actions.addView(button("CONNECT") { connect(d) },weight())
            item.addView(actions); list.addView(item)
        }
    }
    private fun connect(device: BluetoothDevice) {
        try {
            val g=device.connectGatt(this,false,object: BluetoothGattCallback() {
                override fun onConnectionStateChange(gatt: BluetoothGatt, code: Int, state: Int) {
                    runOnUiThread { status.text=if(state==BluetoothGatt.STATE_CONNECTED && code==BluetoothGatt.GATT_SUCCESS) "Connected: ${device.address}" else "BLE state: ${state}, status: ${code}" }
                }
            })
            if(g!=null) { gatts.add(g); status.text="Connecting to ${device.address}…" }
        } catch (_: Exception) { toast("Connection nahi ho paya; device connectable hona chahiye") }
    }


    private fun showPasswordAudit(target: String) {
        val input = EditText(this).apply {
            hint = if (target == "Wi-Fi") "Apna Wi-Fi password yahan daalein" else "Apna Bluetooth PIN/password yahan daalein"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), 0)
            addView(label("Offline audit only. Password isi phone par check hoga; app kisi router/device par login ya password guesses nahi bhejega."))
            addView(input)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("$target Password Audit")
            .setView(panel)
            .setNegativeButton("CANCEL", null)
            .setPositiveButton("CHECK") { _, _ ->
                val value = input.text.toString()
                val result = assessPassword(value, target)
                details("$target Password Audit", result)
            }
            .show()
    }

    private fun assessPassword(value: String, target: String): String {
        if (value.isEmpty()) return "Password enter nahi kiya."
        val length = value.length
        val lower = value.any { it.isLowerCase() }
        val upper = value.any { it.isUpperCase() }
        val digit = value.any { it.isDigit() }
        val symbol = value.any { !it.isLetterOrDigit() }
        val variety = listOf(lower, upper, digit, symbol).count { it }
        val common = setOf("12345678", "password", "password123", "qwerty123", "admin123", "11111111", "00000000")
        val sequential = value.matches(Regex(".*(0123|1234|2345|3456|4567|5678|6789|9876|8765|7654).*"))
        val repeated = value.length >= 4 && value.all { it == value.first() }
        val minimum = if (target == "Wi-Fi") 12 else 8
        val score = (if (length >= minimum) 2 else 0) + (if (length >= 16) 1 else 0) + variety + (if (!common.contains(value.lowercase())) 1 else 0) - (if (sequential || repeated) 2 else 0)
        val grade = when {
            common.contains(value.lowercase()) || length < 6 || repeated -> "VERY WEAK"
            score <= 3 -> "WEAK"
            score <= 5 -> "MODERATE"
            score <= 7 -> "STRONG"
            else -> "VERY STRONG"
        }
        val tips = mutableListOf<String>()
        if (length < minimum) tips.add("Kam se kam $minimum characters use karein.")
        if (length < 16) tips.add("16+ characters ka unique passphrase aur behtar hoga.")
        if (variety < 3) tips.add("Upper/lowercase, numbers aur symbols ka mix rakhein.")
        if (common.contains(value.lowercase()) || sequential || repeated) tips.add("Common, sequential ya repeated pattern hataayein.")
        if (tips.isEmpty()) tips.add("Is password ko doosri services par reuse na karein.")
        return "Target: $target\\nStrength estimate: $grade\\nLength: $length characters\\nCharacter groups: $variety of 4\\n\\nSuggestions:\\n• " + tips.joinToString("\\n• ") + "\\n\\nNote: This is an offline heuristic, not a guarantee against cracking. Bluetooth devices use different pairing methods; Android apps cannot safely or generally automate PIN guesses against them."
    }

    private fun showPasswordGuessDemo() {
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(8), dp(18), 0) }
        panel.addView(label("Choose a sample numeric PIN length. This calculator estimates the search space only; it does not run billions of guesses or contact any network."))
        val length = SeekBar(this).apply { max = 14; progress = 3 }
        val chosen = label("PIN length: 4 digits")
        panel.addView(chosen); panel.addView(length)
        val output = label("10,000 possible combinations")
        panel.addView(output)
        length.setOnSeekBarChangeListener(object: android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seek: SeekBar?, progress: Int, fromUser: Boolean) {
                val n = progress + 1
                chosen.text = "PIN length: $n digits"
                val combinations = java.math.BigInteger.TEN.pow(n)
                val seconds = combinations.toDouble() / 1_000_000.0
                val time = when {
                    seconds < 60 -> "%.2f seconds".format(seconds)
                    seconds < 3600 -> "%.1f minutes".format(seconds / 60)
                    seconds < 86400 -> "%.1f hours".format(seconds / 3600)
                    seconds < 31557600 -> "%.1f days".format(seconds / 86400)
                    else -> "%.1f years".format(seconds / 31557600)
                }
                output.text = "Possible combinations: $combinations\\nAt hypothetical 1,000,000 guesses/sec: $time\\nAverage search: about half this time. Real systems add rate limits and cryptographic protections."
            }
            override fun onStartTrackingTouch(seek: SeekBar?) {}
            override fun onStopTrackingTouch(seek: SeekBar?) {}
        })
        android.app.AlertDialog.Builder(this).setTitle("Password Security Lab").setView(panel).setPositiveButton("CLOSE", null).show()
    }

    private fun showWifiAnalyzer() {
        val networks = try { (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager).scanResults } catch (_: Exception) { emptyList<WifiResult>() }
        if (networks.isEmpty()) { details("Wi-Fi Analyzer", "Pehle SCAN NEARBY WI-FI chalayein. Android scan limits apply."); return }
        val report = networks.groupBy { it.frequency }.entries.sortedBy { it.key }.joinToString("\\n") { (freq, entries) ->
            val avg = entries.map { it.level }.average().toInt()
            val band = if (freq < 2500) "2.4 GHz" else if (freq < 5900) "5 GHz" else "6 GHz"
            "$freq MHz ($band): ${entries.size} network(s), average signal $avg dBm"
        }
        val strongest = networks.maxByOrNull { it.level }
        details("Wi-Fi Analyzer", "Networks found: ${networks.size}\\nStrongest: ${strongest?.SSID ?: "Hidden"} (${strongest?.level} dBm)\\n\\nChannel/frequency overview:\\n$report\\n\\nTip: 2.4 GHz channels 1, 6, 11 are commonly preferred when choosing a less crowded channel.")
    }

    @Suppress("DEPRECATION")
    private fun showNetworkDiagnostics() {
        try {
            val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            val info = wm.connectionInfo
            val dhcp = wm.dhcpInfo
            val connected = info != null && info.networkId != -1
            details("Network Diagnostics", "Wi-Fi connected: $connected\\nSSID: ${info?.ssid ?: "Unavailable"}\\nPhone IP: ${info?.ipAddress?.let { "%d.%d.%d.%d".format(it and 255, it shr 8 and 255, it shr 16 and 255, it shr 24 and 255) } ?: "Unavailable"}\\nGateway: ${dhcp?.gateway ?: 0} (integer form)\\nDNS 1: ${dhcp?.dns1 ?: 0}\\nDNS 2: ${dhcp?.dns2 ?: 0}\\n\\nFor full readable IP details use MY CONNECTED WI-FI DETAILS.")
        } catch (_: Exception) { toast("Diagnostics unavailable; check permissions.") }
    }

    private fun showBleGuide() {
        details("BLE GATT Explorer", "1. Start Bluetooth LE scan.\\n2. Select a connectable device and tap CONNECT.\\n3. GATT services and characteristics can be inspected on supported devices.\\n\\nOnly inspect devices you own or have permission to test. Some devices require pairing or an app-specific protocol.")
    }

    private fun showDeviceSecurity() {
        details("Device Security Check", "Android security checklist:\\n• Keep Android security updates current.\\n• Use a screen lock and biometrics.\\n• Review app permissions in Settings.\\n• Install apps from trusted sources.\\n• Keep Google Play Protect enabled.\\n• Avoid unknown USB debugging connections.\\n• Use Find My Device.\\n\\nThis is an educational checklist, not a malware scan.")
    }

    private fun showNfcInfo() {
        val adapter = android.nfc.NfcAdapter.getDefaultAdapter(this)
        details("NFC Tag Reader", when {
            adapter == null -> "This phone does not report NFC hardware. NFC tag reading is unavailable."
            !adapter.isEnabled -> "NFC hardware is available but NFC is OFF. Enable it in Settings."
            else -> "NFC hardware is available and enabled. Bring a compatible NFC tag near the phone's NFC antenna. Reading depends on Android tag support and tag type."
        })
    }

    private fun showSubGhzLab() {
        details("Sub-GHz Radio Learning", "Sub-GHz commonly refers to radio bands below 1 GHz, such as 315, 433, 868 and 915 MHz.\\n\\nLearn these concepts:\\n• Frequency and wavelength\\n• Amplitude/frequency modulation (ASK/OOK/FSK)\\n• Legal regional frequency and power limits\\n• Why rolling codes protect modern remotes\\n\\nMost phones do not contain a Sub-GHz transceiver. This app provides education only; it does not capture, clone or transmit access-control signals.")
    }

    private fun showWifiSecurityLab() {
        val message = "WI-FI SECURITY LAB (EDUCATION)\n\n" +
            "1. Check your own network security: prefer WPA3-Personal, or WPA2-AES if WPA3 is unavailable.\n\n" +
            "2. Use a long, unique Wi-Fi passphrase (at least 14-16 characters). Avoid names, phone numbers and common words.\n\n" +
            "3. Disable WPS if you do not need it. Keep router firmware updated.\n\n" +
            "4. Change the router admin password from its default. Use a separate password from Wi-Fi.\n\n" +
            "5. Use a Guest Network for visitors and unknown devices.\n\n" +
            "6. Review connected devices in your router's official admin page.\n\n" +
            "7. Learn safely: test only your own router or a network where you have explicit permission.\n\n" +
            "This lab does not crack passwords, bypass authentication or connect to networks without permission."
        details("Wi-Fi Security Lab", message)
    }

    @Suppress("DEPRECATION")
    private fun openRouterPage() {
        try {
            val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            val gateway = wm.dhcpInfo.gateway
            if (gateway == 0) {
                toast("Router IP nahi mila. Pehle Wi-Fi se connect karein.")
                return
            }
            val ip = "%d.%d.%d.%d".format(gateway and 255, gateway shr 8 and 255, gateway shr 16 and 255, gateway shr 24 and 255)
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("http://$ip")))
        } catch (_: Exception) {
            toast("Router page open nahi ho paya")
        }
    }

    @Suppress("DEPRECATION")
    private fun showConnectedWifi() {
        try {
            val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            val info = wm.connectionInfo
            val dhcp = wm.dhcpInfo
            if (info == null || info.networkId == -1 || info.ssid == WifiManager.UNKNOWN_SSID) {
                details("Connected Wi-Fi", "Phone kisi Wi-Fi network se connected nahi hai. Pehle Settings se Wi-Fi connect karein.")
                return
            }
            fun ip(value: Int): String = "%d.%d.%d.%d".format(value and 255, value shr 8 and 255, value shr 16 and 255, value shr 24 and 255)
            val freq = info.frequency
            val channel = when {
                freq == 2484 -> 14
                freq in 2412..2472 -> (freq - 2407) / 5
                freq in 5000..5900 -> (freq - 5000) / 5
                freq in 5955..7115 -> (freq - 5950) / 5
                else -> -1
            }
            val report = "SSID: ${info.ssid}\nBSSID: ${info.bssid ?: "Unavailable"}\nPhone IP: ${ip(info.ipAddress)}\nRouter/Gateway IP: ${ip(dhcp.gateway)}\nSubnet mask: ${ip(dhcp.netmask)}\nDHCP server: ${ip(dhcp.serverAddress)}\nDNS 1: ${ip(dhcp.dns1)}\nDNS 2: ${ip(dhcp.dns2)}\nLink speed: ${info.linkSpeed} Mbps\nSignal: ${info.rssi} dBm\nFrequency: $freq MHz\nChannel: ${if (channel > 0) channel else "Unavailable"}\nNetwork ID: ${info.networkId}\nSupplicant: ${info.supplicantState}"
            details("My connected Wi-Fi", report)
        } catch (_: SecurityException) {
            toast("Location aur Nearby Wi-Fi permissions allow karein")
        } catch (_: Exception) {
            toast("Wi-Fi details read nahi ho paayi")
        }
    }

    private fun scanWifi() {
        val wm=applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        val list=body.findViewWithTag<LinearLayout>("wifiList") ?: return
        list.removeAllViews(); list.addView(label("Scanning Wi-Fi…"))
        try {
            receiver?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
            receiver=object: BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    try { unregisterReceiver(this) } catch (_: Exception) {}; receiver=null
                    renderWifi(wm.scanResults)
                }
            }
            val filter=IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
            if(Build.VERSION.SDK_INT>=33) registerReceiver(receiver,filter,Context.RECEIVER_NOT_EXPORTED)
            else @Suppress("DEPRECATION") registerReceiver(receiver,filter)
            if(!wm.startScan()) renderWifi(wm.scanResults)
        } catch (_: SecurityException) { list.removeAllViews(); list.addView(label("Location permission allow karein aur Location ON rakhein.")) }
    }
    private fun renderWifi(networks: List<WifiResult>) {
        val list=body.findViewWithTag<LinearLayout>("wifiList") ?: return
        list.removeAllViews()
        if(networks.isEmpty()) { list.addView(label("No networks found. Wi-Fi aur Location ON karke dobara scan karein.")); return }
        networks.sortedByDescending { it.level }.forEach { n ->
            val item=card()
            item.addView(label("${n.SSID.ifEmpty { "(Hidden network)" }}\n${n.level} dBm • ${n.frequency} MHz\nBSSID: ${n.BSSID}"))
            val actions=row()
            actions.addView(button("DETAILS") { details("Wi-Fi network","SSID: ${n.SSID}\nBSSID: ${n.BSSID}\nSignal: ${n.level} dBm\nFrequency: ${n.frequency} MHz\nCapabilities: ${n.capabilities}") },weight())
            actions.addView(button("COPY MAC") { copy(n.BSSID ?: "") },weight())
            item.addView(actions); list.addView(item)
        }
        status.text="Wi-Fi scan complete • ${networks.size} network(s)"
    }
    private fun copy(value: String) {
        val cb=getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cb.setPrimaryClip(ClipData.newPlainText("PocketTool",value)); toast("Copied: $value")
    }
    private fun details(title: String, message: String) { android.app.AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("OK",null).show() }
    private fun toast(s: String) = Toast.makeText(this,s,Toast.LENGTH_SHORT).show()
    override fun onDestroy() {
        if(scanning) try { (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter?.bluetoothLeScanner?.stopScan(callback) } catch (_: Exception) {}
        gatts.forEach { try { it.close() } catch (_: Exception) {} }
        receiver?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
        nsdListeners.forEach { try { nsdManager.stopServiceDiscovery(it) } catch (_: Exception) {} }
        nsdListeners.clear()
        try { p2pReceiver?.let { unregisterReceiver(it) } } catch (_: Exception) {}
        try { shareSocket?.close() } catch (_: Exception) {}
        try { shareServerSocket?.close() } catch (_: Exception) {}
        try { phoneShareServer?.stop() } catch (_: Exception) {}
        phoneShareServer = null
        super.onDestroy()
    }
}
