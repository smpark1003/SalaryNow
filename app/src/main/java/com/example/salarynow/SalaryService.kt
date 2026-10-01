package com.example.salarynow

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.text.NumberFormat
import java.util.*
import kotlin.concurrent.timer

class SalaryService : Service() {

    private val CHANNEL_ID = "salary_live_channel"
    private val NOTIF_ID = 1001
    private var timer: Timer? = null

    companion object {
        var isMonthlyView = false // false: 오늘 하루, true: 총 월급
        const val ACTION_TOGGLE_VIEW = "ACTION_TOGGLE_VIEW"
        const val ACTION_STOP_SERVICE = "ACTION_STOP_SERVICE"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE_VIEW -> isMonthlyView = !isMonthlyView
            ACTION_STOP_SERVICE -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }

        createNotificationChannel()

        val initialNotif = buildNotification("계산 준비 중...", "데이터를 집계 중입니다.", 0, 100)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, initialNotif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, initialNotif)
        }

        if (timer == null) {
            startLiveTicking()
        }

        return START_STICKY
    }

    private fun startLiveTicking() {
        val prefs = getSharedPreferences("SalaryPrefs", Context.MODE_PRIVATE)
        val salary = prefs.getFloat("salary", 3000000f).toDouble()
        val monthlyHours = prefs.getFloat("monthlyHours", 209f).toDouble()

        val hourlyWage = salary / monthlyHours
        val perSecWage = hourlyWage / 3600.0

        val nf = NumberFormat.getNumberInstance(Locale.KOREA)

        timer = timer(period = 1000) {
            val now = Calendar.getInstance()
            val hour = now.get(Calendar.HOUR_OF_DAY)
            val min = now.get(Calendar.MINUTE)
            val sec = now.get(Calendar.SECOND)
            val dayOfWeek = now.get(Calendar.DAY_OF_WEEK)

            val currentSecOfDay = (hour * 3600) + (min * 60) + sec
            val startWorkSec = 9 * 3600       // 09:00
            val endWorkSec = 18 * 3600        // 18:00
            val lunchStartSec = 12 * 3600     // 12:00
            val lunchEndSec = 13 * 3600       // 13:00
            val totalDailyWorkSec = (endWorkSec - startWorkSec) - (lunchEndSec - lunchStartSec)
            val dailyGoalWage = totalDailyWorkSec * perSecWage

            var workedTodaySec = 0
            val isWeekend = (dayOfWeek == Calendar.SATURDAY || dayOfWeek == Calendar.SUNDAY)

            if (!isWeekend) {
                if (currentSecOfDay in startWorkSec..endWorkSec) {
                    workedTodaySec = if (currentSecOfDay < lunchStartSec) {
                        currentSecOfDay - startWorkSec
                    } else if (currentSecOfDay in lunchStartSec until lunchEndSec) {
                        lunchStartSec - startWorkSec
                    } else {
                        (currentSecOfDay - startWorkSec) - (lunchEndSec - lunchStartSec)
                    }
                } else if (currentSecOfDay > endWorkSec) {
                    workedTodaySec = totalDailyWorkSec
                }
            }

            val todayAccumulated = workedTodaySec * perSecWage

            val passedWorkDays = getPassedWorkDaysBeforeToday(now)
            val monthAccumulated = (passedWorkDays * dailyGoalWage) + todayAccumulated

            val notifManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            if (isMonthlyView) {
                val percent = ((monthAccumulated / salary) * 100).coerceIn(0.0, 100.0)
                val title = "💳 이번 달 누적: ₩ ${nf.format(monthAccumulated.toInt())} (${String.format("%.1f", percent)}%)"
                val text = "초당 ₩${String.format("%.2f", perSecWage)} | 월 목표: ₩${nf.format(salary.toInt())}"
                notifManager.notify(NOTIF_ID, buildNotification(title, text, percent.toInt(), 100))
            } else {
                val percent = if (totalDailyWorkSec > 0) ((workedTodaySec.toDouble() / totalDailyWorkSec) * 100).coerceIn(0.0, 100.0) else 0.0
                val title = "⚡ 오늘 하루 누적: ₩ ${nf.format(todayAccumulated.toInt())} (${String.format("%.1f", percent)}%)"

                val remainSec = (endWorkSec - currentSecOfDay).coerceAtLeast(0)
                val remH = remainSec / 3600
                val remM = (remainSec % 3600) / 60
                val status = if (isWeekend) "주말 휴무" else if (currentSecOfDay >= endWorkSec) "퇴근 완료" else "퇴근까지 ${remH}시간 ${remM}분"

                val text = "초당 ₩${String.format("%.2f", perSecWage)} | $status"
                notifManager.notify(NOTIF_ID, buildNotification(title, text, percent.toInt(), 100))
            }
        }
    }

    private fun getPassedWorkDaysBeforeToday(now: Calendar): Int {
        var count = 0
        val cal = Calendar.getInstance().apply {
            set(Calendar.YEAR, now.get(Calendar.YEAR))
            set(Calendar.MONTH, now.get(Calendar.MONTH))
            set(Calendar.DAY_OF_MONTH, 1)
        }
        while (cal.get(Calendar.DAY_OF_MONTH) < now.get(Calendar.DAY_OF_MONTH)) {
            val d = cal.get(Calendar.DAY_OF_WEEK)
            if (d != Calendar.SATURDAY && d != Calendar.SUNDAY) count++
            cal.add(Calendar.DAY_OF_MONTH, 1)
        }
        return count
    }

    private fun buildNotification(title: String, text: String, progress: Int, maxProgress: Int): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val toggleIntent = PendingIntent.getService(
            this, 1, Intent(this, SalaryService::class.java).apply { action = ACTION_TOGGLE_VIEW },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val toggleText = if (isMonthlyView) "오늘 하루 보기" else "총 월급 보기"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(maxProgress, progress, false)
            .addAction(android.R.drawable.ic_menu_rotate, toggleText, toggleIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "실시간 급여 현황",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "초당 누적 급여를 실시간으로 표시합니다."
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        timer?.cancel()
        timer = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
