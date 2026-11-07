package com.example.pockettool

import java.io.*
import java.net.*

/** Minimal plain-FTP server for trusted local networks. */
class PocketFtpServer(private val root: File, private val user: String, private val pass: String) {
    @Volatile private var running = false
    private var server: ServerSocket? = null
    val port = 2121

    @Synchronized fun start() {
        if (running) return
        root.mkdirs()
        server = ServerSocket(port, 20, InetAddress.getByName("0.0.0.0"))
        running = true
        Thread({ while (running) try {
            val client = server!!.accept()
            Thread({ serve(client) }, "PocketFTP-client").start()
        } catch (_: Exception) { if (!running) break } }, "PocketFTP-server").start()
    }

    @Synchronized fun stop() {
        running = false
        try { server?.close() } catch (_: Exception) {}
        server = null
    }

    private fun resolve(cwd: File, name: String): File? = try {
        val base = if (name.startsWith("/")) root else cwd
        val f = File(base, name).canonicalFile
        if (f.path == root.canonicalPath || f.path.startsWith(root.canonicalPath + File.separator)) f else null
    } catch (_: Exception) { null }

    private fun serve(socket: Socket) {
        var cwd = root
        var logged = false
        var passive: ServerSocket? = null
        val input = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
        val output = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
        fun send(code: Int, message: String) { output.write(code.toString() + " " + message + "\r\n"); output.flush() }
        try {
            send(220, "PocketTool FTP ready")
            while (running) {
                val line = input.readLine() ?: break
                val command = line.substringBefore(' ').uppercase()
                val arg = line.substringAfter(' ', "")
                when (command) {
                    "USER" -> send(331, "Password required")
                    "PASS" -> { logged = arg == pass; send(if (logged) 230 else 530, if (logged) "Login OK" else "Login incorrect") }
                    "QUIT" -> { send(221, "Bye"); break }
                    "SYST" -> send(215, "UNIX Type: L8")
                    "TYPE", "NOOP" -> send(200, "OK")
                    "PWD" -> send(257, "\"/\"")
                    "CWD" -> { val f = resolve(cwd, arg); if (logged && f?.isDirectory == true) { cwd=f; send(250,"Directory changed") } else send(550,"Not found") }
                    "PASV", "EPSV" -> {
                        try { passive?.close() } catch (_: Exception) {}
                        passive = ServerSocket(0, 1, InetAddress.getByName("0.0.0.0"))
                        val p = passive!!.localPort
                        if (command == "EPSV") send(229, "Entering Extended Passive Mode (|||" + p + "|)")
                        else {
                            val b = socket.localAddress.address.map { it.toInt() and 255 }
                            send(227, "Entering Passive Mode (" + b[0]+","+b[1]+","+b[2]+","+b[3]+","+(p/256)+","+(p%256)+")")
                        }
                    }
                    "LIST", "NLST", "RETR", "STOR" -> {
                        if (!logged) { send(530,"Login first"); continue }
                        val f = if (arg.isBlank() || command == "LIST" || command == "NLST") cwd else resolve(cwd,arg)
                        val ps = passive
                        if (f == null || ps == null) { send(425,"Use PASV first"); continue }
                        send(150,"Opening data connection")
                        try {
                            ps.soTimeout = 15000
                            ps.accept().use { data ->
                                when(command) {
                                    "RETR" -> { if (!f.isFile) throw IOException("Not a file"); FileInputStream(f).use { it.copyTo(data.getOutputStream()) } }
                                    "STOR" -> { if (f == root) throw IOException("Invalid path"); f.parentFile?.mkdirs(); FileOutputStream(f).use { data.getInputStream().copyTo(it) } }
                                    else -> {
                                        val entries = if (f.isDirectory) f.listFiles() ?: emptyArray() else arrayOf(f)
                                        val listing = entries.joinToString("\r\n", postfix = if(entries.isEmpty()) "" else "\r\n") { (if(it.isDirectory) "d" else "-") + "rw-r--r-- 1 user group " + it.length() + " Jan 01 00:00 " + it.name }
                                        data.getOutputStream().write(listing.toByteArray(Charsets.UTF_8))
                                    }
                                }
                            }
                            send(226,"Transfer complete")
                        } catch (e: Exception) { send(550,"Transfer failed") }
                        finally { try { ps.close() } catch (_: Exception) {}; passive=null }
                    }
                    "SIZE" -> { val f=resolve(cwd,arg); if(logged && f?.isFile==true) send(213,f.length().toString()) else send(550,"Not found") }
                    else -> send(502,"Command not supported")
                }
            }
        } catch (_: Exception) {
        } finally { try { passive?.close() } catch (_: Exception) {}; try { socket.close() } catch (_: Exception) {} }
    }
}