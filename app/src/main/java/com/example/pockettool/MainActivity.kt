package com.example.pockettool

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.ConsumerIrManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

@SuppressLint("MissingPermission")
class MainActivity : Activity() {
    private lateinit var out: TextView
    private lateinit var addr: EditText
    private lateinit var cmd: EditText
    private val found = linkedMapOf<String, String>()
    private var scanning = false

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) perms.add(Manifest.permission.BLUETOOTH_SCAN)
        requestPermissions(perms.toTypedArray(), 1)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 64, 32, 32)
        }
        addr = EditText(this).apply { hint = "IR address (hex)"; setText("04") }
        cmd = EditText(this).apply { hint = "IR command (hex)"; setText("08") }
        out = TextView(this).apply { textSize = 14f; text = "PocketTool ready (offline)" }

        root.addView(TextView(this).apply { text = "IR remote (NEC). Default = LG TV power example" })
        root.addView(addr)
        root.addView(cmd)
        root.addView(btn("Send IR") {
            sendNec(addr.text.toString().toIntOrNull(16) ?: 0, cmd.text.toString().toIntOrNull(16) ?: 0)
        })
        root.addView(btn("Bluetooth scan start/stop") { bleScan() })
        root.addView(btn("Wi-Fi scan") { wifiScan() })
        root.addView(ScrollView(this).apply { addView(out) })
        setContentView(root)
    }

    private fun btn(t: String, f: () -> Unit) =
        Button(this).apply { text = t; setOnClickListener { f() } }

    private fun sendNec(a: Int, c: Int) {
        val ir = getSystemService(CONSUMER_IR_SERVICE) as? ConsumerIrManager
        if (ir == null || !ir.hasIrEmitter()) { out.text = "IR blaster nahi mila"; return }
        val bits = (a and 0xFF) or ((a.inv() and 0xFF) shl 8) or
            ((c and 0xFF) shl 16) or ((c.inv() and 0xFF) shl 24)
        val p = ArrayList<Int>()
        p.add(9000); p.add(4500)
        for (i in 0 until 32) {
            p.add(560)
            p.add(if (((bits shr i) and 1) == 1) 1690 else 560)
        }
        p.add(560); p.add(20000)
        ir.transmit(38000, p.toIntArray())
        out.text = "IR sent: addr=%02X cmd=%02X".format(a and 0xFF, c and 0xFF)
    }

    private val cb = object : ScanCallback() {
        override fun onScanResult(type: Int, r: ScanResult) {
            val name = r.scanRecord?.deviceName ?: "?"
            found[r.device.address] = "$name  ${r.rssi} dBm  ${r.device.address}"
            runOnUiThread { out.text = found.values.joinToString("\n") }
        }
    }

    private fun bleScan() {
        val sc = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter?.bluetoothLeScanner
        if (sc == null) { out.text = "Bluetooth ON karo"; return }
        if (scanning) { sc.stopScan(cb); scanning = false; return }
        found.clear(); out.text = "Scanning..."
        sc.startScan(cb); scanning = true
    }

    private fun wifiScan() {
        val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        val show = {
            out.text = wm.scanResults.sortedByDescending { it.level }.joinToString("\n") {
                "${it.SSID.ifEmpty { "(hidden)" }}  ${it.level} dBm  ${it.frequency} MHz"
            }
        }
        registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { unregisterReceiver(this); show() }
        }, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
        if (!wm.startScan()) show()
    }
}
