package com.example.salarynow

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.text.NumberFormat
import java.util.*

class SalaryService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val nf = NumberFormat.getNumberInstance(Locale.KOREA)
    private var updateRunnable: Runnable? = null
    private var cachedRoundedIcon: Bitmap? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // 1. URA 로고에 살짝 둥근 모서리(Corner Radius 14dp) 적용
        cachedRoundedIcon = getRoundedBitmap(this, R.drawable.ic_launcher, 14f)
    }

    private fun getRoundedBitmap(context: Context, drawableId: Int, cornerRadiusDp: Float): Bitmap? {
        return try {
            val drawable = ContextCompat.getDrawable(context, drawableId) ?: return null
            val width = drawable.intrinsicWidth.coerceAtLeast(128)
            val height = drawable.intrinsicHeight.coerceAtLeast(128)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, width, height)
            drawable.draw(canvas)

            val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val roundCanvas = Canvas(output)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            val density = context.resources.displayMetrics.density
            val radiusPx = cornerRadiusDp * density
            val rect = RectF(0f, 0f, width.toFloat(), height.toFloat())

            roundCanvas.drawRoundRect(rect, radiusPx, radiusPx, paint)
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
            roundCanvas.drawBitmap(bitmap, 0f, 0f, paint)
            output
        } catch (e: Exception) {
            null
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 3. '퇴근하기' 버튼 클릭 처리: 실시간 누적 종료 및 일일 정산 후 서비스 정지
        if (intent?.action == ACTION_STOP_WORK) {
            val prefs = getSharedPreferences("SalaryPrefs", Context.MODE_PRIVATE)
            val salary = prefs.getFloat("salary", 2600000f).toDouble()
            val perSec = (salary / 209.0) / 3600.0

            val now = Calendar.getInstance()
            val hour = now.get(Calendar.HOUR_OF_DAY)
            val minute = now.get(Calendar.MINUTE)
            val second = now.get(Calendar.SECOND)
            val totalSec = hour * 3600 + minute * 60 + second

            val sec0900 = 9 * 3600
            val sec1200 = 12 * 3600
            val sec1300 = 13 * 3600

            val workedSec = when {
                totalSec < sec0900 -> 0
                totalSec in sec0900 until sec1200 -> totalSec - sec0900
                totalSec in sec1200 until sec1300 -> 3 * 3600
                else -> (3 * 3600) + (totalSec - sec1300).coerceAtMost(5 * 3600)
            }
            val todayEarned = (workedSec * perSec).toLong()

            showFinalNotification(todayEarned)
            scheduleNextWorkAlarm(this)
            stopSelf()
            return START_NOT_STICKY
        }

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
                val totalWorkSec = 8 * 3600.0     // 정규 근무 8시간

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
                    // [구간 0: 08:30 ~ 08:59] 출근 준비
                    totalSecOfDay < sec0900 -> {
                        val waitMinutes = (sec0900 - totalSecOfDay) / 60
                        val monthEarned = pastMonthEarned.toLong()
                        val monthPct = String.format(Locale.KOREA, "%.1f", (monthEarned / salary) * 100)

                        updateRichNotification(
                            cardTitle = "오늘 ₩ 0 (0% 근무함)",
                            cardDesc = "09:00 정규 근무 시작 대기 중 (${waitMinutes + 1}분 남음)\n이달 누적: ₩ ${nf.format(monthEarned)} (${monthPct}%) • 시급 ₩ ${nf.format(hourlyWage)}",
                            nowbarPrimary = "09:00대기",
                            nowbarSecondary = "${waitMinutes + 1}분 후 시작",
                            chipText = "09:00대기/${waitMinutes + 1}분남음",
                            progress = 0
                        )
                    }

                    // 2. [구간 1: 09:00 ~ 11:59] 오전 근무 구간 (점심시간 카운트다운)
                    totalSecOfDay in sec0900 until sec1200 -> {
                        val workedSec = totalSecOfDay - sec0900
                        val currentEarned = (workedSec * perSec).toLong()
                        val progressPct = (workedSec / totalWorkSec) * 100.0
                        val progressInt = progressPct.toInt().coerceIn(0, 100)

                        val remainLunchSec = sec1200 - totalSecOfDay
                        val remainLunchH = remainLunchSec / 3600
                        val remainLunchM = (remainLunchSec % 3600) / 60
                        val lunchCountdownText = if (remainLunchH > 0) "점심 ${remainLunchH}시간 ${remainLunchM}분 남음" else "점심 ${remainLunchM}분 남음"
                        val chipTimeStr = String.format(Locale.KOREA, "%02d:%02d", remainLunchH, remainLunchM)

                        val monthEarned = (pastMonthEarned + currentEarned).toLong()
                        val monthPct = String.format(Locale.KOREA, "%.1f", (monthEarned / salary) * 100)

                        updateRichNotification(
                            cardTitle = "오늘 ₩ ${nf.format(currentEarned)} (${progressInt}% • 오전근무)",
                            cardDesc = "점심시간(12:00)까지 ${if (remainLunchH > 0) "${remainLunchH}시간 " else ""}${remainLunchM}분 남음\n이달 누적 ₩ ${nf.format(monthEarned)} (${monthPct}%) • 초당 ₩ ${String.format(Locale.KOREA, "%.2f", perSec)}",
                            nowbarPrimary = "₩${nf.format(currentEarned)}",
                            nowbarSecondary = lunchCountdownText,
                            chipText = "₩${nf.format(currentEarned)}/점심 ${chipTimeStr}남음",
                            progress = progressInt
                        )
                    }

                    // 2. [구간 2: 12:00 ~ 12:59] 점심시간 구간 (누적 일시정지)
                    totalSecOfDay in sec1200 until sec1300 -> {
                        val morningSec = 3 * 3600
                        val morningEarned = (morningSec * perSec).toLong()
                        val progressPct = (morningSec / totalWorkSec) * 100.0
                        val progressInt = progressPct.toInt().coerceIn(0, 100)
                        val remainLunchMin = (sec1300 - totalSecOfDay) / 60

                        val monthEarned = (pastMonthEarned + morningEarned).toLong()
                        val monthPct = String.format(Locale.KOREA, "%.1f", (monthEarned / salary) * 100)

                        updateRichNotification(
                            cardTitle = "오늘 ₩ ${nf.format(morningEarned)} (${progressInt}% • 점심시간)",
                            cardDesc = "점심시간 누적 정지 (13:00 오후 근무 재개)\n오후 시작까지 ${remainLunchMin + 1}분 남음 • 이달 누적 ₩ ${nf.format(monthEarned)} (${monthPct}%)",
                            nowbarPrimary = "₩${nf.format(morningEarned)}",
                            nowbarSecondary = "점심 ${remainLunchMin + 1}분 남음",
                            chipText = "₩${nf.format(morningEarned)}/점심 ${remainLunchMin + 1}분",
                            progress = progressInt
                        )
                    }

                    // 2. [구간 3: 13:00 ~ 17:59] 오후 근무 구간 (퇴근 카운트다운)
                    totalSecOfDay in sec1300 until sec1800 -> {
                        val morningSec = 3 * 3600
                        val afternoonSec = totalSecOfDay - sec1300
                        val workedSec = morningSec + afternoonSec
                        val currentEarned = (workedSec * perSec).toLong()
                        val progressPct = (workedSec / totalWorkSec) * 100.0
                        val progressInt = progressPct.toInt().coerceIn(0, 100)

                        val remainSec = sec1800 - totalSecOfDay
                        val remainHour = remainSec / 3600
                        val remainMin = (remainSec % 3600) / 60
                        val remainText = if (remainHour > 0) "${remainHour}시간 ${remainMin}분 남음" else "${remainMin}분 남음"
                        val timeStr = String.format(Locale.KOREA, "%02d:%02d", remainHour, remainMin)

                        val monthEarned = (pastMonthEarned + currentEarned).toLong()
                        val monthPct = String.format(Locale.KOREA, "%.1f", (monthEarned / salary) * 100)

                        updateRichNotification(
                            cardTitle = "오늘 ₩ ${nf.format(currentEarned)} (${progressInt}% • 오후근무)",
                            cardDesc = "퇴근(18:00)까지 ${remainHour}시간 ${remainMin}분 남음\n이달 누적 ₩ ${nf.format(monthEarned)} (${monthPct}%) • 초당 ₩ ${String.format(Locale.KOREA, "%.2f", perSec)}",
                            nowbarPrimary = "₩${nf.format(currentEarned)}",
                            nowbarSecondary = remainText,
                            chipText = "₩${nf.format(currentEarned)}/${timeStr}남음",
                            progress = progressInt
                        )
                    }

                    // [정규 퇴근: 18:00 이후]
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
        val openAppIntent = Intent(this, MainActivity::class.java)
        val pendingOpenApp = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // 3. '퇴근하기' 액션 펜딩 인텐트
        val stopWorkIntent = Intent(this, SalaryService::class.java).apply {
            action = ACTION_STOP_WORK
        }
        val pendingStopWork = PendingIntent.getService(
            this, 1, stopWorkIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val extras = Bundle().apply {
            putBoolean("com.samsung.android.support.ongoing_activity", true)
            putInt("android.ongoingActivityNoti.style", 1)
            // 프로그레스바 게이지 속성
            putInt("android.ongoingActivityNoti.progress", progress)
            putInt("android.ongoingActivityNoti.maxProgress", 100)
            putBoolean("android.ongoingActivityNoti.showProgress", true)
            putInt("android.ongoingActivityNoti.progressPercent", progress)
            // 카드 및 나우바 정보
            putString("android.ongoingActivityNoti.primaryInfo", cardTitle)
            putString("android.ongoingActivityNoti.secondaryInfo", cardDesc)
            putString("android.ongoingActivityNoti.nowbarPrimaryInfo", nowbarPrimary)
            putString("android.ongoingActivityNoti.nowbarSecondaryInfo", nowbarSecondary)
            putString("android.ongoingActivityNoti.chipExpandedText", chipText)
            putInt("android.ongoingActivityNoti.chipBgColor", Color.parseColor("#2563EB"))
            putInt("android.ongoingActivityNoti.actionType", 1)
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_salary)
            .setContentTitle(cardTitle)
            .setContentText(cardDesc)
            .setSubText(chipText)
            .setProgress(100, progress, false)
            .setStyle(NotificationCompat.BigTextStyle().bigText(cardDesc))
            .setColor(Color.parseColor("#2563EB"))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .addExtras(extras)
            .setContentIntent(pendingOpenApp)
            .addAction(0, "퇴근하기", pendingStopWork) // '총 월급 보기' 대신 '퇴근하기' 버튼 장착
            .setPriority(NotificationCompat.PRIORITY_LOW)

        // 1. 살짝 둥근 모서리가 적용된 로고 비트맵 적용
        cachedRoundedIcon?.let {
            builder.setLargeIcon(it)
        }

        startForeground(NOTIFICATION_ID, builder.build())
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
            .apply {
                cachedRoundedIcon?.let { setLargeIcon(it) }
            }
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
        const val ACTION_STOP_WORK = "com.example.salarynow.ACTION_STOP_WORK"

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
