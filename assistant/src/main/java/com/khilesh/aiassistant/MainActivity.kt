package com.khilesh.aiassistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.widget.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

class MainActivity : Activity() {
 private val worker=Executors.newSingleThreadExecutor()
 private lateinit var log:TextView
 private lateinit var input:EditText
 private lateinit var gemini:EditText
 private lateinit var eleven:EditText
 private val voiceId="P1bg08DkjqiVEzOn76yG"
 private val prefs by lazy { getSharedPreferences("keys",MODE_PRIVATE) }
 override fun onCreate(savedInstanceState:Bundle?) {
  super.onCreate(savedInstanceState)
  val root=LinearLayout(this).apply { orientation=1; setPadding(18,24,18,16); setBackgroundColor(0xff101827.toInt()) }
  root.addView(TextView(this).apply { text="✦ Khilesh AI"; textSize=28f; setTextColor(-1); gravity=17 })
  gemini=EditText(this).apply { hint="Gemini API Key"; setSingleLine(); setText(prefs.getString("g","")); setTextColor(-1) }; root.addView(gemini)
  eleven=EditText(this).apply { hint="ElevenLabs API Key"; setSingleLine(); inputType=129; setText(prefs.getString("e","")); setTextColor(-1) }; root.addView(eleven)
  root.addView(Button(this).apply { text="Save API Keys"; setOnClickListener { prefs.edit().putString("g",gemini.text.toString()).putString("e",eleven.text.toString()).apply(); add("Keys saved on this device.") } })
  log=TextView(this).apply { text="Namaste! Main Khilesh AI hoon. Hindi mein sawal poochhiye."; setTextColor(-1); textSize=16f }
  val sc=ScrollView(this); sc.addView(log); root.addView(sc,LinearLayout.LayoutParams(-1,0,1f))
  input=EditText(this).apply { hint="Apna sawal likhein"; setTextColor(-1) }; root.addView(input)
  val row=LinearLayout(this)
  row.addView(Button(this).apply { text="🎙 Mic"; setOnClickListener { listen() } },LinearLayout.LayoutParams(0,-2,1f))
  row.addView(Button(this).apply { text="Send"; setOnClickListener { ask(input.text.toString()); input.setText("") } },LinearLayout.LayoutParams(0,-2,1f))
  root.addView(row); setContentView(root)
 }
 private fun add(s:String) { log.append("\n\n"+s) }
 private fun listen() {
  if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO),2); return }
  try { startActivityForResult(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM).putExtra(RecognizerIntent.EXTRA_LANGUAGE,"hi-IN"),3) } catch(_:Exception) { add("Speech recognition unavailable.") }
 }
 override fun onActivityResult(r:Int,c:Int,d:Intent?) { super.onActivityResult(r,c,d); if(r==3 && c==RESULT_OK) { val q=d?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty(); input.setText(q); ask(q) } }
 private fun ask(q:String) {
  if(q.isBlank()) return
  val key=gemini.text.toString().ifBlank { prefs.getString("g","").orEmpty() }
  if(key.isBlank()) { add("Gemini API key daalein aur Save API Keys dabayein."); return }
  add("Aap: "+q+"\nKhilesh AI: ...")
  worker.execute {
   try {
    val u=URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key="+URLEncoder.encode(key,"UTF-8"))
    val c=u.openConnection() as HttpURLConnection; c.requestMethod="POST"; c.doOutput=true; c.setRequestProperty("Content-Type","application/json")
    val req=JSONObject().put("contents",org.json.JSONArray().put(JSONObject().put("parts",org.json.JSONArray().put(JSONObject().put("text","Hindi mein jawab do: "+q)))))
    c.outputStream.use { it.write(req.toString().toByteArray()) }
    val code=c.responseCode; val stream=if(code in 200..299)c.inputStream else c.errorStream
    val raw=stream.bufferedReader().use { it.readText() }; if(code !in 200..299) throw Exception("Gemini HTTP "+code+": "+raw)
    val answer=JSONObject(raw).getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
    runOnUiThread { add(answer) }; tts(answer)
   } catch(ex:Exception) { runOnUiThread { add("Error: "+ex.message) } }
  }
 }
 private fun tts(s:String) {
  val key=eleven.text.toString().ifBlank { prefs.getString("e","").orEmpty() }; if(key.isBlank()) return
  try {
   val c=URL("https://api.elevenlabs.io/v1/text-to-speech/"+voiceId).openConnection() as HttpURLConnection
   c.requestMethod="POST"; c.doOutput=true; c.setRequestProperty("xi-api-key",key); c.setRequestProperty("Content-Type","application/json"); c.setRequestProperty("Accept","audio/mpeg")
   val body=JSONObject().put("text",s).put("model_id","eleven_multilingual_v2")
   c.outputStream.use { it.write(body.toString().toByteArray()) }; if(c.responseCode !in 200..299) throw Exception("ElevenLabs HTTP "+c.responseCode)
   val f=java.io.File(cacheDir,"reply.mp3"); f.writeBytes(c.inputStream.use { it.readBytes() })
   runOnUiThread { val p=android.media.MediaPlayer(); p.setDataSource(f.absolutePath); p.setOnPreparedListener { it.start() }; p.prepareAsync() }
  } catch(ex:Exception) { runOnUiThread { add("Voice error: "+ex.message) } }
 }
}