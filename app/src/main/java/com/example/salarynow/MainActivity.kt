package com.example.salarynow

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.text.NumberFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()

        // 전체 스크롤 뷰
        val scrollView = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#F8FAFC")) // 세련된 Slate-50 배경
            isFillViewport = true
        }

        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(48), dp(24), dp(36))
        }

        // 1. 헤더 (브랜드 타이틀)
        val headerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(24))
        }

        val brandBadge = TextView(this).apply {
            text = "SMART SALARY TRACKER"
            textSize = 11f
            setTextColor(Color.parseColor("#2563EB")) // 코발트 블루
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            letterSpacing = 0.1f
        }

        val brandTitle = TextView(this).apply {
            text = "URA Pay"
            textSize = 30f
            setTextColor(Color.parseColor("#0F172A")) // 딥 네이비
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(2))
        }

        val brandSubtitle = TextView(this).apply {
            text = "실시간 급여 누적 & 스마트 퇴근 메이트"
            textSize = 14f
            setTextColor(Color.parseColor("#64748B"))
        }

        headerLayout.addView(brandBadge)
        headerLayout.addView(brandTitle)
        headerLayout.addView(brandSubtitle)

        // 2. 급여 설정 카드
        val salaryCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            elevation = dp(4).toFloat()
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(20).toFloat()
            }
        }

        val cardLabel = TextView(this).apply {
            text = "월 기본급 (세전)"
            textSize = 13f
            setTextColor(Color.parseColor("#475569"))
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        val inputContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F1F5F9"))
                cornerRadius = dp(14).toFloat()
            }
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(10), 0, dp(8)) }
            layoutParams = params
        }

        val wonSymbol = TextView(this).apply {
            text = "₩ "
            textSize = 20f
            setTextColor(Color.parseColor("#0F172A"))
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        val prefs = getSharedPreferences("SalaryPrefs", Context.MODE_PRIVATE)
        val salaryInput = EditText(this).apply {
            hint = "3000000"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            textSize = 20f
            setTextColor(Color.parseColor("#0F172A"))
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            background = null
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setText(prefs.getFloat("salary", 3000000f).toInt().toString())
        }

        inputContainer.addView(wonSymbol)
        inputContainer.addView(salaryInput)

        val calcPreview = TextView(this).apply {
            text = "초당 약 ₩3.41 적립 (월 209시간 주 40시간 기준)"
            textSize = 12f
            setTextColor(Color.parseColor("#94A3B8"))
        }

        salaryCard.addView(cardLabel)
        salaryCard.addView(inputContainer)
        salaryCard.addView(calcPreview)

        // 3. 스마트 스케줄 정보 카드
        val scheduleCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#EFF6FF")) // 부드러운 블루 틴트
                cornerRadius = dp(20).toFloat()
            }
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(16), 0, dp(28)) }
            layoutParams = params
        }

        val scheduleTitle = TextView(this).apply {
            text = "⚡ 스마트 자동 근무 스케줄"
            textSize = 14f
            setTextColor(Color.parseColor("#1E3A8A"))
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setPadding(0, 0, 0, dp(8))
        }

        val scheduleDesc = TextView(this).apply {
            text = "• 평일 08:30 (출근 30분 전) 자동 시작\n" +
                   "• 12:00 ~ 13:00 점심시간 누적 일시정지\n" +
                   "• 18:00 퇴근 즉시 정산 알림 후 자동 종료\n" +
                   "• 퇴근 후 백그라운드 배터리 소모 0%"
            textSize = 13f
            setTextColor(Color.parseColor("#3B82F6"))
            setLineSpacing(dp(4).toFloat(), 1f)
        }

        scheduleCard.addView(scheduleTitle)
        scheduleCard.addView(scheduleDesc)

        // 4. 세련된 실행 버튼
        val startBtn = Button(this).apply {
            text = "🚀 자동 출퇴근 모드 실행"
            textSize = 16f
            setTextColor(Color.WHITE)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setPadding(0, dp(16), 0, dp(16))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E293B")) // 고급스러운 딥 네이비
                cornerRadius = dp(16).toFloat()
            }
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(12)) }
            layoutParams = params

            setOnClickListener {
                val salVal = salaryInput.text.toString().toFloatOrNull() ?: 3000000f
                prefs.edit().putFloat("salary", salVal).apply()

                SalaryService.scheduleNextWorkAlarm(this@MainActivity)
                checkPermissionAndStartService()
            }
        }

        // 5. 정지 버튼
        val stopBtn = Button(this).apply {
            text = "🛑 알림 및 서비스 끄기"
            textSize = 14f
            setTextColor(Color.parseColor("#64748B"))
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setPadding(0, dp(14), 0, dp(14))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E2E8F0"))
                cornerRadius = dp(16).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )

            setOnClickListener {
                stopService(Intent(this@MainActivity, SalaryService::class.java))
                Toast.makeText(this@MainActivity, "알림 및 자동 스케줄이 중지되었습니다.", Toast.LENGTH_SHORT).show()
            }
        }

        mainLayout.addView(headerLayout)
        mainLayout.addView(salaryCard)
        mainLayout.addView(scheduleCard)
        mainLayout.addView(startBtn)
        mainLayout.addView(stopBtn)

        scrollView.addView(mainLayout)
        setContentView(scrollView)
    }

    private fun checkPermissionAndStartService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
                return
            }
        }
        startSalaryService()
    }

    private fun startSalaryService() {
        val now = Calendar.getInstance()
        val hour = now.get(Calendar.HOUR_OF_DAY)
        val isWeekend = (now.get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY || now.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY)

        val serviceIntent = Intent(this, SalaryService::class.java)
        if (!isWeekend && hour in 8..17) {
            ContextCompat.startForegroundService(this, serviceIntent)
            Toast.makeText(this, "URA Pay 가동 시작! 18시 퇴근 시 정산 후 종료됩니다.", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "스케줄 등록 완료! 다음 출근일 08:30에 자동 실행됩니다.", Toast.LENGTH_LONG).show()
        }
    }
}
