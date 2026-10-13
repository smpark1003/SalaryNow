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
import java.util.*

class SalaryService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var updateRunnable: Runnable? = null
    private var cachedRoundedIcon: Bitmap? = null
    private var isOvertimeMode: Boolean = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
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
        when (intent?.action) {
            ACTION_STOP_WORK -> {
                isOvertimeMode = false
                showShutdownNotification("퇴근 타이머가 종료되었습니다.")
                scheduleNextWorkAlarm(this)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START_OVERTIME -> {
                isOvertimeMode = true
            }
        }

        startTimer()
        return START_STICKY
    }

    private fun startTimer() {
        updateRunnable?.let { handler.removeCallbacks(it) }

        updateRunnable = object : Runnable {
            override fun run() {
                val now = Calendar.getInstance()
                val hour = now.get(Calendar.HOUR_OF_DAY)
                val minute = now.get(Calendar.MINUTE)
                val second = now.get(Calendar.SECOND)
                val totalSecOfDay = hour * 3600 + minute * 60 + second

                val sec0830 = 8 * 3600 + 30 * 60   // 08:30 출근 카운트다운 시작
                val sec0900 = 9 * 3600             // 09:00 정규 근무 시작
                val sec1200 = 12 * 3600            // 12:00 점심시간 시작
                val sec1300 = 13 * 3600            // 13:00 오후 근무 시작
                val sec1800 = 18 * 3600            // 18:00 정규 퇴근 시간
                val sec1830 = 18 * 3600 + 30 * 60  // 18:30 30분 초과 자동 종료 한계선
                val totalWorkSec = 8 * 3600.0

                val paydayInfo = PaydayCalculator.calculate(now)

                when {
                    // 1. [08:30 이전] 대기
                    totalSecOfDay < sec0830 -> {
                        updateRichNotification(
                            cardTitle = "출근 대기 중",
                            cardDesc = "· 현재 상태: 08:30 출근 카운트다운 대기 중\n· 시작 안내: 09:00 정규 근무 시작\n· 다음 월급일: ${paydayInfo.dateString} (D-${paydayInfo.dDay}일)",
                            nowbarPrimary = "09:00 출근 대기",
                            nowbarSecondary = "08:30 카운트다운 시작",
                            chipText = "출근 대기",
                            actionTitle = "앱 종료",
                            actionIntent = getActionPendingIntent(ACTION_STOP_WORK),
                            progress = 0
                        )
                    }

                    // 2. [08:30 ~ 08:59] 출근시간 30분 전 카운트다운
                    totalSecOfDay in sec0830 until sec0900 -> {
                        val remainSec = sec0900 - totalSecOfDay
                        val m = remainSec / 60
                        val s = remainSec % 60
                        val timeStr = "${m}분 ${s}초"

                        updateRichNotification(
                            cardTitle = "근무 시작 전 · 09:00 출근 예정",
                            cardDesc = "· 현재 상태: 출근 준비 및 근무 대기\n· 잔여 시간: 정규 근무 시작까지 ${timeStr} 남음\n· 오늘 일정: 오전 09:00-12:00 | 점심 12:00-13:00 | 오후 13:00-18:00\n· 다음 월급일: ${paydayInfo.dateString} (D-${paydayInfo.dDay}일)",
                            nowbarPrimary = "근무시작 ${timeStr} 남음",
                            nowbarSecondary = "09:00 정규 근무 시작",
                            chipText = "출근 ${String.format(Locale.KOREA, "%02d:%02d", m, s)}",
                            actionTitle = "앱 종료",
                            actionIntent = getActionPendingIntent(ACTION_STOP_WORK),
                            progress = 0
                        )
                    }

                    // 3. [09:00 ~ 11:59] 오전 근무 (점심시간까지 카운트다운)
                    totalSecOfDay in sec0900 until sec1200 -> {
                        val offRemainSec = sec1800 - totalSecOfDay
                        val lunchRemainSec = sec1200 - totalSecOfDay
                        val workedSec = totalSecOfDay - sec0900
                        val progressPct = ((workedSec / totalWorkSec) * 100.0).coerceIn(0.0, 100.0)
                        val progressInt = progressPct.toInt()

                        val offStr = formatHms(offRemainSec)
                        val lunchStr = formatHms(lunchRemainSec)

                        updateRichNotification(
                            cardTitle = "오전 근무 진행 중 · ${progressInt}% 진행",
                            cardDesc = "· 퇴근 카운트다운: 정규 퇴근(18:00)까지 ${offStr} 남음\n· 점심 카운트다운: 점심시간(12:00)까지 ${lunchStr} 남음\n· 오전 근무시간: 09:00 - 12:00 (총 3시간)\n· 다음 월급일: ${paydayInfo.dateString} (D-${paydayInfo.dDay}일)",
                            nowbarPrimary = "퇴근까지 ${offStr} 남음",
                            nowbarSecondary = "점심시간까지 ${lunchStr} 남음",
                            chipText = "퇴근 ${formatHmsShort(offRemainSec)}",
                            actionTitle = "퇴근하기",
                            actionIntent = getActionPendingIntent(ACTION_STOP_WORK),
                            progress = progressInt
                        )
                    }

                    // 4. [12:00 ~ 12:59] 점심시간 (사무실 복귀까지 카운트다운)
                    totalSecOfDay in sec1200 until sec1300 -> {
                        val offRemainSec = sec1800 - totalSecOfDay
                        val resumeRemainSec = sec1300 - totalSecOfDay
                        val progressInt = (((3 * 3600) / totalWorkSec) * 100.0).toInt()

                        val offStr = formatHms(offRemainSec)
                        val resumeStr = formatHms(resumeRemainSec)

                        updateRichNotification(
                            cardTitle = "점심시간 진행 중 · 누적 정지",
                            cardDesc = "· 복귀 카운트다운: 사무실 복귀(13:00)까지 ${resumeStr} 남음\n· 퇴근 카운트다운: 정규 퇴근(18:00)까지 ${offStr} 남음\n· 오후 근무시간: 13:00 - 18:00 (총 5시간)\n· 다음 월급일: ${paydayInfo.dateString} (D-${paydayInfo.dDay}일)",
                            nowbarPrimary = "퇴근까지 ${offStr} 남음",
                            nowbarSecondary = "사무실 복귀까지 ${resumeStr} 남음",
                            chipText = "복귀 ${formatHmsShort(resumeRemainSec)}",
                            actionTitle = "퇴근하기",
                            actionIntent = getActionPendingIntent(ACTION_STOP_WORK),
                            progress = progressInt
                        )
                    }

                    // 5. [13:00 ~ 17:59] 오후 근무 (월급날까지 카운트다운)
                    totalSecOfDay in sec1300 until sec1800 -> {
                        val offRemainSec = sec1800 - totalSecOfDay
                        val workedSec = (3 * 3600) + (totalSecOfDay - sec1300)
                        val progressPct = ((workedSec / totalWorkSec) * 100.0).coerceIn(0.0, 100.0)
                        val progressInt = progressPct.toInt()

                        val offStr = formatHms(offRemainSec)

                        updateRichNotification(
                            cardTitle = "오후 근무 진행 중 · ${progressInt}% 진행",
                            cardDesc = "· 퇴근 카운트다운: 정규 퇴근(18:00)까지 ${offStr} 남음\n· 당일 누적 근무: ${formatHms(workedSec)} 완료 (${String.format(Locale.KOREA, "%.1f", progressPct)}%)\n· 다음 월급일: ${paydayInfo.dateString} 지급 예정\n· 급여 카운트다운: 월급날까지 ${paydayInfo.dDay}일 남음",
                            nowbarPrimary = "퇴근까지 ${offStr} 남음",
                            nowbarSecondary = "월급날까지 ${paydayInfo.dDay}일 남음",
                            chipText = "퇴근 ${formatHmsShort(offRemainSec)}",
                            actionTitle = "퇴근하기",
                            actionIntent = getActionPendingIntent(ACTION_STOP_WORK),
                            progress = progressInt
                        )
                    }

                    // 6. [18:00 이후] 퇴근시간 초과 및 연장 근무 분기
                    else -> {
                        val overtimeSec = totalSecOfDay - sec1800
                        val overtimeStr = formatHms(overtimeSec)

                        if (!isOvertimeMode) {
                            // 30분 동안 연장 근무 버튼을 누르지 않은 경우: 18:30 자동 종료
                            if (totalSecOfDay >= sec1830) {
                                showShutdownNotification("정규 근무 종료 후 30분이 경과하여 퇴근 타이머를 자동 종료합니다.")
                                scheduleNextWorkAlarm(this@SalaryService)
                                stopSelf()
                                return
                            }

                            val autoCloseRemainSec = sec1830 - totalSecOfDay
                            val autoCloseMin = (autoCloseRemainSec / 60) + 1

                            updateRichNotification(
                                cardTitle = "정규 퇴근 시간 경과 · 연장 대기",
                                cardDesc = "· 초과 근무 시간: 정규 퇴근시간 기준 ${overtimeStr} 지남\n· 자동 종료 안내: 연장 근무 미등록 시 18:30 자동 종료 (${autoCloseMin}분 남음)\n· 연장 근무 등록: 계속 근무하려면 하단 [연장 근무]를 누르세요.\n· 다음 월급일: ${paydayInfo.dateString} (D-${paydayInfo.dDay}일)",
                                nowbarPrimary = "퇴근시간 ${overtimeStr} 지남",
                                nowbarSecondary = "30분 후 자동 종료",
                                chipText = "초과 +${formatHmsShort(overtimeSec)}",
                                actionTitle = "연장 근무",
                                actionIntent = getActionPendingIntent(ACTION_START_OVERTIME),
                                progress = 100
                            )
                        } else {
                            // 연장 근무 모드 활성화: 수동 종료 시까지 나우바 영구 유지
                            updateRichNotification(
                                cardTitle = "연장 근무 진행 중",
                                cardDesc = "· 연장 근무 시간: 정규 퇴근시간(18:00) 기준 ${overtimeStr} 지남\n· 상태: 연장 근무 활성화 (수동 종료 전까지 유지)\n· 종료 안내: 퇴근 시 하단 [연장 근무 종료]를 누르면 종료됩니다.\n· 다음 월급일: ${paydayInfo.dateString} (D-${paydayInfo.dDay}일)",
                                nowbarPrimary = "퇴근시간 ${overtimeStr} 지남",
                                nowbarSecondary = "연장 근무 진행 중",
                                chipText = "연장 +${formatHmsShort(overtimeSec)}",
                                actionTitle = "연장 근무 종료",
                                actionIntent = getActionPendingIntent(ACTION_STOP_WORK),
                                progress = 100
                            )
                        }
                    }
                }

                handler.postDelayed(this, 1000)
            }
        }
        handler.post(updateRunnable!!)
    }

    private fun formatHms(sec: Int): String {
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return if (h > 0) {
            "${h}시간 ${m}분 ${s}초"
        } else {
            "${m}분 ${s}초"
        }
    }

    private fun formatHmsShort(sec: Int): String {
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return if (h > 0) {
            String.format(Locale.KOREA, "%02d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.KOREA, "%02d:%02d", m, s)
        }
    }

    private fun getActionPendingIntent(action: String): PendingIntent {
        val intent = Intent(this, SalaryService::class.java).apply {
            this.action = action
        }
        return PendingIntent.getService(
            this,
            if (action == ACTION_START_OVERTIME) 101 else 102,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun updateRichNotification(
        cardTitle: String,
        cardDesc: String,
        nowbarPrimary: String,
        nowbarSecondary: String,
        chipText: String,
        actionTitle: String,
        actionIntent: PendingIntent,
        progress: Int
    ) {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val pendingOpenApp = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val extras = Bundle().apply {
            putBoolean("com.samsung.android.support.ongoing_activity", true)
            putInt("android.ongoingActivityNoti.style", 1)
            putInt("android.ongoingActivityNoti.progress", progress)
            putInt("android.ongoingActivityNoti.maxProgress", 100)
            putBoolean("android.ongoingActivityNoti.showProgress", true)
            putInt("android.ongoingActivityNoti.progressPercent", progress)
            putString("android.ongoingActivityNoti.primaryInfo", cardTitle)
            putString("android.ongoingActivityNoti.secondaryInfo", cardDesc)
            putString("android.ongoingActivityNoti.nowbarPrimaryInfo", nowbarPrimary)
            putString("android.ongoingActivityNoti.nowbarSecondaryInfo", nowbarSecondary)
            putString("android.ongoingActivityNoti.chipExpandedText", chipText)
            putInt("android.ongoingActivityNoti.chipBgColor", Color.parseColor("#1E3A8A"))
            putInt("android.ongoingActivityNoti.actionType", 1)
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_salary)
            .setContentTitle(cardTitle)
            .setContentText(cardDesc)
            .setSubText(chipText)
            .setProgress(100, progress, false)
            .setStyle(NotificationCompat.BigTextStyle().bigText(cardDesc))
            .setColor(Color.parseColor("#1E3A8A"))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .addExtras(extras)
            .setContentIntent(pendingOpenApp)
            .addAction(0, actionTitle, actionIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        cachedRoundedIcon?.let {
            builder.setLargeIcon(it)
        }

        startForeground(NOTIFICATION_ID, builder.build())
    }

    private fun showShutdownNotification(msg: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val finalNoti = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_salary)
            .setContentTitle("퇴근 타이머 종료")
            .setContentText(msg)
            .setColor(Color.parseColor("#1E3A8A"))
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
                "퇴근 실시간 타이머",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "실시간 퇴근 카운트다운 알림 표시"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "leave_timer_channel"
        const val NOTIFICATION_ID = 2001
        const val FINAL_NOTI_ID = 2002
        const val ACTION_STOP_WORK = "com.example.salarynow.ACTION_STOP_WORK"
        const val ACTION_START_OVERTIME = "com.example.salarynow.ACTION_START_OVERTIME"

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

/**
 * 25일 기준 월급일 자동 계산기
 * - 25일이 주말(토, 일)이거나 공휴일이면 직전 평일로 자동 롤백
 */
object PaydayCalculator {
    private fun isHoliday(year: Int, month: Int, day: Int): Boolean {
        when {
            month == 1 && day == 1 -> return true   // 신정
            month == 3 && day == 1 -> return true   // 삼일절
            month == 5 && day == 5 -> return true   // 어린이날
            month == 6 && day == 6 -> return true   // 현충일
            month == 8 && day == 15 -> return true  // 광복절
            month == 10 && day == 3 -> return true  // 개천절
            month == 10 && day == 9 -> return true  // 한글날
            month == 12 && day == 25 -> return true // 성탄절
        }
        if (year == 2026 && month == 5 && day == 25) return true // 부처님오신날 대체공휴일
        if (year == 2026 && month == 9 && (day in 24..26)) return true // 추석 연휴
        if (year == 2025 && month == 5 && day == 6) return true
        if (year == 2025 && month == 10 && (day in 5..8)) return true
        return false
    }

    fun getPayday(year: Int, month: Int): Calendar {
        val cal = Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month - 1)
            set(Calendar.DAY_OF_MONTH, 25)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        while (true) {
            val dow = cal.get(Calendar.DAY_OF_WEEK)
            val y = cal.get(Calendar.YEAR)
            val m = cal.get(Calendar.MONTH) + 1
            val d = cal.get(Calendar.DAY_OF_MONTH)
            val isWeekend = (dow == Calendar.SATURDAY || dow == Calendar.SUNDAY)
            if (isWeekend || isHoliday(y, m, d)) {
                cal.add(Calendar.DAY_OF_MONTH, -1)
            } else {
                break
            }
        }
        return cal
    }

    data class PaydayInfo(val dDay: Int, val dateString: String)

    fun calculate(todayCal: Calendar): PaydayInfo {
        val y = todayCal.get(Calendar.YEAR)
        val m = todayCal.get(Calendar.MONTH) + 1
        val todayZero = Calendar.getInstance().apply {
            timeInMillis = todayCal.timeInMillis
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        var targetPayday = getPayday(y, m)
        if (todayZero.after(targetPayday)) {
            val nextYear = if (m == 12) y + 1 else y
            val nextMonth = if (m == 12) 1 else m + 1
            targetPayday = getPayday(nextYear, nextMonth)
        }

        val diffMillis = targetPayday.timeInMillis - todayZero.timeInMillis
        val diffDays = (diffMillis / (1000 * 60 * 60 * 24)).toInt()

        val monthName = targetPayday.get(Calendar.MONTH) + 1
        val dayName = targetPayday.get(Calendar.DAY_OF_MONTH)
        val dayOfWeekStr = when (targetPayday.get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> "월"
            Calendar.TUESDAY -> "화"
            Calendar.WEDNESDAY -> "수"
            Calendar.THURSDAY -> "목"
            Calendar.FRIDAY -> "금"
            Calendar.SATURDAY -> "토"
            else -> "일"
        }
        val dateString = "${monthName}월 ${dayName}일(${dayOfWeekStr})"

        return PaydayInfo(diffDays, dateString)
    }
}
