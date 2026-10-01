package com.example.salarynow

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.*
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media.app.NotificationCompat.MediaStyle
import java.text.NumberFormat
import java.util.*
import kotlin.concurrent.timer

class SalaryService : Service() {

    private val CHANNEL_ID = "salary_media_channel"
    private val SUMMARY_CHANNEL_ID = "salary_summary_channel"
    private val NOTIF_ID = 2001
    private val SUMMARY_NOTIF_ID = 9999

    private var timer: Timer? = null
    private lateinit var mediaSession: MediaSessionCompat
    private var cachedAlbumArt: Bitmap? = null

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
        cachedAlbumArt = createCustomArt()

        mediaSession = MediaSessionCompat(this, "SalaryNowMedia").apply {
            isActive = true
            setMediaButtonReceiver(null)
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onSkipToNext() {
                    isMonthlyView = !isMonthlyView
                }
                override fun onPlay() {
                    isMonthlyView = false
                }
                override fun onPause() {
                    isMonthlyView = true
                }
            })
        }
    }

    // 휑한 회색 네모를 채워줄 세련된 다크 골드 카드 비트맵 동적 생성
    private fun createCustomArt(): Bitmap {
        val size = 500
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // 세련된 다크 블루-차콜 그라데이션 배경
        val bgPaint = Paint().apply {
            isAntiAlias = true
            shader = LinearGradient(
                0f, 0f, size.toFloat(), size.toFloat(),
                Color.parseColor("#1e293b"), Color.parseColor("#0f172a"),
                Shader.TileMode.CLAMP
            )
        }
        val rect = RectF(0f, 0f, size.toFloat(), size.toFloat())
        canvas.drawRoundRect(rect, 40f, 40f, bgPaint)

        // 메인 ₩ 로고
        val textPaint = Paint().apply {
            color = Color.parseColor("#fbbf24") // 따뜻한 골드
            textSize = 170f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        canvas.drawText("₩", size / 2f, size / 2f + 20f, textPaint)

        // 하단 서브 텍스트
        val subPaint = Paint().apply {
            color = Color.parseColor("#94a3b8")
            textSize = 38f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
            letterSpacing = 0.15f
        }
        canvas.drawText("SALARY NOW", size / 2f, size / 2f + 110f, subPaint)

        return bitmap
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE_VIEW -> isMonthlyView = !isMonthlyView
            ACTION_STOP_SERVICE -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }

        val initialNotif = buildNotification("준비 중...", "데이터를 집계 중입니다.")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, initialNotif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
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

            val isWeekend = (dayOfWeek == Calendar.SATURDAY || dayOfWeek == Calendar.SUNDAY)

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

            val title: String
            val subtitle: String

            if (currentSecOfDay < startWorkSec && !isWeekend) {
                title = "출근 준비 중 (09:00 시작)"
                val remainM = (startWorkSec - currentSecOfDay) / 60
                subtitle = "초당 ₩${String.format("%.2f", perSecWage)} | 출근까지 ${remainM}분"
            } else if (isMonthlyView) {
                val percent = ((monthAccumulated / salary) * 100).coerceIn(0.0, 100.0)
                title = "이번달 누적: ₩ ${nf.format(monthAccumulated.toInt())} (${String.format("%.1f", percent)}%)"
                subtitle = "초당 ₩${String.format("%.2f", perSecWage)} | 월 목표: ₩${nf.format(salary.toInt())}"
            } else {
                val percent = if (totalDailyWorkSec > 0) ((workedTodaySec.toDouble() / totalDailyWorkSec) * 100).coerceIn(0.0, 100.0) else 0.0
                title = "오늘 하루: ₩ ${nf.format(todayAccumulated.toInt())} (${String.format("%.1f", percent)}%)"
                val remainSec = (endWorkSec - currentSecOfDay).coerceAtLeast(0)
                val remH = remainSec / 3600
                val remM = (remainSec % 3600) / 60
                val status = if (isWeekend) "주말 휴무" else "퇴근까지 ${remH}시간 ${remM}분"
                subtitle = "초당 ₩${String.format("%.2f", perSecWage)} | $status"
            }

            // 퇴근 게이지 바 연동 (밀리초 기준)
            val durationMs = (totalDailyWorkSec * 1000L).coerceAtLeast(1000L)
            val currentPosMs = (workedTodaySec * 1000L).coerceIn(0L, durationMs)

            val metaBuilder = MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, subtitle)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, if (isMonthlyView) "실시간 월급 현황" else "오늘 근무 현황")
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durationMs)

            cachedAlbumArt?.let {
                metaBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it)
                metaBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, it)
            }

            mediaSession.setMetadata(metaBuilder.build())

            // 진행률 게이지 바 실제 위치 갱신
            mediaSession.setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setState(PlaybackStateCompat.STATE_PLAYING, currentPosMs, 1.0f)
                    .setActions(PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_SKIP_TO_NEXT)
                    .build()
            )

            val notifManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notifManager.notify(NOTIF_ID, buildNotification(title, subtitle))
        }
    }

    private fun showRetireSummaryNotification(todayEarned: Int, monthEarned: Int, nf: NumberFormat) {
        val notifManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val summaryNotif = NotificationCompat.Builder(this, SUMMARY_CHANNEL_ID)
            .setContentTitle("🎉 오늘 퇴근 완료! 고생하셨습니다.")
            .setContentText("오늘 번 돈: ₩ ${nf.format(todayEarned)} | 이번달 누적: ₩ ${nf.format(monthEarned)}")
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "👏 오늘 하루도 정말 수고 많으셨습니다!\n\n" +
                "• 오늘 번 돈: ₩ ${nf.format(todayEarned)}\n" +
                "• 이번 달 총 누적: ₩ ${nf.format(monthEarned)}\n\n" +
                "🔋 배터리 절전을 위해 서비스가 완전 종료되며, 다음 출근일 08:30에 자동으로 다시 켜집니다."
            ))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
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

    private fun buildNotification(title: String, text: String): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val toggleIntent = PendingIntent.getService(
            this, 1, Intent(this, SalaryService::class.java).apply { action = ACTION_TOGGLE_VIEW },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val toggleLabel = if (isMonthlyView) "오늘 하루 보기" else "총 월급 보기"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setLargeIcon(cachedAlbumArt)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_media_next, toggleLabel, toggleIntent)
            .setStyle(
                MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0)
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)

            val liveChannel = NotificationChannel(
                CHANNEL_ID,
                "실시간 급여 현황 (나우바)",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
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
        mediaSession.release()
        cachedAlbumArt?.recycle()
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
