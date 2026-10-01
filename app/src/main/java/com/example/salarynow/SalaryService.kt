package com.example.salarynow

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import java.text.NumberFormat
import java.util.*
import kotlin.concurrent.timer

class SalaryService : Service() {

    private val CHANNEL_ID = "salary_media_channel"
    private val NOTIF_ID = 2001
    private var timer: Timer? = null
    private lateinit var mediaSession: MediaSessionCompat

    companion object {
        var isMonthlyView = false // false: 오늘 하루, true: 총 월급
        const val ACTION_TOGGLE_VIEW = "ACTION_TOGGLE_VIEW"
        const val ACTION_STOP_SERVICE = "ACTION_STOP_SERVICE"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        // 갤럭시 나우바 미디어 플레이어 세션 생성
        mediaSession = MediaSessionCompat(this, "SalaryNowMedia").apply {
            isActive = true
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE_VIEW -> isMonthlyView = !isMonthlyView
            ACTION_STOP_SERVICE -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }

        val initialNotif = buildNotification("계산 준비 중...", "데이터를 집계 중입니다.")
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
            val startWorkSec = 9 * 3600
            val endWorkSec = 18 * 3600
            val lunchStartSec = 12 * 3600
            val lunchEndSec = 13 * 3600
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

            val title: String
            val subtitle: String

            if (isMonthlyView) {
                val percent = ((monthAccumulated / salary) * 100).coerceIn(0.0, 100.0)
                title = "이번달 ₩ ${nf.format(monthAccumulated.toInt())} (${String.format("%.1f", percent)}%)"
                subtitle = "초당 ₩${String.format("%.2f", perSecWage)} | 월 목표: ₩${nf.format(salary.toInt())}"
            } else {
                val percent = if (totalDailyWorkSec > 0) ((workedTodaySec.toDouble() / totalDailyWorkSec) * 100).coerceIn(0.0, 100.0) else 0.0
                title = "오늘 ₩ ${nf.format(todayAccumulated.toInt())} (${String.format("%.1f", percent)}%)"
                val remainSec = (endWorkSec - currentSecOfDay).coerceAtLeast(0)
                val remH = remainSec / 3600
                val remM = (remainSec % 3600) / 60
                val status = if (isWeekend) "주말 휴무" else if (currentSecOfDay >= endWorkSec) "퇴근 완료" else "퇴근까지 ${remH}시간 ${remM}분"
                subtitle = "초당 ₩${String.format("%.2f", perSecWage)} | $status"
            }

            // 갤럭시 나우바 / 잠금화면 미디어 캡슐에 데이터 전달
            mediaSession.setMetadata(
                MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, subtitle)
                    .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, if (isMonthlyView) "월급 누적기 (월간)" else "월급 누적기 (일간)")
                    .build()
            )

            // One UI가 미디어가 재생 중인 것으로 인식하도록 활성화
            mediaSession.setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setState(PlaybackStateCompat.STATE_PLAYING, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1.0f)
                    .setActions(PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_SKIP_TO_NEXT)
                    .build()
            )

            val notifManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notifManager.notify(NOTIF_ID, buildNotification(title, subtitle))
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

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "실시간 급여 현황 (나우바)",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "미디어 플레이어 기반 실시간 급여 나우바"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        timer?.cancel()
        timer = null
        mediaSession.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
