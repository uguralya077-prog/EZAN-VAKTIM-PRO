package com.ezanvaktim.pro

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.*
import android.location.LocationManager
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.view.Gravity
import android.widget.*
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.net.URL
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.*

private const val PREF = "ezan_settings"
private const val CHANNEL = "prayer_alerts_v1"
private const val REFRESH_ACTION = "com.ezanvaktim.pro.REFRESH"
private val names = listOf("İmsak", "Güneş", "Öğle", "İkindi", "Akşam", "Yatsı")
private val keys = listOf("Fajr", "Sunrise", "Dhuhr", "Asr", "Maghrib", "Isha")

class MainActivity : Activity(), SensorEventListener {
    private lateinit var root: LinearLayout
    private lateinit var heading: TextView
    private lateinit var timesBox: LinearLayout
    private lateinit var compass: TextView
    private val prefs by lazy { getSharedPreferences(PREF, MODE_PRIVATE) }
    private val sensorManager by lazy { getSystemService(SENSOR_SERVICE) as SensorManager }
    private var gravity: FloatArray? = null
    private var magnetic: FloatArray? = null
    private var latitude = 37.5753
    private var longitude = 36.9228
    private var bearing = 0.0
    private var dial: QiblaDial? = null
    private var audioPreview:android.media.MediaPlayer?=null
    private var tilavetPlayer:android.media.MediaPlayer?=null
    private var juzPlaybackGeneration=0
    private fun stopTilavet() {
        juzPlaybackGeneration++
        try { tilavetPlayer?.stop() } catch(_:Exception) { }
        try { tilavetPlayer?.release() } catch(_:Exception) { }
        tilavetPlayer=null
    }
    private fun playQuranVerse(surahNumber:Int,ayahNumber:Int,reciter:String) {
        val chapters=try { loadBundledOrImportedQuran().getJSONArray("surahs") }
            catch(e:Exception) { Toast.makeText(this,"Kur'an ayetleri açılamadı",Toast.LENGTH_LONG).show(); return }
        if(surahNumber !in 1..114) return
        val count=chapters.getJSONObject(surahNumber-1).getJSONArray("verses").length()
        if(ayahNumber !in 1..count) return
        var global=ayahNumber
        for(i in 0 until surahNumber-1) global+=chapters.getJSONObject(i).getJSONArray("verses").length()
        stopReading()
        stopTilavet()
        val generation=juzPlaybackGeneration
        val safeReciter=reciter.replace(Regex("[^a-zA-Z0-9._-]"),"")
        val cacheDir=java.io.File(filesDir,"quran_verse_audio").apply { mkdirs() }
        val cached=java.io.File(cacheDir,"${safeReciter}_${global}.mp3")
        fun playFile(file:java.io.File) {
            if(generation!=juzPlaybackGeneration) return
            val player=android.media.MediaPlayer()
            tilavetPlayer=player
            try {
                player.setAudioAttributes(android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build())
                player.setDataSource(file.absolutePath)
                player.setOnPreparedListener { if(tilavetPlayer===it) it.start() }
                player.setOnCompletionListener { if(tilavetPlayer===it) stopTilavet() }
                player.setOnErrorListener { _,_,_ ->
                    Toast.makeText(this,"Hafız kaydı açılamadı",Toast.LENGTH_LONG).show()
                    stopTilavet()
                    true
                }
                player.prepareAsync()
            } catch(e:Exception) {
                stopTilavet()
                Toast.makeText(this,"Ayet sesi açılamadı",Toast.LENGTH_LONG).show()
            }
        }
        if(cached.isFile && cached.length()>1024L) { playFile(cached); return }
        Toast.makeText(this,"Hafız kaydı indiriliyor; ilk dinlemede internet gerekir",Toast.LENGTH_SHORT).show()
        thread {
            val partial=java.io.File(cacheDir,"${safeReciter}_${global}.part")
            try {
                val url=java.net.URL("https://cdn.islamic.network/quran/audio/128/$safeReciter/$global.mp3")
                val connection=url.openConnection().apply {
                    connectTimeout=12000
                    readTimeout=20000
                }
                var bytes=0L
                connection.getInputStream().use { input ->
                    partial.outputStream().use { output ->
                        val buffer=ByteArray(16384)
                        while(true) {
                            val n=input.read(buffer)
                            if(n<0) break
                            bytes+=n
                            if(bytes>12L*1024L*1024L) error("Ses kaydı çok büyük")
                            output.write(buffer,0,n)
                        }
                    }
                }
                if(bytes<1024L) error("Geçerli ses kaydı bulunamadı")
                if(!partial.renameTo(cached)) error("Ses kaydı kaydedilemedi")
                runOnUiThread { if(generation==juzPlaybackGeneration) playFile(cached) }
            } catch(e:Exception) {
                partial.delete()
                runOnUiThread {
                    if(generation==juzPlaybackGeneration)
                        Toast.makeText(this,"Hafız kaydı indirilemedi. İnternet bağlantısını kontrol edin.",Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    private fun playJuz(juzIndex:Int,reciter:String) {
        if(juzIndex !in 0..29) return
        stopReading()
        stopTilavet()
        val generation=juzPlaybackGeneration
        val start=juzStarts[juzIndex]
        val end=if(juzIndex==29) 115 to 1 else juzStarts[juzIndex+1]
        val chapters=try { loadBundledOrImportedQuran().getJSONArray("surahs") }
            catch(e:Exception) {
                Toast.makeText(this,"Cüz ayetleri okunamadı",Toast.LENGTH_LONG).show()
                return
            }
        val verseNumbers=mutableListOf<Int>()
        var global=0
        for(surah in 1..114) {
            val count=chapters.getJSONObject(surah-1).getJSONArray("verses").length()
            for(ayah in 1..count) {
                global++
                if((surah>start.first || surah==start.first && ayah>=start.second) &&
                    (surah<end.first || surah==end.first && ayah<end.second)) verseNumbers.add(global)
            }
        }
        if(verseNumbers.isEmpty()) {
            Toast.makeText(this,"Bu cüz için ayet bulunamadı",Toast.LENGTH_LONG).show()
            return
        }
        var position=0
        fun next() {
            if(generation!=juzPlaybackGeneration) return
            if(position>=verseNumbers.size) {
                Toast.makeText(this,"Cüz tamamlandı",Toast.LENGTH_LONG).show()
                stopTilavet()
                return
            }
            val number=verseNumbers[position++]
            val url="https://cdn.islamic.network/quran/audio/128/$reciter/$number.mp3"
            val player=android.media.MediaPlayer()
            try {
                tilavetPlayer?.release()
                tilavetPlayer=player
                player.setAudioAttributes(android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build())
                player.setDataSource(url)
                player.setOnPreparedListener { if(generation==juzPlaybackGeneration) it.start() }
                player.setOnCompletionListener { if(generation==juzPlaybackGeneration) next() }
                player.setOnErrorListener { _,what,extra ->
                    if(generation==juzPlaybackGeneration) {
                        Toast.makeText(this,"Cüz ses bağlantısı hatası ($what/$extra)",Toast.LENGTH_LONG).show()
                        stopTilavet()
                    }
                    true
                }
                player.prepareAsync()
            } catch(e:Exception) {
                Toast.makeText(this,"Cüz okunamadı: ${e.message}",Toast.LENGTH_LONG).show()
                stopTilavet()
            }
        }
        Toast.makeText(this,"${juzIndex+1}. cüz ayet ayet yükleniyor",Toast.LENGTH_SHORT).show()
        next()
    }
    private fun offlineSurahFile(number:Int,reciter:String):java.io.File {
        val safe=reciter.replace(Regex("[^a-zA-Z0-9.]"),"_")
        return java.io.File(java.io.File(filesDir,"quran_audio/$safe"),"${number.toString().padStart(3,'0')}.mp3")
    }
    private fun downloadAllSurahs() {
        Toast.makeText(this,"114 sure indirmesi basladi. Uygulamayi acik tutun.",Toast.LENGTH_LONG).show()
        thread {
            var done=0
            var failed=0
            for(number in 1..114) {
                val target=offlineSurahFile(number,"ar.alafasy")
                if(target.length()>1024L) { done++; continue }
                val temp=java.io.File(target.absolutePath+".part")
                try {
                    target.parentFile?.mkdirs()
                    val conn=URL("https://cdn.islamic.network/quran/audio-surah/128/ar.alafasy/$number.mp3").openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout=15000
                    conn.readTimeout=45000
                    try {
                        if(conn.responseCode !in 200..299) error("HTTP error")
                        var total=0L
                        conn.inputStream.use { input ->
                            temp.outputStream().use { output ->
                                val buf=ByteArray(16384)
                                while(true) {
                                    val n=input.read(buf)
                                    if(n<0) break
                                    total+=n
                                    if(total>150L*1024*1024) error("Too large")
                                    output.write(buf,0,n)
                                }
                            }
                        }
                        if(total<1024) error("Empty")
                        val metadata=android.media.MediaMetadataRetriever()
                        try {
                            metadata.setDataSource(temp.absolutePath)
                            if(metadata.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION).isNullOrBlank()) error("Invalid audio")
                        } finally { metadata.release() }
                        if(!temp.renameTo(target)) error("Save failed")
                        done++
                    } finally { conn.disconnect() }
                } catch(_:Exception) { failed++; temp.delete() }
                if(number%10==0 || number==114) {
                    val progress=number
                    val ready=done
                    val errors=failed
                    runOnUiThread { Toast.makeText(this,"Sureler: $progress/114; hazir $ready, hata $errors",Toast.LENGTH_SHORT).show() }
                }
            }
            val ready=done
            val errors=failed
            runOnUiThread { android.app.AlertDialog.Builder(this)
                .setTitle("Cevrim disi sureler")
                .setMessage("$ready sure kaydedildi. $errors hata. Eksikler icin yeniden deneyin.")
                .setPositiveButton("Tamam",null).show() }
        }
    }
    private fun downloadSurah(number:Int,reciter:String) {
        val target=offlineSurahFile(number,reciter)
        if(target.length()>1024L) {
            Toast.makeText(this,"Bu sûre zaten indirilmiş",Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this,"Sûre indiriliyor…",Toast.LENGTH_SHORT).show()
        thread {
            val temp=java.io.File(target.absolutePath+".part")
            try {
                target.parentFile?.mkdirs()
                val url="https://cdn.islamic.network/quran/audio-surah/128/$reciter/$number.mp3"
                val connection=URL(url).openConnection() as java.net.HttpURLConnection
                connection.connectTimeout=15000
                connection.readTimeout=30000
                try {
                    if(connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")
                    var bytes=0L
                    connection.inputStream.use { input ->
                        temp.outputStream().use { output ->
                            val buffer=ByteArray(16384)
                            while(true) {
                                val count=input.read(buffer)
                                if(count<0) break
                                bytes+=count
                                if(bytes>150L*1024*1024) error("Dosya boyutu sınırı aşıldı")
                                output.write(buffer,0,count)
                            }
                        }
                    }
                    if(bytes<1024) error("Boş ses kaydı")
                    val metadata=android.media.MediaMetadataRetriever()
                    try {
                        metadata.setDataSource(temp.absolutePath)
                        if(metadata.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION).isNullOrBlank())
                            error("Geçersiz ses kaydı")
                    } finally { metadata.release() }
                    if(!temp.renameTo(target)) error("Kayıt tamamlanamadı")
                    runOnUiThread { Toast.makeText(this,"Sûre indirildi; artık internetsiz dinlenebilir",Toast.LENGTH_LONG).show() }
                } finally { connection.disconnect() }
            } catch(e:Exception) {
                temp.delete()
                runOnUiThread { Toast.makeText(this,"İndirme başarısız: ${e.message}",Toast.LENGTH_LONG).show() }
            }
        }
    }
    private fun playTilavet(surahNumber:Int, reciter:String="ar.alafasy") {
        stopReading()
        stopTilavet()
        if(surahNumber !in 1..114) return
        val local=offlineSurahFile(surahNumber,reciter)
        val primary=if(local.length()>1024L) local.absolutePath else "https://cdn.islamic.network/quran/audio-surah/128/$reciter/$surahNumber.mp3"
        // Alternate whole-surah recording for Al-Afasy if the primary CDN fails.
        val fallback=if(reciter=="ar.alafasy")
            "https://server8.mp3quran.net/afs/${surahNumber.toString().padStart(3,'0')}.mp3"
            else null
        fun start(url:String, allowFallback:Boolean) {
            stopTilavet()
            val player=android.media.MediaPlayer()
            tilavetPlayer=player
            try {
                player.setAudioAttributes(android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build())
                player.setDataSource(url)
                player.setOnPreparedListener { if(tilavetPlayer===it) it.start() }
                player.setOnErrorListener { mp,what,extra ->
                    if(tilavetPlayer===mp) {
                        stopTilavet()
                        if(allowFallback && fallback!=null) {
                            Toast.makeText(this,"İlk ses kaynağı açılamadı; alternatif deneniyor",Toast.LENGTH_SHORT).show()
                            start(fallback,false)
                        } else {
                            Toast.makeText(this,"Tilavet oynatılamadı ($what/$extra). İnternet bağlantısı ve ses seviyesini kontrol edin.",Toast.LENGTH_LONG).show()
                        }
                    }
                    true
                }
                player.setOnCompletionListener { if(tilavetPlayer===it) stopTilavet() }
                player.prepareAsync()
            } catch(e:Exception) {
                stopTilavet()
                if(allowFallback && fallback!=null) start(fallback,false)
                else Toast.makeText(this,"Ses bağlantısı hatası: ${e.javaClass.simpleName}",Toast.LENGTH_LONG).show()
            }
        }
        Toast.makeText(this,"Hafız kaydı yükleniyor…",Toast.LENGTH_SHORT).show()
        start(primary,true)
    }
    private var speech:android.speech.tts.TextToSpeech?=null
    private var speechReady=false
    private fun readAloud(value:String,arabic:Boolean=true) {
        if(!speechReady) {
            Toast.makeText(this,"Sesli okuma motoru hazırlanıyor veya cihazda uygun ses yok",Toast.LENGTH_LONG).show()
            return
        }
        val engine=speech ?: return
        val language=if(arabic) Locale("ar") else Locale("tr","TR")
        val availability=engine.setLanguage(language)
        if(availability<0) {
            Toast.makeText(this,"Bu dilin ses paketi yüklü değil. Telefonun metin okuma ayarlarından yükleyin.",Toast.LENGTH_LONG).show()
            return
        }
        engine.setSpeechRate(if(arabic) 0.78f else 0.9f)
        val result=engine.speak(value,android.speech.tts.TextToSpeech.QUEUE_FLUSH,null,"ezan_reader")
        if(result==android.speech.tts.TextToSpeech.ERROR)
            Toast.makeText(this,"Ses motoru başlatılamadı. Telefonun metin okuma ayarlarını kontrol edin.",Toast.LENGTH_LONG).show()
    }
    private fun stopReading() { speech?.stop() }

    private var locationLabel = "Kahramanmaraş (örnek konum)"
    private val gold = Color.rgb(215,181,109)
    private val white = Color.rgb(241,244,239)
    private val green = Color.rgb(13,39,30)
    private val pickFull = 101
    private val pickShort = 102
    private val pickQuran = 103
    private var section = "Vakitler"
    private var zikrCount = 0
    private val timerHandler=Handler(Looper.getMainLooper())
    private var timerLabel:TextView?=null
    private var timerTarget:LocalDateTime?=null
    private var timerPrayer=""
    private var requestGeneration=0
    private val timerTick=object:Runnable {
        override fun run() {
            val target=timerTarget ?: return
            if(section!="Vakitler") return
            if(!target.isAfter(LocalDateTime.now())) { timerHandler.removeCallbacks(this); loadTimes(); return }
            val sec=java.time.Duration.between(LocalDateTime.now(),target).seconds.coerceAtLeast(0)
            timerLabel?.text=String.format(Locale.getDefault(),"Sıradaki: %s   %02d:%02d:%02d",timerPrayer,sec/3600,(sec%3600)/60,sec%60)
            timerHandler.postDelayed(this,1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        migratePrayerCalculationCache(prefs)
        latitude = java.lang.Double.longBitsToDouble(prefs.getLong("lat", java.lang.Double.doubleToRawLongBits(latitude)))
        longitude = java.lang.Double.longBitsToDouble(prefs.getLong("lon", java.lang.Double.doubleToRawLongBits(longitude)))
        locationLabel = prefs.getString("place", locationLabel) ?: locationLabel
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(NotificationChannel(CHANNEL,"Namaz bildirimleri",NotificationManager.IMPORTANCE_HIGH))
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 20)
        if (Build.VERSION.SDK_INT >= 31) {
            val alarmManager=getSystemService(ALARM_SERVICE) as AlarmManager
            if(!alarmManager.canScheduleExactAlarms() && !prefs.getBoolean("exact_alarm_explained_v1",false)) {
                prefs.edit().putBoolean("exact_alarm_explained_v1",true).apply()
                android.app.AlertDialog.Builder(this)
                    .setTitle("Kilit ekranında zamanında ezan")
                    .setMessage("Ezanın vaktinde başlaması için Alarmlar ve hatırlatıcılar iznini açın. Ayrıca telefonun pil ayarlarında bu uygulamayı kısıtlamayın.")
                    .setPositiveButton("İzin ayarını aç") { _,_ ->
                        try { startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,Uri.parse("package:$packageName"))) }
                        catch(_:Exception) { }
                    }
                    .setNegativeButton("Sonra",null).show()
            }
        }
        speech=android.speech.tts.TextToSpeech(this) { status ->
            speechReady=status==android.speech.tts.TextToSpeech.SUCCESS
            if(speechReady) {
                speech?.setOnUtteranceProgressListener(object:android.speech.tts.UtteranceProgressListener() {
                    override fun onStart(utteranceId:String?) {}
                    override fun onDone(utteranceId:String?) {}
                    override fun onError(utteranceId:String?) {
                        runOnUiThread {
                            Toast.makeText(this@MainActivity,"Ses motoru okuyamadı. Android metin okuma ayarlarından Türkçe/Arapça ses paketini kontrol edin.",Toast.LENGTH_LONG).show()
                        }
                    }
                })
            }
        }
        render()
    }

    private val deep=Color.rgb(7,20,18)
    private val surface=Color.rgb(16,43,37)
    private val accent=Color.rgb(228,194,125)
    private fun rounded(fill:Int,stroke:Int=Color.rgb(75,100,80),radius:Float=30f):GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius=radius*resources.displayMetrics.density
            setStroke((1*resources.displayMetrics.density).toInt().coerceAtLeast(1),stroke)
        }
    private fun text(s:String,size:Float=18f,color:Int=white):TextView=TextView(this).apply {
        text=s
        textSize=size*prefs.getFloat("font_scale",1f)
        setTextColor(color)
        setPadding(18,15,18,15)
        includeFontPadding=true
    }
    private fun panel():LinearLayout=LinearLayout(this).apply {
        orientation=LinearLayout.VERTICAL
        setPadding(18,16,18,16)
        background=android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR,intArrayOf(Color.rgb(19,56,49),Color.rgb(10,36,38))).apply { cornerRadius=22f*resources.displayMetrics.density; setStroke((1*resources.displayMetrics.density).toInt(),Color.rgb(78,113,93)) }
        elevation=4f*resources.displayMetrics.density
        layoutParams=LinearLayout.LayoutParams(-1,-2).apply {
            val d=resources.displayMetrics.density
            setMargins(0,(7*d).toInt(),0,(7*d).toInt())
        }
    }
    private fun button(label:String,click:()->Unit):Button=Button(this).apply {
        text=label
        textSize=15f*prefs.getFloat("font_scale",1f)
        isAllCaps=false
        setTextColor(accent)
        background=android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR,intArrayOf(Color.rgb(26,83,65),Color.rgb(12,48,43))).apply { cornerRadius=18f*resources.displayMetrics.density; setStroke((1*resources.displayMetrics.density).toInt(),Color.rgb(116,136,94)) }
        setPadding(14,12,14,12)
        minHeight=(48*resources.displayMetrics.density).toInt()
        setOnClickListener { click() }
    }
    private fun render() {
        requestGeneration++
        stopReading()
        timerHandler.removeCallbacks(timerTick)
        timerTarget=null
        timerLabel=null
        dial=null
        val outer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(deep) }
        val header = text("☪  EZAN VAKTİM PRO", 25f, gold).apply {
            gravity = Gravity.CENTER; setTypeface(null, Typeface.BOLD)
            setPadding(12,24,12,22)
            background=android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR,intArrayOf(Color.rgb(11,34,28),Color.rgb(23,69,59))).apply { cornerRadius=0f }
        }
        outer.addView(header)
        val scroll = ScrollView(this)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20,18,20,28) }
        scroll.addView(root)
        outer.addView(scroll, LinearLayout.LayoutParams(-1,0,1f))
        val tabs=LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL
            setBackgroundColor(Color.rgb(11,34,28))
        }
        listOf("Vakitler" to "Ana Sayfa","Kıble" to "Kıble","Zikir" to "Zikir","Menüler" to "Menüler").forEach { (tab,label) ->
            tabs.addView(button(label) { section=tab; render() }.apply {
                textSize=12f
                setTextColor(if(section==tab) accent else Color.rgb(192,207,194))
                background=rounded(if(section==tab) Color.rgb(32,73,56) else Color.rgb(11,34,28),if(section==tab) accent else Color.rgb(11,34,28),12f)
                setPadding(2,12,2,12)
            },LinearLayout.LayoutParams(0,-2,1f))
        }
        outer.addView(tabs)
        // Android 15+ enforces edge-to-edge for targetSdk 35.
        // Reserve the actual status/navigation bar insets so no tab sits under system buttons.
        outer.setOnApplyWindowInsetsListener { view, insets ->
            val bars=if(Build.VERSION.SDK_INT>=30) {
                insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
            } else {
                @Suppress("DEPRECATION")
                val bottom=insets.systemWindowInsetBottom
                @Suppress("DEPRECATION")
                val top=insets.systemWindowInsetTop
                android.graphics.Insets.of(0,top,0,bottom)
            }
            view.setPadding(bars.left,bars.top,bars.right,bars.bottom)
            insets
        }
        setContentView(outer)
        outer.requestApplyInsets()
        when(section) {
            "Menüler" -> {
                root.addView(text("KEŞFET • İBADET VE YAŞAM",24f,gold))
                root.addView(text("İbadet ve günlük yaşam için bütün bölümler",15f))
                val items=listOf(
                    "🕌 Namaz Vakitleri" to "Vakitler",
                    "🧭 Kıble Pusulası" to "Kıble",
                    "🔔 Ezan ve Alarmlar" to "Ezan",
                    "📖 Kur'an-ı Kerim" to "Kur'an",
                    "🎧 30 Cüz • Hafız Tilaveti" to "Cüzler",
                    "🤲 Günlük Dualar" to "Dualar",
                    "📿 Dijital Tesbih" to "Zikir",
                    "✨ Allah'ın 99 İsmi" to "99 İsim",
                    "📱 Ana Ekran Widget'ı" to "Widget",
                    "🗓 Dini Günler ve Bayramlar" to "Hicri Takvim",
                    "🧎 Namaz ve Kaza Takibi" to "Namaz Takibi",
                    "📚 Hatim Takibi" to "Hatim Takibi",
                    "💰 Zekât Hesaplayıcı" to "Zekât",
                    "⚙ Görünüm ve Ayarlar" to "Ayarlar"
                )
                items.forEach { (title,target) ->
                    root.addView(button("❯  $title") {
                        section=target
                        render()
                    }.apply {
                        textSize=18f
                        gravity=Gravity.CENTER_VERTICAL
                        setPadding(24,18,18,18)
                        minHeight=(58*resources.displayMetrics.density).toInt()
                    })
                }
            }
            "Hicri Takvim" -> {
                root.addView(text("HİCRİ TAKVİM",23f,gold))
                val today=LocalDate.now()
                val hijri=java.time.chrono.HijrahDate.from(today)
                val hday=hijri.get(java.time.temporal.ChronoField.DAY_OF_MONTH)
                val hmonth=hijri.get(java.time.temporal.ChronoField.MONTH_OF_YEAR)
                val hyear=hijri.get(java.time.temporal.ChronoField.YEAR)
                val months=listOf("Muharrem","Safer","Rebiülevvel","Rebiülahir","Cemaziyelevvel","Cemaziyelahir","Recep","Şaban","Ramazan","Şevval","Zilkade","Zilhicce")
                root.addView(panel().apply {
                    addView(text("$hday ${months[hmonth-1]} $hyear",24f,gold))
                    addView(text(today.format(DateTimeFormatter.ofPattern("d MMMM yyyy",Locale("tr","TR"))),17f))
                    addView(text("Hicri tarih hesaplamadır; resmî takvimle gözlem ve yöntem farkı nedeniyle bir gün değişebilir.",13f))
                })
                root.addView(text("DİNİ GÜNLER VE BAYRAMLAR",20f,gold))
                val events=listOf(
                    Triple(1,1,"Hicri yılbaşı"),
                    Triple(1,10,"Aşure günü"),
                    Triple(3,12,"Mevlid Kandili"),
                    Triple(7,1,"Üç ayların başlangıcı"),
                    Triple(7,27,"Miraç Kandili"),
                    Triple(8,15,"Berat Kandili"),
                    Triple(9,1,"Ramazan başlangıcı"),
                    Triple(9,27,"Kadir Gecesi (yaygın kabul)"),
                    Triple(10,1,"Ramazan Bayramı • 1. gün"),
                    Triple(10,2,"Ramazan Bayramı • 2. gün"),
                    Triple(10,3,"Ramazan Bayramı • 3. gün"),
                    Triple(12,9,"Arefe"),
                    Triple(12,10,"Kurban Bayramı • 1. gün"),
                    Triple(12,11,"Kurban Bayramı • 2. gün"),
                    Triple(12,12,"Kurban Bayramı • 3. gün"),
                    Triple(12,13,"Kurban Bayramı • 4. gün")
                )
                val upcoming=mutableListOf<Pair<LocalDate,String>>()
                for(year in hyear..hyear+1) {
                    for((month,day,name) in events) {
                        try {
                            val date=LocalDate.from(java.time.chrono.HijrahDate.of(year,month,day))
                            if(!date.isBefore(today)) upcoming.add(date to name)
                        } catch(_:java.time.DateTimeException) { }
                    }
                }
                upcoming.sortedBy { it.first }.take(18).forEach { (date,name) ->
                    root.addView(panel().apply {
                        addView(text(name,17f,gold))
                        addView(text(date.format(DateTimeFormatter.ofPattern("d MMMM yyyy, EEEE",Locale("tr","TR"))),15f))
                    })
                }
                root.addView(text("Tarihler hesaplanan hicri takvime göredir. Kandil ve bayramların Türkiye'deki resmî günleri için Diyanet takvimini esas alın.",13f))
            }
            "Namaz Takibi" -> {
                root.addView(text("NAMAZ VE KAZA TAKİBİ",23f,gold))
                val today=LocalDate.now().toString()
                val names=listOf("İmsak/Sabah","Öğle","İkindi","Akşam","Yatsı")
                root.addView(text("Bugün • $today",16f,gold))
                names.forEachIndexed { i,name ->
                    val key="prayer_done_${today}_$i"
                    root.addView(CheckBox(this).apply {
                        text="$name namazını kıldım"
                        setTextColor(white)
                        isChecked=prefs.getBoolean(key,false)
                        setOnCheckedChangeListener { _,checked -> prefs.edit().putBoolean(key,checked).apply() }
                    })
                }
                root.addView(text("Kaza namazı sayacı (kişisel kayıt)",19f,gold))
                names.forEachIndexed { i,name ->
                    val key="qaza_count_$i"
                    val count=prefs.getInt(key,0)
                    root.addView(panel().apply {
                        val display=text("$name: $count",17f)
                        addView(display)
                        addView(button("+1 kaza ekle") {
                            val value=prefs.getInt(key,0)+1
                            prefs.edit().putInt(key,value).apply()
                            display.text="$name: $value"
                        })
                        addView(button("−1 kaza düş") {
                            val value=(prefs.getInt(key,0)-1).coerceAtLeast(0)
                            prefs.edit().putInt(key,value).apply()
                            display.text="$name: $value"
                        })
                    })
                }
            }
            "Hatim Takibi" -> {
                root.addView(text("HATİM TAKİBİ",23f,gold))
                root.addView(text("30 cüzü okudukça işaretleyin. Kayıt cihazda saklanır.",15f))
                val completed=(1..30).count { prefs.getBoolean("hatim_juz_$it",false) }
                root.addView(text("$completed / 30 cüz tamamlandı",21f,gold))
                for(i in 1..30) {
                    root.addView(CheckBox(this).apply {
                        text="$i. cüz tamamlandı"
                        setTextColor(white)
                        isChecked=prefs.getBoolean("hatim_juz_$i",false)
                        setOnCheckedChangeListener { _,checked -> prefs.edit().putBoolean("hatim_juz_$i",checked).apply() }
                    })
                }
                root.addView(button("Yeni hatme başla • işaretleri sıfırla") {
                    android.app.AlertDialog.Builder(this).setTitle("Hatim sıfırlansın mı?")
                        .setNegativeButton("Vazgeç",null)
                        .setPositiveButton("Sıfırla") { _,_ ->
                            val edit=prefs.edit()
                            for(i in 1..30) edit.remove("hatim_juz_$i")
                            edit.apply()
                            render()
                        }.show()
                })
            }
            "Zekât" -> {
                root.addView(text("ZEKÂT HESAPLAYICI",23f,gold))
                root.addView(text("Nakit, altın ve diğer zekâta tabi varlıkların TL karşılığını girin. Borçları düşüp yaklaşık %2,5 hesaplayın.",15f))
                val fields=listOf("Nakit ve banka (TL)","Altın ve döviz toplam değeri (TL)","Ticari mallar ve alacaklar (TL)","Düşülebilir kısa vadeli borçlar (TL)")
                val inputs=fields.map { title ->
                    val input=EditText(this).apply {
                        hint=title
                        inputType=android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                        setSingleLine(true)
                        setTextColor(white)
                        setHintTextColor(Color.LTGRAY)
                    }
                    root.addView(input)
                    input
                }
                root.addView(button("Zekât tutarını hesapla") {
                    try {
                        val values=inputs.map { it.text.toString().replace(',','.').toDoubleOrNull() ?: 0.0 }
                        if(values.any { it<0 || !it.isFinite() }) throw IllegalArgumentException("Negatif veya geçersiz tutar")
                        val net=(values[0]+values[1]+values[2]-values[3]).coerceAtLeast(0.0)
                        val amount=net*0.025
                        val format=java.text.NumberFormat.getCurrencyInstance(Locale("tr","TR"))
                        android.app.AlertDialog.Builder(this).setTitle("Tahmini zekât")
                            .setMessage("Net varlık: ${format.format(net)}\n%2,5: ${format.format(amount)}\n\nNisap, bir kameri yıl şartı ve varlık türüne ilişkin hükümler bu basit hesapta otomatik doğrulanmaz.").setPositiveButton("Tamam",null).show()
                    } catch(e:Exception) {
                        Toast.makeText(this,"Geçerli tutarlar girin",Toast.LENGTH_LONG).show()
                    }
                })
            }
            "Vakitler" -> {
                val quick=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
                listOf("📖 Kur'an" to "Kur'an","🤲 Dualar" to "Dualar","☪ 99 İsim" to "99 İsim").forEach { (label,target) ->
                    quick.addView(button(label) { section=target; render() }.apply {
                        textSize=12f
                        setPadding(2,8,2,8)
                    },LinearLayout.LayoutParams(0,-2,1f))
                }
                root.addView(quick)
                heading=text(locationLabel,17f,gold).apply { gravity=Gravity.CENTER }
                root.addView(heading)
                root.addView(text(LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy",Locale("tr","TR"))),17f,white).apply { gravity=Gravity.CENTER })
                val locationActions=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
                locationActions.addView(button("📍 Konum") { locate() },LinearLayout.LayoutParams(0,-2,1f))
                locationActions.addView(button("↻ Yenile") {
                    val key="times_${LocalDate.now()}_${latitude}_${longitude}"
                    prefs.edit().remove(key).apply()
                    loadTimes()
                },LinearLayout.LayoutParams(0,-2,1f))
                root.addView(locationActions)
                root.addView(button("🏙 Şehir adıyla ara") { findCity() })
                root.addView(button("Şehir / koordinat seç") {
                    val latInput=EditText(this).apply { hint="Enlem (ör. 37.5753)"; setText(latitude.toString()); inputType=android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED }
                    val lonInput=EditText(this).apply { hint="Boylam (ör. 36.9228)"; setText(longitude.toString()); inputType=android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED }
                    val labelInput=EditText(this).apply { hint="Şehir adı"; setText(locationLabel) }
                    val form=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(28,12,28,0); addView(labelInput); addView(latInput); addView(lonInput) }
                    AlertDialog.Builder(this).setTitle("Konumu elle ayarla").setView(form)
                        .setNegativeButton("Vazgeç",null)
                        .setPositiveButton("Kaydet") { _,_ ->
                            val lat=latInput.text.toString().toDoubleOrNull()
                            val lon=lonInput.text.toString().toDoubleOrNull()
                            if(lat==null || lon==null || lat !in -90.0..90.0 || lon !in -180.0..180.0) {
                                Toast.makeText(this,"Geçerli enlem ve boylam girin",Toast.LENGTH_LONG).show()
                            } else {
                                latitude=lat; longitude=lon
                                locationLabel=labelInput.text.toString().ifBlank { "Seçilen konum" }
                                prefs.edit().putLong("lat",java.lang.Double.doubleToRawLongBits(lat))
                                    .putLong("lon",java.lang.Double.doubleToRawLongBits(lon))
                                    .putString("place",locationLabel).apply()
                                render()
                            }
                        }.show()
                })

                timerLabel=text("Sonraki vakit hesaplanıyor…",20f,gold).apply { gravity=Gravity.CENTER; setBackgroundColor(green) }
                root.addView(panel().apply { addView(timerLabel) })
                root.addView(text("GÜNLÜK NAMAZ VAKİTLERİ",21f,gold))
                timesBox=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
                root.addView(timesBox)
                root.addView(button("Vakitleri yenile") { loadTimes() })
                loadTimes()
            }
            "Kıble" -> {
                root.addView(text("🕋  KIBLE",25f,gold).apply { gravity=Gravity.CENTER })
                root.addView(panel().apply {
                    dial=QiblaDial(this@MainActivity)
                    addView(dial,LinearLayout.LayoutParams(-1,(resources.displayMetrics.density*320).toInt()))
                    compass=text("🧭",18f,gold).apply {
                        gravity=Gravity.CENTER
                        minHeight=(48*resources.displayMetrics.density).toInt()
                    }
                    addView(compass)
                })
                root.addView(button("📍 Konumumu güncelle") { locate() })
                root.addView(button("🏙 Şehir seç") { findCity() })
            }
            "Ezan" -> {
                root.addView(text("EZAN VE BİLDİRİMLER",22f,gold))
                root.addView(panel().apply {
                    addView(text("EZAN SESİ KURULUMU",20f,gold))
                    addView(text("Telefonunuzdaki bir ezan MP3 kaydını seçin veya aşağıdaki bağlantı alanından indirin. Seçtiğiniz ses telefona kaydedilir ve vakitlerde çalınır.",15f))
                    val configured=prefs.getString("full",null)
                    addView(text(if(configured.isNullOrBlank()) "⚠ Ezan kaydı henüz ayarlanmadı" else "✓ Ezan kaydı ayarlı",17f,if(configured.isNullOrBlank()) Color.rgb(255,185,125) else gold))
                })
                root.addView(button("▶ Gerçek ezan sesini şimdi test et") {
                    val mode=prefs.getInt("mode",0)
                    val audio=prefs.getString(if(mode==1) "short" else "full",null)
                    if(audio.isNullOrBlank()) {
                        Toast.makeText(this,"Önce tam veya kısa ezan kaydı seçin ya da indirin",Toast.LENGTH_LONG).show()
                    } else try {
                        startForegroundService(Intent(this,AdhanService::class.java)
                            .putExtra("name","Deneme").putExtra("audio",audio))
                        Toast.makeText(this,"Ezan ses testi başlatıldı",Toast.LENGTH_SHORT).show()
                    } catch(e:Exception) {
                        Toast.makeText(this,"Ezan testi başlatılamadı: ${e.message}",Toast.LENGTH_LONG).show()
                    }
                })
                root.addView(text("Ses seçildiğinde veya indirildiğinde ezan modu otomatik etkinleşir. Dakik alarm izni gereklidir.",13f))
                root.addView(panel().apply {
                    addView(text("Kilit ekranında ezan ve bildirim",19f,gold))
                    addView(text("Telefon ayarlarında bildirimlere izin verin ve uygulamanın pil kullanımını kısıtlamayın. Ezan için ayrıca tam/kısa ses kaydı seçilmelidir.",14f))
                    addView(button("🔔 Bildirim ayarlarını aç") {
                        try {
                            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE,packageName))
                        } catch(_:Exception) {
                            Toast.makeText(this@MainActivity,"Ayarlar > Uygulamalar > Ezan Vaktim Pro > Bildirimler",Toast.LENGTH_LONG).show()
                        }
                    })
                    addView(button("🔋 Pil ve arka plan ayarlarını aç") {
                        try {
                            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:$packageName")))
                        } catch(_:Exception) {
                            Toast.makeText(this@MainActivity,"Ayarlar > Uygulamalar > Ezan Vaktim Pro > Pil",Toast.LENGTH_LONG).show()
                        }
                    })
                })
                root.addView(button("Uygulama aktif / kapalı ayarına git") { section="Ayarlar"; render() })
                val modes=arrayOf("Tam ezan","Kısa ezan","Sessiz bildirim")
                val spinner=Spinner(this).apply {
                    adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,modes)
                    setSelection(prefs.getInt("mode",0))
                }
                root.addView(spinner)
                root.addView(button("Seçimi kaydet") {
                    prefs.edit().putInt("mode",spinner.selectedItemPosition).apply()
                    Toast.makeText(this,"Ezan modu kaydedildi",Toast.LENGTH_SHORT).show()
                })
                if(Build.VERSION.SDK_INT>=31) {
                    val alarms=getSystemService(ALARM_SERVICE) as AlarmManager
                    val allowed=alarms.canScheduleExactAlarms()
                    root.addView(text(if(allowed) "✓ Dakik alarm izni açık" else "⚠ Dakik alarm izni kapalı. Ezan bildirimi gecikebilir.",16f,if(allowed) gold else Color.rgb(255,185,125)))
                    if(!allowed) root.addView(button("Dakik alarm iznini aç") {
                        try { startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,Uri.parse("package:$packageName"))) }
                        catch(_:Exception) { Toast.makeText(this,"Ayarlar > Uygulamalar > Özel erişim > Alarmlar ve hatırlatıcılar",Toast.LENGTH_LONG).show() }
                    })
                }
                if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) {
                    root.addView(button("Bildirim iznini aç") { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),20) })
                }
                root.addView(panel().apply {
                    addView(text("Bildirim kontrolü",19f,gold))
                    val notificationsAllowed=Build.VERSION.SDK_INT<33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED
                    addView(text(if(notificationsAllowed) "✓ Bildirim izni hazır" else "⚠ Bildirim izni gerekli",15f,if(notificationsAllowed) accent else Color.rgb(255,185,125)))
                    addView(text("Ezan uyarılarının gecikmemesi için telefonun pil optimizasyonu ayarlarını kontrol edin.",14f))
                    addView(button("Uygulama bildirim ayarlarını aç") {
                        try {
                            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,packageName))
                        } catch(_:Exception) { Toast.makeText(this@MainActivity,"Bildirim ayarları açılamadı",Toast.LENGTH_SHORT).show() }
                    })
                    addView(button("Pil optimizasyonu ayarlarını aç") {
                        try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                        catch(_:Exception) { Toast.makeText(this@MainActivity,"Pil ayarları açılamadı",Toast.LENGTH_SHORT).show() }
                    })
                })
                root.addView(button("Bildirim testi gönder (sessiz)") {
                    val allowed=Build.VERSION.SDK_INT<33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED
                    val manager=getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                    if(!allowed || !manager.areNotificationsEnabled()) {
                        Toast.makeText(this,"Bildirim izni kapalı. Önce izni açın.",Toast.LENGTH_LONG).show()
                    } else try {
                        val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                        val notification=Notification.Builder(this,CHANNEL)
                            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                            .setContentTitle("Ezan Vaktim Pro • Test")
                            .setContentText("Bildirim testi başarılı. Bu test ezan sesini ve zamanında alarmı doğrulamaz.")
                            .setContentIntent(open)
                            .setAutoCancel(true)
                            .build()
                        manager.notify(9901,notification)
                        Toast.makeText(this,"Test bildirimi gönderildi",Toast.LENGTH_SHORT).show()
                    } catch(_:Exception) {
                        Toast.makeText(this,"Bildirim gönderilemedi; uygulama bildirim ayarlarını kontrol edin.",Toast.LENGTH_LONG).show()
                    }
                })
                root.addView(panel().apply {
                    addView(text("Alarm tanılama",19f,gold))
                    val exact=if(Build.VERSION.SDK_INT>=31) (getSystemService(ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms() else true
                    val notificationManager=getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                    val notificationReady=notificationManager.areNotificationsEnabled() &&
                        (Build.VERSION.SDK_INT<33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED)
                    val full=prefs.getString("full",null)!=null
                    val short=prefs.getString("short",null)!=null
                    addView(text("Dakik alarm: "+if(exact) "İzin var" else "İzin yok • gecikme olabilir",15f))
                    addView(text("Bildirim: "+if(notificationReady) "Açık" else "Kapalı",15f))
                    addView(text("Tam ezan ses dosyası: "+if(full) "Seçildi" else "Seçilmedi",15f))
                    addView(text("Kısa ezan ses dosyası: "+if(short) "Seçildi" else "Seçilmedi",15f))
                    addView(text("Bu tanılama izinleri gösterir; kilitli ekran ve pil tasarrufu altında gerçek ezan zamanlamasını garanti etmez.",13f))
                })
                root.addView(button("Alarm tanılama raporunu paylaş") {
                    val alarms=getSystemService(ALARM_SERVICE) as AlarmManager
                    val notifications=getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                    val exact=Build.VERSION.SDK_INT<31 || alarms.canScheduleExactAlarms()
                    val granted=Build.VERSION.SDK_INT<33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED
                    val last=prefs.getLong("last_successful_refresh",0L)
                    val refresh=if(last>0L) java.text.SimpleDateFormat("dd.MM.yyyy HH:mm",Locale("tr","TR")).format(java.util.Date(last)) else "Yok"
                    val report=listOf(
                        "EZAN VAKTİM PRO • ALARM TANILAMA",
                        "Android SDK: ${Build.VERSION.SDK_INT}",
                        "Dakik alarm izni: ${if(exact) "Açık" else "Kapalı"}",
                        "Bildirim izni: ${if(granted && notifications.areNotificationsEnabled()) "Açık" else "Kapalı"}",
                        "Tam ezan dosyası: ${if(prefs.getString("full",null)!=null) "Seçildi" else "Yok"}",
                        "Kısa ezan dosyası: ${if(prefs.getString("short",null)!=null) "Seçildi" else "Yok"}",
                        "Son başarılı vakit yenileme: $refresh",
                        "Not: Bu rapor ses çalımını veya zamanında alarmı doğrulamaz."
                    ).joinToString("\n")
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type="text/plain"
                        putExtra(Intent.EXTRA_TEXT,report)
                    },"Tanılama raporunu paylaş"))
                })
                root.addView(text("NAMAZ BAZINDA BİLDİRİMLER",19f,gold))
                listOf("İmsak","Öğle","İkindi","Akşam","Yatsı").forEach { prayer ->
                    root.addView(CheckBox(this).apply {
                        text="$prayer bildirimi"
                        setTextColor(white)
                        textSize=17f
                        isChecked=prefs.getBoolean("alarm_$prayer",true)
                        setOnCheckedChangeListener { _, enabled ->
                            prefs.edit().putBoolean("alarm_$prayer",enabled).apply()
                            PrayerRefresh.refreshFromCache(this@MainActivity)
                        }
                    })
                }
                root.addView(panel().apply {
                    addView(text("EZAN SESİNİ TELEFONA İNDİR",19f,gold))
                    addView(text("İzinli bir MP3 ezan kaydının HTTPS bağlantısını girin. Dosya telefona indirilir ve çevrim dışı kullanılır.",14f))
                    val address=EditText(this@MainActivity).apply {
                        hint="https://.../ezan.mp3"
                        setSingleLine(true)
                        inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
                    }
                    addView(address)
                    addView(button("⬇ Tam ezan olarak indir") { downloadAdhan(address.text.toString(),"full") })
                    addView(button("⬇ Kısa ezan olarak indir") { downloadAdhan(address.text.toString(),"short") })
                    addView(text("Not: Uygulama henüz hazır bir ezan kayıt kataloğu sunmuyor. Kayıtların kullanım iznine dikkat edin.",13f))
                })
                root.addView(button("Tam ezan dosyası seç") { pickAudio(pickFull) })
                root.addView(button("Kısa ezan dosyası seç") { pickAudio(pickShort) })
                root.addView(text("SES ÖN İZLEMESİ",19f,gold))
                root.addView(button("▶ Tam ezanı dinle") { previewAudio("full") })
                root.addView(button("▶ Kısa ezanı dinle") { previewAudio("short") })
                root.addView(button("■ Dinlemeyi durdur") { stopAudioPreview() })
                root.addView(text("Ön izleme yalnızca seçtiğiniz yerel ses dosyasını çalar; alarmı başlatmaz.",13f))
                root.addView(text("Ses çalmak için cihazınızdan bir ezan kaydı seçin. Namaz vakti bildirimleri Android izinlerine bağlıdır.",14f))
            }
            "Ayarlar" -> {
                root.addView(text("GÖRÜNÜM VE AYARLAR",23f,gold))
                root.addView(panel().apply {
                    addView(text("Uygulama ve ezan uyarıları",19f,gold))
                    addView(text("Kapalıyken vakitleri ve duaları açıp okuyabilirsiniz; yalnızca otomatik ezan alarmları devre dışı kalır.",14f))
                    addView(CheckBox(this@MainActivity).apply {
                        text="Ezan Vaktim Pro otomatik uyarıları aktif"
                        setTextColor(white)
                        isChecked=prefs.getBoolean("app_active",true)
                        setOnCheckedChangeListener { _,enabled ->
                            prefs.edit().putBoolean("app_active",enabled).apply()
                            if(!enabled) {
                                AlarmScheduler.cancelPrayerAlarms(this@MainActivity)
                                stopService(Intent(this@MainActivity,AdhanService::class.java))
                                stopAudioPreview()
                            } else {
                                PrayerRefresh.refreshFromCache(this@MainActivity)
                            }
                            Toast.makeText(this@MainActivity,if(enabled) "Ezan uyarıları açıldı" else "Ezan uyarıları kapatıldı",Toast.LENGTH_SHORT).show()
                        }
                    })
                })

                root.addView(text("Yazı boyutu",19f,gold))
                val sizes=listOf("Küçük" to 0.90f,"Normal" to 1.0f,"Büyük" to 1.15f,"Çok büyük" to 1.30f)
                val sizeSpinner=Spinner(this).apply {
                    adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,sizes.map { it.first })
                    val current=prefs.getFloat("font_scale",1f)
                    setSelection(sizes.indices.minByOrNull { kotlin.math.abs(sizes[it].second-current) } ?: 1)
                }
                root.addView(sizeSpinner)
                root.addView(button("Yazı boyutunu uygula") {
                    prefs.edit().putFloat("font_scale",sizes[sizeSpinner.selectedItemPosition].second).apply()
                    render()
                })
                root.addView(panel().apply {
                    addView(text("Konum ve gizlilik",19f,gold))
                    addView(text("Vakit hesabı için seçilen koordinatlar ve tercihler cihazınızda saklanır. Vakitler AlAdhan servisi üzerinden hesaplanır; bu resmî Diyanet vakit listesi değildir.",14f))
                })
                root.addView(button("Kayıtlı konumu sıfırla") {
                    AlertDialog.Builder(this).setTitle("Konumu sıfırla?")
                        .setMessage("Seçili koordinatlar varsayılan örnek konuma dönecek. Diğer tercihler korunacak.")
                        .setNegativeButton("Vazgeç",null)
                        .setPositiveButton("Sıfırla") { _,_ ->
                            latitude=37.5753
                            longitude=36.9228
                            locationLabel="Kahramanmaraş (örnek konum)"
                            prefs.edit().remove("lat").remove("lon").remove("place").apply()
                            PrayerWidget.refreshAll(this)
                            section="Vakitler"
                            render()
                        }.show()
                })
                root.addView(button("Paylaş • Ezan Vaktim Pro") {
                    val share=Intent(Intent.ACTION_SEND).apply {
                        type="text/plain"
                        putExtra(Intent.EXTRA_TEXT,"EZAN VAKTİM PRO — Namaz vakitleri, kıble pusulası, dualar ve zikir.")
                    }
                    startActivity(Intent.createChooser(share,"Uygulamayı paylaş"))
                })
                root.addView(panel().apply {
                    addView(text("Vakit verisi durumu",19f,gold))
                    val last=prefs.getLong("last_successful_refresh",0L)
                    val status=if(last==0L) "Henüz arka plan yenileme kaydı yok" else "Son başarılı arka plan yenilemesi: "+java.text.SimpleDateFormat("dd.MM.yyyy HH:mm",Locale("tr","TR")).format(java.util.Date(last))
                    addView(text(status,14f,white))
                    addView(text("Bağlantı kesilirse yenileme 30 dakika sonra tekrar denenecek.",14f))
                })
                val syncStatus=text("Güncelleme için internet bağlantısı gerekir.",14f,accent)
                root.addView(syncStatus)
                root.addView(button("Namaz vakitlerini sunucudan yeniden al ve alarmları güncelle") {
                    syncStatus.text="Namaz vakitleri güncelleniyor…"
                    thread {
                        try {
                            PrayerRefresh.refresh(applicationContext,forceNetwork=true)
                            runOnUiThread {
                                syncStatus.text="Namaz vakitleri, alarmlar ve widget güncellendi."
                                Toast.makeText(this,"Güncelleme başarılı",Toast.LENGTH_SHORT).show()
                            }
                        } catch(_:Exception) {
                            try { AlarmScheduler.scheduleRefreshRetry(applicationContext) } catch(_:Exception) { }
                            runOnUiThread {
                                syncStatus.text="Güncelleme başarısız. İnterneti kontrol edin; otomatik yeniden deneme planlandı."
                            }
                        }
                    }
                })
                root.addView(button("Alarm ve bildirim ayarları") { section="Ezan"; render() })
                root.addView(button("Widget ayarları") { section="Widget"; render() })
                root.addView(text("Not: Telefonun pil tasarrufu ve bildirim izinleri ezan uyarılarının zamanında çalışmasını etkileyebilir.",14f))
            }
            "Widget" -> {
                root.addView(text("ANA EKRAN NAMAZ VAKTİ WIDGET'I",23f,gold))
                root.addView(text("Telefonunuzun ana ekranında sonraki namaz vaktini gösterir.",16f))
                root.addView(panel().apply {
                    addView(text("Widget nasıl eklenir?",19f,gold))
                    addView(text("1. Telefonunuzun ana ekranında boş bir alana uzun basın.",16f))
                    addView(text("2. Widget'lar bölümünü açın.",16f))
                    addView(text("3. Ezan Vaktim Pro widget'ını seçerek ana ekrana ekleyin.",16f))
                    addView(text("4. Namaz vakitleri için uygulamada konumunuzu belirleyin.",16f))
                })
                root.addView(button("Widget verisini şimdi yenile") {
                    PrayerWidget.refreshAll(this)
                    Toast.makeText(this,"Widget bilgileri güncellendi",Toast.LENGTH_SHORT).show()
                })
                root.addView(text("Widget Android tarafından yaklaşık 30 dakikada bir ve vakit verisi yenilendiğinde güncellenir. Saatlik geri sayım yerine sonraki vaktin saati gösterilir.",14f))
            }
            "Kur'an" -> {
                root.addView(text("KUR'AN-I KERİM",23f,gold))
                root.addView(text("114 sûre Arapça çevrim dışı okuma • Türkçe meal isteğe bağlı",15f))
                root.addView(text("114 sûrelik Arapça metin uygulama paketinde bulunur ve internetsiz okunur. Türkçe meal için izinli bir çeviri dosyası içe aktarabilir veya Diyanet sitesini çevrim içi açabilirsiniz.",14f))
                root.addView(button("⬇ 114 sûreyi telefona indir • Mişârî el-Afâsî") { downloadAllSurahs() })
                root.addView(text("Bir kez indirdikten sonra sûreleri internetsiz dinleyebilirsiniz. İndirme boyunca uygulamayı açık tutun.",13f,gold))
                root.addView(button("📚 30 Cüz • cüzden ayete git") { section="Cüzler"; render() })
                root.addView(button("Çevrim dışı kısa sûreler") {
                    section="Kısa Sûreler"
                    render()
                })
                root.addView(button("114 sûre çevrim dışı metin dosyası içe aktar") {
                    startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        type="application/json"
                        addCategory(Intent.CATEGORY_OPENABLE)
                    },pickQuran)
                })
                root.addView(button("İçe aktardığım Kur'an dosyasını dışa aktar") {
                    if(!java.io.File(filesDir,"quran_import.json").exists()) {
                        Toast.makeText(this,"Önce 114 sûre dosyasını içe aktarın",Toast.LENGTH_LONG).show()
                    } else {
                        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type="application/json"
                            putExtra(Intent.EXTRA_TITLE,"ezan_vaktim_kuran_yedek.json")
                        },104)
                    }
                })
                root.addView(button("İçe aktarılan Kur'an verisini sil") {
                    val file=java.io.File(filesDir,"quran_import.json")
                    if(!file.exists()) {
                        Toast.makeText(this,"Silinecek içe aktarılmış dosya yok",Toast.LENGTH_SHORT).show()
                    } else {
                        android.app.AlertDialog.Builder(this)
                            .setTitle("Çevrim dışı Kur'an dosyasını sil?")
                            .setMessage("Yalnızca içe aktarılan dosya ve bu dosyaya ait ayet işaretleri silinir. Diğer uygulama ayarları korunur.")
                            .setNegativeButton("Vazgeç",null)
                            .setPositiveButton("Sil") { _, _ ->
                                val atomic=android.util.AtomicFile(file)
                                atomic.delete()
                                val edit=prefs.edit().remove("quran_import_last").remove("quran_jump_bookmark")
                                for(i in 0 until 114) edit.remove("quran_bookmark_$i")
                                edit.apply()
                                Toast.makeText(this,"İçe aktarılan dosya silindi",Toast.LENGTH_SHORT).show()
                                render()
                            }.show()
                    }
                })
                root.addView(button("Çevrim dışı son okunan sûreye git") {
                    if(java.io.File(filesDir,"quran_import.json").exists() || assets.list("")?.contains("quran_arabic.json")==true) {
                        section="İçe Aktarılan Sûre"
                        render()
                    } else Toast.makeText(this,"Önce 114 sûre dosyasını içe aktarın",Toast.LENGTH_LONG).show()
                })
                root.addView(button("114 sûre çevrim dışı Kur'anı oku") {
                    section="İçe Aktarılan Kur'an"
                    render()
                })
                root.addView(text("Hazır Arapça metin: Quran Core Dataset (CC BY 4.0), Tanzil (CC BY 3.0). İçe aktarma isteğe bağlıdır; Türkçe mealin doğruluğu ve kullanım izni dosyayı sağlayana aittir.",13f))
                root.addView(button("Kur'an okuma ekranını aç") {
                    section="Kur'an Oku"
                    render()
                })
            }
            "Kısa Sûreler" -> {
                root.addView(button("← Kur'an menüsüne dön") { section="Kur'an"; render() })
                root.addView(text("ÇEVRİM DIŞI KISA SÛRELER",23f,gold))
                root.addView(text("Arapça metinler cihazda saklanır; internet bağlantısı gerektirmez. Bu bölüm tam Kur'an veya meal veritabanı değildir.",14f))
                root.addView(text("Sûreleri aşağı kaydırarak okuyabilir, her birinin Arapça metnini kopyalayabilirsiniz.",14f,accent))
                val surahs=listOf(
                    Triple("Fâtiha • 1. sûre","بِسْمِ اللَّهِ الرَّحْمَٰنِ الرَّحِيمِ\nالْحَمْدُ لِلَّهِ رَبِّ الْعَالَمِينَ\nالرَّحْمَٰنِ الرَّحِيمِ\nمَالِكِ يَوْمِ الدِّينِ\nإِيَّاكَ نَعْبُدُ وَإِيَّاكَ نَسْتَعِينُ\nاهْدِنَا الصِّرَاطَ الْمُسْتَقِيمَ\nصِرَاطَ الَّذِينَ أَنْعَمْتَ عَلَيْهِمْ غَيْرِ الْمَغْضُوبِ عَلَيْهِمْ وَلَا الضَّالِّينَ","7 âyet"),
                    Triple("İhlâs • 112. sûre","بِسْمِ اللَّهِ الرَّحْمَٰنِ الرَّحِيمِ\nقُلْ هُوَ اللَّهُ أَحَدٌ\nاللَّهُ الصَّمَدُ\nلَمْ يَلِدْ وَلَمْ يُولَدْ\nوَلَمْ يَكُنْ لَهُ كُفُوًا أَحَدٌ","4 âyet"),
                    Triple("Felak • 113. sûre","بِسْمِ اللَّهِ الرَّحْمَٰنِ الرَّحِيمِ\nقُلْ أَعُوذُ بِرَبِّ الْفَلَقِ\nمِنْ شَرِّ مَا خَلَقَ\nوَمِنْ شَرِّ غَاسِقٍ إِذَا وَقَبَ\nوَمِنْ شَرِّ النَّفَّاثَاتِ فِي الْعُقَدِ\nوَمِنْ شَرِّ حَاسِدٍ إِذَا حَسَدَ","5 âyet"),
                    Triple("Nâs • 114. sûre","بِسْمِ اللَّهِ الرَّحْمَٰنِ الرَّحِيمِ\nقُلْ أَعُوذُ بِرَبِّ النَّاسِ\nمَلِكِ النَّاسِ\nإِلَٰهِ النَّاسِ\nمِنْ شَرِّ الْوَسْوَاسِ الْخَنَّاسِ\nالَّذِي يُوَسْوِسُ فِي صُدُورِ النَّاسِ\nمِنَ الْجِنَّةِ وَالنَّاسِ","6 âyet")
                )
                val surahFavorites=prefs.getStringSet("favorite_surahs",emptySet())?.toMutableSet() ?: mutableSetOf()
                var surahFont=prefs.getInt("surah_font_size",25).coerceIn(19,38)
                val fontLabel=text("Arapça yazı boyutu: $surahFont",15f,gold)
                root.addView(fontLabel)
                val fontControls=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
                root.addView(fontControls)
                val searchSurah=EditText(this).apply {
                    hint="Sûre ara..."
                    setSingleLine(true)
                    setTextColor(white)
                    setHintTextColor(Color.LTGRAY)
                }
                root.addView(searchSurah)
                val onlySurahFavorites=CheckBox(this).apply { text="Yalnızca favori sûreler"; setTextColor(gold) }
                root.addView(onlySurahFavorites)
                val lastReadLabel=text("Son okunan: "+(prefs.getString("last_read_surah",null) ?: "Henüz kaydedilmedi"),14f,accent)
                val surahList=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
                root.addView(surahList)
                fun showSurahs(query:String) {
                    surahList.removeAllViews()
                    surahs.filter { it.first.contains(query,true) && (!onlySurahFavorites.isChecked || surahFavorites.contains(it.first)) }.forEach { (title,arabic,count) ->
                        surahList.addView(panel().apply {
                            addView(text("$title • $count",19f,gold))
                            addView(button("▶ Gerçek hafızdan sûreyi dinle") {
                                val number=when {
                                    title.startsWith("Fâtiha") -> 1
                                    title.startsWith("İhlâs") -> 112
                                    title.startsWith("Felak") -> 113
                                    else -> 114
                                }
                                playTilavet(number)
                            })
                            addView(button("■ Tilaveti durdur") { stopTilavet() })
                            addView(text(arabic,surahFont.toFloat(),white).apply {
                                gravity=Gravity.RIGHT
                                textDirection=android.view.View.TEXT_DIRECTION_RTL
                                setLineSpacing(12f,1.25f)
                            })
                            addView(button(if(surahFavorites.contains(title)) "★ Favoriden çıkar" else "☆ Favoriye ekle") {
                                if(!surahFavorites.add(title)) surahFavorites.remove(title)
                                prefs.edit().putStringSet("favorite_surahs",surahFavorites.toSet()).apply()
                                showSurahs(searchSurah.text.toString())
                            })
                            addView(button("Okuduğum sûre olarak kaydet") {
                                prefs.edit().putString("last_read_surah",title).apply()
                                lastReadLabel.text="Son okunan: $title"
                                Toast.makeText(this@MainActivity,"Okuma kaydedildi",Toast.LENGTH_SHORT).show()
                            })
                            addView(button("Metni kopyala") {
                                val clipboard=getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText(title,arabic))
                                Toast.makeText(this@MainActivity,"Metin kopyalandı",Toast.LENGTH_SHORT).show()
                            })
                        })
                    }
                    if(surahList.childCount==0) surahList.addView(text("Eşleşen sûre bulunamadı",15f))
                }
                fontControls.addView(button("A−") { surahFont=(surahFont-2).coerceAtLeast(19); prefs.edit().putInt("surah_font_size",surahFont).apply(); fontLabel.text="Arapça yazı boyutu: $surahFont"; showSurahs(searchSurah.text.toString()) },LinearLayout.LayoutParams(0,-2,1f))
                fontControls.addView(button("A+") { surahFont=(surahFont+2).coerceAtMost(38); prefs.edit().putInt("surah_font_size",surahFont).apply(); fontLabel.text="Arapça yazı boyutu: $surahFont"; showSurahs(searchSurah.text.toString()) },LinearLayout.LayoutParams(0,-2,1f))
                onlySurahFavorites.setOnCheckedChangeListener { _,_ -> showSurahs(searchSurah.text.toString()) }
                root.addView(lastReadLabel)
                root.addView(button("Son okuduğum sûreyi göster") {
                    val saved=prefs.getString("last_read_surah",null)
                    if(saved==null) Toast.makeText(this,"Henüz sûre kaydedilmedi",Toast.LENGTH_SHORT).show()
                    else {
                        onlySurahFavorites.isChecked=false
                        searchSurah.setText(saved.substringBefore(" • "))
                    }
                })

                searchSurah.addTextChangedListener(object:android.text.TextWatcher {
                    override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int) {}
                    override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) { showSurahs(s?.toString() ?: "") }
                    override fun afterTextChanged(s:android.text.Editable?) {}
                })
                showSurahs("")
            }
            "Cüzler" -> {
                root.addView(button("← Kur'an'a dön") { section="Kur'an"; render() })
                root.addView(text("KUR'AN-I KERİM • 30 CÜZ",23f,gold))
                root.addView(text("Cüz başlangıcını seçin. İlgili sûre ve ayete geçilir. Sûreyi gerçek hafızdan dinleyebilirsiniz.",15f))
                val juzReciters=listOf(
                    "Mişârî Râşid el-Afâsî" to "ar.alafasy",
                    "Mahmud Halil el-Husarî" to "ar.husary"
                )
                val juzPicker=Spinner(this).apply {
                    adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,juzReciters.map { it.first })
                }
                root.addView(juzPicker)
                root.addView(button("■ Cüz tilavetini durdur") { stopTilavet() })
                root.addView(text("Cüz tilaveti seçilen ayetten başlar, sonraki cüzün ilk ayetinden önce biter. Her ayet için internet gerekir.",13f))
                try {
                    val chapters=loadBundledOrImportedQuran().getJSONArray("surahs")
                    juzStarts.forEachIndexed { i, pair ->
                        val (surah,ayah)=pair
                        val title=chapters.getJSONObject(surah-1).getString("name")
                        root.addView(button("▶ ${i+1}. cüzü hafızdan dinle") {
                            playJuz(i,juzReciters[juzPicker.selectedItemPosition].second)
                        })
                        root.addView(button("${i+1}. Cüz  •  $title  •  $ayah. ayet") {
                            prefs.edit().putInt("quran_import_last",surah-1)
                                .putInt("quran_search_jump_verse",ayah-1).apply()
                            section="İçe Aktarılan Sûre"
                            render()
                        })
                    }
                } catch(_:Exception) {
                    root.addView(text("Cüz listesi açılamadı.",16f))
                }
            }
            "İçe Aktarılan Kur'an" -> {
                root.addView(button("← Kur'an menüsüne dön") { section="Kur'an"; render() })
                root.addView(text("ÇEVRİM DIŞI KUR'AN OKUYUCU",23f,gold))
                try {
                    val imported=loadBundledOrImportedQuran()
                    val items=imported.getJSONArray("surahs")
                    val source=imported.optString("source","").trim()
                    val translationSource=imported.optString("translation_source","").trim()
                    var totalVerses=0
                    var translatedVerses=0
                    for(i in 0 until items.length()) {
                        val surah=items.getJSONObject(i)
                        totalVerses+=surah.getJSONArray("verses").length()
                        translatedVerses+=surah.optJSONArray("translations")?.length() ?: 0
                    }
                    root.addView(text("İçe aktarılan: ${items.length()} sûre • $totalVerses ayet • $translatedVerses meal satırı",14f,accent))
                    root.addView(button("Kur'an içinde ayet veya meal ara") {
                        val input=EditText(this).apply {
                            hint="Arapça ayet veya Türkçe kelime"
                            setSingleLine(true)
                        }
                        android.app.AlertDialog.Builder(this)
                            .setTitle("114 sûrede ara")
                            .setView(input)
                            .setNegativeButton("Vazgeç",null)
                            .setPositiveButton("Ara") { _, _ ->
                                val query=input.text.toString().trim()
                                if(query.length<2) {
                                    Toast.makeText(this,"En az iki karakter girin",Toast.LENGTH_SHORT).show()
                                } else {
                                    val hits=ArrayList<Pair<Int,Int>>()
                                    val labels=ArrayList<String>()
                                    loop@ for(i in 0 until items.length()) {
                                        val surah=items.getJSONObject(i)
                                        val verses=surah.getJSONArray("verses")
                                        val translations=surah.optJSONArray("translations")
                                        for(v in 0 until verses.length()) {
                                            val arabic=verses.getString(v)
                                            val meal=translations?.optString(v,"") ?: ""
                                            if(arabic.contains(query,true) || meal.contains(query,true)) {
                                                hits.add(i to v)
                                                val excerpt=(if(meal.contains(query,true)) meal else arabic).take(90)
                                                labels.add("${i+1}. ${surah.getString("name")} • ${v+1}. ayet: $excerpt")
                                                if(hits.size>=100) break@loop
                                            }
                                        }
                                    }
                                    if(hits.isEmpty()) {
                                        Toast.makeText(this,"Eşleşen ayet bulunamadı",Toast.LENGTH_LONG).show()
                                    } else {
                                        android.app.AlertDialog.Builder(this)
                                            .setTitle("Arama sonuçları (${hits.size}${if(hits.size==100) "+" else ""})")
                                            .setItems(labels.toTypedArray()) { _, which ->
                                                val (surahIndex,verseIndex)=hits[which]
                                                prefs.edit().putInt("quran_import_last",surahIndex)
                                                    .putInt("quran_search_jump_verse",verseIndex).apply()
                                                section="İçe Aktarılan Sûre"
                                                render()
                                            }.setNegativeButton("Kapat",null).show()
                                    }
                                }
                            }.show()
                    })
                    root.addView(button("★ İşaretlediğim sûreler") {
                        val marks=(0 until items.length()).filter { prefs.getInt("quran_bookmark_$it",-1)>=0 }
                        if(marks.isEmpty()) {
                            Toast.makeText(this,"Henüz işaretlenmiş ayet yok",Toast.LENGTH_SHORT).show()
                        } else {
                            val labels=marks.map {
                                "${it+1}. ${items.getJSONObject(it).getString("name")} • Ayet ${prefs.getInt("quran_bookmark_$it",-1)+1}"
                            }.toTypedArray()
                            android.app.AlertDialog.Builder(this)
                                .setTitle("İşaretlediğim ayetler")
                                .setItems(labels) { _, which ->
                                    prefs.edit().putInt("quran_import_last",marks[which]).putBoolean("quran_jump_bookmark",true).apply()
                                    section="İçe Aktarılan Sûre"
                                    render()
                                }.setNegativeButton("Kapat",null).show()
                        }
                    })
                    val filter=EditText(this).apply { hint="Sûre adı veya numarası ara"; setTextColor(white); setHintTextColor(accent) }
                    root.addView(filter)
                    val results=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
                    root.addView(results)
                    fun show(query:String) {
                        results.removeAllViews()
                        for(index in 0 until items.length()) {
                            val surah=items.getJSONObject(index)
                            val title="${index+1}. ${surah.getString("name")}"
                            if(query.isNotBlank() && !title.contains(query,true)) continue
                            results.addView(button(title) {
                                section="İçe Aktarılan Sûre"
                                prefs.edit().putInt("quran_import_last",index).apply()
                                render()
                            })
                        }
                    }
                    filter.addTextChangedListener(object:android.text.TextWatcher {
                        override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int) {}
                        override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) { show(s?.toString() ?: "") }
                        override fun afterTextChanged(s:android.text.Editable?) {}
                    })
                    show("")
                } catch(_:Exception) { root.addView(text("Dosya okunamadı. Yeniden içe aktarın.",16f)) }
            }
            "İçe Aktarılan Sûre" -> {
                root.addView(button("← Sûre listesine dön") { section="İçe Aktarılan Kur'an"; render() })
                try {
                    val items=loadBundledOrImportedQuran().getJSONArray("surahs")
                    val index=prefs.getInt("quran_import_last",0).coerceIn(0,113)
                    val surah=items.getJSONObject(index)
                    root.addView(text("${index+1}. ${surah.getString("name")}",23f,gold))
                    root.addView(text("GERÇEK KUR'AN TİLAVETİ • internet gerektirir",17f,gold))
                    val reciters=listOf(
                        "Mişârî Râşid el-Afâsî" to "ar.alafasy",
                    "Mahmud Halil el-Husarî" to "ar.husary"
                        )
                    val reciterPicker=Spinner(this).apply {
                        adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,reciters.map { it.first })
                    }
                    root.addView(reciterPicker)
                    root.addView(button("▶ Sûreyi hafızdan dinle") {
                        playTilavet(index+1,reciters[reciterPicker.selectedItemPosition].second)
                    })
                    root.addView(button("⬇ Sûreyi çevrim dışı indir") {
                        downloadSurah(index+1,reciters[reciterPicker.selectedItemPosition].second)
                    })
                    root.addView(button("🗑 İndirilen sûreyi sil") {
                        val file=offlineSurahFile(index+1,reciters[reciterPicker.selectedItemPosition].second)
                        stopTilavet()
                        Toast.makeText(this,if(file.delete()) "İndirilen kayıt silindi" else "İndirilmiş kayıt bulunamadı",Toast.LENGTH_SHORT).show()
                    })
                    root.addView(button("■ Tilaveti durdur") { stopTilavet() })
                    root.addView(text("Önce indirilen kayıt kullanılır. Kayıt yoksa çevrim içi dinleme denenir. Cüz düğmesi başlangıç ayetine gider; tam cüz kesintisiz oynatma henüz eklenmedi.",13f,accent))

                    root.addView(button("Sûre metnini paylaş") {
                        val versesForShare=surah.getJSONArray("verses")
                        val mealForShare=surah.optJSONArray("translations")
                        val out=StringBuilder("${index+1}. ${surah.getString("name")}\n\n")
                        for(v in 0 until versesForShare.length()) {
                            out.append("${v+1}. ").append(versesForShare.getString(v)).append('\n')
                            if(mealForShare!=null && prefs.getBoolean("quran_show_translation",true)) {
                                out.append(mealForShare.getString(v)).append("\n")
                            }
                            out.append('\n')
                        }
                        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                            type="text/plain"
                            putExtra(Intent.EXTRA_TEXT,out.toString())
                        },"Sûreyi paylaş"))
                    })

                    val verses=surah.getJSONArray("verses")
                    val translations=surah.optJSONArray("translations")
                    var onlineTranslations:org.json.JSONArray?=null
                    var refreshVerseDisplay:(()->Unit)?=null
                    val source=loadBundledOrImportedQuran()
                    val attribution=source.optString("source","").trim()
                    val translationSource=source.optString("translation_source","").trim()
                    if(attribution.isNotBlank()) root.addView(text("Arapça metin kaynağı: $attribution",13f,accent))
                    if(translations!=null && translationSource.isNotBlank()) root.addView(text("Meal kaynağı: $translationSource",13f,accent))
                    val bookmarkedVerse=prefs.getInt("quran_bookmark_${index}",-1)
                    val bookmarkStatus=text(if(bookmarkedVerse in 0 until verses.length()) "İşaretli ayet: ${bookmarkedVerse+1}" else "Bu sûrede işaretli ayet yok",14f,accent)
                    root.addView(bookmarkStatus)
                    val mealToggle=CheckBox(this).apply {
                        text="Türkçe meali göster"
                        setTextColor(white)
                        isChecked=prefs.getBoolean("quran_show_translation",true)
                        isEnabled=true
                    }
                    root.addView(mealToggle)
                    if(translations==null) {
                        val mealStatus=text("Türkçe meal: çevrim içi yükleyebilir veya izinli JSON meal dosyası içe aktarabilirsiniz.",14f,accent)
                        root.addView(mealStatus)
                        root.addView(button("🌐 Türkçe meali ayetlerin altına yükle") {
                            mealStatus.text="Türkçe meal yükleniyor…"
                            thread {
                                try {
                                    val url=URL("https://api.alquran.cloud/v1/surah/${index+1}/tr.vakfi")
                                    val connection=(url.openConnection() as java.net.HttpURLConnection).apply {
                                        connectTimeout=12000; readTimeout=12000
                                    }
                                    val response=try {
                                        if(connection.responseCode !in 200..299) throw IllegalStateException("Meal servisi yanıt vermedi")
                                        JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                                    } finally { connection.disconnect() }
                                    val ayahs=response.getJSONObject("data").getJSONArray("ayahs")
                                    if(ayahs.length()!=verses.length()) throw IllegalStateException("Ayet sayısı uyuşmuyor")
                                    val result=org.json.JSONArray()
                                    for(v in 0 until ayahs.length()) result.put(ayahs.getJSONObject(v).getString("text"))
                                    runOnUiThread {
                                        if(section=="İçe Aktarılan Sûre" && prefs.getInt("quran_import_last",0)==index) {
                                            onlineTranslations=result
                                            mealToggle.isChecked=true
                                            mealStatus.text="Türkçe meal: Türkiye Diyanet Vakfı (Al Quran Cloud üzerinden). Çevrim içi gösterilir."
                                            refreshVerseDisplay?.invoke()
                                        }
                                    }
                                } catch(e:Exception) {
                                    runOnUiThread { mealStatus.text="Meal yüklenemedi: ${e.message}. İzinli meal dosyası içe aktarabilirsiniz." }
                                }
                            }
                        })
                    }
                    var arabicSize=prefs.getFloat("quran_import_arabic_size",26f).coerceIn(18f,42f)
                    var translationSize=prefs.getFloat("quran_import_translation_size",16f).coerceIn(12f,28f)
                    val fontControls=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
                    root.addView(fontControls)
                    val verseContainer=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
                    root.addView(verseContainer)
                    val verseViews=ArrayList<TextView>()
                    fun jumpToVerse(number:Int) {
                        if(number !in 0 until verseViews.size) return
                        val target=verseViews[number]
                        target.post {
                            val scroll=root.parent as? ScrollView ?: return@post
                            val location=IntArray(2)
                            val parentLocation=IntArray(2)
                            target.getLocationOnScreen(location)
                            scroll.getLocationOnScreen(parentLocation)
                            scroll.smoothScrollBy(0,location[1]-parentLocation[1]-80)
                        }
                    }
                    fun drawVerses() {
                        verseContainer.removeAllViews()
                        verseViews.clear()
                        for(v in 0 until verses.length()) {
                            val verseText=text("${if(prefs.getInt("quran_bookmark_${index}",-1)==v) "★ " else ""}${verses.getString(v)}  ﴿${v+1}﴾",arabicSize).apply {
                                gravity=Gravity.RIGHT
                                textDirection=android.view.View.TEXT_DIRECTION_RTL
                                setOnClickListener {
                                    val key="quran_bookmark_${index}"
                                    val previous=prefs.getInt(key,-1)
                                    val next=if(previous==v) -1 else v
                                    prefs.edit().putInt(key,next).apply()
                                    if(previous in 0 until verseViews.size) {
                                        verseViews[previous].text="${verses.getString(previous)}  ﴿${previous+1}﴾"
                                    }
                                    if(next>=0) {
                                        verseViews[next].text="★ ${verses.getString(next)}  ﴿${next+1}﴾"
                                    }
                                    bookmarkStatus.text=if(next>=0) "İşaretli ayet: ${next+1}" else "Bu sûrede işaretli ayet yok"
                                }
                            }
                            verseViews.add(verseText)
                            verseContainer.addView(verseText)
                            val activeMeal=translations ?: onlineTranslations
                            if(mealToggle.isChecked && activeMeal!=null) {
                                verseContainer.addView(text("${v+1}. ${activeMeal.getString(v)}",translationSize,accent))
                            }
                        }
                    }
                    refreshVerseDisplay={ drawVerses() }
                    fontControls.addView(button("A−") {
                        arabicSize=(arabicSize-2f).coerceAtLeast(18f)
                        prefs.edit().putFloat("quran_import_arabic_size",arabicSize).apply()
                        drawVerses()
                    },LinearLayout.LayoutParams(0,-2,1f))
                    fontControls.addView(button("A+") {
                        arabicSize=(arabicSize+2f).coerceAtMost(42f)
                        prefs.edit().putFloat("quran_import_arabic_size",arabicSize).apply()
                        drawVerses()
                    },LinearLayout.LayoutParams(0,-2,1f))
                    if(translations!=null) {
                        fontControls.addView(button("Meal −") {
                            translationSize=(translationSize-2f).coerceAtLeast(12f)
                            prefs.edit().putFloat("quran_import_translation_size",translationSize).apply()
                            drawVerses()
                        },LinearLayout.LayoutParams(0,-2,1f))
                        fontControls.addView(button("Meal +") {
                            translationSize=(translationSize+2f).coerceAtMost(28f)
                            prefs.edit().putFloat("quran_import_translation_size",translationSize).apply()
                            drawVerses()
                        },LinearLayout.LayoutParams(0,-2,1f))
                    }
                    mealToggle.setOnCheckedChangeListener { _, checked ->
                        prefs.edit().putBoolean("quran_show_translation",checked).apply()
                        drawVerses()
                    }
                    drawVerses()
                    val searchJump=prefs.getInt("quran_search_jump_verse",-1)
                    if(searchJump>=0) {
                        prefs.edit().remove("quran_search_jump_verse").apply()
                        jumpToVerse(searchJump)
                    }
                    if(prefs.getBoolean("quran_jump_bookmark",false)) {
                        prefs.edit().remove("quran_jump_bookmark").apply()
                        val marked=prefs.getInt("quran_bookmark_${index}",-1)
                        if(marked>=0) jumpToVerse(marked)
                    }
                    root.addView(button("★ İşaretli ayete git") {
                        val marked=prefs.getInt("quran_bookmark_${index}",-1)
                        if(marked>=0) jumpToVerse(marked)
                        else Toast.makeText(this,"Önce bir ayete dokunarak işaretleyin",Toast.LENGTH_SHORT).show()
                    })
                    root.addView(LinearLayout(this).apply {
                        orientation=LinearLayout.HORIZONTAL
                        if(index>0) addView(button("← Önceki sûre") {
                            prefs.edit().putInt("quran_import_last",index-1).apply()
                            render()
                        },LinearLayout.LayoutParams(0,-2,1f))
                        if(index<113) addView(button("Sonraki sûre →") {
                            prefs.edit().putInt("quran_import_last",index+1).apply()
                            render()
                        },LinearLayout.LayoutParams(0,-2,1f))
                    })

                } catch(_:Exception) { root.addView(text("Sûre açılamadı. Dosyayı yeniden içe aktarın.",16f)) }
            }
            "Kur'an Oku" -> {
                root.addView(button("← Kur'an menüsüne dön") { section="Kur'an"; render() })
                val webStatus=text("Diyanet Kur’an sayfası yükleniyor…",14f,accent)
                root.addView(webStatus)
                var webFailed=false
                val browser=WebView(this).apply {
                    settings.javaScriptEnabled=true
                    settings.domStorageEnabled=true
                    settings.allowFileAccess=false
                    settings.allowContentAccess=false
                    webViewClient=object:WebViewClient() {
                        override fun onPageFinished(view:WebView?,url:String?) {
                            if(!webFailed) webStatus.text="Diyanet Kur’an portalı • Çevrim içi"
                        }
                        override fun onReceivedError(view:WebView?,request:android.webkit.WebResourceRequest?,error:android.webkit.WebResourceError?) {
                            if(request?.isForMainFrame==true) {
                                webFailed=true
                                webStatus.text="Sayfa yüklenemedi. İnternet bağlantısını kontrol edip yeniden deneyin."
                            }
                        }
                        override fun shouldOverrideUrlLoading(view:WebView?,request:android.webkit.WebResourceRequest?):Boolean {
                            val url=request?.url ?: return true
                            return url.scheme!="https" || (url.host!="diyanet.gov.tr" && url.host?.endsWith(".diyanet.gov.tr")!=true)
                        }
                    }
                    loadUrl("https://kuran.diyanet.gov.tr/")
                }
                root.addView(button("Sayfayı yeniden yükle") { webFailed=false; webStatus.text="Sayfa yükleniyor…"; browser.reload() })
                root.addView(button("Diyanet sitesini tarayıcıda aç") {
                    try { startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://kuran.diyanet.gov.tr/"))) }
                    catch(_:android.content.ActivityNotFoundException) { Toast.makeText(this,"Tarayıcı bulunamadı",Toast.LENGTH_LONG).show() }
                })
                root.addView(browser,LinearLayout.LayoutParams(-1,resources.displayMetrics.heightPixels*2/3))
                root.addView(text("Görüntülenen içerik Diyanet'in çevrim içi hizmetidir; uygulamanın yerel çevrim dışı Kur'an veritabanı henüz bulunmuyor.",13f))
            }
            "Dualar" -> {
                root.addView(text("GÜNLÜK DUALAR VE ZİKİRLER",23f,gold))
                root.addView(text("Kur’an’daki duaları hafızdan dinleyin.",15f))
                root.addView(text("KUR'AN'DAN DUALAR • ARAPÇA VE TÜRKÇE ANLAMI",18f,gold))
                val quranDuas=listOf(
                    Triple("Bakara 2:201 • Dünya ve ahiret iyiliği","رَبَّنَا آتِنَا فِي الدُّنْيَا حَسَنَةً وَفِي الْآخِرَةِ حَسَنَةً وَقِنَا عَذَابَ النَّارِ","Rabbimiz! Bize dünyada iyilik, ahirette de iyilik ver; bizi ateşin azabından kor."),
                    Triple("Tâhâ 20:114 • İlim","رَبِّ زِدْنِي عِلْمًا","Rabbim! İlmimi artır."),
                    Triple("A'râf 7:23 • Bağışlanma","رَبَّنَا ظَلَمْنَا أَنْفُسَنَا وَإِنْ لَمْ تَغْفِرْ لَنَا وَتَرْحَمْنَا لَنَكُونَنَّ مِنَ الْخَاسِرِينَ","Rabbimiz! Biz kendimize zulmettik. Bizi bağışlamaz ve bize merhamet etmezsen mutlaka ziyana uğrayanlardan oluruz."),
                    Triple("Âl-i İmrân 3:8 • Kalbin doğruluğu","رَبَّنَا لَا تُزِغْ قُلُوبَنَا بَعْدَ إِذْ هَدَيْتَنَا وَهَبْ لَنَا مِنْ لَدُنْكَ رَحْمَةً إِنَّكَ أَنْتَ الْوَهَّابُ","Rabbimiz! Bizi doğru yola ilettikten sonra kalplerimizi eğriltme; bize katından rahmet bağışla. Şüphesiz çok bağışlayan sensin."),
                    Triple("İbrâhîm 14:40 • Namaz duası","رَبِّ اجْعَلْنِي مُقِيمَ الصَّلَاةِ وَمِنْ ذُرِّيَّتِي رَبَّنَا وَتَقَبَّلْ دُعَاءِ","Rabbim! Beni ve soyumdan gelecekleri namazı devamlı kılanlardan eyle. Rabbimiz, duamı kabul et."),
                    Triple("Furkân 25:74 • Aile duası","رَبَّنَا هَبْ لَنَا مِنْ أَزْوَاجِنَا وَذُرِّيَّاتِنَا قُرَّةَ أَعْيُنٍ وَاجْعَلْنَا لِلْمُتَّقِينَ إِمَامًا","Rabbimiz! Eşlerimizi ve çocuklarımızı bize göz aydınlığı kıl; bizi takvâ sahiplerine önder eyle."),
                    Triple("Enbiyâ 21:87 • Yunus peygamberin duası","لَا إِلَٰهَ إِلَّا أَنْتَ سُبْحَانَكَ إِنِّي كُنْتُ مِنَ الظَّالِمِينَ","Senden başka ilah yoktur. Seni tenzih ederim. Gerçekten ben haksızlık edenlerden oldum."),
                    Triple("Kasas 28:24 • Hayır istemek","رَبِّ إِنِّي لِمَا أَنْزَلْتَ إِلَيَّ مِنْ خَيْرٍ فَقِيرٌ","Rabbim! Bana indireceğin her hayra muhtacım."),
                    Triple("Enbiyâ 21:83 • Eyyûb peygamberin duası","أَنِّي مَسَّنِيَ الضُّرُّ وَأَنْتَ أَرْحَمُ الرَّاحِمِينَ","Bana sıkıntı dokundu; sen merhametlilerin en merhametlisisin."),
                    Triple("Âl-i İmrân 3:16 • Bağışlanma","رَبَّنَا إِنَّنَا آمَنَّا فَاغْفِرْ لَنَا ذُنُوبَنَا وَقِنَا عَذَابَ النَّارِ","Rabbimiz! İman ettik. Günahlarımızı bağışla ve bizi ateşin azabından kor."),
                    Triple("Bakara 2:286 • Gücümüzün üstünde yük","رَبَّنَا وَلَا تُحَمِّلْنَا مَا لَا طَاقَةَ لَنَا بِهِ","Rabbimiz! Gücümüzün yetmeyeceği yükü bize yükleme."),
                    Triple("İbrâhîm 14:41 • Anne baba için","رَبَّنَا اغْفِرْ لِي وَلِوَالِدَيَّ وَلِلْمُؤْمِنِينَ يَوْمَ يَقُومُ الْحِسَابُ","Rabbimiz! Hesap gününde beni, anne babamı ve müminleri bağışla."),
                    Triple("Tâhâ 20:25 • Gönül ferahlığı","رَبِّ اشْرَحْ لِي صَدْرِي","Rabbim! Gönlüme ferahlık ver.")
                )
                val duaSurahNumbers=mapOf(
                    "Bakara 2:201" to (2 to 201),
                    "Tâhâ 20:114" to (20 to 114),
                    "A'râf 7:23" to (7 to 23),
                    "Âl-i İmrân 3:8" to (3 to 8),
                    "İbrâhîm 14:40" to (14 to 40),
                    "Furkân 25:74" to (25 to 74),
                    "Enbiyâ 21:87" to (21 to 87),
                    "Kasas 28:24" to (28 to 24),
                    "Enbiyâ 21:83" to (21 to 83),
                    "Âl-i İmrân 3:16" to (3 to 16),
                    "Bakara 2:286" to (2 to 286),
                    "İbrâhîm 14:41" to (14 to 41),
                    "Tâhâ 20:25" to (20 to 25)
                )
                val duaReciters=listOf(
                    "Mişârî Râşid el-Afâsî" to "ar.alafasy",
                    "Mahmud Halil el-Husarî" to "ar.husary"
                )
                val duaReciterPicker=Spinner(this).apply {
                    adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,duaReciters.map { it.first })
                }
                root.addView(text("Dua tilaveti • Hafız seçimi",14f,gold))
                root.addView(duaReciterPicker)
                quranDuas.forEach { (title,arabic,meaning) ->
                    root.addView(panel().apply {
                        addView(text(title,17f,gold))
                        addView(text(arabic,24f,white).apply { gravity=Gravity.RIGHT; textDirection=android.view.View.TEXT_DIRECTION_RTL })
                        addView(text(meaning,16f,white))
                        val duaSurah=duaSurahNumbers.entries.firstOrNull { title.startsWith(it.key) }?.value
                        if(duaSurah!=null) {
                            addView(button("▶ Dua ayetini hafızdan dinle") {
                                playQuranVerse(duaSurah.first,duaSurah.second,duaReciters[duaReciterPicker.selectedItemPosition].second)
                            })
                        }
                        addView(button("■ Tilaveti durdur") { stopTilavet() })
                        addView(button("Duayı kopyala") {
                            val clipboard=getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText(title,"$title\n$arabic\n$meaning"))
                            Toast.makeText(this@MainActivity,"Dua kopyalandı",Toast.LENGTH_SHORT).show()
                        })
                    })
                }

                root.addView(button("▶ Sesli Dua Arşivi • 267 gerçek kayıt") { showHisnAudioCatalog() })
                root.addView(text("Hisnü'l-Müslim • Arapça metin ve kayıt eşleşmeleri: Islamic Pro Azkar API / Hamad Al-Durayhim. Sesler internet üzerinden oynatılır.",12f,gold))
                val duas=listOf(
                    Triple("Başlarken","Besmele","Bismillâhirrahmânirrahîm."),
                    Triple("Yemek","Yemekten önce","Bismillâh."),
                    Triple("Yemek","Yemekten sonra","Elhamdülillâh."),
                    Triple("Bağışlanma","İstiğfar","Estağfirullâh."),
                    Triple("Zikir","Tesbih","Sübhânallâh."),
                    Triple("Zikir","Hamd","Elhamdülillâh."),
                    Triple("Zikir","Tekbir","Allâhu ekber."),
                    Triple("Zikir","Tevhid","Lâ ilâhe illallâh."),
                    Triple("Salavat","Salavat","Allâhümme salli alâ Muhammed."),
                    Triple("Kur'an","Rabbena âtinâ (Bakara 2:201)","Rabbenâ âtinâ fid-dünyâ haseneten ve fil-âhireti haseneten ve kınâ azâben-nâr."),
                    Triple("Kur'an","İlim duası (Tâhâ 20:114)","Rabbi zidnî ilmâ."),
                    Triple("Kur'an","Bağışlanma (A'râf 7:23)","Rabbenâ zalemnâ enfüsenâ ve in lem tağfir lenâ ve terhamnâ le-nekûnenne minel hâsirîn."),
                    Triple("Sabah","Uyanınca","Elhamdülillâhillezî ahyânâ ba‘de mâ emâtenâ ve ileyhin-nüşûr."),
                    Triple("Akşam","Uyumadan önce","Bismikellâhümme emûtü ve ahyâ."),
                    Triple("Sabah","Sabah zikri","Allâhümme bike asbahnâ ve bike emseynâ ve bike nahyâ ve bike nemûtü ve ileyken-nüşûr."),
                    Triple("Akşam","Akşam zikri","Allâhümme bike emseynâ ve bike asbahnâ ve bike nahyâ ve bike nemûtü ve ileykel-masîr."),
                    Triple("Yemek","Yemeği unutunca","Bismillâhi fî evvelihî ve âhirihî."),
                    Triple("Yemek","İkram edene","Allâhümme bârik lehüm fîmâ razaktehüm vağfir lehüm verhamhüm."),
                    Triple("Ev","Evden çıkarken","Bismillâhi tevekkeltü alallâh. Lâ havle ve lâ kuvvete illâ billâh."),
                    Triple("Ev","Eve girerken","Bismillâhi velecnâ ve bismillâhi haracnâ ve alâ rabbinâ tevekkelnâ."),
                    Triple("Yolculuk","Yolculuk duası","Sübhânellezî sehhara lenâ hâzâ ve mâ künnâ lehû mukrinîn. Ve innâ ilâ rabbinâ lemünkalibûn."),
                    Triple("Yolculuk","Yolculukta kolaylık","Allâhümme hevvin aleynâ seferenâ hâzâ vatvi annâ bu‘deh."),
                    Triple("Sağlık","Şifa duası","Allâhümme rabben-nâsi ezhibil-be'se işfi ente'ş-Şâfî, lâ şifâe illâ şifâüke."),
                    Triple("Sağlık","Hasta ziyareti","Lâ be'se tahûrun inşâallâh."),
                    Triple("Korunma","Kötülüklerden korunma","Eûzü bi-kelimâtillâhit-tâmmâti min şerri mâ halak."),
                    Triple("Korunma","Sabah akşam korunma","Bismillâhillezî lâ yadurru measmihî şey'ün fil-ardı ve lâ fis-semâi ve hüves-Semîul-Alîm."),
                    Triple("Korunma","Vesveseden korunma","Eûzü billâhi mineş-şeytânir-racîm."),
                    Triple("Bağışlanma","Seyyidü'l-istiğfar","Allâhümme ente rabbî lâ ilâhe illâ ente, halaktenî ve ene abdüke ve ene alâ ahdike ve va‘dike mesteta‘tü. Eûzü bike min şerri mâ sana‘tü. Ebûü leke bi-ni‘metike aleyye ve ebûü bi-zenbî fağfirlî fe-innehû lâ yağfiruz-zünûbe illâ ente."),
                    Triple("Bağışlanma","Tövbe duası","Rabbiğfirlî ve tüb aleyye inneke entet-Tevvâbur-Rahîm."),
                    Triple("Namaz","Namaz sonrası istiğfar","Estağfirullâh. Estağfirullâh. Estağfirullâh."),
                    Triple("Namaz","Namaz sonrası selam","Allâhümme entes-selâmü ve minkes-selâmü tebârekte yâ zel-celâli vel-ikrâm."),
                    Triple("Namaz","Hidayet ve afiyet","Allâhümme innî es'elükel-hüdâ vet-tükâ vel-afâfe vel-ğınâ."),
                    Triple("Namaz","Namaza yardım","Allâhümme einnî alâ zikrike ve şükrike ve husni ibâdetik."),
                    Triple("İlim","Faydalı ilim","Allâhümme innî es'elüke ilmen nâfi‘an ve rizkan tayyiben ve amelen mütekabbelen."),
                    Triple("Rızık","Helal rızık","Allâhümmekfinî bi-halâlike an harâmike ve ağninî bi-fadlike ammen sivâk."),
                    Triple("Rızık","Bereket","Allâhümme bârik lenâ fîmâ razaktenâ."),
                    Triple("Aile","Anne babaya dua","Rabbiğfirlî ve li-vâlideyye ve lil-mü'minîne yevme yekûmül-hisâb."),
                    Triple("Aile","Aile huzuru","Rabbenâ heb lenâ min ezvâcinâ ve zürriyyâtinâ kurrate a‘yunin vec‘alnâ lil-müttakîne imâmâ."),
                    Triple("Aile","Evlat için dua","Rabbi heb lî min ledünke zürriyyeten tayyibeten inneke semîud-duâ."),
                    Triple("Zorluk","Sıkıntıda","Lâ ilâhe illâ ente sübhâneke innî küntü minez-zâlimîn."),
                    Triple("Zorluk","İşlerin kolaylaşması","Rabbişrah lî sadrî ve yessir lî emrî."),
                    Triple("Zorluk","Üzüntü ve keder","Allâhümme innî eûzü bike minel-hemmi vel-hazen."),
                    Triple("Zorluk","Sabır","Rabbenâ efriğ aleynâ sabran ve sebbit akdâmenâ."),
                    Triple("Şükür","Nimete şükür","Rabbi evzi‘nî en eşküra ni‘metekelletî en‘amte aleyye ve alâ vâlideyye."),
                    Triple("Zikir","Havkale","Lâ havle ve lâ kuvvete illâ billâh."),
                    Triple("Zikir","Kısa zikir","Sübhânallâhi ve bi-hamdihî."),
                    Triple("Zikir","Tevhid zikri","Lâ ilâhe illallâhü vahdehû lâ şerîke leh, lehül-mülkü ve lehül-hamdü ve hüve alâ külli şey'in kadîr."),
                    Triple("Salavat","İbrahimî salavat","Allâhümme salli alâ Muhammedin ve alâ âli Muhammed, kemâ salleyte alâ İbrâhîme ve alâ âli İbrâhîm."),
                    Triple("Cenaze","Vefat haberi","İnnâ lillâhi ve innâ ileyhi râciûn."),
                    Triple("Cenaze","Vefat eden için","Allâhümmağfir lehû verhamhû ve âfihî va‘fü anh."),
                    Triple("Oruç","İftar duası","Zehebe'z-zamaü vebtelletil-urûku ve sebetel-ecru inşâallâh."),
                    Triple("Oruç","Oruçluya ikram","Eftara indekümüs-sâimûn ve ekele taâmekümül-ebrâr ve sallet aleykümül-melâike."),
                    Triple("Mescit","Mescide girerken","Allâhümmeftah lî ebvâbe rahmetik."),
                    Triple("Mescit","Mescitten çıkarken","Allâhümme innî es'elüke min fadlik."),
                    Triple("Temizlik","Tuvalete girmeden","Allâhümme innî eûzü bike minel-hubüsi vel-habâis."),
                    Triple("Temizlik","Tuvaletten çıkınca","Ğufrânek."),
                    Triple("Abdest","Abdest sonrası","Eşhedü en lâ ilâhe illallâhü vahdehû lâ şerîke leh ve eşhedü enne Muhammeden abdühû ve resûlüh."),
                    Triple("Hava","Yağmur yağınca","Allâhümme sayyiben nâfi‘â."),
                    Triple("Hava","Rüzgârda","Allâhümme innî es'elüke hayrahâ ve eûzü bike min şerrihâ."),
                    Triple("Topluluk","Meclis kefareti","Sübhânekellâhümme ve bi-hamdike eşhedü en lâ ilâhe illâ ente estağfiruke ve etûbü ileyk."),
                    Triple("Kur'an","Yunus peygamberin duası (Enbiyâ 21:87)","Lâ ilâhe illâ ente sübhâneke innî küntü minez-zâlimîn."),
                    Triple("Kur'an","Hayır istemek (Kasas 28:24)","Rabbi innî limâ enzelte ileyye min hayrin fakîr."),
                    Triple("Kur'an","Anne baba için (İbrâhîm 14:41)","Rabbenâğfirlî ve li-vâlideyye ve lil-mü'minîne yevme yekûmül-hisâb."),
                    Triple("Kur'an","Gönül ferahlığı (Tâhâ 20:25)","Rabbişrah lî sadrî."),
                    Triple("Kur'an","Gücümüzün üstünde yük (Bakara 2:286)","Rabbenâ ve lâ tühammilnâ mâ lâ tâkate lenâ bih."),

                    Triple("Zikir","Sübhânallâhi ve bihamdihî","Sübhânallâhi ve bihamdihî."),
                    Triple("Bağışlanma","Rabbim beni bağışla","Rabbiğfirlî."),
                    Triple("Korunma","Şeytanın vesvesesinden","Eûzü billâhi mineş-şeytânirracîm."),
                    Triple("Şükür","Nimete hamd","Elhamdülillâhi rabbil âlemîn."),
                    Triple("Sabah","Uyanınca şükür","Elhamdülillâhillezî âfânî fî cesedî ve redde aleyye rûhî ve ezine lî bi zikrih."),
                    Triple("Sabah","Sabahın hayrını istemek","Allâhümme innî es'elüke hayra hâzel-yevmi ve fethahû ve nasrahû ve nûrahû ve bereketehû ve hüdâh."),
                    Triple("Akşam","Gecenin hayrını istemek","Allâhümme innî es'elüke hayra hâzihil-leyle ve eûzü bike min şerrihâ."),
                    Triple("Uyku","Uyumadan önce korunma","Bismike rabbî vada'tü cenbî ve bike erfa'uhû. İn emsekte nefsî ferhamhâ ve in erseltehâ fahfazhâ."),
                    Triple("Uyku","Uykudan korkuyla uyanınca","Eûzü bi-kelimâtillâhit-tâmmâti min gadabihî ve ikâbihî ve şerri ibâdihî."),
                    Triple("Yemek","Yemek bereketi","Allâhümme bârik lenâ fîhi ve et'imnâ hayran minh."),
                    Triple("Ev","Yeni eve girerken","Eûzü bi-kelimâtillâhit-tâmmâti min şerri mâ halak."),
                    Triple("Yolculuk","Yolculuktan dönüş","Âyibûne tâibûne âbidûne li-rabbinâ hâmidûn."),
                    Triple("Yolculuk","Bineğe binerken","Bismillâh, elhamdülillâh, sübhânellezî sehhara lenâ hâzâ ve mâ künnâ lehû mukrinîn."),
                    Triple("Sağlık","Ağrıyan yere dua","Bismillâh. Eûzü bi-izzetillâhi ve kudretihî min şerri mâ ecidü ve ühâzir."),
                    Triple("Sağlık","Afiyet istemek","Allâhümme innî es'elükel-afve vel-âfiyete fid-dünyâ vel-âhireh."),
                    Triple("Sağlık","Hastalıkta şifa istemek","Es'elullâhel-azîme rabbel-arşil-azîmi en yeşfiyek."),
                    Triple("Korunma","Nazardan korunma","Eûzü bi-kelimâtillâhit-tâmmeti min külli şeytânin ve hâmmetin ve min külli aynin lâmmeh."),
                    Triple("Korunma","Korku anında","Hasbünallâhü ve ni'mel-vekîl."),
                    Triple("Korunma","Güven ve tevekkül","Hasbiyallâhü lâ ilâhe illâ hû, aleyhi tevekkeltü ve hüve rabbül-arşil-azîm."),
                    Triple("Bağışlanma","Günahların affı","Allâhümme inneke afüvvün tuhibbül-afve fa'fü annî."),
                    Triple("Bağışlanma","Günahlar için istiğfar","Estağfirullâhel-azîmellezî lâ ilâhe illâ hüvel-hayyel-kayyûme ve etûbü ileyh."),
                    Triple("Namaz","Rükû tesbihi","Sübhâne rabbiyel-azîm."),
                    Triple("Namaz","Secde tesbihi","Sübhâne rabbiyel-a'lâ."),
                    Triple("Namaz","İki secde arasında","Rabbiğfirlî, rabbiğfirlî."),
                    Triple("Namaz","Namazdan sonra zikir","Lâ ilâhe illallâhü vahdehû lâ şerîke leh, lehül-mülkü ve lehül-hamdü ve hüve alâ külli şey'in kadîr."),
                    Triple("Oruç","İftar ederken","Allâhümme leke sumtü ve alâ rizkıke eftartü."),
                    Triple("Aile","Evlatları koruma","Üîzükümâ bi-kelimâtillâhit-tâmmeti min külli şeytânin ve hâmmetin ve min külli aynin lâmmeh."),
                    Triple("Aile","Hayırlı eş ve nesil","Rabbenâ heb lenâ min ezvâcinâ ve zürriyyâtinâ kurrate a'yunin vec'alnâ lil-müttakîne imâmâ."),
                    Triple("İlim","Hikmet istemek","Rabbi heb lî hukmen ve elhıknî bis-sâlihîn."),
                    Triple("Zorluk","Borç ve sıkıntıdan korunma","Allâhümme innî eûzü bike minel-hemmi vel-hazeni vel-aczi vel-keseli vel-buhli vel-cübni ve dale'id-deyni ve galebetir-ricâl."),
                    Triple("Zorluk","Zor işi kolaylaştırma","Allâhümme lâ sehle illâ mâ cealtehû sehlen ve ente tec'alül-hazne izâ şi'te sehlen."),
                    Triple("Şükür","Nimetin devamı","Allâhümme mâ asbaha bî min ni'metin ev bi-ehadin min halkıke fe-minke vahdeke lâ şerîke lek, fe-lekel-hamdü ve lekeş-şükr."),
                    Triple("Mescit","Mescide giderken","Allâhümmec'al fî kalbî nûran ve fî lisânî nûran."),
                    Triple("Kur'an","İmanla sebat (Âl-i İmrân 3:8)","Rabbenâ lâ tüziğ kulûbenâ ba'de iz hedeytenâ ve heb lenâ min ledünke rahmeh."),
                    Triple("Kur'an","İman edenler için (Haşr 59:10)","Rabbenâğfir lenâ ve li-ihvâninellezîne sebekûnâ bil-îmân."),
                    Triple("Kur'an","İyilerle beraber olmak (Âl-i İmrân 3:193)","Rabbenâ fağfir lenâ zünûbenâ ve keffir annâ seyyiâtinâ ve teveffenâ meal-ebrâr."),
                    Triple("Kur'an","Cehennemden korunmak (Furkân 25:65)","Rabbenasrif annâ azâbe cehennem, inne azâbehâ kâne garâmâ."),
                    Triple("Kur'an","Sabır istemek (Bakara 2:250)","Rabbenâ efriğ aleynâ sabran ve sebbit akdâmenâ vensurnâ alel-kavmil-kâfirîn.")
                )
                val dailyDuaReciters=listOf("Mişârî Râşid el-Afâsî" to "ar.alafasy","Mahmud Halil el-Husarî" to "ar.husary")
                val dailyDuaReciter=Spinner(this).apply {
                    adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,dailyDuaReciters.map { it.first })
                }
                root.addView(text("Kur'an ayetleri: seçilen hafızın internet üzerinden tilaveti. Diğer dualar: eklenmiş ses kaydı veya cihaz sesli okuması.",14f,gold))
                root.addView(dailyDuaReciter)
                root.addView(button("Kur'an dualarını iki hafızla telefona kaydet") {
                    val verses=listOf(2 to 201,20 to 114,7 to 23,21 to 87,28 to 24,
                        14 to 41,20 to 25,2 to 286,3 to 8,59 to 10,3 to 193,25 to 65,2 to 250)
                    Toast.makeText(this@MainActivity,"26 hafız kaydı indiriliyor. İnternet gerekli.",Toast.LENGTH_LONG).show()
                    thread {
                        var completed=0
                        var failed=0
                        try {
                            val surahs=loadBundledOrImportedQuran().getJSONArray("surahs")
                            val folder=java.io.File(filesDir,"quran_verse_audio").apply { mkdirs() }
                            for((surah,verse) in verses) {
                                var global=verse
                                for(i in 0 until surah-1) global+=surahs.getJSONObject(i).getJSONArray("verses").length()
                                for((_,reciter) in dailyDuaReciters) {
                                    val file=java.io.File(folder,"${reciter}_${global}.mp3")
                                    if(file.isFile && file.length()>1024L) { completed++; continue }
                                    val partial=java.io.File(folder,"${reciter}_${global}.part")
                                    try {
                                        val conn=java.net.URL("https://cdn.islamic.network/quran/audio/128/$reciter/$global.mp3")
                                            .openConnection().apply { connectTimeout=12000; readTimeout=20000 }
                                        var bytes=0L
                                        conn.getInputStream().use { input ->
                                            partial.outputStream().use { output ->
                                                val buffer=ByteArray(16384)
                                                while(true) {
                                                    val n=input.read(buffer)
                                                    if(n<0) break
                                                    bytes+=n
                                                    if(bytes>12L*1024L*1024L) error("Audio limit exceeded")
                                                    output.write(buffer,0,n)
                                                }
                                            }
                                        }
                                        if(bytes<1024L || !partial.renameTo(file)) error("Invalid audio")
                                        completed++
                                    } catch(_:Exception) { failed++; partial.delete() }
                                }
                            }
                        } catch(_:Exception) { failed+=26-completed-failed }
                        runOnUiThread {
                            Toast.makeText(this@MainActivity,
                                "Hafız kayıtları: $completed/26 hazır, $failed başarısız. Kaydedilenler internetsiz çalışır.",
                                Toast.LENGTH_LONG).show()
                        }
                    }
                })
                val duaFavorites=prefs.getStringSet("favorite_duas",emptySet())?.toMutableSet() ?: mutableSetOf()
                val categories=listOf("Tümü")+duas.map { it.first }.distinct()
                val category=Spinner(this).apply {
                    adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,categories)
                }
                root.addView(category)
                val search=EditText(this).apply {
                    hint="Dua veya konu ara"
                    setSingleLine(true)
                    setTextColor(white)
                    setHintTextColor(Color.LTGRAY)
                }
                root.addView(search)
                val onlyDuaFavorites=CheckBox(this).apply { text="Yalnızca favori dualarım"; setTextColor(gold) }
                root.addView(onlyDuaFavorites)
                val duaCountLabel=text("Toplam ${duas.size} dua ve zikir • tümü gösteriliyor",15f,gold)
                root.addView(duaCountLabel)
                root.addView(button("Bütün duaları göster • Filtreleri temizle") {
                    onlyDuaFavorites.isChecked=false
                    search.setText("")
                    category.setSelection(0)
                })
                val results=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
                root.addView(results)
                fun refreshDuas() {
                    results.removeAllViews()
                    val selected=categories[category.selectedItemPosition]
                    val query=search.text.toString().trim()
                    var matching=0
                    duas.filter { (group,title,body) ->
                        (!onlyDuaFavorites.isChecked || duaFavorites.contains(title)) &&
                        (selected=="Tümü" || selected==group) &&
                        (query.isEmpty() || title.contains(query,true) || body.contains(query,true) || group.contains(query,true))
                    }.forEach { (group,title,body) ->
                        matching++
                        results.addView(panel().apply {
                            addView(text(group.uppercase(Locale("tr","TR")),13f,gold))
                            addView(text(title,19f,white))
                            addView(text(body,17f,gold))
                            val localDuaAudio=duaAudioPath(title)
                            val hasLocalDuaAudio=localDuaAudio.isFile && localDuaAudio.length()>=1024L
                            val quranMatch=Regex("""\(([^)]*?)\s+(\d+):(\d+)\)""").find(title)
                            val quranSurahName=quranMatch?.groupValues?.get(1)?.trim()
                            val quranSurahNumber=when(quranSurahName) {
                                "Bakara" -> 2; "Tâhâ" -> 20; "A'râf" -> 7; "Âl-i İmrân" -> 3
                                "İbrâhîm" -> 14; "Furkân" -> 25; "Enbiyâ" -> 21
                                "Kasas" -> 28; "Haşr" -> 59; else -> null
                            }
                            val quranVerseNumber=quranMatch?.groupValues?.get(3)?.toIntOrNull()
                            val cachedVerse=if(quranSurahNumber!=null && quranVerseNumber!=null) {
                                try {
                                    val surahs=loadBundledOrImportedQuran().getJSONArray("surahs")
                                    var globalVerse=quranVerseNumber
                                    for(index in 0 until quranSurahNumber-1)
                                        globalVerse+=surahs.getJSONObject(index).getJSONArray("verses").length()
                                    val selectedReciter=dailyDuaReciters[dailyDuaReciter.selectedItemPosition].second
                                    java.io.File(filesDir,"quran_verse_audio/$selectedReciter" + "_$globalVerse.mp3")
                                } catch(_:Exception) { null }
                            } else null
                            val cachedVerseAvailable=cachedVerse?.let { it.isFile && it.length()>1024L } ?: false
                            addView(text(
                                if(hasLocalDuaAudio) "✓ Dua kaydı telefonda • internetsiz"
                                else if(cachedVerseAvailable) "✓ Seçilen hafızın ayeti telefonda • internetsiz"
                                else if(quranSurahNumber!=null) "♫ Hafız tilaveti • ilk dinlemede internet gerekli"
                                else "♫ Cihaz sesli okuması • hafız kaydı henüz yok",
                                13f,gold
                            ))
                            addView(button("▶ Duayı sesli dinle") {
                                val ayah=Regex("""\(([^)]*?)\s+(\d+):(\d+)\)""").find(title)
                                val surah=ayah?.groupValues?.get(1)?.trim()
                                val number=when(surah) {
                                    "Bakara" -> 2; "Tâhâ" -> 20; "A'râf" -> 7; "Âl-i İmrân" -> 3
                                    "İbrâhîm" -> 14; "Furkân" -> 25; "Enbiyâ" -> 21
                                    "Kasas" -> 28; "Haşr" -> 59; else -> null
                                }
                                val verse=ayah?.groupValues?.get(3)?.toIntOrNull()
                                if(hasLocalDuaAudio)
                                    playDailyDua(title,body)
                                else if(number!=null && verse!=null)
                                    playQuranVerse(number,verse,dailyDuaReciters[dailyDuaReciter.selectedItemPosition].second)
                                else playDailyDua(title,body)
                            })
                            addView(button("♫ Bu dua için ses kaydı seç") { selectDailyDua(title) })
                            addView(button("■ Dinlemeyi durdur") { stopTilavet(); stopReading() })
                            addView(button(if(duaFavorites.contains(title)) "★ Favorilerimden çıkar" else "☆ Favorilere ekle") {
                                if(!duaFavorites.add(title)) duaFavorites.remove(title)
                                prefs.edit().putStringSet("favorite_duas",duaFavorites.toSet()).apply()
                                refreshDuas()
                            })
                            addView(button("Paylaş") {
                                val send=Intent(Intent.ACTION_SEND).apply {
                                    type="text/plain"
                                    putExtra(Intent.EXTRA_TEXT,title+"\n"+body)
                                }
                                startActivity(Intent.createChooser(send,"Duayı paylaş"))
                            })
                            addView(button("Metni kopyala") {
                                val clipboard=getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText(title,body))
                                Toast.makeText(this@MainActivity,"Dua kopyalandı",Toast.LENGTH_SHORT).show()
                            })
                        })
                    }
                    duaCountLabel.text="Gösterilen: $matching / ${duas.size} dua ve zikir"
                    if(matching==0) results.addView(text("Bu filtreyle eşleşen dua bulunamadı.",15f))
                }
                onlyDuaFavorites.setOnCheckedChangeListener { _,_ -> refreshDuas() }
                category.onItemSelectedListener=object:android.widget.AdapterView.OnItemSelectedListener {
                    override fun onNothingSelected(parent:android.widget.AdapterView<*>?) {}
                    override fun onItemSelected(parent:android.widget.AdapterView<*>?,view:android.view.View?,position:Int,id:Long) { refreshDuas() }
                }
                search.addTextChangedListener(object:android.text.TextWatcher {
                    override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int) {}
                    override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) { refreshDuas() }
                    override fun afterTextChanged(s:android.text.Editable?) {}
                })
                refreshDuas()
                root.addView(text("Öncelik: telefona kaydedilmiş gerçek dua sesi. Kur’an ayetlerinde seçilen hafızın çevrim içi tilaveti kullanılır. Diğerlerinde cihazın sesli okuması devreye girer; bu hafız sesi değildir.",13f))
            }
            "Zikir" -> {
                zikrCount=prefs.getInt("zikr_count",0)
                root.addView(text("DİJİTAL TESBİH",23f,gold))
                root.addView(text("Hedef seç, zikrini say ve kaldığın yerden devam et.",15f))
                root.addView(button("▶ Sübhanallah sesli oku") { readAloud("Sübhânallâh",false) })
                root.addView(button("▶ Elhamdülillah sesli oku") { readAloud("Elhamdülillâh",false) })
                root.addView(button("▶ Allahu ekber sesli oku") { readAloud("Allâhu ekber",false) })
                root.addView(button("■ Okumayı durdur") { stopReading() })
                val goals=arrayOf("33","99","100","1000","Sınırsız")
                val selector=Spinner(this).apply {
                    adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,goals)
                    setSelection(prefs.getInt("zikr_goal_index",1).coerceIn(0,goals.lastIndex))
                }
                root.addView(selector)
                val counter=text("$zikrCount",56f,gold).apply { gravity=Gravity.CENTER }
                root.addView(panel().apply { addView(counter) })
                val progress=text("",17f,white).apply { gravity=Gravity.CENTER }
                root.addView(progress)
                val goalProgress=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply {
                    max=100
                    progressTintList=android.content.res.ColorStateList.valueOf(accent)
                    progressBackgroundTintList=android.content.res.ColorStateList.valueOf(Color.rgb(45,79,62))
                }
                root.addView(goalProgress,LinearLayout.LayoutParams(-1,(8*resources.displayMetrics.density).toInt()))
                fun refreshCounter() {
                    val target=goals[selector.selectedItemPosition].toIntOrNull()
                    progress.text=if(target==null) "Serbest zikir" else "Hedef: $target • Kalan: ${(target-zikrCount).coerceAtLeast(0)}"
                    counter.text="$zikrCount"
                    goalProgress.progress=if(target==null) 0 else ((zikrCount.toLong()*100L/target.coerceAtLeast(1)).coerceIn(0L,100L)).toInt()
                    goalProgress.visibility=if(target==null) android.view.View.GONE else android.view.View.VISIBLE
                }
                selector.onItemSelectedListener=object:android.widget.AdapterView.OnItemSelectedListener {
                    override fun onNothingSelected(parent:android.widget.AdapterView<*>?) {}
                    override fun onItemSelected(parent:android.widget.AdapterView<*>?,view:android.view.View?,position:Int,id:Long) {
                        prefs.edit().putInt("zikr_goal_index",position).apply()
                        refreshCounter()
                    }
                }
                root.addView(button("TESBİH +1") {
                    zikrCount++
                    prefs.edit().putInt("zikr_count",zikrCount).apply()
                    refreshCounter()
                    val target=goals[selector.selectedItemPosition].toIntOrNull()
                    if(target!=null && zikrCount==target) Toast.makeText(this,"Zikir hedefi tamamlandı",Toast.LENGTH_SHORT).show()
                })
                root.addView(button("Bir geri al") {
                    zikrCount=(zikrCount-1).coerceAtLeast(0)
                    prefs.edit().putInt("zikr_count",zikrCount).apply()
                    refreshCounter()
                })
                root.addView(button("Sayacı sıfırla") {
                    android.app.AlertDialog.Builder(this).setTitle("Zikir sayacı").setMessage("Sayaç sıfırlansın mı?")
                        .setNegativeButton("Vazgeç",null)
                        .setPositiveButton("Sıfırla") { _,_ ->
                            zikrCount=0; prefs.edit().putInt("zikr_count",0).apply(); refreshCounter()
                        }.show()
                })
                refreshCounter()
            }
            "99 İsim" -> {
                root.addView(text("ESMÂÜ'L HÜSNÂ",23f,gold))
                root.addView(text("Allah'ın güzel isimleri",16f))
                val names99=listOf(
                    "Er-Rahmân","Er-Rahîm","El-Melik","El-Kuddûs","Es-Selâm","El-Mü'min","El-Müheymin","El-Azîz","El-Cebbâr","El-Mütekebbir",
                    "El-Hâlık","El-Bâri'","El-Musavvir","El-Gaffâr","El-Kahhâr","El-Vehhâb","Er-Rezzâk","El-Fettâh","El-Alîm","El-Kâbıd",
                    "El-Bâsıt","El-Hâfıd","Er-Râfi'","El-Muizz","El-Müzill","Es-Semî'","El-Basîr","El-Hakem","El-Adl","El-Latîf",
                    "El-Habîr","El-Halîm","El-Azîm","El-Gafûr","Eş-Şekûr","El-Aliyy","El-Kebîr","El-Hafîz","El-Mukît","El-Hasîb",
                    "El-Celîl","El-Kerîm","Er-Rakîb","El-Mücîb","El-Vâsi'","El-Hakîm","El-Vedûd","El-Mecîd","El-Bâis","Eş-Şehîd",
                    "El-Hakk","El-Vekîl","El-Kaviyy","El-Metîn","El-Veliyy","El-Hamîd","El-Muhsî","El-Mübdi'","El-Muîd","El-Muhyî",
                    "El-Mümît","El-Hayy","El-Kayyûm","El-Vâcid","El-Mâcid","El-Vâhid","El-Ehad","Es-Samed","El-Kâdir","El-Muktedir",
                    "El-Mukaddim","El-Muahhir","El-Evvel","El-Âhir","Ez-Zâhir","El-Bâtın","El-Vâlî","El-Müteâlî","El-Berr","Et-Tevvâb",
                    "El-Müntakım","El-Afüvv","Er-Raûf","Mâlikü'l-Mülk","Zü'l-Celâli ve'l-İkrâm","El-Muksit","El-Câmi'","El-Ganiyy","El-Muğnî","El-Mâni'",
                    "Ed-Dârr","En-Nâfi'","En-Nûr","El-Hâdî","El-Bedî'","El-Bâkî","El-Vâris","Er-Reşîd","Es-Sabûr"
                )
                val nameMeanings=listOf(
                    "Rahmeti bütün varlıkları kuşatan",
                    "Merhameti sürekli olan",
                    "Mutlak hükümdar",
                    "Her türlü eksiklikten uzak",
                    "Esenlik veren",
                    "Güven veren",
                    "Gözetip koruyan",
                    "Üstün ve güçlü",
                    "Dilediğini yaptırmaya gücü yeten",
                    "Büyüklükte eşsiz",
                    "Yaratan",
                    "Örneksiz var eden",
                    "Şekil veren",
                    "Çok bağışlayan",
                    "Her şeye galip gelen",
                    "Karşılıksız bolca veren",
                    "Rızık veren",
                    "Kapıları açan",
                    "Her şeyi bilen",
                    "Daraltan",
                    "Genişleten",
                    "Alçaltan",
                    "Yükselten",
                    "İzzet veren",
                    "Zillete düşüren",
                    "Her şeyi işiten",
                    "Her şeyi gören",
                    "Hükmeden",
                    "Mutlak adalet sahibi",
                    "Lütuf ve incelik sahibi",
                    "Her şeyden haberdar",
                    "Cezalandırmada acele etmeyen",
                    "Azameti sonsuz",
                    "Bağışlaması bol",
                    "Az amele çok karşılık veren",
                    "Yüce olan",
                    "Büyüklüğü sınırsız",
                    "Koruyup gözeten",
                    "Her şeye güç ve rızık veren",
                    "Hesaba çeken",
                    "Ululuk sahibi",
                    "Cömertliği sonsuz",
                    "Her şeyi gözeten",
                    "Dualara karşılık veren",
                    "İlmi ve rahmeti geniş",
                    "Hikmetle hükmeden",
                    "Kullarını seven ve sevilen",
                    "Şanı yüce",
                    "Öldükten sonra dirilten",
                    "Her şeye şahit",
                    "Varlığı ve hükmü gerçek",
                    "Güvenilip dayanılan",
                    "Kudreti sonsuz",
                    "Çok sağlam ve dayanıklı",
                    "Dost ve yardımcı",
                    "Övgüye layık",
                    "Her şeyi sayıp bilen",
                    "İlk defa yaratan",
                    "Yeniden yaratan",
                    "Hayat veren",
                    "Ölümü yaratan",
                    "Diri ve ölümsüz",
                    "Her şeyi ayakta tutan",
                    "Her istediğini bulan",
                    "Şanı ve keremi büyük",
                    "Bir ve tek",
                    "Eşi ve benzeri olmayan tek",
                    "Hiçbir şeye muhtaç olmayan",
                    "Her şeye gücü yeten",
                    "Kudreti sınırsız",
                    "Öne alan",
                    "Geri bırakan",
                    "Başlangıcı olmayan",
                    "Sonu olmayan",
                    "Varlığı açık olan",
                    "Mahiyeti gizli olan",
                    "İdare eden",
                    "Her şeyden yüce",
                    "İyilik ve ihsan sahibi",
                    "Tövbeleri kabul eden",
                    "Zalimlerden intikam alan",
                    "Affı bol olan",
                    "Çok şefkatli",
                    "Mülkün gerçek sahibi",
                    "Azamet ve ikram sahibi",
                    "Adaletle davranan",
                    "Bir araya getiren",
                    "Hiçbir şeye muhtaç olmayan",
                    "Zengin eden",
                    "Engelleyen",
                    "Zararın yaratılmasına hükmeden",
                    "Faydayı yaratan",
                    "Aydınlatan",
                    "Doğru yola ileten",
                    "Eşsiz yaratan",
                    "Varlığı sürekli olan",
                    "Her şeyin gerçek mirasçısı",
                    "Doğru yolu gösteren",
                    "Çok sabırlı"                )
                root.addView(text("99 isim için kısa Türkçe anlamlar eklenmiştir. Açıklamalar özet niteliğindedir; dinî kaynak doğrulaması yayımdan önce yapılacaktır.",14f))
                val saved=prefs.getStringSet("favorite_names",emptySet())?.toMutableSet() ?: mutableSetOf()
                val onlyFavorites=CheckBox(this).apply {
                    text="Yalnızca favorilerim"
                    setTextColor(gold)
                }
                root.addView(onlyFavorites)
                val search=EditText(this).apply {
                    hint="İsim veya sıra numarası ara..."
                    setSingleLine(true)
                    setTextColor(white)
                    setHintTextColor(Color.LTGRAY)
                }
                root.addView(search)
                val results=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
                root.addView(results)
                fun fill(query:String) {
                    results.removeAllViews()
                    var count=0
                    names99.forEachIndexed { index,name ->
                        if((!onlyFavorites.isChecked || saved.contains(index.toString())) &&
                            (query.isBlank() || name.contains(query,true) || nameMeanings.getOrNull(index)?.contains(query,true)==true || (index+1).toString()==query.trim())) {
                            count++
                            val card=panel()
                            card.addView(text("${index+1}. $name",19f,gold))
                            nameMeanings.getOrNull(index)?.let { card.addView(text(it,15f,white)) }
                            val buttons=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
                            buttons.addView(button(if(saved.contains(index.toString())) "★ Favoride" else "☆ Favoriye ekle") {
                                val key=index.toString()
                                if(!saved.add(key)) saved.remove(key)
                                prefs.edit().putStringSet("favorite_names",saved.toSet()).apply()
                                fill(search.text.toString())
                            },LinearLayout.LayoutParams(0,-2,1f))
                            buttons.addView(button("Kopyala") {
                                val clipboard=getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("Esmaül Hüsna",name))
                                Toast.makeText(this@MainActivity,"İsim kopyalandı",Toast.LENGTH_SHORT).show()
                            },LinearLayout.LayoutParams(0,-2,1f))
                            buttons.addView(button("Paylaş") {
                                val send=Intent(Intent.ACTION_SEND).apply {
                                    type="text/plain"
                                    putExtra(Intent.EXTRA_TEXT,name+" — "+(nameMeanings.getOrNull(index) ?: ""))
                                }
                                startActivity(Intent.createChooser(send,"İsmi paylaş"))
                            },LinearLayout.LayoutParams(0,-2,1f))
                            card.addView(buttons)
                            results.addView(card)
                        }
                    }
                    if(count==0) results.addView(text("Bu filtreyle eşleşen isim bulunamadı.",15f))
                }
                onlyFavorites.setOnCheckedChangeListener { _,_ -> fill(search.text.toString()) }
                search.addTextChangedListener(object:android.text.TextWatcher {
                    override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int) {}
                    override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) { fill(s?.toString() ?: "") }
                    override fun afterTextChanged(s:android.text.Editable?) {}
                })
                fill("")
                root.addView(text("İsimlerin farklı kaynaklarda yazılışları değişebilir. Açıklamalar ve kaynak denetimi ayrıca eklenecek.",13f))
            }
        }
    }
    private fun downloadAdhan(raw:String,key:String) {
        val source=raw.trim()
        val uri=Uri.parse(source)
        if(uri.scheme!="https" || uri.host.isNullOrBlank()) {
            Toast.makeText(this,"Geçerli bir HTTPS MP3 bağlantısı girin",Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this,"Ezan kaydı indiriliyor…",Toast.LENGTH_SHORT).show()
        thread {
            val destination=java.io.File(filesDir,"adhan_${key}.mp3")
            val temp=java.io.File(filesDir,"adhan_${key}.download")
            try {
                val conn=URL(source).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout=12000
                conn.readTimeout=20000
                conn.instanceFollowRedirects=true
                try {
                    if(conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
                    val size=conn.contentLengthLong
                    if(size>30L*1024*1024) error("Dosya 30 MB sınırını aşıyor")
                    var count=0L
                    conn.inputStream.use { input ->
                        temp.outputStream().use { output ->
                            val buffer=ByteArray(8192)
                            while(true) {
                                val n=input.read(buffer)
                                if(n<0) break
                                count+=n
                                if(count>30L*1024*1024) error("Dosya 30 MB sınırını aşıyor")
                                output.write(buffer,0,n)
                            }
                        }
                    }
                    if(count<1024) error("Ses dosyası boş veya geçersiz")
                    val probe=android.media.MediaMetadataRetriever()
                    try {
                        probe.setDataSource(temp.absolutePath)
                        if(probe.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION).isNullOrBlank())
                            error("Dosya geçerli bir ses kaydı değil")
                    } finally { probe.release() }
                    if(destination.exists()) destination.delete()
                    if(!temp.renameTo(destination)) error("Ses dosyası kaydedilemedi")
                    prefs.edit().putString(key,Uri.fromFile(destination).toString()).putInt("mode",if(key=="full") 0 else 1).apply()
                    runOnUiThread {
                        Toast.makeText(this,"Ezan indirildi. Çevrim dışı kullanılabilir.",Toast.LENGTH_LONG).show()
                    }
                } finally { conn.disconnect() }
            } catch(e:Exception) {
                temp.delete()
                runOnUiThread {
                    Toast.makeText(this,"Ezan indirilemedi: ${e.message}",Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    private fun stopAudioPreview() {
        try { audioPreview?.stop() } catch(_:Exception) {}
        try { audioPreview?.release() } catch(_:Exception) {}
        audioPreview=null
    }
    private fun previewAudio(key:String) {
        stopAudioPreview()
        val value=prefs.getString(key,null)
        if(value.isNullOrBlank()) {
            Toast.makeText(this,"Önce bu ezan için ses dosyası seçin",Toast.LENGTH_LONG).show()
            return
        }
        try {
            val player=android.media.MediaPlayer()
            audioPreview=player
            player.setDataSource(this,Uri.parse(value))
            player.setOnPreparedListener { it.start() }
            player.setOnCompletionListener { stopAudioPreview() }
            player.setOnErrorListener { _,_,_ ->
                stopAudioPreview()
                Toast.makeText(this,"Ses oynatılamadı",Toast.LENGTH_SHORT).show()
                true
            }
            player.prepareAsync()
        } catch(_:Exception) {
            stopAudioPreview()
            Toast.makeText(this,"Ses dosyası açılamadı. Dosyayı yeniden seçin.",Toast.LENGTH_LONG).show()
        }
    }
    private fun duaAudioPath(title:String):java.io.File {
        val hash=java.security.MessageDigest.getInstance("SHA-256")
            .digest(title.toByteArray(Charsets.UTF_8)).take(12)
            .joinToString("") { "%02x".format(it) }
        return java.io.File(java.io.File(filesDir,"daily_dua_audio"),"$hash.audio")
    }
    private fun showHisnAudioCatalog() {
        try {
            val raw=assets.open("hisn_audio_catalog.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
            val items=org.json.JSONArray(raw)
            val titles=(0 until items.length()).map { i ->
                val item=items.getJSONObject(i)
                "${i+1}. ${item.getString("category")} — ${item.getString("text").take(65)}"
            }.toTypedArray()
            android.app.AlertDialog.Builder(this)
                .setTitle("Sesli Dua Arşivi • ${items.length()} kayıt")
                .setItems(titles) { _,index ->
                    val item=items.getJSONObject(index)
                    val arabic=item.getString("text")
                    val audio=item.getString("audio")
                    android.app.AlertDialog.Builder(this)
                        .setTitle(item.getString("category"))
                        .setMessage(arabic)
                        .setPositiveButton("▶ Gerçek sesi dinle") { _,_ ->
                            stopReading()
                            stopTilavet()
                            try {
                                val player=android.media.MediaPlayer()
                                tilavetPlayer=player
                                player.setDataSource("https://raw.githubusercontent.com/YousefAsalya/Islamic-Pro-azkar-API/main/audio/"+audio)
                                player.setOnPreparedListener { if(tilavetPlayer===it) it.start() }
                                player.setOnCompletionListener { if(tilavetPlayer===it) stopTilavet() }
                                player.setOnErrorListener { _,_,_ ->
                                    stopTilavet()
                                    Toast.makeText(this,"Kayıt açılamadı; internet bağlantısını kontrol edin.",Toast.LENGTH_LONG).show()
                                    true
                                }
                                player.prepareAsync()
                            } catch(_:Exception) {
                                stopTilavet()
                                Toast.makeText(this,"Ses kaydı açılamadı.",Toast.LENGTH_LONG).show()
                            }
                        }
                        .setNegativeButton("Kapat",null)
                        .show()
                }
                .setNegativeButton("Kapat",null)
                .show()
        } catch(_:Exception) {
            Toast.makeText(this,"Sesli dua arşivi okunamadı.",Toast.LENGTH_LONG).show()
        }
    }

    private fun playDailyDua(title:String,body:String) {
        val file=duaAudioPath(title)
        if(!file.isFile || file.length()<1024L) {
            // Only an exact, manually verified text-to-audio match is streamed.
            // Do not bundle or cache third-party recordings without permission.
            val verifiedUrl=when(title) {
                "Uyanınca" -> "https://raw.githubusercontent.com/YousefAsalya/Islamic-Pro-azkar-API/main/audio/1.mp3"
                "Uyumadan önce" -> "https://raw.githubusercontent.com/YousefAsalya/Islamic-Pro-azkar-API/main/audio/105.mp3"
                "Evden çıkarken" -> "https://raw.githubusercontent.com/YousefAsalya/Islamic-Pro-azkar-API/main/audio/16.mp3"
                "Eve girerken" -> "https://raw.githubusercontent.com/YousefAsalya/Islamic-Pro-azkar-API/main/audio/18.mp3"
                else -> null
            }
            if(verifiedUrl!=null) {
                stopReading()
                stopTilavet()
                try {
                    val player=android.media.MediaPlayer()
                    tilavetPlayer=player
                    player.setDataSource(verifiedUrl)
                    player.setOnPreparedListener { if(tilavetPlayer===it) it.start() }
                    player.setOnCompletionListener { if(tilavetPlayer===it) stopTilavet() }
                    player.setOnErrorListener { _,_,_ ->
                        stopTilavet()
                        Toast.makeText(this,"Ses kaydı açılamadı; internet bağlantısını kontrol edin.",Toast.LENGTH_LONG).show()
                        true
                    }
                    player.prepareAsync()
                } catch(_:Exception) {
                    stopTilavet()
                    Toast.makeText(this,"Ses kaydı açılamadı.",Toast.LENGTH_LONG).show()
                }
                return
            }
            stopTilavet()
            stopReading()
            Toast.makeText(this,"Bu dua için doğrulanmış hafız kaydı henüz yok; cihaz sesli okuması deneniyor.",Toast.LENGTH_LONG).show()
            readAloud(body,false)
            return
        }
        stopReading()
        stopTilavet()
        try {
            val player=android.media.MediaPlayer()
            tilavetPlayer=player
            player.setDataSource(file.absolutePath)
            player.setOnPreparedListener { if(tilavetPlayer===it) it.start() }
            player.setOnCompletionListener { if(tilavetPlayer===it) stopTilavet() }
            player.setOnErrorListener { _,_,_ -> stopTilavet(); true }
            player.prepareAsync()
        } catch(e:Exception) {
            stopTilavet()
            Toast.makeText(this,"Dua sesi açılamadı: ${e.message}",Toast.LENGTH_LONG).show()
        }
    }
    private fun selectDailyDua(title:String) {
        prefs.edit().putString("dua_audio_pending",title).apply()
        pickAudio(905)
    }
    fun pickAudio(code:Int) { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type="audio/*"; addCategory(Intent.CATEGORY_OPENABLE) },code) }
    @Deprecated("Activity result API compatibility")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode==905) {
            val title=prefs.getString("dua_audio_pending",null)
            prefs.edit().remove("dua_audio_pending").apply()
            if(resultCode==RESULT_OK && title!=null && data?.data!=null) {
                val source=data.data!!
                thread {
                    val target=duaAudioPath(title)
                    val temp=java.io.File(target.absolutePath+".part")
                    try {
                        target.parentFile?.mkdirs()
                        var count=0L
                        contentResolver.openInputStream(source)?.use { input ->
                            temp.outputStream().use { output ->
                                val buf=ByteArray(16384)
                                while(true) {
                                    val n=input.read(buf)
                                    if(n<0) break
                                    count+=n
                                    if(count>30L*1024L*1024L) error("Ses dosyası 30 MB sınırını aşıyor")
                                    output.write(buf,0,n)
                                }
                            }
                        } ?: error("Ses dosyası açılamadı")
                        if(count<1024L) error("Ses dosyası boş")
                        val metadata=android.media.MediaMetadataRetriever()
                        try {
                            metadata.setDataSource(temp.absolutePath)
                            if(metadata.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION).isNullOrBlank()) error("Geçersiz ses kaydı")
                        } finally { metadata.release() }
                        if(target.exists()) target.delete()
                        if(!temp.renameTo(target)) error("Ses dosyası kaydedilemedi")
                        runOnUiThread { Toast.makeText(this,"Dua kaydı telefona kopyalandı; internetsiz dinlenebilir",Toast.LENGTH_LONG).show() }
                    } catch(e:Exception) {
                        temp.delete()
                        runOnUiThread { Toast.makeText(this,"Dua kaydı eklenemedi: ${e.message}",Toast.LENGTH_LONG).show() }
                    }
                }
            }
            return
        }
        if(resultCode==RESULT_OK && requestCode==104 && data?.data!=null) {
            try {
                val source=java.io.File(filesDir,"quran_import.json")
                if(!source.exists()) throw IllegalStateException("İçe aktarılmış dosya bulunamadı")
                contentResolver.openOutputStream(data.data!!,"w")?.use { output ->
                    source.inputStream().use { input -> input.copyTo(output) }
                } ?: throw IllegalStateException("Hedef dosya açılamadı")
                Toast.makeText(this,"Kur'an JSON yedeği kaydedildi",Toast.LENGTH_LONG).show()
            } catch(e:Exception) {
                Toast.makeText(this,"Dışa aktarma başarısız: ${e.message}",Toast.LENGTH_LONG).show()
            }
            return
        }
        if(resultCode==RESULT_OK && requestCode==pickQuran && data?.data!=null) {
            try {
                val bytes=contentResolver.openInputStream(data.data!!)?.use { stream ->
                    val output=java.io.ByteArrayOutputStream()
                    val buffer=ByteArray(8192)
                    while(true) {
                        val n=stream.read(buffer)
                        if(n<0) break
                        if(output.size()+n>8*1024*1024) throw IllegalArgumentException("Dosya 8 MB sınırını aşıyor")
                        output.write(buffer,0,n)
                    }
                    output.toByteArray()
                } ?: throw IllegalArgumentException("Dosya açılamadı")
                val raw=bytes.toString(Charsets.UTF_8)
                val metadata=JSONObject(raw)
                if(metadata.has("source") && metadata.getString("source").length>500) throw IllegalArgumentException("Kaynak bilgisi çok uzun")
                if(metadata.has("translation_source") && metadata.getString("translation_source").length>500) throw IllegalArgumentException("Meal kaynak bilgisi çok uzun")
                val surahs=metadata.getJSONArray("surahs")
                if(surahs.length()!=114) throw IllegalArgumentException("Tam 114 sûre gerekli")
                val canonicalAyahCounts=intArrayOf(
                    7,286,200,176,120,165,206,75,129,109,123,111,43,52,99,128,111,110,98,135,
                    112,78,118,64,77,227,93,88,69,60,34,30,73,54,45,83,182,88,75,85,
                    54,53,89,59,37,35,38,29,18,45,60,49,62,55,78,96,29,22,24,13,
                    14,11,11,18,12,12,30,52,52,44,28,28,20,56,40,31,50,40,46,42,
                    29,19,36,25,22,17,19,26,30,20,15,21,11,8,8,19,5,8,8,11,
                    11,8,3,9,5,4,7,3,6,3,5,4,5,6
                )

                for(i in 0 until 114) {
                    val surah=surahs.getJSONObject(i)
                    if(surah.getString("name").isBlank()) throw IllegalArgumentException("Boş sûre adı")
                    val verses=surah.getJSONArray("verses")
                    if(verses.length()!=canonicalAyahCounts[i]) throw IllegalArgumentException("${i+1}. sûrede beklenen ${canonicalAyahCounts[i]} ayet, bulunan ${verses.length()}")
                    val translations=surah.optJSONArray("translations")
                    if(translations!=null) {
                        if(translations.length()!=verses.length()) throw IllegalArgumentException("Meal ayet sayısı uyuşmuyor")
                        for(j in 0 until translations.length()) {
                            val translation=translations.getString(j)
                            if(translation.isBlank() || translation.length>5000) throw IllegalArgumentException("Meal metni geçersiz")
                        }
                    }
                    for(j in 0 until verses.length()) {
                        val verse=verses.getString(j)
                        if(verse.isBlank() || verse.length>3000 || !verse.any { it in '؀'..'ۿ' }) throw IllegalArgumentException("Arapça ayet metni geçersiz")
                    }
                }
                val file=android.util.AtomicFile(java.io.File(filesDir,"quran_import.json"))
                val output=file.startWrite()
                try {
                    output.write(bytes)
                    file.finishWrite(output)
                } catch(e:Exception) {
                    file.failWrite(output)
                    throw e
                }
                Toast.makeText(this,"114 sûre içe aktarıldı",Toast.LENGTH_LONG).show()
                section="İçe Aktarılan Kur'an"
                render()
            } catch(e:Exception) {
                Toast.makeText(this,"İçe aktarma başarısız: ${e.message}",Toast.LENGTH_LONG).show()
            }
            return
        }
        if(resultCode==RESULT_OK && data?.data!=null && (requestCode==pickFull || requestCode==pickShort)) {
            val uri=data.data!!
            contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)
            prefs.edit().putString(if(requestCode==pickFull) "full" else "short",uri.toString()).putInt("mode",if(requestCode==pickFull) 0 else 1).apply()
            Toast.makeText(this,"Ses kaydedildi",Toast.LENGTH_SHORT).show()
        }
    }
    private fun qiblaBearing():Double {
        val lat1=Math.toRadians(latitude)
        val lat2=Math.toRadians(21.4225)
        val delta=Math.toRadians(39.8262-longitude)
        return (Math.toDegrees(atan2(sin(delta),cos(lat1)*tan(lat2)-sin(lat1)*cos(delta)))+360)%360
    }
    private fun findCity() {
        val input=EditText(this).apply {
            hint="Örnek: İstanbul, Ankara, Berlin"
            setSingleLine(true)
            setPadding(28,24,28,24)
        }
        AlertDialog.Builder(this).setTitle("Şehir adıyla konum bul").setView(input)
            .setNegativeButton("Vazgeç",null)
            .setPositiveButton("Ara") { _,_ ->
                val city=input.text.toString().trim()
                if(city.length<2) {
                    Toast.makeText(this,"En az iki harf girin",Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this,"Şehir aranıyor: $city",Toast.LENGTH_SHORT).show()
                    thread {
                        try {
                            val coder=android.location.Geocoder(this,Locale("tr","TR"))
                            @Suppress("DEPRECATION")
                            val matches=coder.getFromLocationName(city,5) ?: emptyList()
                            runOnUiThread {
                                if(matches.isEmpty()) Toast.makeText(this,"Şehir bulunamadı; internet veya konum servisini kontrol edin",Toast.LENGTH_LONG).show()
                                else {
                                    val labels=matches.map { address ->
                                        listOfNotNull(address.locality,address.adminArea,address.countryName).distinct().joinToString(", ")
                                            .ifBlank { "%.4f, %.4f".format(Locale.US,address.latitude,address.longitude) }
                                    }.toTypedArray()
                                    AlertDialog.Builder(this).setTitle("Konumu seç")
                                        .setItems(labels) { _,index ->
                                            val selected=matches[index]
                                            latitude=selected.latitude
                                            longitude=selected.longitude
                                            locationLabel=labels[index]
                                            prefs.edit().putLong("lat",java.lang.Double.doubleToRawLongBits(latitude))
                                                .putLong("lon",java.lang.Double.doubleToRawLongBits(longitude))
                                                .putString("place",locationLabel).apply()
                                            PrayerWidget.refreshAll(this)
                                            section="Vakitler"
                                            render()
                                        }.setNegativeButton("Vazgeç",null).show()
                                }
                            }
                        } catch(e:Exception) {
                            runOnUiThread { Toast.makeText(this,"Şehir arama başarısız: ${e.message}",Toast.LENGTH_LONG).show() }
                        }
                    }
                }
            }.show()
    }
    private fun locate() {
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION),21); return
        }
        try {
            val lm=getSystemService(LOCATION_SERVICE) as LocationManager
            val loc=lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            if(loc==null) { Toast.makeText(this,"Konum henüz hazır değil. GPS'i açıp tekrar deneyin.",Toast.LENGTH_LONG).show(); return }
            latitude=loc.latitude; longitude=loc.longitude; locationLabel="Konum: %.4f, %.4f".format(Locale.US,latitude,longitude)
            prefs.edit().putLong("lat",java.lang.Double.doubleToRawLongBits(latitude)).putLong("lon",java.lang.Double.doubleToRawLongBits(longitude)).putString("place",locationLabel).apply()
            heading.text=locationLabel; loadTimes()
        } catch(e:Exception) { Toast.makeText(this,"Konum alınamadı: ${e.message}",Toast.LENGTH_LONG).show() }
    }
    override fun onRequestPermissionsResult(requestCode:Int,permissions:Array<out String>,grantResults:IntArray) {
        super.onRequestPermissionsResult(requestCode,permissions,grantResults)
        if(requestCode==21 && grantResults.any { it==PackageManager.PERMISSION_GRANTED }) locate()
    }
    // Standard 30 juz starting ayahs (surah number, 1-based ayah number).
    private val juzStarts=listOf(
        1 to 1,2 to 142,2 to 253,3 to 93,4 to 24,4 to 148,
        5 to 82,6 to 111,7 to 88,8 to 41,9 to 93,11 to 6,
        12 to 53,15 to 1,17 to 1,18 to 75,21 to 1,23 to 1,
        25 to 21,27 to 56,29 to 46,33 to 31,36 to 28,39 to 32,
        41 to 47,46 to 1,51 to 31,58 to 1,67 to 1,78 to 1
    )
    private fun loadBundledOrImportedQuran():JSONObject {
        val imported=java.io.File(filesDir,"quran_import.json")
        val raw=if(imported.exists()) imported.readText(Charsets.UTF_8)
            else assets.open("quran_arabic.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        return JSONObject(raw)
    }
    private fun loadTimes() {
        val date=LocalDate.now().format(DateTimeFormatter.ofPattern("dd-MM-yyyy"))
        val cacheKey="times_${LocalDate.now()}_${latitude}_${longitude}"
        if(section!="Vakitler") return
        val generation=requestGeneration
        val requestedLat=latitude
        val requestedLon=longitude
        val cached=prefs.getString(cacheKey,null)
        if(cached!=null) { try {
            val obj=JSONObject(cached)
            if(!keys.all { key -> try { LocalTime.parse(obj.optString(key).take(5)); true } catch(_:Exception) { false } }) throw IllegalStateException("Bozuk vakit kaydı")
            showTimes(obj)
            return
        } catch(_:Exception) { prefs.edit().remove(cacheKey).apply() } }
        timesBox.removeAllViews(); timesBox.addView(text("Vakitler yükleniyor…",16f))
        thread {
            try {
                val url=URL("https://api.aladhan.com/v1/timings/$date?latitude=$requestedLat&longitude=$requestedLon&method=13&school=0")
                val connection=(url.openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout=10000
                    readTimeout=10000
                    requestMethod="GET"
                }
                val timings=try {
                    if(connection.responseCode !in 200..299) throw IllegalStateException("Vakit servisi HTTP hatası")
                    JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                        .getJSONObject("data").getJSONObject("timings")
                } finally {
                    connection.disconnect()
                }
                if(!keys.all { key -> try { LocalTime.parse(timings.optString(key).take(5)); true } catch(_:Exception) { false } }) throw IllegalStateException("Eksik vakit verisi")
                prefs.edit().putString(cacheKey,timings.toString()).apply()
                runOnUiThread { if(section=="Vakitler" && generation==requestGeneration && latitude==requestedLat && longitude==requestedLon) showTimes(timings) }
            } catch(e:Exception) { runOnUiThread { if(section=="Vakitler" && generation==requestGeneration && latitude==requestedLat && longitude==requestedLon) { timesBox.removeAllViews(); timesBox.addView(text("Vakitler alınamadı. İnternet bağlantısını kontrol edin.",15f)) } } }
        }
    }
    private fun showTimes(obj:JSONObject) {
        timesBox.removeAllViews()
        val now=LocalTime.now()
        var next=""
        val schedule=mutableListOf<Pair<String,LocalDateTime>>()
        keys.forEachIndexed { index,key ->
            val raw=obj.optString(key,"--:--").take(5)
            timesBox.addView(panel().apply {
                val line=LinearLayout(this@MainActivity).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL }
                line.addView(text(names[index],19f,if(index==4) gold else white),LinearLayout.LayoutParams(0,-2,1f))
                line.addView(text(raw,22f,gold))
                addView(line)
            })
            try {
                val at=LocalDateTime.of(LocalDate.now(),LocalTime.parse(raw))
                if(next.isEmpty() && at.toLocalTime().isAfter(now)) next=names[index]
                if(index!=1) schedule.add(names[index] to at)
            } catch(_:Exception) {}
        }
        val upcoming=schedule.firstOrNull { it.second.isAfter(LocalDateTime.now()) }
        if(upcoming!=null) {
            timerPrayer=upcoming.first
            timerTarget=upcoming.second
        } else {
            timerPrayer="Yarın İmsak"
            val tomorrow=LocalDate.now().plusDays(1)
            val tomorrowKey="times_${tomorrow}_${latitude}_${longitude}"
            val savedTomorrow=prefs.getString(tomorrowKey,null)
            timerTarget=try {
                val fajr=if(savedTomorrow!=null) JSONObject(savedTomorrow).getString("Fajr").take(5)
                    else obj.optString("Fajr","05:00").take(5)
                LocalDateTime.of(tomorrow,LocalTime.parse(fajr))
            } catch(_:Exception) { null }
            if(savedTomorrow==null) {
                val expectedGeneration=requestGeneration
                val lat=latitude
                val lon=longitude
                thread {
                    try {
                        val date=tomorrow.format(DateTimeFormatter.ofPattern("dd-MM-yyyy"))
                        val url=URL("https://api.aladhan.com/v1/timings/$date?latitude=$lat&longitude=$lon&method=13&school=0")
                        val connection=url.openConnection().apply { connectTimeout=10000; readTimeout=10000 }
                        val json=JSONObject(connection.getInputStream().bufferedReader().use { it.readText() })
                        val timing=json.getJSONObject("data").getJSONObject("timings")
                        val exact=LocalDateTime.of(tomorrow,LocalTime.parse(timing.getString("Fajr").take(5)))
                        prefs.edit().putString(tomorrowKey,timing.toString()).apply()
                        runOnUiThread {
                            if(section=="Vakitler" && requestGeneration==expectedGeneration && latitude==lat && longitude==lon) {
                                timerTarget=exact
                                timerPrayer="Yarın İmsak"
                                timerHandler.removeCallbacks(timerTick)
                                timerHandler.post(timerTick)
                            }
                        }
                    } catch(_:Exception) {}
                }
            }
        }
        timerHandler.removeCallbacks(timerTick)
        timerHandler.post(timerTick)
        AlarmScheduler.schedule(this,schedule)
        AlarmScheduler.scheduleRefresh(this)
        PrayerWidget.refreshAll(this)
    }
    override fun onResume() {
        super.onResume()
        if(section=="Vakitler" && timerTarget!=null) { timerHandler.removeCallbacks(timerTick); timerHandler.post(timerTick) }
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.also { sensorManager.registerListener(this,it,SensorManager.SENSOR_DELAY_UI) }
        sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.also { sensorManager.registerListener(this,it,SensorManager.SENSOR_DELAY_UI) }
        if(section=="Kıble" && sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)==null) compass.text="Bu cihazda pusula sensörü bulunamadı"
    }
    override fun onPause() { stopTilavet(); stopReading(); stopAudioPreview(); timerHandler.removeCallbacks(timerTick); sensorManager.unregisterListener(this); super.onPause() }
    override fun onDestroy() {
        stopTilavet()
        stopReading()
        speech?.shutdown()
        speech=null
        super.onDestroy()
    }
    override fun onSensorChanged(event:SensorEvent) {
        if(event.sensor.type==Sensor.TYPE_ACCELEROMETER) gravity=event.values.clone()
        if(event.sensor.type==Sensor.TYPE_MAGNETIC_FIELD) magnetic=event.values.clone()
        val g=gravity ?: return; val m=magnetic ?: return
        val rotation=FloatArray(9); val orientation=FloatArray(3)
        if(!SensorManager.getRotationMatrix(rotation,null,g,m)) return
        SensorManager.getOrientation(rotation,orientation)
        val magneticAzimuth=(Math.toDegrees(orientation[0].toDouble())+360)%360
        val declination=android.hardware.GeomagneticField(latitude.toFloat(),longitude.toFloat(),0f,System.currentTimeMillis()).declination.toDouble()
        val azimuth=(magneticAzimuth+declination+360)%360
        val lat1=Math.toRadians(latitude); val lonDiff=Math.toRadians(39.8262-longitude)
        val lat2=Math.toRadians(21.4225)
        bearing=(Math.toDegrees(atan2(sin(lonDiff),cos(lat1)*tan(lat2)-sin(lat1)*cos(lonDiff)))+360)%360
        val relative=(bearing-azimuth+360)%360
        val shortestTurn=if(relative>180) relative-360 else relative
        if(section!="Kıble") return
        dial?.angle=relative.toFloat()
        compass.text=if(kotlin.math.abs(shortestTurn)<5.0) "🕋 Kıble yönündesiniz" else if(shortestTurn<0) "🕋 Sola dönün" else "🕋 Sağa dönün"
    }
    override fun onAccuracyChanged(sensor:Sensor?,accuracy:Int) {}
}
 
class QiblaDial(context:Context):android.view.View(context) {
    var angle:Float=0f
        set(value) { field=value; invalidate() }
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas:Canvas) {
        super.onDraw(canvas)
        val cx=width/2f
        val cy=height/2f
        val radius=minOf(width,height)*0.40f
        paint.style=Paint.Style.STROKE
        paint.strokeWidth=5f
        paint.color=Color.rgb(205,172,104)
        canvas.drawCircle(cx,cy,radius,paint)
        paint.style=Paint.Style.FILL
        paint.textAlign=Paint.Align.CENTER
        paint.typeface=Typeface.DEFAULT_BOLD
        paint.textSize=radius*0.17f
        paint.color=Color.WHITE
        canvas.drawText("K",cx,cy-radius+radius*0.20f,paint)
        canvas.drawText("G",cx,cy+radius-radius*0.08f,paint)
        canvas.drawText("D",cx+radius-radius*0.12f,cy+radius*0.06f,paint)
        canvas.drawText("B",cx-radius+radius*0.12f,cy+radius*0.06f,paint)
        canvas.save()
        canvas.rotate(angle,cx,cy)
        val path=android.graphics.Path().apply {
            moveTo(cx,cy-radius*0.82f)
            lineTo(cx-radius*0.16f,cy+radius*0.25f)
            lineTo(cx,cy+radius*0.09f)
            lineTo(cx+radius*0.16f,cy+radius*0.25f)
            close()
        }
        paint.color=Color.rgb(222,184,98)
        canvas.drawPath(path,paint)
        canvas.restore()
        paint.textSize=radius*0.14f
        canvas.drawText("KIBLE",cx,cy+radius*0.65f,paint)
    }
}

object AlarmScheduler {
    private val prayerIds=mapOf("İmsak" to 10,"Öğle" to 11,"İkindi" to 12,"Akşam" to 13,"Yatsı" to 14)
    fun cancelPrayerAlarms(context:Context) {
        val manager=context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val flags=PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        for((name,id) in prayerIds) {
            val pending=PendingIntent.getBroadcast(context,id,Intent(context,AlarmReceiver::class.java).putExtra("name",name),flags)
            manager.cancel(pending)
            pending.cancel()
        }
    }
    fun schedule(context:Context, times:List<Pair<String,LocalDateTime>>) {
        val manager=context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val flags=PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val prefs=context.getSharedPreferences(PREF,Context.MODE_PRIVATE)
        if(!prefs.getBoolean("alarm_id_migrated_v2",false)) {
            for(oldId in 0..4) {
                val old=PendingIntent.getBroadcast(context,oldId,Intent(context,AlarmReceiver::class.java),flags)
                manager.cancel(old)
                old.cancel()
            }
            prefs.edit().putBoolean("alarm_id_migrated_v2",true).apply()
        }
        if(!prefs.getBoolean("app_active",true)) { cancelPrayerAlarms(context); return }
        val byName=times.toMap()
        for((name,id) in prayerIds) {
            val intent=Intent(context,AlarmReceiver::class.java).putExtra("name",name)
            val pending=PendingIntent.getBroadcast(context,id,intent,flags)
            val time=byName[name]
            if(!prefs.getBoolean("alarm_$name",true) || time==null || !time.isAfter(LocalDateTime.now())) {
                manager.cancel(pending)
                continue
            }
            val epoch=time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            if(Build.VERSION.SDK_INT>=31 && !manager.canScheduleExactAlarms()) {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,epoch,pending)
            } else try {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,epoch,pending)
            } catch(_:SecurityException) {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,epoch,pending)
            }
        }
    }
    fun scheduleRefreshRetry(context:Context) {
        val manager=context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val retryAt=System.currentTimeMillis()+30L*60L*1000L
        val pending=PendingIntent.getBroadcast(context,500,Intent(context,RefreshReceiver::class.java).setAction(REFRESH_ACTION),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,retryAt,pending)
    }
    fun scheduleRefresh(context:Context) {
        val manager=context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val next=LocalDate.now().plusDays(1).atTime(0,5).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val pending=PendingIntent.getBroadcast(context,500,Intent(context,RefreshReceiver::class.java).setAction(REFRESH_ACTION),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,next,pending)
    }
}

private fun migratePrayerCalculationCache(prefs:android.content.SharedPreferences) {
    if(prefs.getBoolean("prayer_school_standard_v1",false)) return
    val editor=prefs.edit()
    prefs.all.keys.filter { it.startsWith("times_") }.forEach { editor.remove(it) }
    editor.putBoolean("prayer_school_standard_v1",true).commit()
}

object PrayerRefresh {
    private fun pruneOldPrayerCache(prefs:android.content.SharedPreferences) {
        val oldest=LocalDate.now().minusDays(3)
        val editor=prefs.edit()
        var changed=false
        prefs.all.keys.forEach { key ->
            if(key.startsWith("times_")) {
                val date=key.removePrefix("times_").take(10)
                val parsed=try { LocalDate.parse(date) } catch(_:Exception) { null }
                if(parsed!=null && parsed.isBefore(oldest)) {
                    editor.remove(key)
                    changed=true
                }
            }
        }
        if(changed) editor.apply()
    }
    private fun isValidPrayerClock(value:String):Boolean = try {
        LocalTime.parse(value.take(5)); true
    } catch(_:Exception) { false }
    private fun scheduleRefreshRetrySafely(context:Context) {
        try { AlarmScheduler.scheduleRefreshRetry(context) } catch(_:Exception) { }
    }
    fun refreshFromCache(context:Context) {
        val prefs=context.getSharedPreferences(PREF,Context.MODE_PRIVATE)
        migratePrayerCalculationCache(prefs)
        val lat=java.lang.Double.longBitsToDouble(prefs.getLong("lat",java.lang.Double.doubleToRawLongBits(37.5753)))
        val lon=java.lang.Double.longBitsToDouble(prefs.getLong("lon",java.lang.Double.doubleToRawLongBits(36.9228)))
        val cached=prefs.getString("times_${LocalDate.now()}_${lat}_${lon}",null) ?: run {
            scheduleRefreshRetrySafely(context)
            return
        }
        val timings=try { JSONObject(cached) } catch(_:Exception) { scheduleRefreshRetrySafely(context); return }
        if(!keys.all { key -> isValidPrayerClock(timings.optString(key)) }) {
            prefs.edit().remove("times_${LocalDate.now()}_${lat}_${lon}").apply()
            scheduleRefreshRetrySafely(context)
            return
        }
        val schedule=keys.mapIndexedNotNull { index,key ->
            if(index==1) null else try {
                names[index] to LocalDateTime.of(LocalDate.now(),LocalTime.parse(timings.getString(key).take(5)))
            } catch(_:Exception) { null }
        }
        AlarmScheduler.schedule(context,schedule)
        AlarmScheduler.scheduleRefresh(context)
        PrayerWidget.refreshAll(context)
    }
    fun refresh(context:Context,forceNetwork:Boolean=false) {
        val prefs=context.getSharedPreferences(PREF,Context.MODE_PRIVATE)
        migratePrayerCalculationCache(prefs)
        val lat=java.lang.Double.longBitsToDouble(prefs.getLong("lat",java.lang.Double.doubleToRawLongBits(37.5753)))
        val lon=java.lang.Double.longBitsToDouble(prefs.getLong("lon",java.lang.Double.doubleToRawLongBits(36.9228)))
        val today=LocalDate.now()
        val cacheKey="times_${today}_${lat}_${lon}"
        val cached=prefs.getString(cacheKey,null)
        val timings=if(cached!=null && !forceNetwork) try {
            JSONObject(cached).takeIf { obj -> keys.all { key -> isValidPrayerClock(obj.optString(key)) } }
        } catch(_:Exception) { null } else null
        val validTimings=timings ?: run {
            val date=today.format(DateTimeFormatter.ofPattern("dd-MM-yyyy"))
            val url=URL("https://api.aladhan.com/v1/timings/$date?latitude=$lat&longitude=$lon&method=13&school=0")
            val connection=(url.openConnection() as java.net.HttpURLConnection).apply { connectTimeout=12000; readTimeout=12000; requestMethod="GET" }
            try {
                if(connection.responseCode !in 200..299) throw IllegalStateException("Vakit servisi HTTP hatası")
                JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                    .getJSONObject("data").getJSONObject("timings").also {
                        if(!keys.all { key -> isValidPrayerClock(it.optString(key)) }) throw IllegalStateException("Eksik vakit verisi")
                        prefs.edit().putString(cacheKey,it.toString()).apply()
                    }
            } finally {
                connection.disconnect()
            }
        }

        val schedule=keys.mapIndexedNotNull { index,key ->
            if(index==1) null else try {
                names[index] to LocalDateTime.of(today,LocalTime.parse(validTimings.getString(key).take(5)))
            } catch(_:Exception) { null }
        }
        AlarmScheduler.schedule(context,schedule)
        AlarmScheduler.scheduleRefresh(context)
        PrayerWidget.refreshAll(context)
        prefs.edit().putBoolean("needs_refresh",false).putLong("last_successful_refresh",System.currentTimeMillis()).apply()
        pruneOldPrayerCache(prefs)
    }
}

class RefreshReceiver: android.content.BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent) {
        val pending=goAsync()
        thread {
            try { PrayerRefresh.refresh(context.applicationContext) }
            catch(_:Exception) { AlarmScheduler.scheduleRefreshRetry(context.applicationContext) }
            finally { pending.finish() }
        }
    }
}

class AlarmReceiver: android.content.BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent) {
        PrayerWidget.refreshAll(context)
        val name=intent.getStringExtra("name") ?: "Namaz"
        val prefs=context.getSharedPreferences(PREF,Context.MODE_PRIVATE)
        if(!prefs.getBoolean("app_active",true) || !prefs.getBoolean("alarm_$name",true)) return
        val mode=prefs.getInt("mode",0)
        val key=if(mode==0) "full" else "short"
        val audio=prefs.getString(key,null)
        val manager=context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val openApp=PendingIntent.getActivity(context,0,Intent(context,MainActivity::class.java).apply { flags=Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP },PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val note=Notification.Builder(context,CHANNEL).setSmallIcon(android.R.drawable.ic_lock_idle_alarm).setContentTitle("$name vakti").setContentText("Namaz vakti geldi").setContentIntent(openApp).setAutoCancel(true).build()
        try {
            if(manager.areNotificationsEnabled()) manager.notify(name.hashCode(),note)
        } catch(_:SecurityException) { }
        if(mode!=2 && !audio.isNullOrBlank()) {
            try {
                context.startForegroundService(Intent(context,AdhanService::class.java)
                    .putExtra("name",name).putExtra("audio",audio))
            } catch(_:Exception) { }
        }
    }
}

class AdhanService: Service() {
    private var player:android.media.MediaPlayer?=null
    override fun onBind(intent:Intent?):IBinder?=null
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        val name=intent?.getStringExtra("name") ?: "Namaz"
        val note=Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.ic_lock_idle_alarm).setContentTitle("$name ezanı").setContentText("Ezan çalınıyor").build()
        try { startForeground(1001,note) }
        catch(_:Exception) { stopSelf(startId); return START_NOT_STICKY }
        try {
            val uri=Uri.parse(intent?.getStringExtra("audio") ?: error("Ses dosyası yok"))
            player?.release()
            player=android.media.MediaPlayer().apply {
                setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ALARM).setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build())
                setDataSource(this@AdhanService,uri)
                setOnCompletionListener { stopSelf() }
                setOnErrorListener { _,_,_ -> stopSelf(); true }
                prepare(); start()
            }
        } catch(_:Exception) { stopSelf() }
        return START_NOT_STICKY
    }
    override fun onDestroy() { player?.release(); player=null; super.onDestroy() }
}

class BootReceiver: android.content.BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent) {
        val pending=goAsync()
        thread {
            try { PrayerRefresh.refresh(context.applicationContext) }
            catch(_:Exception) { AlarmScheduler.scheduleRefreshRetry(context.applicationContext) }
            finally { pending.finish() }
        }
    }
}
