package com.yiku.ptzcontrol

import android.app.AlertDialog
import android.content.DialogInterface
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.yiku.ptzcontrol.service.BaseService
import com.yiku.ptzcontrol.service.C12Service


class SettingsActivity : AppCompatActivity() {
    private val TAG = "SettingsActivity"
    private lateinit var streamUrlEditText1: EditText
    private lateinit var streamUrlEditText2: EditText
    private lateinit var ipAddressText: TextView
    private var service: BaseService? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        // 初始化视图
        streamUrlEditText1 = findViewById(R.id.streamUrl_1)
        streamUrlEditText2 = findViewById(R.id.streamUrl_2)
        ipAddressText = findViewById(R.id.ipAddress)

        // 设置返回按钮
        findViewById<ImageButton>(R.id.backButton).setOnClickListener {
            saveSettingsAndFinish()
        }

        ipAddressText.setOnClickListener {
            showDialog(
                ipAddressText.text.toString(),
                object : ValueChangeListener {
                    override fun onValueChanged(newValue: String) {
                        ipAddressText.text = newValue
                        val prefs = getSharedPreferences("camera_settings", MODE_PRIVATE)
                        val prefsEdit = prefs.edit()
                        prefsEdit.putString("ip_address", newValue).apply()
                        // 修改了ip，就同步修改视频流地址
                        val streamUrl1 = "rtsp://$newValue:554/stream=1"
                        val streamUrl2 = "rtsp://$newValue:555/stream=2"
                        streamUrlEditText1.setText(streamUrl1)
                        streamUrlEditText2.setText(streamUrl2)
                    }
                }
            )
        }

        // 加载保存的设置
        loadSavedSettings()

        // 显示版本信息
        val versionText = findViewById<TextView>(R.id.appVersion)
        val manager: PackageManager = this.packageManager
        var name: String? = null
        try {
            val info: PackageInfo = manager.getPackageInfo(this.packageName, 0)
            name = info.versionName
            versionText.text = "V$name"
        } catch (e: PackageManager.NameNotFoundException) {
            e.printStackTrace()
        }
    }

    private fun loadSavedSettings() {
        // 从SharedPreferences加载保存的设置
        val prefs = getSharedPreferences("camera_settings", MODE_PRIVATE)

        streamUrlEditText1.setText(prefs.getString("stream_url_1", "rtsp://192.168.144.108:554/stream=1"))
        streamUrlEditText2.setText(prefs.getString("stream_url_2", "rtsp://192.168.144.108:555/stream=2"))
        ipAddressText.text = prefs.getString("ip_address", "192.168.144.108")
    }

    private fun saveSettingsAndFinish() {
        // 保存设置到SharedPreferences
        val prefs = getSharedPreferences("camera_settings", MODE_PRIVATE).edit()

        prefs.putString("stream_url_1", streamUrlEditText1.text.toString())
            .putString("stream_url_2", streamUrlEditText2.text.toString())
            .putString("ip_address", ipAddressText.text.toString())
            .apply()

        // 返回主界面
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        super.onBackPressed()
        saveSettingsAndFinish()
    }

    // 验证IP格式是否正确
    private fun isValidIPv4(ip: String): Boolean {
        val parts = ip.split(".")
        if (parts.size != 4) return false

        return parts.all { part ->
            // 检查是否为空或包含非数字字符
            part.matches(Regex("\\d+")) && when {
                part.startsWith('0') && part.length > 1 -> false  // 禁止前导零 (01, 001等)
                part.toIntOrNull() in 0..255 -> true              // 检查数字范围
                else -> false
            }
        }
    }

    // 通用方法：显示编辑对话框
    private fun showDialog(
        currentValue: String,
        listener: ValueChangeListener?
    ) {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("设置IP")

        // 设置对话框视图
        val input = EditText(this)
        input.setText(currentValue)
        input.inputType = InputType.TYPE_CLASS_PHONE
        input.hint = "IP地址："
        input.setSelectAllOnFocus(true)

        builder.setView(input)

        // 设置按钮
        builder.setNegativeButton("取消") {
            dialog: DialogInterface,
            which: Int -> dialog.cancel()
        }

        // 先设置确定按钮但暂不实现逻辑
        builder.setPositiveButton("确认", null)

        // 显示对话框
        val dialog: AlertDialog = builder.create()
        // 在对话框显示后覆盖确认按钮的点击行为
        dialog.setOnShowListener {
            val confirmButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            confirmButton.setOnClickListener {
                val newValue = input.text.toString().trim()

                if (newValue.isEmpty()) {
                    input.error = "IP地址不能为空"
                    return@setOnClickListener
                }

                // 4. 验证IP有效性
                if (!isValidIPv4(newValue)) {
                    input.error = "无效的IP地址格式！示例：192.168.144.108"
                    return@setOnClickListener
                }

                // 5. 验证通过才关闭对话框并回调
                listener?.onValueChanged(newValue)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    // 值变化监听接口
    interface ValueChangeListener {
        fun onValueChanged(newValue: String)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "关闭设置页面")
        if(service != null){
            service!!.disconnect()
            service = null
        }
    }
}