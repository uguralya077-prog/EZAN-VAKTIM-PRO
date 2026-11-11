package com.ezanvaktim.pro

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class PrayerWidget:AppWidgetProvider() {
    override fun onUpdate(context:Context,manager:AppWidgetManager,ids:IntArray) {
        ids.forEach { id -> updateWidget(context,manager,id) }
    }
    override fun onReceive(context:Context,intent:Intent) {
        super.onReceive(context,intent)
        if(intent.action==Intent.ACTION_TIME_CHANGED ||
            intent.action==Intent.ACTION_TIMEZONE_CHANGED ||
            intent.action=="com.ezanvaktim.pro.UPDATE_WIDGET") {
            val manager=AppWidgetManager.getInstance(context)
            val ids=manager.getAppWidgetIds(android.content.ComponentName(context,PrayerWidget::class.java))
            ids.forEach { updateWidget(context,manager,it) }
        }
    }
    companion object {
        fun updateWidget(context:Context,manager:AppWidgetManager,id:Int) {
            val prefs=context.getSharedPreferences("ezan_settings",Context.MODE_PRIVATE)
            val lat=java.lang.Double.longBitsToDouble(prefs.getLong("lat",java.lang.Double.doubleToRawLongBits(37.5753)))
            val lon=java.lang.Double.longBitsToDouble(prefs.getLong("lon",java.lang.Double.doubleToRawLongBits(36.9228)))
            val date=LocalDate.now()
            val cache=prefs.getString("times_${date}_${lat}_${lon}",null)
            val views=RemoteViews(context.packageName,R.layout.prayer_widget)
            views.setTextViewText(R.id.widget_location,prefs.getString("place","Konum seçilmedi"))
            var next="Vakitleri uygulamadan güncelleyin"
            var detail="Güncel saatler için uygulamayı açın"
            if(cache!=null) try {
                val json=JSONObject(cache)
                val names=listOf("İmsak","Öğle","İkindi","Akşam","Yatsı")
                val keys=listOf("Fajr","Dhuhr","Asr","Maghrib","Isha")
                val upcoming=keys.indices.mapNotNull { i ->
                    try {
                        val time=LocalTime.parse(json.getString(keys[i]).take(5))
                        Triple(names[i],time,LocalDateTime.of(date,time))
                    } catch(_:Exception) { null }
                }.firstOrNull { it.third.isAfter(LocalDateTime.now()) }
                if(upcoming!=null) {
                    next="${upcoming.first} • ${upcoming.second}"
                    detail="Bugünün sonraki namaz vakti"
                } else {
                    val tomorrow=date.plusDays(1)
                    val tomorrowCache=prefs.getString("times_${tomorrow}_${lat}_${lon}",null)
                    val fajr=if(tomorrowCache!=null) JSONObject(tomorrowCache).optString("Fajr").take(5) else ""
                    next=if(fajr.isNotBlank()) "Yarın İmsak • $fajr" else "Bugünün vakitleri tamamlandı"
                    detail="Yeni günün vakitleri için uygulamayı açın"
                }
            } catch(_:Exception) {}
            views.setTextViewText(R.id.widget_next,next)
            views.setTextViewText(R.id.widget_detail,detail)
            val open=Intent(context,MainActivity::class.java)
            val pending=PendingIntent.getActivity(context,0,open,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.widget_title,pending)
            views.setOnClickPendingIntent(R.id.widget_next,pending)
            manager.updateAppWidget(id,views)
        }
    }
}