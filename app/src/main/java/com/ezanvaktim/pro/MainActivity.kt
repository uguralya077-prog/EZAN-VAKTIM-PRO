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
        latitude = java.lang.Double.longBitsToDouble(prefs.getLong("lat", java.lang.Double.doubleToRawLongBits(latitude)))
        longitude = java.lang.Double.longBitsToDouble(prefs.getLong("lon", java.lang.Double.doubleToRawLongBits(longitude)))
        locationLabel = prefs.getString("place", locationLabel) ?: locationLabel
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(NotificationChannel(CHANNEL,"Namaz bildirimleri",NotificationManager.IMPORTANCE_HIGH))
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 20)
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
        background=rounded(surface,Color.rgb(54,88,70),18f)
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
        background=rounded(Color.rgb(22,60,49),Color.rgb(95,119,84),14f)
        setPadding(14,12,14,12)
        minHeight=(48*resources.displayMetrics.density).toInt()
        setOnClickListener { click() }
    }
    private fun render() {
        requestGeneration++
        timerHandler.removeCallbacks(timerTick)
        timerTarget=null
        timerLabel=null
        dial=null
        val outer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(deep) }
        val header = text("☪  EZAN VAKTİM PRO", 25f, gold).apply {
            gravity = Gravity.CENTER; setTypeface(null, Typeface.BOLD)
            setPadding(12,24,12,22)
            setBackgroundColor(Color.rgb(11,34,28))
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
        setContentView(outer)
        when(section) {
            "Menüler" -> {
                root.addView(text("TÜM MENÜLER",24f,gold))
                root.addView(text("İbadet ve günlük yaşam için bütün bölümler",15f))
                val items=listOf(
                    "Namaz Vakitleri" to "Vakitler",
                    "Kıble Pusulası" to "Kıble",
                    "Ezan ve Alarmlar" to "Ezan",
                    "Kur'an-ı Kerim" to "Kur'an",
                    "Günlük Dualar" to "Dualar",
                    "Dijital Tesbih" to "Zikir",
                    "Allah'ın 99 İsmi" to "99 İsim",
                    "Ana Ekran Widget'ı" to "Widget",
                    "Görünüm ve Ayarlar" to "Ayarlar"
                )
                items.chunked(2).forEach { pair ->
                    val line=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
                    pair.forEach { (title,target) ->
                        val tile=panel().apply {
                            gravity=Gravity.CENTER
                            minimumHeight=140
                            addView(text(title,18f,gold).apply { gravity=Gravity.CENTER })
                            setOnClickListener { section=target; render() }
                        }
                        line.addView(tile,LinearLayout.LayoutParams(0,-2,1f).apply { setMargins(4,4,4,4) })
                    }
                    if(pair.size==1) line.addView(android.view.View(this),LinearLayout.LayoutParams(0,1,1f))
                    root.addView(line)
                }
            }
            "Vakitler" -> {
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
                root.addView(text("Kaynak: AlAdhan hesaplama servisi; Diyanet'in resmî verisi değildir.",12f,Color.LTGRAY))
                loadTimes()
            }
            "Kıble" -> {
                root.addView(text("KIBLE PUSULASI",23f,gold))
                root.addView(text(locationLabel,15f,white).apply { gravity=Gravity.CENTER })
                dial=QiblaDial(this)
                root.addView(dial,LinearLayout.LayoutParams(-1,(resources.displayMetrics.density*300).toInt()))
                compass=text("Telefonu düz tutun. Sensör bekleniyor…",18f,gold).apply {
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
                root.addView(text("SES ÖN İZLEMESİ",19f,gold))
                root.addView(button("▶ Tam ezanı dinle") { previewAudio("full") })
                root.addView(button("▶ Kısa ezanı dinle") { previewAudio("short") })
                root.addView(button("■ Dinlemeyi durdur") { stopAudioPreview() })
                root.addView(text("Ön izleme yalnızca seçtiğiniz yerel ses dosyasını çalar; alarmı başlatmaz.",13f))
                root.addView(text("Ses çalmak için cihazınızdan bir ezan kaydı seçin. Namaz vakti bildirimleri Android izinlerine bağlıdır.",14f))
            }
            "Ayarlar" -> {
                root.addView(text("GÖRÜNÜM VE AYARLAR",23f,gold))
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
                root.addView(text("Diyanet Kur'an Portalı • Çevrim içi okuma",15f))
                root.addView(text("Arapça metin ve Türkçe meal için Diyanet'in resmî sitesini uygulama içinde açabilirsiniz. İnternet bağlantısı gereklidir.",14f))
                root.addView(button("Çevrim dışı kısa sûreler") {
                    section="Kısa Sûreler"
                    render()
                })
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
                val searchSurah=EditText(this).apply {
                    hint="Sûre ara..."
                    setSingleLine(true)
                    setTextColor(white)
                    setHintTextColor(Color.LTGRAY)
                }
                root.addView(searchSurah)
                val surahList=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
                root.addView(surahList)
                fun showSurahs(query:String) {
                    surahList.removeAllViews()
                    surahs.filter { it.first.contains(query,true) }.forEach { (title,arabic,count) ->
                        surahList.addView(panel().apply {
                            addView(text("$title • $count",19f,gold))
                            addView(text(arabic,25f,white).apply {
                                gravity=Gravity.RIGHT
                                textDirection=android.view.View.TEXT_DIRECTION_RTL
                                setLineSpacing(12f,1.25f)
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
                searchSurah.addTextChangedListener(object:android.text.TextWatcher {
                    override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int) {}
                    override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) { showSurahs(s?.toString() ?: "") }
                    override fun afterTextChanged(s:android.text.Editable?) {}
                })
                showSurahs("")
            }
            "Kur'an Oku" -> {
                root.addView(button("← Kur'an menüsüne dön") { section="Kur'an"; render() })
                val browser=WebView(this).apply {
                    settings.javaScriptEnabled=true
                    settings.domStorageEnabled=true
                    settings.allowFileAccess=false
                    settings.allowContentAccess=false
                    webViewClient=object:WebViewClient() {
                        override fun shouldOverrideUrlLoading(view:WebView?,request:android.webkit.WebResourceRequest?):Boolean {
                            val url=request?.url ?: return true
                            return url.scheme!="https" || (url.host!="diyanet.gov.tr" && url.host?.endsWith(".diyanet.gov.tr")!=true)
                        }
                    }
                    loadUrl("https://kuran.diyanet.gov.tr/")
                }
                root.addView(browser,LinearLayout.LayoutParams(-1,resources.displayMetrics.heightPixels*2/3))
                root.addView(text("Görüntülenen içerik Diyanet'in çevrim içi hizmetidir; uygulamanın yerel çevrim dışı Kur'an veritabanı henüz bulunmuyor.",13f))
            }
            "Dualar" -> {
                root.addView(text("GÜNLÜK DUALAR VE ZİKİRLER",23f,gold))
                root.addView(text("Kolay erişim için konulara ayrılmış başlangıç koleksiyonu",15f))
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
                    Triple("Kur'an","Bağışlanma (A'râf 7:23)","Rabbenâ zalemnâ enfüsenâ ve in lem tağfir lenâ ve terhamnâ le-nekûnenne minel hâsirîn.")
                )
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
                root.addView(text("Bu seçki tam Hisnü'l-Müslim kataloğu değildir. Arapça asıllar, anlamlar ve kaynak denetimi ilerleyen aşamada eklenecektir.",13f))
            }
            "Zikir" -> {
                zikrCount=prefs.getInt("zikr_count",0)
                root.addView(text("DİJİTAL TESBİH",23f,gold))
                root.addView(text("Hedef seç, zikrini say ve kaldığın yerden devam et.",15f))
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
        if(section!="Vakitler") return
        val generation=requestGeneration
        val cached=prefs.getString(cacheKey,null)
        if(cached!=null) { try { showTimes(JSONObject(cached)); return } catch(_:Exception) { prefs.edit().remove(cacheKey).apply() } }
        timesBox.removeAllViews(); timesBox.addView(text("Vakitler yükleniyor…",16f))
        thread {
            try {
                val url=URL("https://api.aladhan.com/v1/timings/$date?latitude=$latitude&longitude=$longitude&method=13")
                val connection=url.openConnection().apply { connectTimeout=10000; readTimeout=10000 }
                val json=JSONObject(connection.getInputStream().bufferedReader().use { it.readText() })
                val timings=json.getJSONObject("data").getJSONObject("timings")
                prefs.edit().putString(cacheKey,timings.toString()).apply()
                runOnUiThread { if(section=="Vakitler" && generation==requestGeneration) showTimes(timings) }
            } catch(e:Exception) { runOnUiThread { if(section=="Vakitler" && generation==requestGeneration) { timesBox.removeAllViews(); timesBox.addView(text("Vakitler alınamadı. İnternet bağlantısını kontrol edin.",15f)) } } }
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
                        val url=URL("https://api.aladhan.com/v1/timings/$date?latitude=$lat&longitude=$lon&method=13")
                        val connection=url.openConnection().apply { connectTimeout=10000; readTimeout=10000 }
                        val json=JSONObject(connection.getInputStream().bufferedReader().use { it.readText() })
                        val timing=json.getJSONObject("data").getJSONObject("timings")
                        prefs.edit().putString(tomorrowKey,timing.toString()).apply()
                        val exact=LocalDateTime.of(tomorrow,LocalTime.parse(timing.getString("Fajr").take(5)))
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
    override fun onPause() { stopAudioPreview(); timerHandler.removeCallbacks(timerTick); sensorManager.unregisterListener(this); super.onPause() }
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
        compass.text="🕋\nKıble: %.1f° (gerçek kuzey)\nPusula yönü: %.1f° (gerçek kuzey)\nKıbleye dönüş: %.1f° %s".format(Locale.getDefault(),bearing,azimuth,kotlin.math.abs(shortestTurn),if(shortestTurn<0) "sola" else "sağa")
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
            else try { manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,epoch,pending) }
            catch(_:SecurityException) { manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,epoch,pending) }
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
        PrayerWidget.refreshAll(context)
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
        PrayerWidget.refreshAll(context)
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
        PrayerWidget.refreshAll(context)
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
