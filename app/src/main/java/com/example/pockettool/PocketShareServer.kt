package com.example.pockettool

import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.util.UUID

class PocketShareServer(private val folder: File) : NanoHTTPD(8765) {
    private val token = UUID.randomUUID().toString().replace("-", "")
    private val maxBytes = 5L * 1024L * 1024L * 1024L
    init { folder.mkdirs() }
    fun shareUrl(ip: String): String = "http://$ip:8765/$token/"

    override fun serve(session: IHTTPSession): Response {
        val sub = session.uri.removePrefix("/$token").ifEmpty { "/" }
        if (!session.uri.startsWith("/$token")) return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Invalid share token")
        return try {
            when {
                session.method == Method.GET && sub == "/" -> page()
                session.method == Method.GET && sub == "/list" -> listJson()
                session.method == Method.GET && sub == "/download" -> download(session.parameters["name"]?.firstOrNull() ?: "")
                session.method == Method.POST && sub == "/upload" -> upload(session)
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
            }
        } catch (e: Exception) { newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, e.message ?: "Server error") }
    }

    private fun safeFile(name: String): File? {
        val clean = File(name).name
        if (clean.isBlank() || clean == "." || clean == "..") return null
        val f = File(folder, clean).canonicalFile
        return if (f.parentFile == folder.canonicalFile) f else null
    }

    private fun page(): Response {
        val html = """<!doctype html><meta name="viewport" content="width=device-width,initial-scale=1"><title>PocketTool Share</title>
<style>body{font:16px system-ui;max-width:760px;margin:24px auto;padding:0 14px;background:#f4f7fb;color:#17243a}section{background:white;padding:16px;border-radius:14px;margin:14px 0}button{padding:10px 14px;background:#1769aa;color:white;border:0;border-radius:8px}li{padding:10px;border-bottom:1px solid #ddd;overflow-wrap:anywhere}a{color:#075ca8}</style>
<h2>PocketTool • Wi-Fi File Share</h2><section><b>Shared folder:</b> ${folder.name}<p>Select a file to upload (max 5 GB).</p><form method="post" action="/$token/upload" enctype="multipart/form-data"><input type="file" name="file" required><button>Upload</button></form></section>
<section><h3>Files</h3><ul id="files">Loading…</ul></section>
<script>async function load(){let r=await fetch('/$token/list');let a=await r.json();document.getElementById('files').innerHTML=a.map(x=>'<li>'+x.name+' ('+x.size+' bytes) — <a href="/$token/download?name='+encodeURIComponent(x.name)+'">Download</a></li>').join('')||'No files yet'}load();</script>"""
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
    }

    private fun listJson(): Response {
        val items = folder.listFiles()?.filter { it.isFile }?.joinToString(",") {
            val n = it.name.replace("\\", "\\\\").replace("\"", "\\\"")
            """{"name":"$n","size":${it.length()}}"""
        } ?: ""
        return newFixedLengthResponse(Response.Status.OK, "application/json", "[$items]")
    }

    private fun download(name: String): Response {
        val f = safeFile(name) ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Bad filename")
        if (!f.isFile) return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "File not found")
        val response = newChunkedResponse(Response.Status.OK, "application/octet-stream", f.inputStream())
        response.addHeader("Content-Disposition", "attachment; filename=\"${f.name.replace("\"", "")}\"")
        return response
    }

    private fun upload(session: IHTTPSession): Response {
        val files = HashMap<String, String>()
        session.parseBody(files)
        val temp = files["file"] ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Choose a file")
        val raw = session.parameters["file"]?.firstOrNull() ?: "upload.bin"
        val target = safeFile(raw) ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Bad filename")
        if (File(temp).length() > maxBytes) return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Max file size is 5 GB")
        File(temp).copyTo(target, overwrite = true)
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", "<meta http-equiv='refresh' content='1;url=/$token/'><p>Upload complete. <a href='/$token/'>Back</a></p>")
    }
}