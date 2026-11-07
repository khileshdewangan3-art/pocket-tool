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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val permissions = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        requestPermissions(permissions.toTypedArray(), 10)
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(245,247,250)) }
        page.addView(TextView(this).apply {
            text = "PocketTool 2.0\nSmart device utilities"; textSize = 23f
            setTypeface(null, Typeface.BOLD); setTextColor(Color.WHITE); setPadding(22,22,22,22)
            setBackgroundColor(Color.rgb(24,35,55))
        })
        val scroll = ScrollView(this)
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16,14,16,20) }
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

        heading("Wi-Fi network scanner")
        val wc = card()
        wc.addView(button("MY CONNECTED WI-FI DETAILS") { showConnectedWifi() })
        wc.addView(button("OPEN WI-FI SETTINGS / SHARE QR") {
            startActivity(Intent(android.provider.Settings.ACTION_WIFI_SETTINGS))
        })
        wc.addView(button("OPEN ROUTER ADMIN PAGE") { openRouterPage() })
        wc.addView(button("SCAN NEARBY WI-FI") { scanWifi() })
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
        status = label("Ready • Offline utility")
        body.addView(status)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun weight() = LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(4) }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    private fun heading(s: String) { body.addView(TextView(this).apply {
        text=s; textSize=17f; setTypeface(null,Typeface.BOLD); setTextColor(Color.rgb(28,39,58)); setPadding(3,16,3,8)
    }) }
    private fun card() = LinearLayout(this).apply {
        orientation=LinearLayout.VERTICAL; setPadding(dp(14),dp(12),dp(14),dp(14))
        background=GradientDrawable().apply { setColor(Color.WHITE); cornerRadius=dp(14).toFloat(); setStroke(dp(1),Color.rgb(225,230,237)) }
        elevation=dp(2).toFloat()
    }
    private fun label(s: String) = TextView(this).apply { text=s; textSize=12f; setTextColor(Color.DKGRAY); setPadding(5,8,5,8) }
    private fun button(s: String, action: () -> Unit) = Button(this).apply {
        text=s; textSize=11f; isAllCaps=false; setTextColor(Color.WHITE)
        backgroundTintList=android.content.res.ColorStateList.valueOf(Color.rgb(35,104,190))
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
        super.onDestroy()
    }
}
