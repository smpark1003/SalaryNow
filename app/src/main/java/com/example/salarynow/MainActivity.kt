package com.example.salarynow

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(60, 80, 60, 60)
        }

        val salaryInput = EditText(this).apply {
            hint = "월 기본급 입력 (예: 3000000)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }

        val prefs = getSharedPreferences("SalaryPrefs", Context.MODE_PRIVATE)
        salaryInput.setText(prefs.getFloat("salary", 3000000f).toInt().toString())

        val startBtn = Button(this).apply {
            text = "🚀 나우 바 / 상단 알림 켜기"
            setOnClickListener {
                val salVal = salaryInput.text.toString().toFloatOrNull() ?: 3000000f
                prefs.edit().putFloat("salary", salVal).apply()

                checkPermissionAndStartService()
            }
        }

        val stopBtn = Button(this).apply {
            text = "🛑 알림 끄기"
            setOnClickListener {
                stopService(Intent(this@MainActivity, SalaryService::class.java))
                Toast.makeText(this@MainActivity, "알림이 중지되었습니다.", Toast.LENGTH_SHORT).show()
            }
        }

        layout.addView(salaryInput)
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

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startSalaryService()
        }
    }

    private fun startSalaryService() {
        val serviceIntent = Intent(this, SalaryService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        Toast.makeText(this, "나우 바에 실시간 급여가 활성화되었습니다!", Toast.LENGTH_SHORT).show()
    }
}
