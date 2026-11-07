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
        wc.addView(label("Router/gateway IP, phone IP, subnet, DNS, link speed, channel, security and nearby network details."))
        wc.addView(LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; tag = "wifiList" })
        body.addView(wc)
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
