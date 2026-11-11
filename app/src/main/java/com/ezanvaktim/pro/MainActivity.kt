package com.ezanvaktim.pro

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
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
    private var locationLabel = "Kahramanmaraş (örnek konum)"
    private val gold = Color.rgb(215,181,109)
    private val white = Color.rgb(241,244,239)
    private val green = Color.rgb(13,39,30)
    private val pickFull = 101
    private val pickShort = 102
    private var section = "Vakitler"
    private var zikrCount = 0
    private val timerHandler=Handler(Looper.getMainLooper())
    private var timerLabel:TextView?=null
    private var timerTarget:LocalDateTime?=null
    private var timerPrayer=""
    private val timerTick=object:Runnable {
        override fun run() {
            val target=timerTarget ?: return
            if(section!="Vakitler") return
            val sec=java.time.Duration.between(LocalDateTime.now(),target).seconds.coerceAtLeast(0)
            timerLabel?.text=String.format(Locale.getDefault(),"Sıradaki: %s   %02d:%02d:%02d",timerPrayer,sec/3600,(sec%3600)/60,sec%60)
            timerHandler.postDelayed(this,1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        latitude = java.lang.Double.longBitsToDouble(prefs.getLong("lat", java.lang.Double.doubleToRawLongBits(latitude)))
        longitude = java.lang.Double.longBitsToDouble(prefs.getLong("lon", java.lang.Double.doubleToRawLongBits(longitude)))
        locationLabel = prefs.getString("place", locationLabel) ?: locationLabel
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(NotificationChannel(CHANNEL,"Namaz bildirimleri",NotificationManager.IMPORTANCE_HIGH))
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 20)
        render()
        loadTimes()
    }

    private fun text(s: String, size: Float = 18f, color: Int = white): TextView = TextView(this).apply {
        text=s; textSize=size; setTextColor(color); setPadding(18,15,18,15)
    }
    private fun panel(): LinearLayout = LinearLayout(this).apply {
        orientation=LinearLayout.VERTICAL
        setPadding(18,16,18,16)
        background=GradientDrawable().apply {
            setColor(Color.rgb(16,49,39))
            cornerRadius=24f
            setStroke(2,Color.rgb(66,91,69))
        }
        val margins=LinearLayout.LayoutParams(-1,-2).apply { setMargins(0,8,0,8) }
        layoutParams=margins
    }
    private fun button(label: String, click: () -> Unit): Button = Button(this).apply {
        text=label; setTextColor(white); setBackgroundColor(green); setOnClickListener { click() }
    }
    private fun render() {
        timerHandler.removeCallbacks(timerTick)
        timerTarget=null
        timerLabel=null
        val outer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(7,19,15)) }
        val header = text("☪  EZAN VAKTİM PRO", 25f, gold).apply {
            gravity = Gravity.CENTER; setTypeface(null, Typeface.BOLD)
        }
        outer.addView(header)
        val scroll = ScrollView(this)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16,12,16,20) }
        scroll.addView(root)
        outer.addView(scroll, LinearLayout.LayoutParams(-1,0,1f))
        val navigation = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("Vakitler","Kıble","Ezan","Kur'an","Dualar","Zikir","99 İsim").forEach { tab ->
            tabs.addView(button(tab) { section=tab; render() }.apply {
                setTextColor(if(section==tab) gold else white)
                setPadding(12,12,12,12)
            })
        }
        navigation.addView(tabs); outer.addView(navigation)
        setContentView(outer)
        when(section) {
            "Vakitler" -> {
                heading=text(locationLabel,17f,gold).apply { gravity=Gravity.CENTER }
                root.addView(heading)
                root.addView(text(LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy",Locale("tr","TR"))),17f,white).apply { gravity=Gravity.CENTER })
                root.addView(button("📍 Konumumu kullan") { locate() })
                timerLabel=text("Sonraki vakit hesaplanıyor…",20f,gold).apply { gravity=Gravity.CENTER; setBackgroundColor(green) }
                root.addView(panel().apply { addView(timerLabel) })
                root.addView(text("GÜNLÜK NAMAZ VAKİTLERİ",21f,gold))
                timesBox=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
                root.addView(timesBox)
                root.addView(button("Vakitleri yenile") { loadTimes() })
                root.addView(text("Kaynak: AlAdhan hesaplama servisi; Diyanet'in resmî verisi değildir.",12f,Color.LTGRAY))
                loadTimes()
            }
            "Kıble" -> {
                root.addView(text("KIBLE PUSULASI",23f,gold))
                compass=text("Telefonu düz tutun. Sensör bekleniyor…",22f,gold).apply {
                    gravity=Gravity.CENTER; minHeight=280
                }
                root.addView(compass)
                root.addView(text("Manyetik alan ve metal eşyalar pusula doğruluğunu etkileyebilir.",15f))
            }
            "Ezan" -> {
                root.addView(text("EZAN VE BİLDİRİMLER",22f,gold))
                val modes=arrayOf("Tam ezan","Kısa ezan","Sessiz bildirim")
                val spinner=Spinner(this).apply {
                    adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,modes)
                    setSelection(prefs.getInt("mode",2))
                }
                root.addView(spinner)
                root.addView(button("Seçimi kaydet") {
                    prefs.edit().putInt("mode",spinner.selectedItemPosition).apply()
                    Toast.makeText(this,"Ezan modu kaydedildi",Toast.LENGTH_SHORT).show()
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
                root.addView(button("Tam ezan dosyası seç") { pickAudio(pickFull) })
                root.addView(button("Kısa ezan dosyası seç") { pickAudio(pickShort) })
                root.addView(text("Ses çalmak için cihazınızdan bir ezan kaydı seçin. Namaz vakti bildirimleri Android izinlerine bağlıdır.",14f))
            }
            "Kur'an" -> {
                root.addView(text("KUR'AN-I KERİM",23f,gold))
                root.addView(text("Kur'an okuma ve Türkçe meal bağlantıları",17f))
                root.addView(button("Kur'an-ı Kerim'i aç") {
                    startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://kuran.diyanet.gov.tr/")))
                })
                root.addView(text("Bu bölüm şimdilik Diyanet'in çevrim içi Kur'an sayfasını açar. Uygulama içi tam metin ve sesli okuma sonraki geliştirme aşamasındadır.",14f))
            }
            "Dualar" -> {
                root.addView(text("GÜNLÜK DUALAR",23f,gold))
                listOf(
                    "Yemekten önce" to "Bismillâhirrahmânirrahîm.",
                    "Yemekten sonra" to "Elhamdülillâh.",
                    "Bağışlanma" to "Estağfirullâhel azîm ve etûbü ileyh.",
                    "Günlük zikir" to "Sübhânallâh • Elhamdülillâh • Allâhu ekber"
                ).forEach { (title,body) ->
                    root.addView(text(title,19f,gold))
                    root.addView(text(body,17f))
                }
                root.addView(text("Bu başlangıç listesidir; kapsamlı ve kaynaklı dua kataloğu henüz eklenmedi.",13f))
            }
            "Zikir" -> {
                zikrCount=prefs.getInt("zikr_count",0)
                root.addView(text("ZİKİR SAYACI",23f,gold))
                val counter=text("$zikrCount",52f,gold).apply { gravity=Gravity.CENTER }
                root.addView(counter)
                root.addView(button("TESBİH +1") {
                    zikrCount++
                    prefs.edit().putInt("zikr_count",zikrCount).apply()
                    counter.text="$zikrCount"
                })
                root.addView(button("Sayacı sıfırla") {
                    zikrCount=0; prefs.edit().putInt("zikr_count",0).apply(); counter.text="0"
                })
            }
            "99 İsim" -> {
                root.addView(text("ALLAH'IN GÜZEL İSİMLERİ",22f,gold))
                listOf(
                    "Er-Rahmân — Sonsuz merhamet sahibi",
                    "Er-Rahîm — Çok merhametli",
                    "El-Melik — Mutlak hükümran",
                    "El-Kuddûs — Her türlü eksiklikten uzak",
                    "Es-Selâm — Esenlik kaynağı",
                    "El-Mü'min — Güven veren",
                    "El-Müheymin — Gözetip koruyan"
                ).forEach { root.addView(text(it,17f)) }
                root.addView(text("Şimdilik örnek isimler yer alıyor; 99 ismin tamamı ve açıklamaları henüz eklenmedi.",13f))
            }
        }
    }
    private fun pickAudio(code:Int) { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type="audio/*"; addCategory(Intent.CATEGORY_OPENABLE) },code) }
    @Deprecated("Activity result API compatibility")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(resultCode==RESULT_OK && data?.data!=null && (requestCode==pickFull || requestCode==pickShort)) {
            val uri=data.data!!
            contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)
            prefs.edit().putString(if(requestCode==pickFull) "full" else "short",uri.toString()).apply()
            Toast.makeText(this,"Ses kaydedildi",Toast.LENGTH_SHORT).show()
        }
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
    private fun loadTimes() {
        val date=LocalDate.now().format(DateTimeFormatter.ofPattern("dd-MM-yyyy"))
        val cacheKey="times_${LocalDate.now()}_${latitude}_${longitude}"
        val cached=prefs.getString(cacheKey,null)
        if(cached!=null) { showTimes(JSONObject(cached)); return }
        timesBox.removeAllViews(); timesBox.addView(text("Vakitler yükleniyor…",16f))
        thread {
            try {
                val url=URL("https://api.aladhan.com/v1/timings/$date?latitude=$latitude&longitude=$longitude&method=13")
                val connection=url.openConnection().apply { connectTimeout=10000; readTimeout=10000 }
                val json=JSONObject(connection.getInputStream().bufferedReader().use { it.readText() })
                val timings=json.getJSONObject("data").getJSONObject("timings")
                prefs.edit().putString(cacheKey,timings.toString()).apply()
                runOnUiThread { showTimes(timings) }
            } catch(e:Exception) { runOnUiThread { timesBox.removeAllViews(); timesBox.addView(text("Vakitler alınamadı. İnternet bağlantısını kontrol edin. ${e.message}",15f)) } }
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
            timerTarget=try { LocalDateTime.of(LocalDate.now().plusDays(1),LocalTime.parse(obj.optString("Fajr","05:00").take(5))) } catch(_:Exception) { null }
        }
        timerHandler.removeCallbacks(timerTick)
        timerHandler.post(timerTick)
        AlarmScheduler.schedule(this,schedule)
        AlarmScheduler.scheduleRefresh(this)
    }
    override fun onResume() {
        super.onResume()
        if(section=="Vakitler" && timerTarget!=null) { timerHandler.removeCallbacks(timerTick); timerHandler.post(timerTick) }
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.also { sensorManager.registerListener(this,it,SensorManager.SENSOR_DELAY_UI) }
        sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.also { sensorManager.registerListener(this,it,SensorManager.SENSOR_DELAY_UI) }
        if(section=="Kıble" && sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)==null) compass.text="Bu cihazda pusula sensörü bulunamadı"
    }
    override fun onPause() { timerHandler.removeCallbacks(timerTick); sensorManager.unregisterListener(this); super.onPause() }
    override fun onSensorChanged(event:SensorEvent) {
        if(event.sensor.type==Sensor.TYPE_ACCELEROMETER) gravity=event.values.clone()
        if(event.sensor.type==Sensor.TYPE_MAGNETIC_FIELD) magnetic=event.values.clone()
        val g=gravity ?: return; val m=magnetic ?: return
        val rotation=FloatArray(9); val orientation=FloatArray(3)
        if(!SensorManager.getRotationMatrix(rotation,null,g,m)) return
        SensorManager.getOrientation(rotation,orientation)
        val azimuth=(Math.toDegrees(orientation[0].toDouble())+360)%360
        val lat1=Math.toRadians(latitude); val lonDiff=Math.toRadians(39.8262-longitude)
        val lat2=Math.toRadians(21.4225)
        bearing=(Math.toDegrees(atan2(sin(lonDiff),cos(lat1)*tan(lat2)-sin(lat1)*cos(lonDiff)))+360)%360
        val relative=(bearing-azimuth+360)%360
        if(section!="Kıble") return
        compass.text="🕋\nKıble: %.1f° (gerçek kuzey)\nPusula yönü: %.1f° (manyetik)\nKıbleye dönüş: %.1f° sağa".format(Locale.getDefault(),bearing,azimuth,relative)
    }
    override fun onAccuracyChanged(sensor:Sensor?,accuracy:Int) {}
}

object AlarmScheduler {
    fun schedule(context:Context, times:List<Pair<String,LocalDateTime>>) {
        val manager=context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        times.forEachIndexed { index,(name,time) ->
            if(!context.getSharedPreferences(PREF,Context.MODE_PRIVATE).getBoolean("alarm_$name",true)) {
                manager.cancel(PendingIntent.getBroadcast(context,index,Intent(context,AlarmReceiver::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                return@forEachIndexed
            }
            if(!time.isAfter(LocalDateTime.now())) {
                manager.cancel(PendingIntent.getBroadcast(context,index,Intent(context,AlarmReceiver::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                return@forEachIndexed
            }
            val intent=Intent(context,AlarmReceiver::class.java).putExtra("name",name)
            val pending=PendingIntent.getBroadcast(context,index,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val epoch=time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            if(Build.VERSION.SDK_INT>=31 && !manager.canScheduleExactAlarms()) manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,epoch,pending)
            else manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,epoch,pending)
        }
    }
    fun scheduleRefresh(context:Context) {
        val manager=context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val next=LocalDate.now().plusDays(1).atTime(0,5).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val pending=PendingIntent.getBroadcast(context,500,Intent(context,RefreshReceiver::class.java).setAction(REFRESH_ACTION),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,next,pending)
    }
}

object PrayerRefresh {
    fun refreshFromCache(context:Context) {
        val prefs=context.getSharedPreferences(PREF,Context.MODE_PRIVATE)
        val lat=java.lang.Double.longBitsToDouble(prefs.getLong("lat",java.lang.Double.doubleToRawLongBits(37.5753)))
        val lon=java.lang.Double.longBitsToDouble(prefs.getLong("lon",java.lang.Double.doubleToRawLongBits(36.9228)))
        val cached=prefs.getString("times_${LocalDate.now()}_${lat}_${lon}",null) ?: return
        val timings=JSONObject(cached)
        val schedule=keys.mapIndexedNotNull { index,key ->
            if(index==1) null else try {
                names[index] to LocalDateTime.of(LocalDate.now(),LocalTime.parse(timings.getString(key).take(5)))
            } catch(_:Exception) { null }
        }
        AlarmScheduler.schedule(context,schedule)
    }
    fun refresh(context:Context) {
        val prefs=context.getSharedPreferences(PREF,Context.MODE_PRIVATE)
        val lat=java.lang.Double.longBitsToDouble(prefs.getLong("lat",java.lang.Double.doubleToRawLongBits(37.5753)))
        val lon=java.lang.Double.longBitsToDouble(prefs.getLong("lon",java.lang.Double.doubleToRawLongBits(36.9228)))
        val today=LocalDate.now()
        val cacheKey="times_${today}_${lat}_${lon}"
        val cached=prefs.getString(cacheKey,null)
        val timings=if(cached!=null) JSONObject(cached) else {
            val date=today.format(DateTimeFormatter.ofPattern("dd-MM-yyyy"))
            val url=URL("https://api.aladhan.com/v1/timings/$date?latitude=$lat&longitude=$lon&method=13")
            val connection=url.openConnection().apply { connectTimeout=12000; readTimeout=12000 }
            JSONObject(connection.getInputStream().bufferedReader().use { it.readText() }).getJSONObject("data").getJSONObject("timings").also {
                prefs.edit().putString(cacheKey,it.toString()).apply()
            }
        }
        val schedule=keys.mapIndexedNotNull { index,key ->
            if(index==1) null else try {
                names[index] to LocalDateTime.of(today,LocalTime.parse(timings.getString(key).take(5)))
            } catch(_:Exception) { null }
        }
        AlarmScheduler.schedule(context,schedule)
        AlarmScheduler.scheduleRefresh(context)
        prefs.edit().putBoolean("needs_refresh",false).apply()
    }
}

class RefreshReceiver: android.content.BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent) {
        val pending=goAsync()
        thread {
            try { PrayerRefresh.refresh(context.applicationContext) }
            catch(_:Exception) { AlarmScheduler.scheduleRefresh(context.applicationContext) }
            finally { pending.finish() }
        }
    }
}

class AlarmReceiver: android.content.BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent) {
        val name=intent.getStringExtra("name") ?: "Namaz"
        val prefs=context.getSharedPreferences(PREF,Context.MODE_PRIVATE)
        if(!prefs.getBoolean("alarm_$name",true)) return
        val mode=prefs.getInt("mode",2)
        val key=if(mode==0) "full" else "short"
        val audio=prefs.getString(key,null)
        if(mode!=2 && audio!=null) {
            try { context.startForegroundService(Intent(context,AdhanService::class.java).putExtra("name",name).putExtra("audio",audio)); return }
            catch(_:Exception) { }
        }
        val manager=context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val note=Notification.Builder(context,CHANNEL).setSmallIcon(android.R.drawable.ic_lock_idle_alarm).setContentTitle("$name vakti").setContentText("Namaz vakti geldi").setAutoCancel(true).build()
        manager.notify(name.hashCode(),note)
    }
}

class AdhanService: Service() {
    private var player:android.media.MediaPlayer?=null
    override fun onBind(intent:Intent?):IBinder?=null
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        val name=intent?.getStringExtra("name") ?: "Namaz"
        val note=Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.ic_lock_idle_alarm).setContentTitle("$name ezanı").setContentText("Ezan çalınıyor").build()
        startForeground(1001,note)
        try {
            val uri=Uri.parse(intent?.getStringExtra("audio") ?: error("Ses dosyası yok"))
            player?.release()
            player=android.media.MediaPlayer().apply {
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
            catch(_:Exception) { AlarmScheduler.scheduleRefresh(context.applicationContext) }
            finally { pending.finish() }
        }
    }
}
