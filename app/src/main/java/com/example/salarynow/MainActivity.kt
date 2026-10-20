package com.example.salarynow

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. 권한 요청 (알림, 정확한 알람)
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

        // 2. UI 헬퍼 함수 (dp 변환 및 둥근 모서리 플랫 배경 생성)
        fun dpToPx(dp: Float): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics).toInt()

        fun createFlatBackground(colorHex: String, radiusDp: Float): GradientDrawable {
            return GradientDrawable().apply {
                setColor(Color.parseColor(colorHex))
                cornerRadius = dpToPx(radiusDp).toFloat()
            }
        }

        fun createButtonState(normalColor: String, pressedColor: String, radiusDp: Float): StateListDrawable {
            return StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), createFlatBackground(pressedColor, radiusDp))
                addState(intArrayOf(), createFlatBackground(normalColor, radiusDp))
            }
        }

        // 3. 최상위 레이아웃 (토스 스타일 라이트 그레이 배경)
        val scrollView = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#F2F4F6")) // 핀테크 앱 특유의 깔끔한 배경색
        }

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(24f), dpToPx(40f), dpToPx(24f), dpToPx(40f))
        }

        // 4. 타이틀 영역
        val titleText = TextView(this).apply {
            text = "퇴근 타이머"
            textSize = 26f
            setTextColor(Color.parseColor("#191F28")) // 완전 검은색보다 부드러운 다크 차콜
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, dpToPx(8f))
        }

        val subTitleText = TextView(this).apply {
            text = "오늘의 근무 스케줄을 선택하세요"
            textSize = 15f
            setTextColor(Color.parseColor("#8B95A1")) // 모던한 그레이
            setPadding(0, 0, 0, dpToPx(32f))
        }

        rootLayout.addView(titleText)
        rootLayout.addView(subTitleText)

        // 5. 모드 변경 버튼 생성기 (토스 스타일의 큼직하고 둥근 버튼)
        val prefs = getSharedPreferences("TimerPrefs", Context.MODE_PRIVATE)

        fun createTossStyleButton(label: String, modeId: Int, isPrimary: Boolean = false): TextView {
            val bgColor = if (isPrimary) "#3182F6" else "#FFFFFF" // Primary는 블루, 나머지는 화이트 카드
            val pressedColor = if (isPrimary) "#1B64DA" else "#E5E8EB"
            val textColor = if (isPrimary) "#FFFFFF" else "#333D4B"

            return TextView(this).apply {
                text = label
                textSize = 16f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor(textColor))
                gravity = Gravity.CENTER
                background = createButtonState(bgColor, pressedColor, 16f) // 16dp의 부드러운 라운딩
                
                val layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(56f)).apply {
                    setMargins(0, 0, 0, dpToPx(12f))
                }
                this.layoutParams = layoutParams

                setOnClickListener {
                    prefs.edit().putInt("work_mode", modeId).apply()
                    Toast.makeText(this@MainActivity, "$label 설정 완료", Toast.LENGTH_SHORT).show()
                    val intent = Intent(this@MainActivity, SalaryService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(intent)
                    } else {
                        startService(intent)
                    }
                }
            }
        }

        // 6. 버튼 배치
        val btnNormal = createTossStyleButton("평일 정규 종일 근무", SalaryService.MODE_NORMAL, isPrimary = true)
        val btnHalfAM = createTossStyleButton("오전 반차 (14:00 출근)", SalaryService.MODE_HALF_AM)
        val btnHalfPM = createTossStyleButton("오후 반차 (13:00 퇴근)", SalaryService.MODE_HALF_PM)
        val btnHoliday = createTossStyleButton("🔥 휴일 특근 모드", SalaryService.MODE_HOLIDAY)

        rootLayout.addView(btnNormal)
        rootLayout.addView(btnHalfAM)
        rootLayout.addView(btnHalfPM)
        rootLayout.addView(btnHoliday)

        // 7. 하단 여백 및 제어 영역
        rootLayout.addView(TextView(this).apply { height = dpToPx(40f) }) // 간격 띄우기

        val stopLabel = TextView(this).apply {
            text = "타이머 제어"
            textSize = 14f
            setTextColor(Color.parseColor("#8B95A1"))
            setPadding(dpToPx(4f), 0, 0, dpToPx(8f))
        }

        val stopBtn = TextView(this).apply {
            text = "타이머 강제 종료"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#F04452")) // 토스 스타일 레드
            gravity = Gravity.CENTER
            background = createButtonState("#FEE2E2", "#FCA5A5", 16f) // 연한 레드 배경
            
            val layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(56f)).apply {
                setMargins(0, 0, 0, dpToPx(32f))
            }
            this.layoutParams = layoutParams

            setOnClickListener {
                stopService(Intent(this@MainActivity, SalaryService::class.java))
                Toast.makeText(this@MainActivity, "타이머가 종료되었습니다.", Toast.LENGTH_SHORT).show()
            }
        }

        rootLayout.addView(stopLabel)
        rootLayout.addView(stopBtn)

        // 8. 개발자 정보
        val devInfo = TextView(this).apply {
            text = "개발자: 박상민 (010-3343-7131)"
            textSize = 13f
            setTextColor(Color.parseColor("#B0B8C1")) // 연한 텍스트
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        rootLayout.addView(devInfo)
        scrollView.addView(rootLayout)

        setContentView(scrollView)
    }
}
