package com.example.salarynow

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.text.NumberFormat
import java.util.*
import kotlin.concurrent.timer

class SalaryService : Service() {

    private val CHANNEL_ID = "salary_dashboard_channel"
    private val SUMMARY_CHANNEL_ID = "salary_summary_channel"
    private val NOTIF_ID = 3001
    private val SUMMARY_NOTIF_ID = 9999

    private var timer: Timer? = null

    companion object {
        var isMonthlyView = false
        const val ACTION_TOGGLE_VIEW = "ACTION_TOGGLE_VIEW"
        const val ACTION_STOP_SERVICE = "ACTION_STOP_SERVICE"

        fun scheduleNextWorkAlarm(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, WorkAlarmReceiver::class.java).apply {
                action = "START_SALARY_SERVICE"
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context, 1002, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val now = Calendar.getInstance()
            val target = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 8)
                set(Calendar.MINUTE, 30)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }

            if (target.before(now) || now.get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY || now.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY) {
                target.add(Calendar.DAY_OF_YEAR, 1)
                while (target.get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY || target.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY) {
                    target.add(Calendar.DAY_OF_YEAR, 1)
                }
            }

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, target.timeInMillis, pendingIntent)
                } else {
                    alarmManager.setExact(AlarmManager.RTC_WAKEUP, target.timeInMillis, pendingIntent)
                }
            } catch (e: Exception) {
                alarmManager.set(AlarmManager.RTC_WAKEUP, target.timeInMillis, pendingIntent)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE_VIEW -> isMonthlyView = !isMonthlyView
            ACTION_STOP_SERVICE -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }

        startForeground(NOTIF_ID, buildPlaceholderNotification())

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

            val isWeekend = (dayOfWeek == Calendar.SATURDAY || dayOfWeek == Calendar.SUNDAY)

            // 18:00 퇴근 도달 시
            if (!isWeekend && currentSecOfDay >= endWorkSec) {
                val passedWorkDays = getPassedWorkDaysBeforeToday(now)
                val totalMonthWage = (passedWorkDays * dailyGoalWage) + dailyGoalWage

                showRetireSummaryNotification(dailyGoalWage.toInt(), totalMonthWage.toInt(), nf)
                scheduleNextWorkAlarm(this@SalaryService)

                timer?.cancel()
                timer = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return@timer
            }

            var workedTodaySec = 0
            if (!isWeekend) {
                if (currentSecOfDay in startWorkSec..endWorkSec) {
                    workedTodaySec = if (currentSecOfDay < lunchStartSec) {
                        currentSecOfDay - startWorkSec
                    } else if (currentSecOfDay in lunchStartSec until lunchEndSec) {
                        lunchStartSec - startWorkSec
                    } else {
                        (currentSecOfDay - startWorkSec) - (lunchEndSec - lunchStartSec)
                    }
                }
            }

            val todayAccumulated = workedTodaySec * perSecWage
            val passedWorkDays = getPassedWorkDaysBeforeToday(now)
            val monthAccumulated = (passedWorkDays * dailyGoalWage) + todayAccumulated

            val remainSec = (endWorkSec - currentSecOfDay).coerceAtLeast(0)
            val remH = remainSec / 3600
            val remM = (remainSec % 3600) / 60

            val todayPercent = if (totalDailyWorkSec > 0) ((workedTodaySec.toDouble() / totalDailyWorkSec) * 100).coerceIn(0.0, 100.0) else 0.0
            val monthPercent = ((monthAccumulated / salary) * 100).coerceIn(0.0, 100.0)

            val title: String
            val contentText: String
            val progressVal: Int

            if (currentSecOfDay < startWorkSec && !isWeekend) {
                val waitMin = (startWorkSec - currentSecOfDay) / 60
                title = "출근 전 (09:00 시작)"
                contentText = "초당 +₩${String.format("%.2f", perSecWage)} · 출근까지 ${waitMin}분"
                progressVal = 0
            } else if (isMonthlyView) {
                title = "이달 ₩ ${nf.format(monthAccumulated.toInt())} (${String.format("%.1f", monthPercent)}%)"
                contentText = "초당 +₩${String.format("%.2f", perSecWage)} · 목표 ₩${nf.format(salary.toInt())}"
                progressVal = monthPercent.toInt()
            } else {
                val timeStatus = if (isWeekend) "주말 휴무" else "퇴근까지 ${remH}시간 ${remM}분"
                title = "오늘 ₩ ${nf.format(todayAccumulated.toInt())} (${todayPercent.toInt()}%)"
                contentText = "초당 +₩${String.format("%.2f", perSecWage)} · $timeStatus"
                progressVal = todayPercent.toInt()
            }

            val expandedDashboard = StringBuilder().apply {
                append("💵 오늘 급여 : ₩ ${nf.format(todayAccumulated.toInt())} (${String.format("%.1f", todayPercent)}%)\n")
                append("💳 이달 누적 : ₩ ${nf.format(monthAccumulated.toInt())} (${String.format("%.1f", monthPercent)}%)\n")
                append("⏱️ 남은 시간 : ${if (isWeekend) "주말" else "${remH}시간 ${remM}분 남음"}\n")
                append("⚡ 초당 수령 : ₩ ${String.format("%.2f", perSecWage)} (시급 ₩ ${nf.format(hourlyWage.toInt())})")
            }.toString()

            val notif = buildRichNotification(title, contentText, expandedDashboard, progressVal)
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, notif)
        }
    }

    private fun buildPlaceholderNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("월급 누적기 준비 중...")
            .setContentText("데이터를 집계하고 있습니다.")
            .setSmallIcon(R.drawable.ic_salary)
            .setShowWhen(false)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun buildRichNotification(
        title: String,
        contentText: String,
        expandedText: String,
        progress: Int
    ): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val toggleIntent = PendingIntent.getService(
            this, 1, Intent(this, SalaryService::class.java).apply { action = ACTION_TOGGLE_VIEW },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val toggleLabel = if (isMonthlyView) "📊 오늘 하루 보기" else "💳 총 월급 보기"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_salary) // 지폐/돈 전용 아이콘 적용
            .setShowWhen(false)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, progress, false)
            .setStyle(NotificationCompat.BigTextStyle().bigText(expandedText))
            .addAction(android.R.drawable.ic_menu_rotate, toggleLabel, toggleIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun showRetireSummaryNotification(todayEarned: Int, monthEarned: Int, nf: NumberFormat) {
        val notifManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val summaryNotif = NotificationCompat.Builder(this, SUMMARY_CHANNEL_ID)
            .setContentTitle("🎉 오늘 퇴근 완료! 고생하셨습니다.")
            .setContentText("오늘 번 돈: ₩ ${nf.format(todayEarned)} | 이달 누적: ₩ ${nf.format(monthEarned)}")
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "👏 오늘 하루도 정말 수고 많으셨습니다!\n\n" +
                "• 오늘 번 돈 : ₩ ${nf.format(todayEarned)}\n" +
                "• 이달 총 누적 : ₩ ${nf.format(monthEarned)}\n\n" +
                "🔋 배터리 절전을 위해 알림이 종료되며, 다음 출근일 08:30에 자동으로 다시 켜집니다."
            ))
            .setSmallIcon(R.drawable.ic_salary)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        notifManager.notify(SUMMARY_NOTIF_ID, summaryNotif)
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

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)

            val liveChannel = NotificationChannel(
                CHANNEL_ID,
                "실시간 급여 대시보드",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "초당 누적 급여를 알림바에 깔끔하게 표시합니다."
                setShowBadge(false)
            }
            nm.createNotificationChannel(liveChannel)

            val summaryChannel = NotificationChannel(
                SUMMARY_CHANNEL_ID,
                "퇴근 정산 알림",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "퇴근 시 오늘 번 돈을 정산하여 알려줍니다."
            }
            nm.createNotificationChannel(summaryChannel)
        }
    }

    override fun onDestroy() {
        timer?.cancel()
        timer = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

class WorkAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val dayOfWeek = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
        if (dayOfWeek != Calendar.SATURDAY && dayOfWeek != Calendar.SUNDAY) {
            val serviceIntent = Intent(context, SalaryService::class.java)
            ContextCompat.startForegroundService(context, serviceIntent)
        }
        SalaryService.scheduleNextWorkAlarm(context)
    }
}
