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

    data class NetSalaryResult(
        val monthlyGross: Long,
        val monthlyNet: Long,
        val pension: Long,
        val health: Long,
        val care: Long,
        val employment: Long,
        val incomeTax: Long,
        val localTax: Long,
        val totalDeduction: Long
    )

    private fun calculateNetSalary(annualSalary: Long, severanceIncluded: Boolean): NetSalaryResult {
        val divisor = if (severanceIncluded) 13.0 else 12.0
        val monthlyGross = annualSalary / divisor
        val nontaxable = 200000.0 // 식대 비과세 월 20만 원
        val taxableMonthly = (monthlyGross - nontaxable).coerceAtLeast(0.0)

        // 4대보험
        val pensionBase = taxableMonthly.coerceIn(390000.0, 6170000.0)
        val pension = pensionBase * 0.045
        val health = taxableMonthly * 0.03545
        val care = health * 0.1295
        val employment = taxableMonthly * 0.009
        val insuranceTotal = pension + health + care + employment

        // 근로소득세 간이세액표 근사식
        val annualTaxable = taxableMonthly * 12.0
        val earnedIncomeDeduction = when {
            annualTaxable <= 5000000.0 -> annualTaxable * 0.7
            annualTaxable <= 15000000.0 -> 3500000.0 + (annualTaxable - 5000000.0) * 0.4
            annualTaxable <= 45000000.0 -> 7500000.0 + (annualTaxable - 15000000.0) * 0.15
            annualTaxable <= 100000000.0 -> 12000000.0 + (annualTaxable - 45000000.0) * 0.05
            else -> 14750000.0 + (annualTaxable - 100000000.0) * 0.02
        }
        val incomeAfterDeduction = (annualTaxable - earnedIncomeDeduction).coerceAtLeast(0.0)
        val taxBase = (incomeAfterDeduction - 1500000.0).coerceAtLeast(0.0) // 1인 기본공제

        val annualTax = when {
            taxBase <= 14000000.0 -> taxBase * 0.06
            taxBase <= 50000000.0 -> 840000.0 + (taxBase - 14000000.0) * 0.15
            taxBase <= 88000000.0 -> 6240000.0 + (taxBase - 50000000.0) * 0.24
            else -> 15360000.0 + (taxBase - 88000000.0) * 0.35
        }

        val taxCredit = if (annualTax <= 1300000.0) {
            (annualTax * 0.55).coerceAtMost(740000.0)
        } else {
            (715000.0 + (annualTax - 1300000.0) * 0.3).coerceAtMost(660000.0)
        }

        val finalAnnualTax = (annualTax - taxCredit).coerceAtLeast(0.0)
        val monthlyIncomeTax = finalAnnualTax / 12.0
        val monthlyLocalTax = monthlyIncomeTax * 0.1

        val totalDeduction = insuranceTotal + monthlyIncomeTax + monthlyLocalTax
        val monthlyNet = (monthlyGross - totalDeduction).coerceAtLeast(0.0)

        return NetSalaryResult(
            monthlyGross = monthlyGross.toLong(),
            monthlyNet = monthlyNet.toLong(),
            pension = pension.toLong(),
            health = health.toLong(),
            care = care.toLong(),
            employment = employment.toLong(),
            incomeTax = monthlyIncomeTax.toLong(),
            localTax = monthlyLocalTax.toLong(),
            totalDeduction = totalDeduction.toLong()
        )
    }

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

        // 1-1. 헤더 (이모티콘 제거, 핀테크 타이포그래피)
        val headerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(24))
        }

        val brandBadge = TextView(this).apply {
            text = "URA PAY"
            textSize = 11f
            setTextColor(Color.parseColor("#2563EB"))
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            letterSpacing = 0.12f
        }

        val brandTitle = TextView(this).apply {
            text = "유라 급여"
            textSize = 30f
            setTextColor(Color.parseColor("#0F172A"))
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(2))
        }

        val brandSubtitle = TextView(this).apply {
            text = "실시간 급여 누적 및 출퇴근 관리"
            textSize = 14f
            setTextColor(Color.parseColor("#64748B"))
        }

        headerLayout.addView(brandBadge)
        headerLayout.addView(brandTitle)
        headerLayout.addView(brandSubtitle)

        // 1-2. 급여 설정 카드 (실수령액 vs 연봉 선택형)
        val salaryCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(22))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), Color.parseColor("#E2E8F0"))
            }
        }

        val cardTitle = TextView(this).apply {
            text = "급여 산정 방식"
            textSize = 13f
            setTextColor(Color.parseColor("#475569"))
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        // 방식 선택 탭 (세그먼트 컨트롤)
        var isAnnualMode = false
        var isSeveranceIncludedInSalary = false

        val segmentContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F1F5F9"))
                cornerRadius = dp(10).toFloat()
            }
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(10), 0, dp(14)) }
            layoutParams = params
        }

        val btnModeNet = TextView(this).apply {
            text = "월 실수령액 직접 입력"
            textSize = 12f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(Color.parseColor("#0F172A"))
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(8))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(8).toFloat()
                elevation = dp(2).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val btnModeAnnual = TextView(this).apply {
            text = "연봉(세전)으로 자동 계산"
            textSize = 12f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            setTextColor(Color.parseColor("#64748B"))
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(8))
            background = null
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        segmentContainer.addView(btnModeNet)
        segmentContainer.addView(btnModeAnnual)

        // [모드 A] 월 실수령액 직접 입력 컨테이너
        val netInputContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val netFieldBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F8FAFC"))
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), Color.parseColor("#CBD5E1"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val netSymbol = TextView(this).apply {
            text = "₩ "
            textSize = 20f
            setTextColor(Color.parseColor("#0F172A"))
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        val initialNetSalary = prefs.getFloat("salary", 2600000f).toLong()

        val netSalaryInput = EditText(this).apply {
            hint = "2,600,000"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            textSize = 20f
            setTextColor(Color.parseColor("#0F172A"))
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            background = null
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setText(nf.format(initialNetSalary))
        }

        netFieldBox.addView(netSymbol)
        netFieldBox.addView(netSalaryInput)

        val netPreviewText = TextView(this).apply {
            val perSec = (initialNetSalary.toDouble() / 209.0) / 3600.0
            text = "초당 약 ₩${String.format(Locale.KOREA, "%.2f", perSec)} 실수령 적립 (월 209시간 기준)"
            textSize = 12f
            setTextColor(Color.parseColor("#64748B"))
            setPadding(0, dp(8), 0, 0)
        }

        netInputContainer.addView(netFieldBox)
        netInputContainer.addView(netPreviewText)

        // [모드 B] 연봉(세전) 입력 컨테이너
        val annualInputContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }

        val annualFieldBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F8FAFC"))
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), Color.parseColor("#CBD5E1"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val annualSymbol = TextView(this).apply {
            text = "₩ "
            textSize = 20f
            setTextColor(Color.parseColor("#0F172A"))
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        val annualSalaryInput = EditText(this).apply {
            hint = "36,000,000"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            textSize = 20f
            setTextColor(Color.parseColor("#0F172A"))
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            background = null
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setText(nf.format(36000000L))
        }

        annualFieldBox.addView(annualSymbol)
        annualFieldBox.addView(annualSalaryInput)

        // 퇴직금 포함 여부 선택 버튼 (Chips)
        val severanceContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(10), 0, dp(8)) }
            layoutParams = params
        }

        val btnSevSeparate = TextView(this).apply {
            text = "퇴직금 별도 (연봉/12)"
            textSize = 12f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(Color.parseColor("#2563EB"))
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#EFF6FF"))
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), Color.parseColor("#BFDBFE"))
            }
            val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(6)
            }
            layoutParams = params
        }

        val btnSevIncluded = TextView(this).apply {
            text = "퇴직금 포함 (연봉/13)"
            textSize = 12f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            setTextColor(Color.parseColor("#64748B"))
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F8FAFC"))
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), Color.parseColor("#E2E8F0"))
            }
            val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(6)
            }
            layoutParams = params
        }

        severanceContainer.addView(btnSevSeparate)
        severanceContainer.addView(btnSevIncluded)

        // 연봉 자동계산 결과 요약 카드
        val annualResultCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F1F5F9"))
                cornerRadius = dp(10).toFloat()
            }
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
            layoutParams = params
        }

        val tvAnnualCalculatedNet = TextView(this).apply {
            text = "예상 월 실수령액: ₩ 2,612,798"
            textSize = 14f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(Color.parseColor("#2563EB"))
        }

        val tvAnnualDeductionDetail = TextView(this).apply {
            text = "세전 월 ₩ 3,000,000 | 4대보험·세금 공제 -₩ 387,202"
            textSize = 12f
            setTextColor(Color.parseColor("#64748B"))
            setPadding(0, dp(4), 0, 0)
        }

        annualResultCard.addView(tvAnnualCalculatedNet)
        annualResultCard.addView(tvAnnualDeductionDetail)

        annualInputContainer.addView(annualFieldBox)
        annualInputContainer.addView(severanceContainer)
        annualInputContainer.addView(annualResultCard)

        fun updateAnnualCalculations() {
            val clean = annualSalaryInput.text.toString().replace(",", "").trim()
            val annualVal = clean.toLongOrNull() ?: 0L
            val result = calculateNetSalary(annualVal, isSeveranceIncludedInSalary)

            tvAnnualCalculatedNet.text = "예상 월 실수령액: ₩ ${nf.format(result.monthlyNet)}"
            tvAnnualDeductionDetail.text = "세전 월 ₩ ${nf.format(result.monthlyGross)} | 4대보험·세금 -₩ ${nf.format(result.totalDeduction)}"
        }

        btnSevSeparate.setOnClickListener {
            isSeveranceIncludedInSalary = false
            btnSevSeparate.setTextColor(Color.parseColor("#2563EB"))
            btnSevSeparate.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            (btnSevSeparate.background as GradientDrawable).apply {
                setColor(Color.parseColor("#EFF6FF"))
                setStroke(dp(1), Color.parseColor("#BFDBFE"))
            }

            btnSevIncluded.setTextColor(Color.parseColor("#64748B"))
            btnSevIncluded.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            (btnSevIncluded.background as GradientDrawable).apply {
                setColor(Color.parseColor("#F8FAFC"))
                setStroke(dp(1), Color.parseColor("#E2E8F0"))
            }
            updateAnnualCalculations()
        }

        btnSevIncluded.setOnClickListener {
            isSeveranceIncludedInSalary = true
            btnSevIncluded.setTextColor(Color.parseColor("#2563EB"))
            btnSevIncluded.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            (btnSevIncluded.background as GradientDrawable).apply {
                setColor(Color.parseColor("#EFF6FF"))
                setStroke(dp(1), Color.parseColor("#BFDBFE"))
            }

            btnSevSeparate.setTextColor(Color.parseColor("#64748B"))
            btnSevSeparate.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            (btnSevSeparate.background as GradientDrawable).apply {
                setColor(Color.parseColor("#F8FAFC"))
                setStroke(dp(1), Color.parseColor("#E2E8F0"))
            }
            updateAnnualCalculations()
        }

        btnModeNet.setOnClickListener {
            isAnnualMode = false
            btnModeNet.setTextColor(Color.parseColor("#0F172A"))
            btnModeNet.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            btnModeNet.background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(8).toFloat()
            }

            btnModeAnnual.setTextColor(Color.parseColor("#64748B"))
            btnModeAnnual.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            btnModeAnnual.background = null

            netInputContainer.visibility = View.VISIBLE
            annualInputContainer.visibility = View.GONE
        }

        btnModeAnnual.setOnClickListener {
            isAnnualMode = true
            btnModeAnnual.setTextColor(Color.parseColor("#0F172A"))
            btnModeAnnual.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            btnModeAnnual.background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(8).toFloat()
            }

            btnModeNet.setTextColor(Color.parseColor("#64748B"))
            btnModeNet.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            btnModeNet.background = null

            netInputContainer.visibility = View.GONE
            annualInputContainer.visibility = View.VISIBLE
            updateAnnualCalculations()
        }

        // 천 단위 콤마 포맷터
        fun attachCommaFormatter(editText: EditText, onFormatted: (Long) -> Unit) {
            editText.addTextChangedListener(object : TextWatcher {
                private var current = ""
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val str = s.toString()
                    if (str != current) {
                        val clean = str.replace(",", "").trim()
                        if (clean.isNotEmpty()) {
                            val parsed = clean.toLongOrNull() ?: 0L
                            val formatted = nf.format(parsed)
                            current = formatted
                            editText.setText(formatted)
                            editText.setSelection(formatted.length)
                            onFormatted(parsed)
                        } else {
                            current = ""
                            onFormatted(0L)
                        }
                    }
                }
            })
        }

        attachCommaFormatter(netSalaryInput) { parsed ->
            val perSec = (parsed.toDouble() / 209.0) / 3600.0
            netPreviewText.text = if (parsed > 0) {
                "초당 약 ₩${String.format(Locale.KOREA, "%.2f", perSec)} 실수령 적립 (월 209시간 기준)"
            } else {
                "실수령액을 입력해 주세요"
            }
        }

        attachCommaFormatter(annualSalaryInput) {
            updateAnnualCalculations()
        }

        salaryCard.addView(cardTitle)
        salaryCard.addView(segmentContainer)
        salaryCard.addView(netInputContainer)
        salaryCard.addView(annualInputContainer)

        // 1-3. 스마트 근무 스케줄 카드 (이모티콘 제거)
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
            text = "자동 근무 스케줄 안내"
            textSize = 14f
            setTextColor(Color.parseColor("#1E3A8A"))
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setPadding(0, 0, 0, dp(8))
        }

        val scheduleDesc = TextView(this).apply {
            text = "• 평일 08:30 알림 대기 및 출근 준비 시작\n" +
                    "• 09:00 ~ 18:00 정규 근무 실시간 누적 (실근무 8시간)\n" +
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
            text = "자동 출퇴근 모드 실행"
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
                val finalNetSalary: Float = if (isAnnualMode) {
                    val clean = annualSalaryInput.text.toString().replace(",", "").trim()
                    val annualVal = clean.toLongOrNull() ?: 36000000L
                    calculateNetSalary(annualVal, isSeveranceIncludedInSalary).monthlyNet.toFloat()
                } else {
                    val clean = netSalaryInput.text.toString().replace(",", "").trim()
                    clean.toFloatOrNull() ?: 2600000f
                }

                prefs.edit().putFloat("salary", finalNetSalary).apply()
                SalaryService.scheduleNextWorkAlarm(this@MainActivity)
                checkPermissionAndStartService()
            }
        }

        // 1-5. 정지 버튼
        val stopBtn = Button(this).apply {
            text = "알림 및 서비스 중지"
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
        // 2. [도구 탭] 화면 ('연봉 실수령 계산기' 단독 배치)
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

                // 헤더
                addView(TextView(context).apply {
                    text = "직장인 도구"
                    textSize = 30f
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    setTextColor(Color.parseColor("#0F172A"))
                })
                addView(TextView(context).apply {
                    text = "연봉 및 세금 공제 상세 계산기"
                    textSize = 14f
                    setTextColor(Color.parseColor("#64748B"))
                    setPadding(0, dp(4), 0, dp(24))
                })

                // 연봉 실수령 계산기 카드
                val calcCard = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(20), dp(22), dp(20), dp(22))
                    background = GradientDrawable().apply {
                        setColor(Color.WHITE)
                        cornerRadius = dp(18).toFloat()
                        setStroke(dp(1), Color.parseColor("#E2E8F0"))
                    }
                }

                calcCard.addView(TextView(context).apply {
                    text = "희망/현재 연봉 (세전)"
                    textSize = 13f
                    setTextColor(Color.parseColor("#475569"))
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                })

                val calcInputBox = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(16), dp(12), dp(16), dp(12))
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#F8FAFC"))
                        cornerRadius = dp(12).toFloat()
                        setStroke(dp(1), Color.parseColor("#CBD5E1"))
                    }
                    val params = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(0, dp(8), 0, dp(12)) }
                    layoutParams = params
                }

                calcInputBox.addView(TextView(context).apply {
                    text = "₩ "
                    textSize = 20f
                    setTextColor(Color.parseColor("#0F172A"))
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                })

                val calcSalaryInput = EditText(context).apply {
                    hint = "40,000,000"
                    inputType = android.text.InputType.TYPE_CLASS_NUMBER
                    textSize = 20f
                    setTextColor(Color.parseColor("#0F172A"))
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    background = null
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    setText(nf.format(40000000L))
                }
                calcInputBox.addView(calcSalaryInput)
                calcCard.addView(calcInputBox)

                // 퇴직금 조건 선택 버튼
                var isCalcSeveranceIncluded = false
                val calcSevContainer = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    val params = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(16) }
                    layoutParams = params
                }

                val btnCalcSevSep = TextView(context).apply {
                    text = "퇴직금 별도 (연봉/12)"
                    textSize = 12f
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    setTextColor(Color.parseColor("#2563EB"))
                    gravity = Gravity.CENTER
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#EFF6FF"))
                        cornerRadius = dp(8).toFloat()
                        setStroke(dp(1), Color.parseColor("#BFDBFE"))
                    }
                    val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginEnd = dp(6)
                    }
                    layoutParams = params
                }

                val btnCalcSevInc = TextView(context).apply {
                    text = "퇴직금 포함 (연봉/13)"
                    textSize = 12f
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
                    setTextColor(Color.parseColor("#64748B"))
                    gravity = Gravity.CENTER
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#F8FAFC"))
                        cornerRadius = dp(8).toFloat()
                        setStroke(dp(1), Color.parseColor("#E2E8F0"))
                    }
                    val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginStart = dp(6)
                    }
                    layoutParams = params
                }

                calcSevContainer.addView(btnCalcSevSep)
                calcSevContainer.addView(btnCalcSevInc)
                calcCard.addView(calcSevContainer)

                // 실수령액 결과 하이라이트 박스
                val highlightBox = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(18), dp(16), dp(18), dp(16))
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#EFF6FF"))
                        cornerRadius = dp(14).toFloat()
                    }
                    val params = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(16) }
                    layoutParams = params
                }

                highlightBox.addView(TextView(context).apply {
                    text = "예상 월 실수령액"
                    textSize = 12f
                    setTextColor(Color.parseColor("#1E40AF"))
                })

                val tvCalcBigNet = TextView(context).apply {
                    text = "₩ 2,868,034"
                    textSize = 28f
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    setTextColor(Color.parseColor("#2563EB"))
                    setPadding(0, dp(4), 0, dp(4))
                }
                highlightBox.addView(tvCalcBigNet)

                val tvCalcSubSummary = TextView(context).apply {
                    text = "월 세전 ₩ 3,333,333 | 공제 총액 -₩ 465,299"
                    textSize = 12f
                    setTextColor(Color.parseColor("#64748B"))
                }
                highlightBox.addView(tvCalcSubSummary)
                calcCard.addView(highlightBox)

                // 세부 공제 내역 리스트
                val detailContainer = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#F8FAFC"))
                        cornerRadius = dp(10).toFloat()
                        setStroke(dp(1), Color.parseColor("#E2E8F0"))
                    }
                }

                fun createDetailRow(label: String, value: String): Pair<TextView, TextView> {
                    val row = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(0, dp(4), 0, dp(4))
                    }
                    val tvL = TextView(context).apply {
                        text = label
                        textSize = 12f
                        setTextColor(Color.parseColor("#64748B"))
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    val tvV = TextView(context).apply {
                        text = value
                        textSize = 12f
                        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                        setTextColor(Color.parseColor("#0F172A"))
                    }
                    row.addView(tvL)
                    row.addView(tvV)
                    detailContainer.addView(row)
                    return Pair(tvL, tvV)
                }

                val rowPension = createDetailRow("국민연금 (4.5%)", "₩ 141,000")
                val rowHealth = createDetailRow("건강보험 (3.545%)", "₩ 111,077")
                val rowCare = createDetailRow("장기요양 (12.95%)", "₩ 14,384")
                val rowEmp = createDetailRow("고용보험 (0.9%)", "₩ 28,200")
                val rowTax = createDetailRow("근로소득세 + 지방세", "₩ 170,637")

                calcCard.addView(detailContainer)

                // 이 금액으로 내 급여 적용하기 버튼
                var currentCalculatedNet: Long = 2868034L

                val btnApplyToMySalary = Button(context).apply {
                    text = "이 금액을 내 급여로 적용하기"
                    textSize = 14f
                    setTextColor(Color.WHITE)
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#2563EB"))
                        cornerRadius = dp(12).toFloat()
                    }
                    val params = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(16) }
                    layoutParams = params
                }
                calcCard.addView(btnApplyToMySalary)
                addView(calcCard)

                fun refreshCalculator() {
                    val clean = calcSalaryInput.text.toString().replace(",", "").trim()
                    val annualVal = clean.toLongOrNull() ?: 0L
                    val res = calculateNetSalary(annualVal, isCalcSeveranceIncluded)
                    currentCalculatedNet = res.monthlyNet

                    tvCalcBigNet.text = "₩ ${nf.format(res.monthlyNet)}"
                    tvCalcSubSummary.text = "월 세전 ₩ ${nf.format(res.monthlyGross)} | 공제 총액 -₩ ${nf.format(res.totalDeduction)}"
                    rowPension.second.text = "₩ ${nf.format(res.pension)}"
                    rowHealth.second.text = "₩ ${nf.format(res.health)}"
                    rowCare.second.text = "₩ ${nf.format(res.care)}"
                    rowEmp.second.text = "₩ ${nf.format(res.employment)}"
                    rowTax.second.text = "₩ ${nf.format(res.incomeTax + res.localTax)}"
                }

                btnCalcSevSep.setOnClickListener {
                    isCalcSeveranceIncluded = false
                    btnCalcSevSep.setTextColor(Color.parseColor("#2563EB"))
                    btnCalcSevSep.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    (btnCalcSevSep.background as GradientDrawable).apply {
                        setColor(Color.parseColor("#EFF6FF"))
                        setStroke(dp(1), Color.parseColor("#BFDBFE"))
                    }
                    btnCalcSevInc.setTextColor(Color.parseColor("#64748B"))
                    btnCalcSevInc.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
                    (btnCalcSevInc.background as GradientDrawable).apply {
                        setColor(Color.parseColor("#F8FAFC"))
                        setStroke(dp(1), Color.parseColor("#E2E8F0"))
                    }
                    refreshCalculator()
                }

                btnCalcSevInc.setOnClickListener {
                    isCalcSeveranceIncluded = true
                    btnCalcSevInc.setTextColor(Color.parseColor("#2563EB"))
                    btnCalcSevInc.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    (btnCalcSevInc.background as GradientDrawable).apply {
                        setColor(Color.parseColor("#EFF6FF"))
                        setStroke(dp(1), Color.parseColor("#BFDBFE"))
                    }
                    btnCalcSevSep.setTextColor(Color.parseColor("#64748B"))
                    btnCalcSevSep.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
                    (btnCalcSevSep.background as GradientDrawable).apply {
                        setColor(Color.parseColor("#F8FAFC"))
                        setStroke(dp(1), Color.parseColor("#E2E8F0"))
                    }
                    refreshCalculator()
                }

                attachCommaFormatter(calcSalaryInput) {
                    refreshCalculator()
                }

                refreshCalculator()

                btnApplyToMySalary.setOnClickListener {
                    prefs.edit().putFloat("salary", currentCalculatedNet.toFloat()).apply()
                    netSalaryInput.setText(nf.format(currentCalculatedNet))
                    btnModeNet.performClick()
                    Toast.makeText(context, "월 실수령액 ₩${nf.format(currentCalculatedNet)}이 적용되었습니다.", Toast.LENGTH_SHORT).show()
                }
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
                        text = "• 정규 근무: 09:00 ~ 18:00 (실근무 8시간)\n" +
                                "• 출근 준비: 평일 08:30 알람 자동 가동\n" +
                                "• 점심 시간: 12:00 ~ 13:00 (누적 일시정지)\n" +
                                "• 주 40시간 / 월 209시간 표준 급여 산정"
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
        // 4. ⭐ iOS 스타일 블러 & 부드러운 무빙 전환 플로팅 독(Dock)
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
                setPadding(dp(20), dp(8), dp(20), dp(8))
                addView(iv)
                addView(tv)
            }
            return Pair(itemLayout, Pair(iv, tv))
        }

        val tabSalary = createTabItem(R.drawable.ic_salary, "급여", true)
        val tabTools = createTabItem(android.R.drawable.ic_menu_agenda, "도구", false)
        val tabSettings = createTabItem(android.R.drawable.ic_menu_preferences, "설정", false)

        // 반투명 블러 느낌의 프로스티드 글래스 배경 (iOS Dock 스타일)
        val floatingDock = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(4), dp(6), dp(4))
            elevation = dp(16).toFloat()
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E6FFFFFF")) // 90% 투명도 글래스모피즘
                cornerRadius = dp(32).toFloat()
                setStroke(dp(1), Color.parseColor("#40CBD5E1"))
            }
            addView(tabSalary.first)
            addView(tabTools.first)
            addView(tabSettings.first)
        }

        var currentTab = 0
        val views = listOf(scrollView, toolsView, settingsView)
        val tabPairs = listOf(tabSalary.second, tabTools.second, tabSettings.second)

        // 무빙(Moving) 슬라이드 & 페이드 화면 전환 함수
        fun switchTab(target: Int) {
            if (currentTab == target) return

            val prevView = views[currentTab]
            val nextView = views[target]
            val direction = if (target > currentTab) 1f else -1f
            val screenWidth = resources.displayMetrics.widthPixels.toFloat()

            nextView.visibility = View.VISIBLE
            nextView.alpha = 0f
            nextView.translationX = direction * (screenWidth * 0.2f)

            prevView.animate()
                .translationX(-direction * (screenWidth * 0.2f))
                .alpha(0f)
                .setDuration(220)
                .withEndAction {
                    prevView.visibility = View.GONE
                    prevView.translationX = 0f
                    prevView.alpha = 1f
                }
                .start()

            nextView.animate()
                .translationX(0f)
                .alpha(1f)
                .setDuration(220)
                .start()

            // 탭 시각 효과 업데이트
            for (i in tabPairs.indices) {
                val isSelected = (i == target)
                val pair = tabPairs[i]
                pair.first.setColorFilter(if (isSelected) colorActive else colorInactive)
                pair.second.setTextColor(if (isSelected) colorActive else colorInactive)
                pair.second.typeface = if (isSelected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            }

            currentTab = target
        }

        tabSalary.first.setOnClickListener { switchTab(0) }
        tabTools.first.setOnClickListener { switchTab(1) }
        tabSettings.first.setOnClickListener { switchTab(2) }

        // ==========================================
        // 5. 루트 레이아웃 (3개 화면 + 플로팅 독)
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
