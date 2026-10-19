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

    private var currentMode: Int = MODE_NORMAL
    private var holidayStartTimeMillis: Long = 0L

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
                showShutdownNotification("타이머가 종료되었습니다.")
                scheduleNextWorkAlarm(this)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START_OVERTIME -> {
                isOvertimeMode = true
            }
            else -> {
                val prefs = getSharedPreferences("TimerPrefs", Context.MODE_PRIVATE)
                currentMode = prefs.getInt("work_mode", MODE_NORMAL)
                
                // 휴무일 모드인지 자동 판단
                val now = Calendar.getInstance()
                val y = now.get(Calendar.YEAR)
                val m = now.get(Calendar.MONTH) + 1
                val d = now.get(Calendar.DAY_OF_MONTH)
                val dow = now.get(Calendar.DAY_OF_WEEK)
                if (dow == Calendar.SATURDAY || dow == Calendar.SUNDAY || PaydayCalculator.isHoliday(y, m, d)) {
                    if (currentMode != MODE_HOLIDAY) {
                        currentMode = MODE_HOLIDAY
                        holidayStartTimeMillis = System.currentTimeMillis()
                    }
                }
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
                val totalSecOfDay = now.get(Calendar.HOUR_OF_DAY) * 3600 + now.get(Calendar.MINUTE) * 60 + now.get(Calendar.SECOND)

                // 출근/퇴근 시간 동적 설정
                val startSec = if (currentMode == MODE_HALF_AM) 14 * 3600 else 9 * 3600
                val waitStartSec = startSec - (30 * 60)
                val endSec = if (currentMode == MODE_HALF_PM) 13 * 3600 else 18 * 3600
                val autoCloseSec = endSec + (30 * 60)
                
                val sec1200 = 12 * 3600
                val sec1300 = 13 * 3600
                val totalWorkSec = (endSec - startSec).toDouble().coerceAtLeast(1.0)
                
                val paydayInfo = PaydayCalculator.calculate(now)

                if (currentMode == MODE_HOLIDAY) {
                    // [특근 모드] 카운트업
                    val elapsedSec = ((System.currentTimeMillis() - holidayStartTimeMillis) / 1000).toInt()
                    val hStr = formatTimerHms(elapsedSec)
                    val startTimeStr = String.format(Locale.KOREA, "%02d:%02d", Calendar.getInstance().apply{timeInMillis=holidayStartTimeMillis}.get(Calendar.HOUR_OF_DAY), Calendar.getInstance().apply{timeInMillis=holidayStartTimeMillis}.get(Calendar.MINUTE))
                    
                    updateRichNotification(
                        cardTitle = "휴일 특근 진행 중",
                        cardDesc = "· 출근 시각: ${startTimeStr}\n· 현재 상태: 휴게시간 공제 없이 실시간 스톱워치 기록 중\n· 누적 시간: 총 ${formatHmsFull(elapsedSec)} 근무",
                        nowbarPrimary = "휴일근무 ${hStr}",
                        nowbarSecondary = "${startTimeStr} 출근 · 근무 중",
                        chipText = "⚡︎ 특근 ${hStr}",
                        actionTitle = "휴일 근무 종료",
                        actionIntent = getActionPendingIntent(ACTION_STOP_WORK),
                        progress = ((elapsedSec / (8 * 3600.0)) * 100).toInt().coerceIn(0, 100)
                    )
                } else {
                    // [정규/반차 모드] 카운트다운
                    when {
                        totalSecOfDay < waitStartSec -> {
                            updateRichNotification(
                                cardTitle = "출근 대기 중",
                                cardDesc = "· 현재 상태: 출근 카운트다운 대기\n· 다음 월급일: ${paydayInfo.dateString} (D-${paydayInfo.dDay})",
                                nowbarPrimary = "출근 대기",
                                nowbarSecondary = "정규 시작 전",
                                chipText = "⎆ 출근 대기",
                                actionTitle = "앱 종료",
                                actionIntent = getActionPendingIntent(ACTION_STOP_WORK),
                                progress = 0
                            )
                        }
                        totalSecOfDay in waitStartSec until startSec -> {
                            val remainSec = startSec - totalSecOfDay
                            val timerStr = formatTimerHmsShort(remainSec)
                            updateRichNotification(
                                cardTitle = "근무 시작 전 카운트다운",
                                cardDesc = "· 잔여 시간: 근무 시작까지 ${formatHmsFull(remainSec)} 남음",
                                nowbarPrimary = "근무시작 ${timerStr} 남음",
                                nowbarSecondary = "정규 시작 카운트다운",
                                chipText = "⎆ 출근 ${timerStr}",
                                actionTitle = "앱 종료",
                                actionIntent = getActionPendingIntent(ACTION_STOP_WORK),
                                progress = 0
                            )
                        }
                        totalSecOfDay in startSec until sec1200 && currentMode != MODE_HALF_AM -> {
                            val offRemain = endSec - totalSecOfDay
                            val lunchRemain = sec1200 - totalSecOfDay
                            val progress = (((totalSecOfDay - startSec) / totalWorkSec) * 100).toInt()
                            
                            updateRichNotification(
                                cardTitle = "오전 근무 중 · ${progress}% 진행",
                                cardDesc = "· 퇴근까지: ${formatHmsFull(offRemain)} 남음\n· 점심까지: ${formatHmsFull(lunchRemain)} 남음\n· 다음 월급일: ${paydayInfo.dateString} (D-${paydayInfo.dDay})",
                                nowbarPrimary = "퇴근까지 ${formatTimerHms(offRemain)}",
                                nowbarSecondary = "점심까지 ${formatTimerHms(lunchRemain)}",
                                chipText = "🖥\uFE0E 퇴근 ${formatTimerHms(offRemain)}",
                                actionTitle = "조기 퇴근",
                                actionIntent = getActionPendingIntent(ACTION_STOP_WORK),
                                progress = progress
                            )
                        }
                        totalSecOfDay in sec1200 until sec1300 && currentMode != MODE_HALF_PM && currentMode != MODE_HALF_AM -> {
                            val offRemain = endSec - totalSecOfDay
                            val resumeRemain = sec1300 - totalSecOfDay
                            val progress = (((sec1200 - startSec) / totalWorkSec) * 100).toInt()
                            
                            updateRichNotification(
                                cardTitle = "점심시간 · 누적 일시정지",
                                cardDesc = "· 복귀까지: ${formatHmsFull(resumeRemain)} 남음\n· 퇴근까지: ${formatHmsFull(offRemain)} 남음",
                                nowbarPrimary = "퇴근까지 ${formatTimerHms(offRemain)}",
                                nowbarSecondary = "복귀까지 ${formatTimerHmsShort(resumeRemain)}",
                                chipText = "🍴\uFE0E 복귀 ${formatTimerHmsShort(resumeRemain)}",
                                actionTitle = "퇴근하기",
                                actionIntent = getActionPendingIntent(ACTION_STOP_WORK),
                                progress = progress
                            )
                        }
                        totalSecOfDay in sec1300 until endSec || (currentMode == MODE_HALF_AM && totalSecOfDay in startSec until endSec) -> {
                            val offRemain = endSec - totalSecOfDay
                            val worked = if (currentMode == MODE_HALF_AM) totalSecOfDay - startSec else (sec1200 - startSec) + (totalSecOfDay - sec1300)
                            val progress = ((worked / totalWorkSec) * 100).toInt()
                            
                            updateRichNotification(
                                cardTitle = "오후 근무 중 · ${progress}% 진행",
                                cardDesc = "· 퇴근까지: ${formatHmsFull(offRemain)} 남음\n· 월급날: ${paydayInfo.dateString} (D-${paydayInfo.dDay})",
                                nowbarPrimary = "퇴근까지 ${formatTimerHms(offRemain)}",
                                nowbarSecondary = if (paydayInfo.dDay == 0) "오늘 급여일 (지급 예정)" else "월급날까지 ${paydayInfo.dDay}일 남음",
                                chipText = "🖥\uFE0E 퇴근 ${formatTimerHms(offRemain)}",
                                actionTitle = "퇴근하기",
                                actionIntent = getActionPendingIntent(ACTION_STOP_WORK),
                                progress = progress
                            )
                        }
                        else -> {
                            val overSec = totalSecOfDay - endSec
                            val timerStr = formatTimerHms(overSec)
                            
                            if (!isOvertimeMode) {
                                if (totalSecOfDay >= autoCloseSec) {
                                    showShutdownNotification("근무 종료 30분 경과로 타이머가 자동 종료되었습니다.")
                                    scheduleNextWorkAlarm(this@SalaryService)
                                    stopSelf()
                                    return
                                }
                                val autoMin = ((autoCloseSec - totalSecOfDay) / 60) + 1
                                updateRichNotification(
                                    cardTitle = "퇴근 경과 · 연장 대기",
                                    cardDesc = "· 초과 시간: ${formatHmsFull(overSec)} 지남\n· ${autoMin}분 후 자동 종료 (연장 근무 미등록 시)",
                                    nowbarPrimary = "퇴근 경과 +${timerStr}",
                                    nowbarSecondary = "${autoMin}분 후 자동 종료",
                                    chipText = "☾ 초과 +${timerStr}",
                                    actionTitle = "연장 근무",
                                    actionIntent = getActionPendingIntent(ACTION_START_OVERTIME),
                                    progress = 100
                                )
                            } else {
                                updateRichNotification(
                                    cardTitle = "연장 근무 진행 중",
                                    cardDesc = "· 연장 시간: 정규 퇴근 후 ${formatHmsFull(overSec)} 지남\n· 상태: 수동 종료 시까지 영구 유지",
                                    nowbarPrimary = "퇴근 경과 +${timerStr}",
                                    nowbarSecondary = "연장 근무 진행 중",
                                    chipText = "☾ 연장 +${timerStr}",
                                    actionTitle = "연장 종료 (퇴근)",
                                    actionIntent = getActionPendingIntent(ACTION_STOP_WORK),
                                    progress = 100
                                )
                            }
                        }
                    }
                }
                handler.postDelayed(this, 1000)
            }
        }
        handler.post(updateRunnable!!)
    }

    private fun formatTimerHms(sec: Int): String {
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return String.format(Locale.KOREA, "%d:%02d:%02d", h, m, s)
    }

    private fun formatTimerHmsShort(sec: Int): String {
        val m = sec / 60
        val s = sec % 60
        return String.format(Locale.KOREA, "%02d:%02d", m, s)
    }

    private fun formatHmsFull(sec: Int): String {
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return if (h > 0) "${h}시간 ${m}분 ${s}초" else "${m}분 ${s}초"
    }

    private fun getActionPendingIntent(action: String): PendingIntent {
        val intent = Intent(this, SalaryService::class.java).apply { this.action = action }
        return PendingIntent.getService(this, if (action == ACTION_START_OVERTIME) 101 else 102, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun updateRichNotification(
        cardTitle: String, cardDesc: String, nowbarPrimary: String, nowbarSecondary: String, chipText: String, actionTitle: String, actionIntent: PendingIntent, progress: Int
    ) {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val pendingOpenApp = PendingIntent.getActivity(this, 0, openAppIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

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
            .setSmallIcon(R.drawable.ic_launcher_foreground)
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

        cachedRoundedIcon?.let { builder.setLargeIcon(it) }
        startForeground(NOTIFICATION_ID, builder.build())
    }

    private fun showShutdownNotification(msg: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val finalNoti = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("퇴근 타이머 알림")
            .setContentText(msg)
            .setColor(Color.parseColor("#1E3A8A"))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .apply { cachedRoundedIcon?.let { setLargeIcon(it) } }
            .build()
        manager.notify(FINAL_NOTI_ID, finalNoti)
    }

    override fun onDestroy() {
        super.onDestroy()
        updateRunnable?.let { handler.removeCallbacks(it) }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "퇴근 실시간 타이머", NotificationManager.IMPORTANCE_LOW).apply {
                description = "실시간 퇴근 카운트다운 알림 표시"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "leave_timer_channel"
        const val NOTIFICATION_ID = 2001
        const val FINAL_NOTI_ID = 2002
        const val ACTION_STOP_WORK = "com.example.salarynow.ACTION_STOP_WORK"
        const val ACTION_START_OVERTIME = "com.example.salarynow.ACTION_START_OVERTIME"
        
        const val MODE_NORMAL = 0
        const val MODE_HALF_AM = 1
        const val MODE_HALF_PM = 2
        const val MODE_HOLIDAY = 3

        fun scheduleNextWorkAlarm(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, SalaryService::class.java)
            val pendingIntent = PendingIntent.getService(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

            val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 8)
                set(Calendar.MINUTE, 30)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
                
                while (true) {
                    val y = get(Calendar.YEAR)
                    val m = get(Calendar.MONTH) + 1
                    val d = get(Calendar.DAY_OF_MONTH)
                    val dow = get(Calendar.DAY_OF_WEEK)
                    if (dow == Calendar.SATURDAY || dow == Calendar.SUNDAY || PaydayCalculator.isHoliday(y, m, d)) {
                        add(Calendar.DAY_OF_YEAR, 1)
                    } else {
                        break
                    }
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

object PaydayCalculator {
    fun isHoliday(year: Int, month: Int, day: Int): Boolean {
        when {
            month == 1 && day == 1 -> return true
            month == 3 && day == 1 -> return true
            month == 5 && day == 5 -> return true
            month == 6 && day == 6 -> return true
            month == 8 && day == 15 -> return true
            month == 10 && day == 3 -> return true
            month == 10 && day == 9 -> return true
            month == 12 && day == 25 -> return true
        }
        if (year == 2026 && month == 5 && day == 25) return true
        if (year == 2026 && month == 9 && (day in 24..26)) return true
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
        }
        while (true) {
            val dow = cal.get(Calendar.DAY_OF_WEEK)
            val y = cal.get(Calendar.YEAR)
            val m = cal.get(Calendar.MONTH) + 1
            val d = cal.get(Calendar.DAY_OF_MONTH)
            if (dow == Calendar.SATURDAY || dow == Calendar.SUNDAY || isHoliday(y, m, d)) {
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
            targetPayday = getPayday(if (m == 12) y + 1 else y, if (m == 12) 1 else m + 1)
        }

        val diffDays = ((targetPayday.timeInMillis - todayZero.timeInMillis) / (1000 * 60 * 60 * 24)).toInt()
        val dStr = when (targetPayday.get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> "월"
            Calendar.TUESDAY -> "화"
            Calendar.WEDNESDAY -> "수"
            Calendar.THURSDAY -> "목"
            Calendar.FRIDAY -> "금"
            Calendar.SATURDAY -> "토"
            else -> "일"
        }
        return PaydayInfo(diffDays, "${targetPayday.get(Calendar.MONTH) + 1}월 ${targetPayday.get(Calendar.DAY_OF_MONTH)}일($dStr)")
    }
}
