package com.example.pockettool

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.wifi.WifiManager
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

class MainActivity : Activity() {
    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var link: TextView
    private lateinit var filesList: LinearLayout
    private var server: PocketShareServer? = null
    private val pickFileRequest = 501

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "PocketTool Share"
        window.statusBarColor = Color.rgb(18, 45, 78)
        window.navigationBarColor = Color.rgb(18, 45, 78)
        buildScreen()
        refreshFiles()
    }

    private fun buildScreen() {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(244, 247, 251))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(24), dp(22), dp(24))
            setBackgroundColor(Color.rgb(18, 45, 78))
        }
        header.addView(TextView(this).apply {
            text = "PocketTool Share"
            textSize = 27f
            setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
        })
        header.addView(TextView(this).apply {
            text = "अपने फोन से दूसरे फोन या कंप्यूटर में फाइल भेजें"
            textSize = 14f
            setTextColor(Color.rgb(220, 232, 245))
            setPadding(0, dp(6), 0, 0)
        })
        page.addView(header)
        val scroll = ScrollView(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(28))
        }
        scroll.addView(root)
        page.addView(scroll)
        setContentView(page)

        status = text("सर्वर बंद है", 16f, Color.rgb(40, 55, 70))
        panel().also { it.addView(text("1. फाइल शेयरिंग चालू करें", 19f, Color.BLACK, true)); it.addView(status)
            it.addView(actionButton("फाइल शेयरिंग चालू करें") { startShareServer() })
            it.addView(actionButton("शेयरिंग बंद करें") { stopShareServer() })
            it.addView(text("दोनों फोन एक ही Wi-Fi से जुड़े होने चाहिए।", 13f, Color.DKGRAY))
        }
        link = text("शेयर लिंक यहाँ दिखेगा", 14f, Color.rgb(20, 80, 140))
        panel().also { it.addView(text("2. लिंक दूसरे फोन में खोलें", 19f, Color.BLACK, true))
            it.addView(link)
            it.addView(actionButton("लिंक कॉपी करें") {
                val value = link.text.toString()
                if (value.startsWith("http")) {
                    val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("PocketTool link", value))
                    toast("लिंक कॉपी हो गया")
                } else toast("पहले फाइल शेयरिंग चालू करें")
            })
            it.addView(text("दूसरे फोन के Chrome में लिंक खोलें। वहाँ से फाइल डाउनलोड करें या अपने फोन से फाइल अपलोड करें।", 13f, Color.DKGRAY))
        }
        panel().also { it.addView(text("3. डाउनलोड के लिए फाइल चुनें", 19f, Color.BLACK, true))
            it.addView(actionButton("फोन से फाइल चुनें") { chooseFile() })
            filesList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            it.addView(filesList)
        }
        root.addView(text("फाइलें इस फोन के PocketToolShare फोल्डर में रहेंगी।", 12f, Color.GRAY))
    }

    private fun panel(): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setBackgroundColor(Color.WHITE)
        }
        val params = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) }
        root.addView(box, params)
        return box
    }

    private fun actionButton(caption: String, action: () -> Unit): Button =
        Button(this).apply {
            text = caption
            isAllCaps = false
            textSize = 16f
            setOnClickListener { action() }
        }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, dp(5), 0, dp(7))
        }

    private fun startShareServer() {
        if (server != null) { toast("शेयरिंग पहले से चालू है"); return }
        try {
            @Suppress("DEPRECATION")
            val wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val rawIp = wifi.connectionInfo.ipAddress
            if (rawIp == 0) {
                status.text = "Wi-Fi से कनेक्ट करें, फिर दोबारा कोशिश करें"
                toast("पहले Wi-Fi से जुड़ें")
                return
            }
            val ip = "%d.%d.%d.%d".format(rawIp and 255, rawIp shr 8 and 255, rawIp shr 16 and 255, rawIp shr 24 and 255)
            val folder = shareFolder()
            val newServer = PocketShareServer(folder)
            newServer.start(10000, false)
            server = newServer
            link.text = newServer.shareUrl(ip)
            status.text = "शेयरिंग चालू है • Wi-Fi: $ip"
            toast("फाइल शेयरिंग चालू हो गई")
        } catch (e: Exception) {
            server = null
            status.text = "शुरू नहीं हुआ: ${e.message ?: "Unknown error"}"
            toast("Server शुरू नहीं हुआ")
        }
    }

    private fun stopShareServer() {
        try { server?.stop() } catch (_: Exception) {}
        server = null
        status.text = "सर्वर बंद है"
        link.text = "शेयर लिंक यहाँ दिखेगा"
        toast("शेयरिंग बंद हो गई")
    }

    private fun chooseFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(intent, pickFileRequest)
    }

    @Deprecated("Deprecated Android callback retained for broad device compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != pickFileRequest || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            val name = queryName(uri) ?: "shared_file_${System.currentTimeMillis()}"
            val safeName = File(name).name
            val destination = File(shareFolder(), safeName)
            contentResolver.openInputStream(uri)?.use { input ->
                destination.outputStream().buffered().use { output -> input.copyTo(output) }
            } ?: throw IllegalStateException("फाइल पढ़ नहीं पाए")
            refreshFiles()
            toast("फाइल जोड़ दी गई")
        } catch (e: Exception) {
            toast("फाइल नहीं जुड़ी: ${e.message}")
        }
    }

    private fun queryName(uri: android.net.Uri): String? {
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (index >= 0) return cursor.getString(index)
            }
        }
        return null
    }

    private fun shareFolder(): File {
        val base = getExternalFilesDir(null) ?: filesDir
        return File(base, "PocketToolShare").apply { mkdirs() }
    }

    private fun refreshFiles() {
        if (!::filesList.isInitialized) return
        filesList.removeAllViews()
        val files = shareFolder().listFiles()?.filter { it.isFile } ?: emptyList()
        if (files.isEmpty()) {
            filesList.addView(text("अभी कोई फाइल नहीं है। ऊपर से फाइल चुनें या दूसरे फोन से लिंक द्वारा अपलोड करें।", 13f, Color.GRAY))
        } else {
            files.forEach { file ->
                filesList.addView(text("• ${file.name}  (${android.text.format.Formatter.formatFileSize(this, file.length())})", 14f, Color.DKGRAY))
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        try { server?.stop() } catch (_: Exception) {}
        server = null
        super.onDestroy()
    }
}
