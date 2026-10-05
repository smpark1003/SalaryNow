package com.example.salarynow

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import java.text.NumberFormat
import java.util.*

class SalaryService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val nf = NumberFormat.getNumberInstance(Locale.KOREA)
    private var updateRunnable: Runnable? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startSalaryTracker()
        return START_STICKY
    }

    private fun startSalaryTracker() {
        val prefs = getSharedPreferences("SalaryPrefs", Context.MODE_PRIVATE)
        val salary = prefs.getFloat("salary", 2600000f).toDouble()

        val hourlyWage = (salary / 209.0).toLong()
        val perSec = (salary / 209.0) / 3600.0
        val dailySalary = perSec * 8 * 3600

        updateRunnable = object : Runnable {
            override fun run() {
                val now = Calendar.getInstance()
                val hour = now.get(Calendar.HOUR_OF_DAY)
                val minute = now.get(Calendar.MINUTE)
                val second = now.get(Calendar.SECOND)
                val totalSecOfDay = hour * 3600 + minute * 60 + second

                val sec0900 = 9 * 3600            // 09:00 정규 시작
                val sec1200 = 12 * 3600           // 12:00 점심 시작
                val sec1300 = 13 * 3600           // 13:00 오후 시작
                val sec1800 = 18 * 3600           // 18:00 퇴근
                val totalWorkSec = 8 * 3600.0

                val currentDay = now.get(Calendar.DAY_OF_MONTH)
                var pastWeekdays = 0
                val tempCal = Calendar.getInstance().apply {
                    set(Calendar.DAY_OF_MONTH, 1)
                }
                for (d in 1 until currentDay) {
                    tempCal.set(Calendar.DAY_OF_MONTH, d)
                    val dow = tempCal.get(Calendar.DAY_OF_WEEK)
                    if (dow != Calendar.SATURDAY && dow != Calendar.SUNDAY) {
                        pastWeekdays++
                    }
                }
                val pastMonthEarned = pastWeekdays * dailySalary

                when {
                    // 1. [08:30 ~ 08:59] 출근 준비 & 대기 모드
                    totalSecOfDay < sec0900 -> {
                        val waitMinutes = (sec0900 - totalSecOfDay) / 60
                        val monthEarned = pastMonthEarned.toLong()
                        val monthPct = String.format(Locale.KOREA, "%.1f", (monthEarned / salary) * 100)

                        updateRichNotification(
                            cardTitle = "오늘 ₩ 0 (0% 근무함)",
                            cardDesc = "09:00 정규 근무 시작 대기 중 (${waitMinutes + 1}분 남음)\n이달 누적: ₩ ${nf.format(monthEarned)} (${monthPct}%) • 시급 ₩ ${nf.format(hourlyWage)}",
                            nowbarPrimary = "09:00대기",
                            nowbarSecondary = "근무 준비",
                            chipText = "09:00대기/${waitMinutes + 1}분남음",
                            progress = 0
                        )
                    }

                    // 2. [09:00 ~ 11:59] 오전 정규 근무
                    totalSecOfDay in sec0900 until sec1200 -> {
                        val workedSec = totalSecOfDay - sec0900
                        val currentEarned = (workedSec * perSec).toLong()
                        val progressPct = (workedSec / totalWorkSec) * 100.0
                        val remainSec = sec1800 - totalSecOfDay - 3600
                        val remainHour = remainSec / 3600
                        val remainMin = (remainSec % 3600) / 60

                        val monthEarned = (pastMonthEarned + currentEarned).toLong()
                        val monthPct = String.format(Locale.KOREA, "%.1f", (monthEarned / salary) * 100)
                        val progressInt = progressPct.toInt().coerceIn(0, 100)
                        val timeStr = String.format(Locale.KOREA, "%02d:%02d", remainHour, remainMin)

                        updateRichNotification(
                            cardTitle = "오늘 ₩ ${nf.format(currentEarned)} (${progressInt}% 근무함)",
                            cardDesc = "퇴근까지 ${remainHour}시간 ${remainMin}분 남음 (점심 1시간 제외)\n이달 누적 ₩ ${nf.format(monthEarned)} (${monthPct}%) • 초당 ₩ ${String.format(Locale.KOREA, "%.2f", perSec)}",
                            nowbarPrimary = "₩${nf.format(currentEarned)}",
                            nowbarSecondary = "${progressInt}% 근무함",
                            chipText = "₩${nf.format(currentEarned)}/${timeStr}남음",
                            progress = progressInt
                        )
                    }

                    // 3. [12:00 ~ 12:59] 점심시간
                    totalSecOfDay in sec1200 until sec1300 -> {
                        val morningSec = 3 * 3600
                        val morningEarned = (morningSec * perSec).toLong()
                        val progressPct = (morningSec / totalWorkSec) * 100.0
                        val remainSec = sec1800 - sec1300
                        val remainHour = remainSec / 3600
                        val remainMin = (remainSec % 3600) / 60
                        val remainLunchMin = (sec1300 - totalSecOfDay) / 60

                        val monthEarned = (pastMonthEarned + morningEarned).toLong()
                        val monthPct = String.format(Locale.KOREA, "%.1f", (monthEarned / salary) * 100)
                        val progressInt = progressPct.toInt().coerceIn(0, 100)

                        updateRichNotification(
                            cardTitle = "오늘 ₩ ${nf.format(morningEarned)} (${progressInt}% 근무함)",
                            cardDesc = "점심시간 누적 정지 (13:00 오후 근무 재개)\n퇴근까지 ${remainHour}시간 ${remainMin}분 • 이달 누적 ₩ ${nf.format(monthEarned)} (${monthPct}%)",
                            nowbarPrimary = "점심시간",
                            nowbarSecondary = "13시 재개",
                            chipText = "₩${nf.format(morningEarned)}/점심${remainLunchMin + 1}분",
                            progress = progressInt
                        )
                    }

                    // 4. [13:00 ~ 17:59] 오후 정규 근무
                    totalSecOfDay in sec1300 until sec1800 -> {
                        val morningSec = 3 * 3600
                        val afternoonSec = totalSecOfDay - sec1300
                        val workedSec = morningSec + afternoonSec
                        val currentEarned = (workedSec * perSec).toLong()
                        val progressPct = (workedSec / totalWorkSec) * 100.0
                        val remainSec = sec1800 - totalSecOfDay
                        val remainHour = remainSec / 3600
                        val remainMin = (remainSec % 3600) / 60

                        val monthEarned = (pastMonthEarned + currentEarned).toLong()
                        val monthPct = String.format(Locale.KOREA, "%.1f", (monthEarned / salary) * 100)
                        val progressInt = progressPct.toInt().coerceIn(0, 100)
                        val timeStr = String.format(Locale.KOREA, "%02d:%02d", remainHour, remainMin)

                        updateRichNotification(
                            cardTitle = "오늘 ₩ ${nf.format(currentEarned)} (${progressInt}% 근무함)",
                            cardDesc = "퇴근까지 ${remainHour}시간 ${remainMin}분 남음\n이달 누적 ₩ ${nf.format(monthEarned)} (${monthPct}%) • 초당 ₩ ${String.format(Locale.KOREA, "%.2f", perSec)}",
                            nowbarPrimary = "₩${nf.format(currentEarned)}",
                            nowbarSecondary = "${progressInt}% 근무함",
                            chipText = "₩${nf.format(currentEarned)}/${timeStr}남음",
                            progress = progressInt
                        )
                    }

                    // 5. [18:00 이후] 퇴근 정산 완료 및 자동 종료
                    else -> {
                        val totalDailyEarned = dailySalary.toLong()
                        showFinalNotification(totalDailyEarned)
                        scheduleNextWorkAlarm(this@SalaryService)
                        stopSelf()
                        return
                    }
                }

                handler.postDelayed(this, 1000)
            }
        }
        handler.post(updateRunnable!!)
    }

    private fun updateRichNotification(
        cardTitle: String,
        cardDesc: String,
        nowbarPrimary: String,
        nowbarSecondary: String,
        chipText: String,
        progress: Int
    ) {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // ⭐ 삼성 One UI 7 Now Bar (잠금화면/상단바 캡슐) & 알림 카드 분리 주입
        val extras = Bundle().apply {
            putBoolean("com.samsung.android.support.ongoing_activity", true)
            putInt("android.ongoingActivityNoti.style", 1)
            // 1. 알림창 카드용 (상세 리포트)
            putString("android.ongoingActivityNoti.primaryInfo", cardTitle)
            putString("android.ongoingActivityNoti.secondaryInfo", cardDesc)
            // 2. 잠금화면 하단 Now Bar 캡슐용 (초간결 데이터)
            putString("android.ongoingActivityNoti.nowbarPrimaryInfo", nowbarPrimary)
            putString("android.ongoingActivityNoti.nowbarSecondaryInfo", nowbarSecondary)
            // 3. ⭐ 2번째 사진 상단바 실시간 알약 칩 텍스트 (예: ₩39,737/04:45남음)
            putString("android.ongoingActivityNoti.chipExpandedText", chipText)
            putInt("android.ongoingActivityNoti.chipBgColor", Color.parseColor("#2563EB"))
            putInt("android.ongoingActivityNoti.actionType", 1)
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_salary)
            .setContentTitle(cardTitle)
            .setContentText(cardDesc)
            .setSubText(chipText) // 안드로이드 표준 상단바 칩 동기화
            .setProgress(100, progress, false)
            .setStyle(NotificationCompat.BigTextStyle().bigText(cardDesc))
            .setColor(Color.parseColor("#2563EB"))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .addExtras(extras)
            .setContentIntent(pendingIntent)
            .addAction(0, "총 월급 보기", pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun showFinalNotification(dailySalary: Long) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val finalNoti = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_salary)
            .setContentTitle("오늘 근무가 종료되었습니다.")
            .setContentText("오늘 정산 급여: ₩${nf.format(dailySalary)} 적립 완료 (퇴근)")
            .setColor(Color.parseColor("#2563EB"))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        manager.notify(FINAL_NOTI_ID, finalNoti)
    }

    override fun onDestroy() {
        super.onDestroy()
        updateRunnable?.let { handler.removeCallbacks(it) }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "급여 실시간 누적",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "실시간 출퇴근 급여 누적 상태 표시"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "salary_service_channel"
        const val NOTIFICATION_ID = 1001
        const val FINAL_NOTI_ID = 1002

        fun scheduleNextWorkAlarm(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, SalaryService::class.java)
            val pendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                PendingIntent.getForegroundService(
                    context, 0, intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            } else {
                PendingIntent.getService(
                    context, 0, intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            }

            val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 8)
                set(Calendar.MINUTE, 30)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)

                if (timeInMillis <= System.currentTimeMillis()) {
                    add(Calendar.DAY_OF_YEAR, 1)
                }
                while (get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY || get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY) {
                    add(Calendar.DAY_OF_YEAR, 1)
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pendingIntent)
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pendingIntent)
            }
        }
    }
}
