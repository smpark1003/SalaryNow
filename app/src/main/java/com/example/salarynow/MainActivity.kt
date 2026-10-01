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
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
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

        val nf = NumberFormat.getNumberInstance(Locale.KOREA)
        val prefs = getSharedPreferences("SalaryPrefs", Context.MODE_PRIVATE)

        // ==========================================
        // 1. [급여 탭] 화면
        // ==========================================
        val scrollView = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#F8FAFC"))
            isFillViewport = true
            clipToPadding = false
            setPadding(0, 0, 0, dp(100))
        }

        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(48), dp(24), dp(36))
        }

        // 1-1. 브랜드 타이틀 헤더
        val headerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(24))
        }

        val brandBadge = TextView(this).apply {
            text = "URA SMART SALARY"
            textSize = 11f
            setTextColor(Color.parseColor("#2563EB"))
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            letterSpacing = 0.1f
        }

        val brandTitle = TextView(this).apply {
            text = "유라 급여"
            textSize = 30f
            setTextColor(Color.parseColor("#0F172A"))
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

        // 1-2. 실수령액 입력 카드
        val salaryCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(22))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), Color.parseColor("#E2E8F0"))
            }
        }

        val cardLabel = TextView(this).apply {
            text = "월 실수령액 (세후 통장 입금액)"
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
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), Color.parseColor("#CBD5E1"))
            }
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(10), 0, dp(8)) }
            layoutParams = params
        }

        val wonSymbol = TextView(this).apply {
            text = "₩ "
            textSize = 21f
            setTextColor(Color.parseColor("#0F172A"))
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        val initialSalary = prefs.getFloat("salary", 2600000f).toLong()

        val salaryInput = EditText(this).apply {
            hint = "2,600,000"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            textSize = 21f
            setTextColor(Color.parseColor("#0F172A"))
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            background = null
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setText(nf.format(initialSalary))
        }

        inputContainer.addView(wonSymbol)
        inputContainer.addView(salaryInput)

        val calcPreview = TextView(this).apply {
            val hourlyWage = initialSalary.toDouble() / 209.0
            val perSec = hourlyWage / 3600.0
            text = "초당 약 ₩${String.format("%.2f", perSec)} 실수령 적립 (월 209시간 기준)"
            textSize = 12f
            setTextColor(Color.parseColor("#64748B"))
        }

        salaryInput.addTextChangedListener(object : TextWatcher {
            private var currentText = ""

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun afterTextChanged(s: Editable?) {
                val str = s.toString()
                if (str != currentText) {
                    val clean = str.replace(",", "").trim()
                    if (clean.isNotEmpty()) {
                        val parsed = clean.toLongOrNull() ?: 0L
                        val formatted = nf.format(parsed)
                        currentText = formatted
                        salaryInput.setText(formatted)
                        salaryInput.setSelection(formatted.length)

                        val hourlyWage = parsed.toDouble() / 209.0
                        val perSec = hourlyWage / 3600.0
                        calcPreview.text = "초당 약 ₩${String.format("%.2f", perSec)} 실수령 적립 (월 209시간 기준)"
                    } else {
                        currentText = ""
                        calcPreview.text = "실수령액을 입력해 주세요"
                    }
                }
            }
        })

        salaryCard.addView(cardLabel)
        salaryCard.addView(inputContainer)
        salaryCard.addView(calcPreview)

        // 1-3. 스마트 스케줄 카드
        val scheduleCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#EFF6FF"))
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), Color.parseColor("#BFDBFE"))
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
            text = "• 평일 08:30 (출근 30분 전) 알림 대기 및 출근 준비\n" +
                    "• 09:00 ~ 18:00 정규 근무 실시간 급여 누적 (실근무 8시간)\n" +
                    "• 12:00 ~ 13:00 점심시간 누적 일시정지\n" +
                    "• 18:00 퇴근 즉시 일일 정산 후 자동 종료"
            textSize = 13f
            setTextColor(Color.parseColor("#2563EB"))
            setLineSpacing(dp(4).toFloat(), 1f)
        }

        scheduleCard.addView(scheduleTitle)
        scheduleCard.addView(scheduleDesc)

        // 1-4. 실행 버튼
        val startBtn = Button(this).apply {
            text = "🚀 자동 출퇴근 모드 실행"
            textSize = 16f
            setTextColor(Color.WHITE)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setPadding(0, dp(16), 0, dp(16))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E293B"))
                cornerRadius = dp(14).toFloat()
            }
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(12)) }
            layoutParams = params

            setOnClickListener {
                val cleanStr = salaryInput.text.toString().replace(",", "").trim()
                val salVal = cleanStr.toFloatOrNull() ?: 2600000f
                prefs.edit().putFloat("salary", salVal).apply()

                SalaryService.scheduleNextWorkAlarm(this@MainActivity)
                checkPermissionAndStartService()
            }
        }

        // 1-5. 정지 버튼
        val stopBtn = Button(this).apply {
            text = "🛑 알림 및 서비스 끄기"
            textSize = 14f
            setTextColor(Color.parseColor("#64748B"))
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setPadding(0, dp(14), 0, dp(14))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E2E8F0"))
                cornerRadius = dp(14).toFloat()
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

        // ==========================================
        // 2. [직장도구 탭] 화면
        // ==========================================
        val toolsView = ScrollView(this).apply {
            visibility = View.GONE
            setBackgroundColor(Color.parseColor("#F8FAFC"))
            isFillViewport = true
            clipToPadding = false
            setPadding(0, 0, 0, dp(100))

            val layout = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24), dp(48), dp(24), dp(36))

                addView(TextView(context).apply {
                    text = "직장인 도구"
                    textSize = 30f
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    setTextColor(Color.parseColor("#0F172A"))
                    setPadding(0, 0, 0, dp(24))
                })

                // 퇴근 대중교통 카드
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(20), dp(22), dp(20), dp(22))
                    background = GradientDrawable().apply {
                        setColor(Color.WHITE)
                        cornerRadius = dp(18).toFloat()
                        setStroke(dp(1), Color.parseColor("#E2E8F0"))
                    }
                    val params = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(16) }
                    layoutParams = params

                    addView(TextView(context).apply {
                        text = "🚌 퇴근 대중교통 전광판"
                        textSize = 16f
                        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                        setTextColor(Color.parseColor("#0F172A"))
                    })
                    addView(TextView(context).apply {
                        text = "회사 앞 버스 / 지하철 실시간 도착 정보 연동 예정"
                        textSize = 13f
                        setTextColor(Color.parseColor("#64748B"))
                        setPadding(0, dp(6), 0, 0)
                    })
                })

                // 점심 메뉴 & N빵 카드
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(20), dp(22), dp(20), dp(22))
                    background = GradientDrawable().apply {
                        setColor(Color.WHITE)
                        cornerRadius = dp(18).toFloat()
                        setStroke(dp(1), Color.parseColor("#E2E8F0"))
                    }
                    addView(TextView(context).apply {
                        text = "🍱 점심 메뉴 룰렛 & 더치페이"
                        textSize = 16f
                        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                        setTextColor(Color.parseColor("#0F172A"))
                    })
                    addView(TextView(context).apply {
                        text = "오늘 뭐 먹지 고민 해결과 1원 단위 더치페이 계산기"
                        textSize = 13f
                        setTextColor(Color.parseColor("#64748B"))
                        setPadding(0, dp(6), 0, 0)
                    })
                })
            }
            addView(layout)
        }

        // ==========================================
        // 3. [설정 탭] 화면
        // ==========================================
        val settingsView = ScrollView(this).apply {
            visibility = View.GONE
            setBackgroundColor(Color.parseColor("#F8FAFC"))
            isFillViewport = true
            clipToPadding = false
            setPadding(0, 0, 0, dp(100))

            val layout = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24), dp(48), dp(24), dp(36))

                addView(TextView(context).apply {
                    text = "환경 설정"
                    textSize = 30f
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    setTextColor(Color.parseColor("#0F172A"))
                    setPadding(0, 0, 0, dp(24))
                })

                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(20), dp(22), dp(20), dp(22))
                    background = GradientDrawable().apply {
                        setColor(Color.WHITE)
                        cornerRadius = dp(18).toFloat()
                        setStroke(dp(1), Color.parseColor("#E2E8F0"))
                    }
                    addView(TextView(context).apply {
                        text = "기본 근무 정보"
                        textSize = 16f
                        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                        setTextColor(Color.parseColor("#0F172A"))
                    })
                    addView(TextView(context).apply {
                        text = "• 정규 근무: 09:00 ~ 18:00 (실근무 8시간)\n• 출근 준비: 평일 08:30 알람 자동 가동\n• 점심 시간: 12:00 ~ 13:00 (누적 일시정지)\n• 주 40시간 / 월 209시간 실수령액 산정"
                        textSize = 13f
                        setTextColor(Color.parseColor("#475569"))
                        setLineSpacing(dp(4).toFloat(), 1f)
                        setPadding(0, dp(8), 0, 0)
                    })
                })
            }
            addView(layout)
        }

        // =========================================================
        // 4. ⭐ iOS 스타일 플로팅 독(Dock) 탭 바
        // =========================================================
        val colorActive = Color.parseColor("#2563EB")
        val colorInactive = Color.parseColor("#94A3B8")

        fun createTabItem(iconRes: Int, label: String, isActive: Boolean): Pair<LinearLayout, Pair<ImageView, TextView>> {
            val iv = ImageView(this).apply {
                setImageResource(iconRes)
                setColorFilter(if (isActive) colorActive else colorInactive)
                layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
            }
            val tv = TextView(this).apply {
                text = label
                textSize = 11f
                typeface = if (isActive) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                setTextColor(if (isActive) colorActive else colorInactive)
                setPadding(0, dp(3), 0, 0)
            }
            val itemLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(18), dp(8), dp(18), dp(8))
                addView(iv)
                addView(tv)
            }
            return Pair(itemLayout, Pair(iv, tv))
        }

        val tabSalary = createTabItem(R.drawable.ic_salary, "급여", true)
        val tabTools = createTabItem(android.R.drawable.ic_menu_agenda, "도구", false)
        val tabSettings = createTabItem(android.R.drawable.ic_menu_preferences, "설정", false)

        val floatingDock = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(4), dp(6), dp(4))
            elevation = dp(14).toFloat()
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(32).toFloat()
                setStroke(dp(1), Color.parseColor("#E2E8F0"))
            }
            addView(tabSalary.first)
            addView(tabTools.first)
            addView(tabSettings.first)
        }

        fun switchTab(idx: Int) {
            scrollView.visibility = if (idx == 0) View.VISIBLE else View.GONE
            toolsView.visibility = if (idx == 1) View.VISIBLE else View.GONE
            settingsView.visibility = if (idx == 2) View.VISIBLE else View.GONE

            fun updateTabVisual(pair: Pair<ImageView, TextView>, selected: Boolean) {
                pair.first.setColorFilter(if (selected) colorActive else colorInactive)
                pair.second.setTextColor(if (selected) colorActive else colorInactive)
                pair.second.typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            }
            updateTabVisual(tabSalary.second, idx == 0)
            updateTabVisual(tabTools.second, idx == 1)
            updateTabVisual(tabSettings.second, idx == 2)
        }

        tabSalary.first.setOnClickListener { switchTab(0) }
        tabTools.first.setOnClickListener { switchTab(1) }
        tabSettings.first.setOnClickListener { switchTab(2) }

        // ==========================================
        // 5. 루트 레이아웃 (화면 3개 + 플로팅 독)
        // ==========================================
        val rootLayout = FrameLayout(this).apply {
            addView(scrollView)
            addView(toolsView)
            addView(settingsView)
            addView(floatingDock, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(24)
            })
        }

        setContentView(rootLayout)
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
            Toast.makeText(this, "유라 급여 가동 시작! 18시 퇴근 시 정산 후 종료됩니다.", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "스케줄 등록 완료! 다음 출근일 08:30에 자동 실행됩니다.", Toast.LENGTH_LONG).show()
        }
    }
}
