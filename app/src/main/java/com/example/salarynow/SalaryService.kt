package com.example.salarynow

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
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

        // 209시간 기준 초당 급여 (실수령액 / 209 / 3600)
        val perSec = (salary / 209.0) / 3600.0

        updateRunnable = object : Runnable {
            override fun run() {
                val now = Calendar.getInstance()
                val hour = now.get(Calendar.HOUR_OF_DAY)
                val minute = now.get(Calendar.MINUTE)
                val second = now.get(Calendar.SECOND)
                val totalSecOfDay = hour * 3600 + minute * 60 + second

                val sec0900 = 9 * 3600            // 09:00 (정규 시작)
                val sec1200 = 12 * 3600           // 12:00 (점심 시작)
                val sec1300 = 13 * 3600           // 13:00 (오후 시작)
                val sec1800 = 18 * 3600           // 18:00 (퇴근)

                when {
                    // 1. [08:30 ~ 08:59] 출근 준비 & 대기 모드 (누적 0원 유지)
                    totalSecOfDay < sec0900 -> {
                        val waitMinutes = (sec0900 - totalSecOfDay) / 60
                        updateNotification(
                            title = "출근 준비 중 ☕ (누적 대기)",
                            content = "09:00 정규 근무 시작까지 약 ${waitMinutes + 1}분 남았습니다."
                        )
                    }

                    // 2. [09:00 ~ 11:59] 오전 정규 근무
                    totalSecOfDay in sec0900 until sec1200 -> {
                        val workedSec = totalSecOfDay - sec0900
                        val currentEarned = (workedSec * perSec).toLong()
                        val remainLunchMin = (sec1200 - totalSecOfDay) / 60
                        updateNotification(
                            title = "오늘 번 돈(실수령): ₩${nf.format(currentEarned)}",
                            content = "오전 근무 중 | 점심시간(12:00)까지 ${remainLunchMin}분"
                        )
                    }

                    // 3. [12:00 ~ 12:59] 점심시간 (오전 3시간분 고정)
                    totalSecOfDay in sec1200 until sec1300 -> {
                        val morningSec = 3 * 3600
                        val morningEarned = (morningSec * perSec).toLong()
                        updateNotification(
                            title = "오늘 번 돈(실수령): ₩${nf.format(morningEarned)} (점심 정지)",
                            content = "점심시간 푹 쉬세요 🍱 (13:00 오후 근무 재개)"
                        )
                    }

                    // 4. [13:00 ~ 17:59] 오후 정규 근무
                    totalSecOfDay in sec1300 until sec1800 -> {
                        val morningSec = 3 * 3600
                        val afternoonSec = totalSecOfDay - sec1300
                        val currentEarned = ((morningSec + afternoonSec) * perSec).toLong()
                        val remainSec = sec1800 - totalSecOfDay
                        val remainHour = remainSec / 3600
                        val remainMin = (remainSec % 3600) / 60
                        updateNotification(
                            title = "오늘 번 돈(실수령): ₩${nf.format(currentEarned)}",
                            content = "실시간 근무 중 | 칼퇴까지 ${remainHour}시간 ${remainMin}분!"
                        )
                    }

                    // 5. [18:00 이후] 퇴근 정산 완료 및 자동 종료
                    else -> {
                        val totalWorkedSec = 8 * 3600
                        val totalDailyEarned = (totalWorkedSec * perSec).toLong()
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

    private fun updateNotification(title: String, content: String) {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_salary)
            .setContentTitle(title)
            .setContentText(content)
            .setColor(Color.parseColor("#2563EB"))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun showFinalNotification(dailySalary: Long) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val finalNoti = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_salary)
            .setContentTitle("🎉 오늘 하루도 고생 많으셨습니다!")
            .setContentText("오늘 정산 급여(실수령): ₩${nf.format(dailySalary)} 적립 완료 (퇴근)")
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

        // 평일 08:30 준비 모드 자동 기상 알람
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
