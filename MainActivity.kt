package com.ezanvaktim.pro

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
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
    private fun button(label: String, click: () -> Unit): Button = Button(this).apply {
        text=label; setTextColor(white); setBackgroundColor(green); setOnClickListener { click() }
    }
    private fun render() {
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.rgb(7,19,15)) }
        root = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(18,30,18,24) }
        scroll.addView(root); setContentView(scroll)
        root.addView(text("☪  EZAN VAKTİM PRO",27f,gold).apply { setTypeface(null,Typeface.BOLD); gravity=Gravity.CENTER })
        heading = text(locationLabel,16f,gold).apply { gravity=Gravity.CENTER }; root.addView(heading)
        root.addView(button("📍 Konumumu kullan") { locate() })
        root.addView(text("GÜNLÜK NAMAZ VAKİTLERİ",20f,gold))
        timesBox = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }; root.addView(timesBox)
        root.addView(text("KIBLE PUSULASI",20f,gold))
        compass = text("Pusula sensörü bekleniyor",22f,gold).apply { gravity=Gravity.CENTER; minHeight=150 }; root.addView(compass)
        root.addView(text("EZAN SESİ",20f,gold))
        val modes = arrayOf("Tam ezan", "Kısa ezan", "Sessiz bildirim")
        val spinner = Spinner(this).apply { adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,modes); setSelection(prefs.getInt("mode",2)) }
        root.addView(spinner)
        root.addView(button("Seçimi kaydet") { prefs.edit().putInt("mode",spinner.selectedItemPosition).apply(); Toast.makeText(this,"Ezan modu kaydedildi",Toast.LENGTH_SHORT).show() })
        root.addView(button("Tam ezan ses dosyası seç") { pickAudio(pickFull) })
        root.addView(button("Kısa ezan ses dosyası seç") { pickAudio(pickShort) })
        root.addView(button("Vakitleri yenile") { loadTimes() })
        root.addView(text("Vakit kaynağı: AlAdhan hesaplama servisi (Diyanet'in resmî yayını değildir). Konum izni verilmezse örnek şehir kullanılır. Pusulayı metalden uzak tutun.",13f,Color.LTGRAY))
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
            timesBox.addView(text("${names[index]}                      $raw",19f,if(index==4) gold else white))
            try {
                val at=LocalDateTime.of(LocalDate.now(),LocalTime.parse(raw))
                if(next.isEmpty() && at.toLocalTime().isAfter(now)) next=names[index]
                if(index!=1) schedule.add(names[index] to at)
            } catch(_:Exception) {}
        }
        timesBox.addView(text("Sıradaki vakit: ${next.ifEmpty { "Yarın İmsak" }}",18f,gold))
        AlarmScheduler.schedule(this,schedule)
        AlarmScheduler.scheduleRefresh(this)
    }
    override fun onResume() {
        super.onResume()
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.also { sensorManager.registerListener(this,it,SensorManager.SENSOR_DELAY_UI) }
        sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.also { sensorManager.registerListener(this,it,SensorManager.SENSOR_DELAY_UI) }
        if(sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)==null) compass.text="Bu cihazda pusula sensörü bulunamadı"
    }
    override fun onPause() { sensorManager.unregisterListener(this); super.onPause() }
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
        compass.text="🕋\nKıble: %.1f° (gerçek kuzey)\nPusula yönü: %.1f° (manyetik)\nKıbleye dönüş: %.1f° sağa".format(Locale.getDefault(),bearing,azimuth,relative)
    }
    override fun onAccuracyChanged(sensor:Sensor?,accuracy:Int) {}
}

object AlarmScheduler {
    fun schedule(context:Context, times:List<Pair<String,LocalDateTime>>) {
        val manager=context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        times.forEachIndexed { index,(name,time) ->
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
