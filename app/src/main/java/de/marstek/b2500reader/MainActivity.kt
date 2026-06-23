package de.marstek.b2500reader

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.UUID
import kotlin.math.max

class MainActivity : Activity() {
    private val batteryServiceUuid = UUID.fromString("0000ff00-0000-1000-8000-00805f9b34fb")
    private val batteryDataUuid = UUID.fromString("0000ff02-0000-1000-8000-00805f9b34fb")
    private val cccdUuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private val devicePrefix = "HM_B2500"
    private val ownerName = "SolarPixelWerk"
    private val githubProfileUrl = "https://github.com/SolarPixelWerk"
    private val appVersionLabel = "Version 1.0 Beta 2026"
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private var gatt: BluetoothGatt? = null
    private var batteryCharacteristic: BluetoothGattCharacteristic? = null
    private var pendingCommands = mutableListOf<Int>()
    private var waitingForCommand: Int? = null
    private var commandTimeout: Runnable? = null
    private var writeAttempts = emptyList<Int>()
    private var writeAttemptIndex = 0
    private var sameWriteRetry = 0

    private lateinit var statusText: TextView
    private lateinit var deviceText: TextView
    private lateinit var socText: TextView
    private lateinit var inputText: TextView
    private lateinit var outputText: TextView
    private lateinit var tempText: TextView
    private lateinit var cellText: TextView
    private lateinit var cellGrid: GridLayout
    private lateinit var rawText: TextView
    private lateinit var scanButton: Button
    private lateinit var refreshButton: Button
    private lateinit var disconnectButton: Button

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.device.name ?: result.scanRecord?.deviceName ?: return
            if (!name.startsWith(devicePrefix)) return

            bluetoothAdapter.bluetoothLeScanner?.stopScan(this)
            setStatus("Gefunden: $name. Verbinde...")
            deviceText.text = "${result.device.address} / $name"
            connect(result.device)
        }

        override fun onScanFailed(errorCode: Int) {
            setStatus("Scan fehlgeschlagen: $errorCode")
            setBusy(false)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                setStatus("Verbunden. Setze MTU...")
                if (!gatt.requestMtu(517)) gatt.discoverServices()
                return
            }

            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                mainHandler.post {
                    batteryCharacteristic = null
                    setStatus("Getrennt")
                    setBusy(false)
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            setStatus("MTU $mtu. Suche Service...")
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val service = gatt.getService(batteryServiceUuid)
            if (service == null) {
                setStatus("Service FF00 nicht gefunden")
                setBusy(false)
                return
            }

            val characteristic = service.getCharacteristic(batteryDataUuid)
            if (characteristic == null) {
                setStatus("Characteristic FF02 nicht gefunden")
                setBusy(false)
                return
            }

            batteryCharacteristic = characteristic
            val props = describeProperties(characteristic.properties)
            mainHandler.post { rawText.text = "FF02 Eigenschaften: $props" }
            enableNotifications(gatt, characteristic)
            mainHandler.post {
                refreshButton.isEnabled = true
                disconnectButton.isEnabled = true
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (descriptor.uuid != cccdUuid) return

            if (status == BluetoothGatt.GATT_SUCCESS) {
                setStatus("BLE OK. Notifications aktiv.")
                mainHandler.postDelayed({ refreshBattery() }, 500)
            } else {
                setStatus("Notification-Setup fehlgeschlagen: $status")
                setBusy(false)
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (characteristic.uuid != batteryDataUuid || status == BluetoothGatt.GATT_SUCCESS) {
                return
            }

            if (tryNextWriteAttempt()) {
                return
            }

            setStatus("Schreiben fehlgeschlagen: $status")
            setBusy(false)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleBatteryResponse(value)
        }

        @Deprecated("Deprecated by Android framework")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            @Suppress("DEPRECATION")
            handleBatteryResponse(characteristic.value ?: ByteArray(0))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(5, 12, 24)
        window.navigationBarColor = Color.rgb(5, 12, 24)
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        buildDisclaimerUi()
    }

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        super.onDestroy()
        bluetoothAdapter.bluetoothLeScanner?.stopScan(scanCallback)
        gatt?.close()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 18, 18, 18)
            setBackgroundColor(Color.rgb(5, 12, 24))
        }

        root.addView(buildBrandHeader(compact = true))

        val statusCard = card().apply {
            orientation = LinearLayout.VERTICAL
        }
        statusText = valueText("Status: bereit").apply {
            textSize = 18f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        }
        deviceText = valueText("Kein Geraet")
        statusCard.addView(statusText)
        statusCard.addView(deviceText)
        root.addView(statusCard)

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 8)
        }
        scanButton = Button(this).apply {
            text = "Scannen"
            setTextColor(Color.WHITE)
            backgroundTintList = ColorStateList.valueOf(Color.rgb(0, 151, 190))
            setOnClickListener { startScan() }
        }
        refreshButton = Button(this).apply {
            text = "Aktualisieren"
            setTextColor(Color.WHITE)
            backgroundTintList = ColorStateList.valueOf(Color.rgb(41, 143, 65))
            isEnabled = false
            setOnClickListener { refreshBattery() }
        }
        disconnectButton = Button(this).apply {
            text = "Trennen"
            setTextColor(Color.WHITE)
            backgroundTintList = ColorStateList.valueOf(Color.rgb(92, 111, 132))
            isEnabled = false
            setOnClickListener { disconnect() }
        }
        buttonRow.addView(scanButton, rowButtonParams())
        buttonRow.addView(refreshButton, rowButtonParams())
        buttonRow.addView(disconnectButton, rowButtonParams())
        root.addView(buttonRow)

        socText = metricCard("SOC", "-- %", large = true)

        val powerGrid = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        inputText = metricCard("Eingang", "-- W")
        outputText = metricCard("Ausgang", "-- W")
        powerGrid.addView(inputText, gridCardParams())
        powerGrid.addView(outputText, gridCardParams())

        tempText = metricCard("Temperatur", "-- C")
        val cellCard = card().apply {
            orientation = LinearLayout.VERTICAL
        }
        cellText = valueText("Zellen: --").apply {
            textSize = 16f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setPadding(0, 0, 0, 8)
        }
        cellGrid = GridLayout(this).apply {
            columnCount = 4
        }
        cellCard.addView(cellText)
        cellCard.addView(cellGrid)
        rawText = valueText("Debug: --").apply {
            textSize = 13f
            setTextColor(Color.rgb(157, 179, 199))
            setPadding(18, 18, 18, 18)
            background = rounded(Color.rgb(12, 27, 47), 18f)
        }

        root.addView(socText)
        root.addView(powerGrid)
        root.addView(tempText)
        root.addView(cellCard)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.rgb(5, 12, 24))
            addView(root)
        })
    }

    private fun buildDisclaimerUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(Color.rgb(5, 12, 24))
        }

        root.addView(buildBrandHeader(compact = false))

        val disclaimerCard = card().apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22, 22, 22, 22)
        }
        disclaimerCard.addView(TextView(this).apply {
            text = "Haftungsausschluss"
            textSize = 24f
            setTextColor(Color.rgb(239, 248, 255))
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setPadding(0, 0, 0, 12)
        })
        disclaimerCard.addView(TextView(this).apply {
            text = """
                Diese App ist eine Beta-Software und ein privates Projekt von SolarPixelWerk. Sie ist nicht vom Hersteller des Akkus bereitgestellt, geprueft oder offiziell freigegeben.

                Die Nutzung erfolgt auf eigene Verantwortung. Es wird keine Gewaehr fuer Richtigkeit, Vollstaendigkeit, Verfuegbarkeit oder Aktualitaet der angezeigten Messwerte uebernommen.

                Die App dient nur zur Anzeige und Auswertung von per Bluetooth empfangenen Daten. Entscheidungen zum Betrieb von Akku, Solaranlage, Wechselrichter oder angeschlossenen Verbrauchern sollten nicht allein auf Basis dieser App getroffen werden.

                Eine Haftung fuer Schaeden, Datenverluste, Fehlanzeigen, Verbindungsprobleme, Betriebsstoerungen oder sonstige Folgen der Nutzung ist, soweit gesetzlich zulaessig, ausgeschlossen.

                Mit OK bestaetigst du, dass du diese Hinweise gelesen hast und die App auf eigene Verantwortung verwendest.
            """.trimIndent()
            textSize = 16f
            setTextColor(Color.rgb(213, 229, 241))
            setLineSpacing(4f, 1.0f)
        })
        root.addView(disclaimerCard)

        val okButton = Button(this).apply {
            text = "OK, verstanden"
            textSize = 17f
            setTextColor(Color.WHITE)
            backgroundTintList = ColorStateList.valueOf(Color.rgb(0, 151, 190))
            setOnClickListener { buildUi() }
        }
        root.addView(okButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = 6
        })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.rgb(5, 12, 24))
            addView(root)
        })
    }

    private fun buildBrandHeader(compact: Boolean): LinearLayout {
        val header = card().apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(
                if (compact) 18 else 22,
                if (compact) 18 else 22,
                if (compact) 18 else 22,
                if (compact) 18 else 22
            )
        }

        val logo = ImageView(this).apply {
            setImageResource(resources.getIdentifier("solar_pixel_werk_logo", "drawable", packageName))
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        header.addView(logo, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            if (compact) 150 else 260
        ).apply {
            bottomMargin = if (compact) 12 else 16
        })

        val textColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        textColumn.addView(TextView(this).apply {
            text = "Marstek B2500 Reader"
            textSize = if (compact) 22f else 24f
            setTextColor(Color.rgb(239, 248, 255))
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
        })
        textColumn.addView(TextView(this).apply {
            text = "$ownerName  |  $appVersionLabel"
            textSize = if (compact) 14f else 15f
            setTextColor(Color.rgb(158, 184, 205))
            setPadding(0, 4, 0, if (compact) 5 else 8)
            gravity = Gravity.CENTER
        })
        textColumn.addView(TextView(this).apply {
            text = "github.com/SolarPixelWerk"
            textSize = if (compact) 14f else 15f
            setTextColor(Color.rgb(0, 218, 255))
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(githubProfileUrl)))
            }
        })
        header.addView(textColumn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        return header
    }

    private fun rowButtonParams(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = 8
        }
    }

    private fun gridCardParams(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = 8
        }
    }

    private fun valueText(value: String): TextView {
        return TextView(this).apply {
            text = value
            textSize = 16f
            setTextColor(Color.rgb(213, 229, 241))
            setPadding(0, 4, 0, 4)
        }
    }

    private fun metricCard(label: String, value: String, large: Boolean = false): TextView {
        return TextView(this).apply {
            text = "$label: $value"
            textSize = if (large) 26f else 16f
            setTextColor(Color.rgb(239, 248, 255))
            setTypeface(Typeface.DEFAULT, if (large) Typeface.BOLD else Typeface.NORMAL)
            setPadding(14, 12, 14, 12)
            background = rounded(Color.rgb(10, 23, 40), 18f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 8
            }
        }
    }

    private fun card(): LinearLayout {
        return LinearLayout(this).apply {
            background = rounded(Color.rgb(9, 20, 36), 20f)
            setPadding(14, 12, 14, 12)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 8
            }
        }
    }

    private fun cellTile(index: Int, mv: Int): TextView {
        return TextView(this).apply {
            text = "C${"%02d".format(index + 1)}\n$mv mV"
            textSize = 12f
            setTextColor(Color.rgb(223, 238, 248))
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(6, 8, 6, 8)
            background = rounded(Color.rgb(12, 27, 47), 12f)
        }
    }

    private fun rounded(color: Int, radius: Float): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
        }
    }

    private fun requiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun hasPermissions(): Boolean {
        return requiredPermissions().all {
            checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissionsIfNeeded(): Boolean {
        if (hasPermissions()) return true
        requestPermissions(requiredPermissions(), 1001)
        return false
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001 && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            startScan()
        } else {
            setStatus("Berechtigung fehlt")
        }
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        if (!requestPermissionsIfNeeded()) return
        if (!bluetoothAdapter.isEnabled) {
            setStatus("Bluetooth ist ausgeschaltet")
            return
        }

        disconnect(closeOnly = true)
        setBusy(true)
        setStatus("Suche $devicePrefix...")
        deviceText.text = "Scan laeuft"
        bluetoothAdapter.bluetoothLeScanner?.startScan(scanCallback)
        mainHandler.postDelayed({
            bluetoothAdapter.bluetoothLeScanner?.stopScan(scanCallback)
            if (gatt == null) {
                setStatus("Kein $devicePrefix gefunden")
                setBusy(false)
            }
        }, 10000)
    }

    @SuppressLint("MissingPermission")
    private fun connect(device: BluetoothDevice) {
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(this, false, gattCallback)
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableNotifications(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic
    ) {
        val properties = characteristic.properties
        val canNotify = properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
        val canIndicate = properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
        val notifyEnabled = gatt.setCharacteristicNotification(characteristic, true)
        val descriptor = characteristic.getDescriptor(cccdUuid)
        if (!notifyEnabled || descriptor == null || (!canNotify && !canIndicate)) {
            setStatus("Keine Notification/Indication auf FF02")
            setBusy(false)
            return
        }

        val value = if (canNotify) {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        }

        setStatus("Aktiviere ${if (canNotify) "Notifications" else "Indications"}...")
        if (Build.VERSION.SDK_INT >= 33) {
            gatt.writeDescriptor(descriptor, value)
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = value
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(descriptor)
        }
    }

    private fun refreshBattery() {
        if (batteryCharacteristic == null || gatt == null) {
            setStatus("Nicht verbunden")
            return
        }
        pendingCommands = mutableListOf(0x03, 0x0F)
        setStatus("Lese Akku...")
        sendNextCommand()
    }

    private fun sendNextCommand() {
        val command = pendingCommands.firstOrNull()
        if (command == null) {
            setStatus("BLE OK")
            setBusy(false)
            return
        }
        setStatus("Sende Kommando 0x${command.toString(16).uppercase()}...")
        writeCommand(command)
    }

    @SuppressLint("MissingPermission")
    private fun writeCommand(command: Int) {
        val characteristic = batteryCharacteristic ?: return
        val properties = characteristic.properties
        writeAttempts = buildList {
            if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
                add(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
            }
            if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) {
                add(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            }
            if (isEmpty()) add(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
        }
        writeAttemptIndex = 0
        sameWriteRetry = 0
        waitingForCommand = command
        performWrite(command)
    }

    @SuppressLint("MissingPermission")
    private fun performWrite(command: Int) {
        val currentGatt = gatt ?: return
        val characteristic = batteryCharacteristic ?: return
        val msg = byteArrayOf(0x73, 0x05, 0x23, command.toByte(), 0x00)
        msg[4] = (msg[0].toInt() xor msg[1].toInt() xor msg[2].toInt() xor msg[3].toInt()).toByte()
        val writeType = writeAttempts.getOrElse(writeAttemptIndex) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }
        characteristic.writeType = writeType
        val mode = if (writeType == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) {
            "ohne Antwort"
        } else {
            "mit Antwort"
        }
        val sentHex = msg.joinToString(" ") { "%02X".format(it) }
        setStatus("Sende 0x${command.toString(16).uppercase()} ($mode)...")
        mainHandler.post {
            rawText.text = "FF02 Eigenschaften: ${describeProperties(characteristic.properties)}\nGesendet: $sentHex"
        }

        val started = if (Build.VERSION.SDK_INT >= 33) {
            currentGatt.writeCharacteristic(
                characteristic,
                msg,
                writeType
            ) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = msg
            @Suppress("DEPRECATION")
            currentGatt.writeCharacteristic(characteristic)
        }

        if (!started) {
            if (tryNextWriteAttempt()) return
            setStatus("Schreiben fehlgeschlagen: 0x${command.toString(16).uppercase()}")
            failCurrentCommand()
            return
        }

        commandTimeout?.let { mainHandler.removeCallbacks(it) }
        commandTimeout = Runnable {
            if (waitingForCommand == command) {
                if (sameWriteRetry < 2) {
                    sameWriteRetry += 1
                    mainHandler.postDelayed({ performWrite(command) }, 250)
                    return@Runnable
                }
                if (tryNextWriteAttempt()) return@Runnable
                val hexCommand = "0x${command.toString(16).uppercase()}"
                setStatus("Keine Antwort auf $hexCommand")
                rawText.text = "${rawText.text}\nKeine Antwort auf $hexCommand nach ${writeAttempts.size} Schreibart(en)"
                failCurrentCommand()
            }
        }
        mainHandler.postDelayed(commandTimeout!!, 1500)
    }

    private fun tryNextWriteAttempt(): Boolean {
        val command = waitingForCommand ?: return false
        if (writeAttemptIndex + 1 >= writeAttempts.size) return false

        writeAttemptIndex += 1
        sameWriteRetry = 0
        commandTimeout?.let { mainHandler.removeCallbacks(it) }
        commandTimeout = null
        mainHandler.postDelayed({ performWrite(command) }, 250)
        return true
    }

    private fun failCurrentCommand() {
        waitingForCommand = null
        pendingCommands.clear()
        writeAttempts = emptyList()
        writeAttemptIndex = 0
        sameWriteRetry = 0
        setBusy(false)
    }

    private fun handleBatteryResponse(data: ByteArray) {
        commandTimeout?.let { mainHandler.removeCallbacks(it) }
        commandTimeout = null
        val hex = data.joinToString(" ") { "%02X".format(it) }
        mainHandler.post { rawText.text = "Rohdaten: $hex" }

        val command = pendingCommands.firstOrNull()
        val parsed = when (command) {
            0x03 -> parseRuntime(data)
            0x0F -> parseCells(data)
            else -> false
        }

        if (parsed && pendingCommands.isNotEmpty()) {
            pendingCommands.removeAt(0)
            waitingForCommand = null
            writeAttempts = emptyList()
            writeAttemptIndex = 0
            sameWriteRetry = 0
            mainHandler.postDelayed({ sendNextCommand() }, 200)
        } else {
            waitingForCommand = null
            writeAttempts = emptyList()
            writeAttemptIndex = 0
            sameWriteRetry = 0
            val expected = command?.let { "0x${it.toString(16).uppercase()}" } ?: "unbekannt"
            setStatus("Antwort passt nicht zu $expected")
            setBusy(false)
        }
    }

    private fun parseRuntime(data: ByteArray): Boolean {
        if (data.size < 39) return false
        if (data[0] != 0x73.toByte() || data[2] != 0x23.toByte() || data[3] != 0x03.toByte()) {
            return false
        }

        val in1W = u16le(data, 6)
        val in2W = u16le(data, 8)
        val soc = u16le(data, 10) / 10
        val out1W = u16le(data, 24)
        val out2W = u16le(data, 26)
        val tempC = s16le(data, 33)

        mainHandler.post {
            socText.text = "SOC: $soc %"
            inputText.text = "Eingang: ${max(0, in1W) + max(0, in2W)} W  ($in1W / $in2W)"
            outputText.text = "Ausgang: ${max(0, out1W) + max(0, out2W)} W  ($out1W / $out2W)"
            tempText.text = "Temperatur: $tempC C"
        }
        return true
    }

    private fun parseCells(data: ByteArray): Boolean {
        val numeric = buildString {
            data.forEach { byte ->
                val c = byte.toInt().toChar()
                if (c in '0'..'9' || c == '_') append(c)
            }
        }
        val parts = numeric.split('_').mapNotNull { it.toIntOrNull() }
        if (parts.size < 6) return false

        val cells = parts.drop(3).take(20)
        if (cells.isEmpty()) return false

        mainHandler.post {
            cellText.text = "Aktuelle Zellspannungen (${cells.size})"
            cellGrid.removeAllViews()
            cells.forEachIndexed { index, mv ->
                val params = GridLayout.LayoutParams().apply {
                    width = 0
                    height = ViewGroup.LayoutParams.WRAP_CONTENT
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    setMargins(0, 0, 8, 8)
                }
                cellGrid.addView(cellTile(index, mv), params)
            }
        }
        return true
    }

    private fun u16le(data: ByteArray, index: Int): Int {
        if (index + 1 >= data.size) return 0
        return (data[index].toInt() and 0xff) or ((data[index + 1].toInt() and 0xff) shl 8)
    }

    private fun s16le(data: ByteArray, index: Int): Int {
        val value = u16le(data, index)
        return if (value and 0x8000 != 0) value - 0x10000 else value
    }

    @SuppressLint("MissingPermission")
    private fun disconnect(closeOnly: Boolean = false) {
        bluetoothAdapter.bluetoothLeScanner?.stopScan(scanCallback)
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        batteryCharacteristic = null
        pendingCommands.clear()
        waitingForCommand = null
        writeAttempts = emptyList()
        writeAttemptIndex = 0
        sameWriteRetry = 0
        commandTimeout?.let { mainHandler.removeCallbacks(it) }
        commandTimeout = null
        if (!closeOnly) {
            setStatus("Getrennt")
            setBusy(false)
            refreshButton.isEnabled = false
            disconnectButton.isEnabled = false
        }
    }

    private fun setStatus(status: String) {
        mainHandler.post { statusText.text = "Status: $status" }
    }

    private fun setBusy(busy: Boolean) {
        mainHandler.post {
            scanButton.isEnabled = !busy
            refreshButton.isEnabled = !busy && batteryCharacteristic != null
        }
    }

    private fun describeProperties(properties: Int): String {
        val names = mutableListOf<String>()
        if (properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) names += "READ"
        if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) names += "WRITE"
        if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
            names += "WRITE_NO_RESPONSE"
        }
        if (properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) names += "NOTIFY"
        if (properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) names += "INDICATE"
        return names.joinToString(", ").ifBlank { "unbekannt ($properties)" }
    }
}
