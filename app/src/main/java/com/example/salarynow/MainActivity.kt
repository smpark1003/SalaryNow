package com.example.salarynow

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.File
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
        val nontaxable = 200000.0
        val taxableMonthly = (monthlyGross - nontaxable).coerceAtLeast(0.0)

        val pensionBase = taxableMonthly.coerceIn(390000.0, 6170000.0)
        val pension = pensionBase * 0.045
        val health = taxableMonthly * 0.03545
        val care = health * 0.1295
        val employment = taxableMonthly * 0.009
        val insuranceTotal = pension + health + care + employment

        val annualTaxable = taxableMonthly * 12.0
        val earnedIncomeDeduction = when {
            annualTaxable <= 5000000.0 -> annualTaxable * 0.7
            annualTaxable <= 15000000.0 -> 3500000.0 + (annualTaxable - 5000000.0) * 0.4
            annualTaxable <= 45000000.0 -> 7500000.0 + (annualTaxable - 15000000.0) * 0.15
            annualTaxable <= 100000000.0 -> 12000000.0 + (annualTaxable - 45000000.0) * 0.05
            else -> 14750000.0 + (annualTaxable - 100000000.0) * 0.02
        }
        val incomeAfterDeduction = (annualTaxable - earnedIncomeDeduction).coerceAtLeast(0.0)
        val taxBase = (incomeAfterDeduction - 1500000.0).coerceAtLeast(0.0)

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

    // 문서(Documents) 폴더 저장 함수
    private fun backupToDocuments(context: Context, jsonStr: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val uri = MediaStore.Files.getContentUri("external")
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "salary_backup.json")
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS + "/URAPay")
                }
                val itemUri = resolver.insert(uri, values)
                itemUri?.let {
                    resolver.openOutputStream(it)?.use { os ->
                        os.write(jsonStr.toByteArray(Charsets.UTF_8))
                    }
                    true
                } ?: false
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "URAPay")
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, "salary_backup.json")
                file.writeText(jsonStr, Charsets.UTF_8)
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    // 문서(Documents) 폴더 읽기 함수
    private fun readFromDocuments(context: Context): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val projection = arrayOf(MediaStore.MediaColumns._ID)
                val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
                val selectionArgs = arrayOf("salary_backup.json", "%${Environment.DIRECTORY_DOCUMENTS}/URAPay%")
                val cursor = resolver.query(
                    MediaStore.Files.getContentUri("external"),
                    projection,
                    selection,
                    selectionArgs,
                    null
                )
                cursor?.use {
                    if (it.moveToFirst()) {
                        val id = it.getLong(it.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                        val contentUri = MediaStore.Files.getContentUri("external").buildUpon().appendPath(id.toString()).build()
                        resolver.openInputStream(contentUri)?.use { stream ->
                            return stream.bufferedReader().readText()
                        }
                    }
                }
                null
            } else {
                val file = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "URAPay/salary_backup.json")
                if (file.exists()) file.readText(Charsets.UTF_8) else null
            }
        } catch (e: Exception) {
            null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()

        val nf = NumberFormat.getNumberInstance(Locale.KOREA)
        val prefs = getSharedPreferences("SalaryPrefs", Context.MODE_PRIVATE)

        // 앱 최초 실행 시 문서 폴더의 기존 백업 데이터 자동 복원
        if (!prefs.contains("salary")) {
            readFromDocuments(this)?.let { jsonStr ->
                try {
                    val obj = JSONObject(jsonStr)
                    prefs.edit()
                        .putFloat("salary", obj.optDouble("salary", 2600000.0).toFloat())
                        .putFloat("total_leave", obj.optDouble("total_leave", 15.0).toFloat())
                        .putFloat("used_leave", obj.optDouble("used_leave", 0.0).toFloat())
                        .apply()
                } catch (_: Exception) {}
            }
        }

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

        // 1-1. 헤더 (핀테크 타이포그래피)
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

        // 1-2. 급여 설정 카드
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

        // [모드 A] 월 실수령액 직접 입력
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

        // [모드 B] 연봉(세전) 입력
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

        // 1-3. 스마트 근무 스케줄 카드
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

        // 문서 폴더 자동 백업 실행 함수
        fun autoSaveBackup() {
            val s = prefs.getFloat("salary", 2600000f)
            val tl = prefs.getFloat("total_leave", 15.0f)
            val ul = prefs.getFloat("used_leave", 0.0f)
            val json = JSONObject().apply {
                put("salary", s.toDouble())
                put("total_leave", tl.toDouble())
                put("used_leave", ul.toDouble())
                put("backup_time", System.currentTimeMillis())
            }
            backupToDocuments(this, json.toString())
        }

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
                autoSaveBackup()
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
        // 2. [도구 탭] 화면 ('연봉 계산기' + '연차/반차 계산기')
        // ==========================================
        var triggerLeaveRefresh: (() -> Unit)? = null

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
                })
                addView(TextView(context).apply {
                    text = "직장 생활에 필요한 필수 도구 모음"
                    textSize = 14f
                    setTextColor(Color.parseColor("#64748B"))
                    setPadding(0, dp(4), 0, dp(24))
                })

                // 도구 1: 연봉 실수령 계산기 카드
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
                    text = "연봉 실수령 계산기"
                    textSize = 18f
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    setTextColor(Color.parseColor("#0F172A"))
                })
                calcCard.addView(TextView(context).apply {
                    text = "연봉 및 세금 공제 상세 계산"
                    textSize = 12f
                    setTextColor(Color.parseColor("#64748B"))
                    setPadding(0, dp(2), 0, dp(14))
                })

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
                    autoSaveBackup()
                    netSalaryInput.setText(nf.format(currentCalculatedNet))
                    btnModeNet.performClick()
                    Toast.makeText(context, "월 실수령액 ₩${nf.format(currentCalculatedNet)}이 적용되었습니다.", Toast.LENGTH_SHORT).show()
                }

                // ---------------------------------------------------------
                // 도구 2: 연차 · 반차 계산기 카드
                // ---------------------------------------------------------
                val leaveCard = LinearLayout(context).apply {
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
                    ).apply { topMargin = dp(20) }
                    layoutParams = params
                }

                leaveCard.addView(TextView(context).apply {
                    text = "연차 · 반차 계산기"
                    textSize = 18f
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    setTextColor(Color.parseColor("#0F172A"))
                })
                leaveCard.addView(TextView(context).apply {
                    text = "잔여 연차 관리 및 미사용 연차보상금 추정"
                    textSize = 12f
                    setTextColor(Color.parseColor("#64748B"))
                    setPadding(0, dp(2), 0, dp(16))
                })

                val totalLeaveRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(16), dp(10), dp(16), dp(10))
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#F8FAFC"))
                        cornerRadius = dp(12).toFloat()
                        setStroke(dp(1), Color.parseColor("#CBD5E1"))
                    }
                }

                totalLeaveRow.addView(TextView(context).apply {
                    text = "총 부여 연차"
                    textSize = 13f
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    setTextColor(Color.parseColor("#475569"))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })

                var totalLeave = prefs.getFloat("total_leave", 15.0f)
                var usedLeave = prefs.getFloat("used_leave", 0.0f)

                val etTotalLeave = EditText(context).apply {
                    hint = "15.0"
                    inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                    textSize = 16f
                    gravity = Gravity.END
                    setTextColor(Color.parseColor("#0F172A"))
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    background = null
                    setText(String.format(Locale.KOREA, "%.1f", totalLeave))
                    layoutParams = LinearLayout.LayoutParams(dp(70), LinearLayout.LayoutParams.WRAP_CONTENT)
                }
                totalLeaveRow.addView(etTotalLeave)
                totalLeaveRow.addView(TextView(context).apply {
                    text = " 일"
                    textSize = 14f
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    setTextColor(Color.parseColor("#0F172A"))
                })
                leaveCard.addView(totalLeaveRow)

                val leaveHighlightBox = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(18), dp(16), dp(18), dp(16))
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#EFF6FF"))
                        cornerRadius = dp(14).toFloat()
                    }
                    val params = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(0, dp(14), 0, dp(14)) }
                    layoutParams = params
                }

                leaveHighlightBox.addView(TextView(context).apply {
                    text = "현재 잔여 연차"
                    textSize = 12f
                    setTextColor(Color.parseColor("#1E40AF"))
                })

                val tvRemainLeave = TextView(context).apply {
                    textSize = 28f
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    setTextColor(Color.parseColor("#2563EB"))
                    setPadding(0, dp(4), 0, dp(4))
                }
                leaveHighlightBox.addView(tvRemainLeave)

                val tvLeaveSubInfo = TextView(context).apply {
                    textSize = 12f
                    setTextColor(Color.parseColor("#64748B"))
                }
                leaveHighlightBox.addView(tvLeaveSubInfo)
                leaveCard.addView(leaveHighlightBox)

                val buttonGroup = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    val params = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(12) }
                    layoutParams = params
                }

                fun createActionButton(title: String, bgColor: String, textColor: String): Button {
                    return Button(context).apply {
                        text = title
                        textSize = 12f
                        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                        setTextColor(Color.parseColor(textColor))
                        background = GradientDrawable().apply {
                            setColor(Color.parseColor(bgColor))
                            cornerRadius = dp(10).toFloat()
                        }
                        setPadding(0, dp(10), 0, dp(10))
                    }
                }

                val btnUseHalf = createActionButton("+ 반차 (0.5일)", "#EFF6FF", "#2563EB").apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(4) }
                }
                val btnUseDay = createActionButton("+ 연차 (1.0일)", "#2563EB", "#FFFFFF").apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(4) }
                }
                val btnCancelLeave = createActionButton("차감 취소 (-0.5일)", "#F1F5F9", "#64748B").apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f)
                }

                buttonGroup.addView(btnUseHalf)
                buttonGroup.addView(btnUseDay)
                buttonGroup.addView(btnCancelLeave)
                leaveCard.addView(buttonGroup)

                val allowanceBox = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#F8FAFC"))
                        cornerRadius = dp(10).toFloat()
                        setStroke(dp(1), Color.parseColor("#E2E8F0"))
                    }
                }

                val tvAllowanceLabel = TextView(context).apply {
                    text = "미사용 연차 보상금 예상액"
                    textSize = 11f
                    setTextColor(Color.parseColor("#64748B"))
                }
                val tvAllowanceValue = TextView(context).apply {
                    textSize = 15f
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    setTextColor(Color.parseColor("#0F172A"))
                    setPadding(0, dp(2), 0, 0)
                }
                allowanceBox.addView(tvAllowanceLabel)
                allowanceBox.addView(tvAllowanceValue)
                leaveCard.addView(allowanceBox)

                fun refreshLeaveUI() {
                    totalLeave = prefs.getFloat("total_leave", 15.0f)
                    usedLeave = prefs.getFloat("used_leave", 0.0f)
                    val remain = (totalLeave - usedLeave).coerceAtLeast(0.0f)
                    val usedPercent = if (totalLeave > 0f) (usedLeave / totalLeave * 100).toInt() else 0

                    etTotalLeave.setText(String.format(Locale.KOREA, "%.1f", totalLeave))
                    tvRemainLeave.text = "${String.format(Locale.KOREA, "%.1f", remain)}일 남음"
                    tvLeaveSubInfo.text = "사용 ${String.format(Locale.KOREA, "%.1f", usedLeave)}일 / 소진율 ${usedPercent}%"

                    val currentSal = prefs.getFloat("salary", 2600000f).toDouble()
                    val dailyWage = (currentSal / 209.0) * 8.0
                    val estimatedPayout = (remain * dailyWage).toLong()

                    tvAllowanceValue.text = "약 ₩ ${nf.format(estimatedPayout)} (1일 8시간 기준)"

                    prefs.edit()
                        .putFloat("total_leave", totalLeave)
                        .putFloat("used_leave", usedLeave)
                        .apply()
                    autoSaveBackup()
                }

                triggerLeaveRefresh = { refreshLeaveUI() }

                btnUseHalf.setOnClickListener {
                    if (totalLeave - usedLeave >= 0.5f) {
                        prefs.edit().putFloat("used_leave", usedLeave + 0.5f).apply()
                        refreshLeaveUI()
                    } else {
                        Toast.makeText(context, "잔여 연차가 부족합니다.", Toast.LENGTH_SHORT).show()
                    }
                }

                btnUseDay.setOnClickListener {
                    if (totalLeave - usedLeave >= 1.0f) {
                        prefs.edit().putFloat("used_leave", usedLeave + 1.0f).apply()
                        refreshLeaveUI()
                    } else {
                        Toast.makeText(context, "잔여 연차가 부족합니다.", Toast.LENGTH_SHORT).show()
                    }
                }

                btnCancelLeave.setOnClickListener {
                    if (usedLeave >= 0.5f) {
                        prefs.edit().putFloat("used_leave", usedLeave - 0.5f).apply()
                        refreshLeaveUI()
                    }
                }

                etTotalLeave.addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun afterTextChanged(s: Editable?) {
                        val input = s.toString().toFloatOrNull()
                        if (input != null && input >= 0f) {
                            prefs.edit().putFloat("total_leave", input).apply()
                            refreshLeaveUI()
                        }
                    }
                })

                refreshLeaveUI()
                addView(leaveCard)
            }
            addView(layout)
        }

        // ==========================================
        // 3. [설정 탭] 화면 (데이터 백업 및 복원 포함)
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

                // 카드 1: 기본 근무 안내 카드
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

                // 카드 2: 데이터 영구 백업 및 복원 카드
                val backupCard = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(20), dp(22), dp(20), dp(22))
                    background = GradientDrawable().apply {
                        setColor(Color.WHITE)
                        cornerRadius = dp(18).toFloat()
                        setStroke(dp(1), Color.parseColor("#E2E8F0"))
                    }
                }

                backupCard.addView(TextView(context).apply {
                    text = "데이터 백업 및 복원"
                    textSize = 16f
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    setTextColor(Color.parseColor("#0F172A"))
                })
                backupCard.addView(TextView(context).apply {
                    text = "내 파일 > Documents > URAPay 폴더에 자동 백업됩니다. 앱을 삭제 후 재설치해도 데이터를 그대로 복원할 수 있습니다."
                    textSize = 13f
                    setTextColor(Color.parseColor("#64748B"))
                    setLineSpacing(dp(3).toFloat(), 1f)
                    setPadding(0, dp(6), 0, dp(14))
                })

                val btnRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    val params = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(8) }
                    layoutParams = params
                }

                fun createSmallButton(title: String, isPrimary: Boolean): Button {
                    return Button(context).apply {
                        text = title
                        textSize = 12f
                        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                        setTextColor(if (isPrimary) Color.WHITE else Color.parseColor("#2563EB"))
                        background = GradientDrawable().apply {
                            setColor(if (isPrimary) Color.parseColor("#2563EB") else Color.parseColor("#EFF6FF"))
                            cornerRadius = dp(10).toFloat()
                            if (!isPrimary) setStroke(dp(1), Color.parseColor("#BFDBFE"))
                        }
                        setPadding(0, dp(10), 0, dp(10))
                    }
                }

                val btnManualBackup = createSmallButton("문서 폴더로 백업", true).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(4) }
                }
                val btnManualRestore = createSmallButton("문서 폴더에서 복원", false).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(4) }
                }

                btnRow.addView(btnManualBackup)
                btnRow.addView(btnManualRestore)
                backupCard.addView(btnRow)

                val btnClipboardBackup = Button(context).apply {
                    text = "클립보드로 백업 텍스트 복사"
                    textSize = 12f
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
                    setTextColor(Color.parseColor("#475569"))
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#F1F5F9"))
                        cornerRadius = dp(10).toFloat()
                    }
                    val params = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(6) }
                    layoutParams = params
                }

                val btnClipboardRestore = Button(context).apply {
                    text = "클립보드에서 텍스트로 복원"
                    textSize = 12f
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
                    setTextColor(Color.parseColor("#475569"))
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#F1F5F9"))
                        cornerRadius = dp(10).toFloat()
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                }

                backupCard.addView(btnClipboardBackup)
                backupCard.addView(btnClipboardRestore)
                addView(backupCard)

                btnManualBackup.setOnClickListener {
                    val s = prefs.getFloat("salary", 2600000f)
                    val tl = prefs.getFloat("total_leave", 15.0f)
                    val ul = prefs.getFloat("used_leave", 0.0f)
                    val json = JSONObject().apply {
                        put("salary", s.toDouble())
                        put("total_leave", tl.toDouble())
                        put("used_leave", ul.toDouble())
                        put("backup_time", System.currentTimeMillis())
                    }
                    val ok = backupToDocuments(context, json.toString())
                    if (ok) {
                        Toast.makeText(context, "Documents/URAPay 폴더에 백업되었습니다.", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(context, "백업 파일 저장에 실패했습니다.", Toast.LENGTH_SHORT).show()
                    }
                }

                btnManualRestore.setOnClickListener {
                    val jsonStr = readFromDocuments(context)
                    if (jsonStr != null) {
                        try {
                            val obj = JSONObject(jsonStr)
                            val sal = obj.optDouble("salary", 2600000.0).toFloat()
                            val tl = obj.optDouble("total_leave", 15.0).toFloat()
                            val ul = obj.optDouble("used_leave", 0.0).toFloat()
                            prefs.edit()
                                .putFloat("salary", sal)
                                .putFloat("total_leave", tl)
                                .putFloat("used_leave", ul)
                                .apply()
                            netSalaryInput.setText(nf.format(sal.toLong()))
                            triggerLeaveRefresh?.invoke()
                            Toast.makeText(context, "문서 폴더에서 데이터를 복원했습니다.", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            Toast.makeText(context, "백업 파일 형식이 올바르지 않습니다.", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        Toast.makeText(context, "Documents/URAPay에 저장된 백업 파일이 없습니다.", Toast.LENGTH_LONG).show()
                    }
                }

                btnClipboardBackup.setOnClickListener {
                    val s = prefs.getFloat("salary", 2600000f)
                    val tl = prefs.getFloat("total_leave", 15.0f)
                    val ul = prefs.getFloat("used_leave", 0.0f)
                    val json = JSONObject().apply {
                        put("salary", s.toDouble())
                        put("total_leave", tl.toDouble())
                        put("used_leave", ul.toDouble())
                    }
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("URAPay_Backup", json.toString()))
                    Toast.makeText(context, "백업 데이터가 클립보드에 복사되었습니다.", Toast.LENGTH_LONG).show()
                }

                btnClipboardRestore.setOnClickListener {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = clipboard.primaryClip
                    if (clip != null && clip.itemCount > 0) {
                        val text = clip.getItemAt(0).text.toString()
                        try {
                            val obj = JSONObject(text)
                            val sal = obj.optDouble("salary", 2600000.0).toFloat()
                            val tl = obj.optDouble("total_leave", 15.0).toFloat()
                            val ul = obj.optDouble("used_leave", 0.0).toFloat()
                            prefs.edit()
                                .putFloat("salary", sal)
                                .putFloat("total_leave", tl)
                                .putFloat("used_leave", ul)
                                .apply()
                            netSalaryInput.setText(nf.format(sal.toLong()))
                            triggerLeaveRefresh?.invoke()
                            Toast.makeText(context, "클립보드에서 데이터를 복원했습니다.", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            Toast.makeText(context, "클립보드 내용이 올바른 백업 데이터 형식이 아닙니다.", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        Toast.makeText(context, "클립보드가 비어 있습니다.", Toast.LENGTH_SHORT).show()
                    }
                }
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

        val floatingDock = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(4), dp(6), dp(4))
            elevation = dp(16).toFloat()
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E6FFFFFF"))
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
