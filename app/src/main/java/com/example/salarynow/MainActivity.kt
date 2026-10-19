package com.example.salarynow

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 권한 요청 (알림, 정확한 알람)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            if (!alarmManager.canScheduleExactAlarms()) {
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                    data = Uri.parse("package:$packageName")
                })
            }
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 100, 60, 60)
            setBackgroundColor(android.graphics.Color.WHITE)
        }

        val title = TextView(this).apply {
            text = "퇴근 타이머 대시보드"
            textSize = 28f
            setTextColor(android.graphics.Color.parseColor("#1E3A8A"))
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 80)
        }

        val prefs = getSharedPreferences("TimerPrefs", Context.MODE_PRIVATE)

        fun createModeButton(label: String, modeId: Int): Button {
            return Button(this).apply {
                text = label
                textSize = 16f
                setPadding(0, 40, 0, 40)
                setOnClickListener {
                    prefs.edit().putInt("work_mode", modeId).apply()
                    Toast.makeText(this@MainActivity, "$label 스케줄로 변경되었습니다.", Toast.LENGTH_SHORT).show()
                    val intent = Intent(this@MainActivity, SalaryService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(intent)
                    } else {
                        startService(intent)
                    }
                }
            }
        }

        val btnNormal = createModeButton("평일 정규 종일 근무", SalaryService.MODE_NORMAL)
        val btnHalfAM = createModeButton("오전 반차 (14:00 출근)", SalaryService.MODE_HALF_AM)
        val btnHalfPM = createModeButton("오후 반차 (13:00 퇴근)", SalaryService.MODE_HALF_PM)
        val btnHoliday = createModeButton("휴일 특근 모드 전환", SalaryService.MODE_HOLIDAY)

        val stopBtn = Button(this).apply {
            text = "타이머 강제 종료"
            textSize = 16f
            setPadding(0, 40, 0, 40)
            setBackgroundColor(android.graphics.Color.parseColor("#DC2626"))
            setTextColor(android.graphics.Color.WHITE)
            setOnClickListener {
                stopService(Intent(this@MainActivity, SalaryService::class.java))
                Toast.makeText(this@MainActivity, "타이머가 종료되었습니다.", Toast.LENGTH_SHORT).show()
            }
        }

        val devInfo = TextView(this).apply {
            text = "개발자: 박상민 (010-3343-7131)"
            textSize = 14f
            setTextColor(android.graphics.Color.GRAY)
            gravity = Gravity.CENTER
            setPadding(0, 100, 0, 0)
        }

        layout.addView(title)
        layout.addView(btnNormal)
        layout.addView(btnHalfAM)
        layout.addView(btnHalfPM)
        layout.addView(btnHoliday)
        layout.addView(TextView(this).apply { height = 80 })
        layout.addView(stopBtn)
        layout.addView(devInfo)

        setContentView(layout)
    }
}
