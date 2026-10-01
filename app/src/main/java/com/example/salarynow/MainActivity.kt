package com.example.salarynow

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.Calendar

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(60, 80, 60, 60)
        }

        val titleText = TextView(this).apply {
            text = "💼 스마트 월급 누적기"
            textSize = 20f
            setPadding(0, 0, 0, 30)
        }

        val salaryInput = EditText(this).apply {
            hint = "월 기본급 입력 (예: 3000000)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }

        val prefs = getSharedPreferences("SalaryPrefs", Context.MODE_PRIVATE)
        salaryInput.setText(prefs.getFloat("salary", 3000000f).toInt().toString())

        val descText = TextView(this).apply {
            text = "🔋 스마트 절전 & 무간섭 모드:\n" +
                   "• 매일 08:30 (출근 30분 전) 자동 시작\n" +
                   "• 매일 18:00 (퇴근 시) 정산 팝업 후 완전 종료\n" +
                   "• 이어폰/음악 앱 간섭 완전 차단 적용됨"
            textSize = 13f
            setPadding(0, 30, 0, 40)
        }

        val startBtn = Button(this).apply {
            text = "🚀 자동 출퇴근 모드 켜기"
            setOnClickListener {
                val salVal = salaryInput.text.toString().toFloatOrNull() ?: 3000000f
                prefs.edit().putFloat("salary", salVal).apply()

                SalaryService.scheduleNextWorkAlarm(this@MainActivity)
                checkPermissionAndStartService()
            }
        }

        val stopBtn = Button(this).apply {
            text = "🛑 알림 및 스케줄 끄기"
            setOnClickListener {
                stopService(Intent(this@MainActivity, SalaryService::class.java))
                Toast.makeText(this@MainActivity, "알림 및 자동 스케줄이 꺼졌습니다.", Toast.LENGTH_SHORT).show()
            }
        }

        layout.addView(titleText)
        layout.addView(salaryInput)
        layout.addView(descText)
        layout.addView(startBtn)
        layout.addView(stopBtn)
        setContentView(layout)
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
            Toast.makeText(this, "나우 바 실행 완료! 18시 퇴근 시 자동 종료됩니다.", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "스케줄 등록 완료! 다음 출근일 08:30에 자동으로 켜집니다.", Toast.LENGTH_LONG).show()
        }
    }
}
