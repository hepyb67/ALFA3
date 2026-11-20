package com.example.esp32control

import android.Manifest
import android.app.Activity
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.UUID

class MainActivity : Activity() {
    private val serviceId = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
    private val characteristicId = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
    private val cccdId = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private lateinit var status: TextView
    private lateinit var action: Button
    private lateinit var root: LinearLayout
    private var gatt: BluetoothGatt? = null
    private var scanning = false
    private val handler = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter? by lazy {
        (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showMainScreen()
        requestNeededPermissions()
    }

    private fun showMainScreen() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(36, 36, 36, 36)
        }
        val title = TextView(this).apply { text = "ESP32 Control"; textSize = 28f; gravity = Gravity.CENTER }
        status = TextView(this).apply { text = "Не подключено"; textSize = 18f; gravity = Gravity.CENTER; setPadding(0, 28, 0, 28) }
        action = Button(this).apply { text = "Подключить ESP32"; setOnClickListener { scanForDevice() } }
        root.addView(title)
        root.addView(status)
        root.addView(action)
        setContentView(root)
    }

    private fun showSecondScreen() {
        root.removeAllViews()
        val title = TextView(this).apply { text = "Второй экран"; textSize = 30f; gravity = Gravity.CENTER }
        val back = Button(this).apply { text = "Назад"; setOnClickListener { showMainScreen() } }
        root.addView(title)
        root.addView(back)
    }

    private fun requestNeededPermissions() {
        val needed = if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        val missing = needed.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 10)
    }

    private fun hasPermissions(): Boolean {
        val needed = if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        return needed.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun scanForDevice() {
        if (!hasPermissions()) { requestNeededPermissions(); status.text = "Разреши Bluetooth и повтори"; return }
        val bt = adapter
        if (bt == null || !bt.isEnabled) { status.text = "Включи Bluetooth"; return }
        val scanner = bt.bluetoothLeScanner ?: run { status.text = "BLE-сканер недоступен"; return }
        status.text = "Ищем ESP32…"
        action.isEnabled = false
        scanning = true
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(null, settings, scanCallback)
        handler.postDelayed({
            if (scanning) {
                stopScan()
                status.text = "ESP32 не найден. Нажми подключение ещё раз."
                action.isEnabled = true
            }
        }, 15000)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val deviceName = result.device.name ?: result.scanRecord?.deviceName
            if (deviceName == "ESP32") {
                stopScan()
                status.text = "Найден ESP32. Подключаемся…"
                gatt = result.device.connectGatt(this@MainActivity, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            }
        }
        override fun onScanFailed(errorCode: Int) {
            scanning = false
            runOnUiThread { status.text = "Ошибка поиска: $errorCode"; action.isEnabled = true }
        }
    }

    private fun stopScan() {
        if (scanning && hasPermissions()) {
            try { adapter?.bluetoothLeScanner?.stopScan(scanCallback) } catch (_: SecurityException) { }
        }
        scanning = false
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, statusCode: Int, newState: Int) {
            runOnUiThread {
                if (newState == BluetoothProfile.STATE_CONNECTED && statusCode == BluetoothGatt.GATT_SUCCESS) {
                    status.text = "Подключено. Настраиваем уведомления…"
                    if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return@runOnUiThread
                    g.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    status.text = "Соединение потеряно"
                    action.isEnabled = true
                    g.close()
                    gatt = null
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, statusCode: Int) {
            val service = g.getService(serviceId)
            val characteristic = service?.getCharacteristic(characteristicId)
            if (characteristic == null) {
                runOnUiThread { status.text = "BLE-характеристика не найдена"; action.isEnabled = true }
                return
            }
            val enabled = g.setCharacteristicNotification(characteristic, true)
            val descriptor = characteristic.getDescriptor(cccdId)
            if (descriptor != null && enabled) {
                if (Build.VERSION.SDK_INT >= 33) {
                    g.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(descriptor)
                }
            } else runOnUiThread { status.text = "Подключено, уведомления не включились" }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, statusCode: Int) {
            if (descriptor.uuid == cccdId && statusCode == BluetoothGatt.GATT_SUCCESS) runOnUiThread { status.text = "Подключено к ESP32. Жду кнопку…" }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            handleCommand(String(value, Charsets.UTF_8).trim())
        }
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            characteristic.value?.let { handleCommand(String(it, Charsets.UTF_8).trim()) }
        }
    }

    private fun handleCommand(command: String) {
        if (command == "OPEN_SCREEN_2") runOnUiThread { showSecondScreen() }
    }

    override fun onDestroy() {
        stopScan()
        try { gatt?.disconnect(); gatt?.close() } catch (_: Exception) { }
        gatt = null
        super.onDestroy()
    }
}
