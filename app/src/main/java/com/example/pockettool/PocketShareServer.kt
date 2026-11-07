package com.example.pockettool

import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.util.UUID

class PocketShareServer(private val folder: File) : NanoHTTPD(8765) {
    private val token = UUID.randomUUID().toString().replace("-", "")
    private val maxBytes = 5L * 1024L * 1024L * 1024L
    private val chunkBytes = 8L * 1024L * 1024L
    private val partsFolder = File(folder, ".pocket_upload_parts")
    init { folder.mkdirs(); partsFolder.mkdirs() }
    fun shareUrl(ip: String): String = "http://$ip:8765/$token/"

    override fun serve(session: IHTTPSession): Response {
        val sub = session.uri.removePrefix("/$token").ifEmpty { "/" }
        if (!session.uri.startsWith("/$token")) return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Invalid share token")
        return try {
            when {
                session.method == Method.GET && sub == "/" -> page()
                session.method == Method.GET && sub == "/list" -> listJson()
                session.method == Method.GET && sub == "/download" -> download(session.parameters["name"]?.firstOrNull() ?: "")
                session.method == Method.POST && sub == "/chunk" -> receiveChunk(session)
                session.method == Method.POST && sub == "/complete" -> completeUpload(session)
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
<h2>PocketTool • Wi-Fi File Share</h2><section><b>Shared folder:</b> ${folder.name}<p>Upload up to 5 GB in 8 MB packets; packets are joined into one file.</p><input id="pick" type="file"><button onclick="upload()">Upload</button><p id="status">Ready</p><progress id="progress" value="0" max="100"></progress></section>
<section><h3>Files</h3><ul id="files">Loading…</ul></section>
<script>async function load(){let r=await fetch('/$token/list');let a=await r.json();document.getElementById('files').innerHTML=a.map(x=>'<li>'+x.name+' ('+x.size+' bytes) — <a href="/$token/download?name='+encodeURIComponent(x.name)+'">Download</a></li>').join('')||'No files yet'}async function upload(){const f=document.getElementById('pick').files[0];if(!f)return;if(f.size>5*1024*1024*1024){alert('Maximum size is 5 GB');return}const id=crypto.randomUUID(),chunk=8*1024*1024,total=Math.ceil(f.size/chunk),s=document.getElementById('status'),p=document.getElementById('progress');try{for(let i=0;i<total;i++){const form=new FormData();form.append('file',f.slice(i*chunk,Math.min(f.size,(i+1)*chunk)),'chunk.bin');const r=await fetch('/$token/chunk?uploadId='+id+'&index='+i+'&total='+total,{method:'POST',body:form});if(!r.ok)throw new Error(await r.text());p.value=(i+1)*100/total;s.textContent='Packet '+(i+1)+' / '+total}const d=await fetch('/$token/complete?uploadId='+id+'&total='+total+'&name='+encodeURIComponent(f.name),{method:'POST'});if(!d.ok)throw new Error(await d.text());s.textContent=await d.text();await load()}catch(e){s.textContent='Upload failed: '+e.message}}load();</script>"""
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

    private fun receiveChunk(session: IHTTPSession): Response {
        val p = session.parameters
        val id = p["uploadId"]?.firstOrNull() ?: return bad("Missing upload id")
        if (!id.matches(Regex("[a-fA-F0-9-]{36}"))) return bad("Invalid upload id")
        val index = p["index"]?.firstOrNull()?.toIntOrNull() ?: return bad("Invalid packet index")
        val total = p["total"]?.firstOrNull()?.toIntOrNull() ?: return bad("Invalid packet count")
        if (total !in 1..640 || index !in 0 until total) return bad("Invalid packet range")
        val files = HashMap<String, String>()
        session.parseBody(files)
        val temp = files["file"] ?: return bad("Missing packet")
        val input = File(temp)
        if (input.length() > chunkBytes || input.length() == 0L) return bad("Packet must be 1–8 MB")
        input.copyTo(File(partsFolder, "${id}_$index.part"), overwrite = true)
        return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "Packet ${index + 1}/$total received")
    }

    private fun completeUpload(session: IHTTPSession): Response {
        val p = session.parameters
        val id = p["uploadId"]?.firstOrNull() ?: return bad("Missing upload id")
        if (!id.matches(Regex("[a-fA-F0-9-]{36}"))) return bad("Invalid upload id")
        val total = p["total"]?.firstOrNull()?.toIntOrNull() ?: return bad("Invalid packet count")
        if (total !in 1..640) return bad("Invalid packet count")
        val target = safeFile(p["name"]?.firstOrNull() ?: "upload.bin") ?: return bad("Invalid filename")
        val parts = (0 until total).map { File(partsFolder, "${id}_$it.part") }
        if (parts.any { !it.isFile }) return bad("Some packets are missing")
        val size = parts.sumOf { it.length() }
        if (size > maxBytes) return bad("Maximum file size is 5 GB")
        val assembled = File(partsFolder, "${id}.assembling")
        assembled.outputStream().buffered().use { out -> parts.forEach { part -> part.inputStream().buffered().use { it.copyTo(out) } } }
        if (assembled.length() != size) return bad("File assembly failed")
        if (target.exists() && !target.delete()) return bad("Cannot replace existing file")
        if (!assembled.renameTo(target)) { assembled.copyTo(target, overwrite = true); assembled.delete() }
        parts.forEach { it.delete() }
        return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "Joined file: ${target.name} ($size bytes)")
    }

    private fun bad(message: String) = newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, message)
}