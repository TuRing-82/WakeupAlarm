package com.wakeup.pro

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.Calendar
import java.util.UUID
import kotlin.math.abs
import kotlin.math.sqrt

class MainActivity : AppCompatActivity(), SensorEventListener {
    private lateinit var root: FrameLayout
    private lateinit var repository: AlarmRepository
    private lateinit var scheduler: AlarmScheduler
    private lateinit var sensorManager: SensorManager

    private var alarms: List<WakeAlarm> = emptyList()
    private var editAlarm: WakeAlarm? = null
    private var triggeredAlarm: WakeAlarm? = null
    private var ringtone: Ringtone? = null
    private var stepsTaken = 0
    private var lastAcceleration = 0f
    private var wifiSignal = -100

    private val handler = Handler(Looper.getMainLooper())
    private val wifiTicker = object : Runnable {
        override fun run() {
            refreshWifiSignal()
            if (triggeredAlarm?.type == AlarmType.WIFI) {
                showTriggered()
                handler.postDelayed(this, 1500)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        root = findViewById(android.R.id.content)
        repository = AlarmRepository(this)
        scheduler = AlarmScheduler(this)
        sensorManager = getSystemService(SensorManager::class.java)
        requestNotificationPermissionIfNeeded()

        alarms = repository.getAlarms()
        scheduler.scheduleAll(alarms)

        val alarmId = intent.getStringExtra(AlarmScheduler.EXTRA_ALARM_ID)
        if (alarmId != null) {
            startTriggered(alarmId)
        } else {
            showHome()
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(AlarmScheduler.EXTRA_ALARM_ID)?.let { startTriggered(it) }
    }

    override fun onStop() {
        super.onStop()
        if (triggeredAlarm?.type == AlarmType.MOTION) {
            sensorManager.unregisterListener(this)
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (triggeredAlarm?.type != AlarmType.MOTION) return
        val x = event.values.getOrNull(0) ?: 0f
        val y = event.values.getOrNull(1) ?: 0f
        val z = event.values.getOrNull(2) ?: 0f
        val acceleration = sqrt(x * x + y * y + z * z)
        val sensitivity = when (triggeredAlarm?.config?.motionSensitivity) {
            Sensitivity.LOW -> 16f
            Sensitivity.HIGH -> 10f
            else -> 12f
        }
        if (acceleration > sensitivity && abs(acceleration - lastAcceleration) > 2f) {
            stepsTaken += 1
            showTriggered()
        }
        lastAcceleration = acceleration
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun showHome() {
        stopChallengeSensors()
        alarms = repository.getAlarms()
        root.replaceWith(
            page {
                addView(appTitle("WakeUp Pro", "Smart alarms that make you actually get up"))
                addView(headerCard())
                addView(titleRow("Your Alarms", pillButton("+ Add", ButtonStyle.DARK) { openNewAlarm() }))

                val list = vertical(color = SURFACE)
                if (alarms.isEmpty()) {
                    list.addView(emptyState())
                } else {
                    alarms.forEach { alarm -> list.addView(alarmRow(alarm)) }
                }
                addView(scroll(list), weightParams())

                addView(pillButton("Test Alarm Trigger", ButtonStyle.GHOST) {
                    alarms.firstOrNull()?.let { startTriggered(it.id) }
                })
            }
        )
    }

    private fun headerCard(): View {
        val next = alarms.filter { it.enabled }
            .minByOrNull { AlarmScheduler.nextTriggerTime(it) ?: Long.MAX_VALUE }
        return card(color = DARK, padding = 24, radius = 28).apply {
            addView(chip("NEXT ALARM", Color.WHITE, 0x24FFFFFF))
            addView(space(14))
            addView(label(next?.displayTime ?: "--:--", 46, Color.WHITE, bold = true, gravity = Gravity.CENTER))
            addView(label(next?.label ?: "No active alarm", 16, MUTED, gravity = Gravity.CENTER))
            addView(space(8))
            addView(label(next?.let { repeatSummary(it.repeatDays) } ?: "Add an alarm to start", 13, 0xFF7F8797.toInt(), gravity = Gravity.CENTER))
        }
    }

    private fun alarmRow(alarm: WakeAlarm): View = card(padding = 16).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setOnClickListener { openEditAlarm(alarm) }

        addView(iconBadge(alarm.type), LinearLayout.LayoutParams(dp(52), dp(52)).withMargin(0, 0, 14, 0))

        val text = vertical()
        text.addView(label(alarm.displayTime, 24, DARK, bold = true))
        text.addView(label(alarm.label.ifBlank { "Wake Up" }, 14, GRAY))
        text.addView(label("${alarm.typeLabel} - ${repeatSummary(alarm.repeatDays)}", 12, SOFT_TEXT))
        addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val toggle = Switch(this@MainActivity).apply {
            isChecked = alarm.enabled
            setOnCheckedChangeListener { _, checked ->
                val updated = alarm.copy(enabled = checked)
                repository.saveAlarm(updated)
                if (checked) scheduler.schedule(updated) else scheduler.cancel(alarm.id)
                alarms = repository.getAlarms()
            }
        }
        addView(toggle)
    }

    private fun openNewAlarm() {
        editAlarm = WakeAlarm(
            id = UUID.randomUUID().toString(),
            hour = 7,
            minute = 30,
            label = "",
            type = AlarmType.SIMPLE,
            enabled = true,
            repeatDays = allRepeatDays()
        )
        showTypePicker()
    }

    private fun openEditAlarm(alarm: WakeAlarm) {
        editAlarm = alarm
        showAlarmEditor()
    }

    private fun showTypePicker() {
        root.replaceWith(
            page {
                addView(titleRow("Add Alarm", pillButton("Back", ButtonStyle.GHOST) { showHome() }))
                addView(label("Choose how the alarm can be dismissed.", 15, GRAY))
                addView(space(10))
                listOf(
                    AlarmType.SIMPLE to "Simple Alarm",
                    AlarmType.WIFI to "WiFi Alarm",
                    AlarmType.MOTION to "Motion Alarm"
                ).forEach { (type, title) ->
                    addView(card(padding = 18, radius = 24).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        addView(iconBadge(type), LinearLayout.LayoutParams(dp(54), dp(54)).withMargin(0, 0, 14, 0))
                        addView(vertical().apply {
                            addView(label(title, 21, DARK, bold = true))
                            addView(label(typeHelp(type), 14, GRAY))
                        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                        addView(label(">", 24, SOFT_TEXT, bold = true))
                        setOnClickListener {
                            editAlarm = editAlarm?.copy(type = type)
                            showAlarmEditor()
                        }
                    })
                }
            }
        )
    }

    private fun showAlarmEditor() {
        val alarm = editAlarm ?: return showHome()
        val hourPicker = NumberPicker(this).apply {
            minValue = 1
            maxValue = 12
            value = toDisplayHour(alarm.hour)
        }
        val minutePicker = NumberPicker(this).apply {
            minValue = 0
            maxValue = 59
            setFormatter { "%02d".format(it) }
            value = alarm.minute
        }
        val periodPicker = NumberPicker(this).apply {
            minValue = 0
            maxValue = 1
            displayedValues = arrayOf("AM", "PM")
            value = if (alarm.hour >= 12) 1 else 0
        }
        val labelInput = EditText(this).apply {
            hint = "Morning Gym"
            setText(alarm.label)
            setSingleLine(true)
            background = rounded(LIGHT, 18)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        val repeat = alarm.repeatDays.toMutableSet()

        root.replaceWith(
            page {
                addView(titleRow("Set Alarm", pillButton("Back", ButtonStyle.GHOST) { showHome() }))

                val content = vertical(color = SURFACE)
                content.addView(card(color = DARK, padding = 20, radius = 28).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(iconBadge(alarm.type, large = true), LinearLayout.LayoutParams(dp(64), dp(64)).withMargin(0, 0, 14, 0))
                    addView(vertical().apply {
                        addView(label("${alarm.typeLabel} Alarm", 24, Color.WHITE, bold = true))
                        addView(label(typeHelp(alarm.type), 14, MUTED))
                    })
                })

                content.addView(sectionTitle("Time"))
                content.addView(card(padding = 10, radius = 24).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    addView(hourPicker, pickerParams())
                    addView(minutePicker, pickerParams())
                    addView(periodPicker, pickerParams())
                })

                content.addView(sectionTitle("Repeat"))
                content.addView(horizontal().apply {
                    dayOptions.forEach { day ->
                        addView(dayButton(day.label, repeat.contains(day.calendarDay)) {
                            if (repeat.contains(day.calendarDay)) repeat.remove(day.calendarDay) else repeat.add(day.calendarDay)
                            showAlarmEditorWith(alarm.copy(repeatDays = repeat))
                        }, LinearLayout.LayoutParams(0, dp(44), 1f).withMargin(3))
                    }
                })

                content.addView(sectionTitle("Label"))
                content.addView(labelInput)

                when (alarm.type) {
                    AlarmType.WIFI -> content.addWifiConfig(alarm)
                    AlarmType.MOTION -> content.addMotionConfig(alarm)
                    AlarmType.SIMPLE -> Unit
                }

                if (repository.getAlarm(alarm.id) != null) {
                    content.addView(pillButton("Delete Alarm", ButtonStyle.DANGER) {
                        repository.updateAlarms(repository.getAlarms().filterNot { it.id == alarm.id })
                        scheduler.cancel(alarm.id)
                        showHome()
                    })
                }

                addView(scroll(content), weightParams())
                addView(pillButton("Save Alarm", ButtonStyle.DARK) {
                    val hour = fromDisplayHour(hourPicker.value, periodPicker.value == 1)
                    val saved = alarm.copy(
                        hour = hour,
                        minute = minutePicker.value,
                        label = labelInput.text.toString().ifBlank { "Wake Up" },
                        repeatDays = repeat.ifEmpty { allRepeatDays() }
                    )
                    repository.saveAlarm(saved)
                    scheduler.schedule(saved)
                    showHome()
                })
            }
        )
    }

    private fun LinearLayout.addWifiConfig(alarm: WakeAlarm) {
        addView(sectionTitle("WiFi Challenge"))
        addView(card(padding = 16, radius = 24).apply {
            addView(label("The alarm unlocks only near a strong saved WiFi signal.", 15, GRAY))
            addView(space(8))
            addView(pillButton(if (alarm.config.wifiLocationSet) "Current WiFi Set" else "Set Current WiFi", ButtonStyle.LIGHT) {
                showAlarmEditorWith(alarm.copy(config = alarm.config.copy(wifiLocationSet = true)))
            })
            addView(configChoice("Sensitivity", alarm.config.wifiSensitivity.name) {
                showAlarmEditorWith(alarm.copy(config = alarm.config.copy(wifiSensitivity = nextSensitivity(alarm.config.wifiSensitivity))))
            })
        })
    }

    private fun LinearLayout.addMotionConfig(alarm: WakeAlarm) {
        addView(sectionTitle("Motion Challenge"))
        addView(card(padding = 16, radius = 24).apply {
            addView(label("Use phone motion to prove you are awake.", 15, GRAY))
            addView(space(8))
            addView(configChoice("Steps Required", alarm.config.motionSteps.toString()) {
                val next = when (alarm.config.motionSteps) {
                    10 -> 20
                    20 -> 30
                    else -> 10
                }
                showAlarmEditorWith(alarm.copy(config = alarm.config.copy(motionSteps = next)))
            })
            addView(configChoice("Sensitivity", alarm.config.motionSensitivity.name) {
                showAlarmEditorWith(alarm.copy(config = alarm.config.copy(motionSensitivity = nextSensitivity(alarm.config.motionSensitivity))))
            })
            addView(configChoice("Mode", alarm.config.motionMode.name) {
                val mode = if (alarm.config.motionMode == MotionMode.WALK) MotionMode.SHAKE else MotionMode.WALK
                showAlarmEditorWith(alarm.copy(config = alarm.config.copy(motionMode = mode)))
            })
        })
    }

    private fun showAlarmEditorWith(alarm: WakeAlarm) {
        editAlarm = alarm
        showAlarmEditor()
    }

    private fun startTriggered(alarmId: String) {
        triggeredAlarm = repository.getAlarm(alarmId) ?: alarms.firstOrNull()
        stepsTaken = 0
        playAlarmSound()
        if (triggeredAlarm?.type == AlarmType.MOTION) {
            sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
            }
        }
        if (triggeredAlarm?.type == AlarmType.WIFI) {
            handler.post(wifiTicker)
        }
        showTriggered()
    }

    private fun showTriggered() {
        val alarm = triggeredAlarm ?: return showHome()
        val canStop = when (alarm.type) {
            AlarmType.SIMPLE -> true
            AlarmType.WIFI -> wifiSignal >= wifiThreshold(alarm.config.wifiSensitivity)
            AlarmType.MOTION -> stepsTaken >= alarm.config.motionSteps
        }

        applyDarkChrome()
        root.replaceWith(
            vertical(color = DARK, padding = 24) {
                gravity = Gravity.CENTER
                addView(iconBadge(alarm.type, large = true), LinearLayout.LayoutParams(dp(84), dp(84)).withMargin(0, 0, 0, 18))
                addView(label(alarm.displayTime, 54, Color.WHITE, bold = true, gravity = Gravity.CENTER))
                addView(label(alarm.label.ifBlank { "Wake Up" }, 20, MUTED, gravity = Gravity.CENTER))
                addView(space(20))
                addView(card(color = Color.rgb(28, 28, 40), padding = 24, radius = 28).apply {
                    when (alarm.type) {
                        AlarmType.SIMPLE -> addView(label("Tap below to stop the alarm.", 18, Color.WHITE, gravity = Gravity.CENTER))
                        AlarmType.WIFI -> {
                            addView(label("Move closer to your WiFi router.", 18, Color.WHITE, gravity = Gravity.CENTER))
                            addView(space(8))
                            addView(label("$wifiSignal dBm", 42, ACCENT_BLUE, bold = true, gravity = Gravity.CENTER))
                            addView(progressBar(wifiProgress(alarm), ACCENT_BLUE))
                            addView(label("Target: ${wifiThreshold(alarm.config.wifiSensitivity)} dBm or stronger", 13, MUTED, gravity = Gravity.CENTER))
                        }
                        AlarmType.MOTION -> {
                            addView(label("Complete the motion challenge.", 18, Color.WHITE, gravity = Gravity.CENTER))
                            addView(space(8))
                            addView(label("$stepsTaken / ${alarm.config.motionSteps}", 42, ACCENT_PURPLE, bold = true, gravity = Gravity.CENTER))
                            addView(progressBar((stepsTaken * 100 / alarm.config.motionSteps).coerceIn(0, 100), ACCENT_PURPLE))
                        }
                    }
                })
                addView(space(12))
                addView(pillButton(if (canStop) "Stop Alarm" else "Challenge In Progress", if (canStop) ButtonStyle.SUCCESS else ButtonStyle.DISABLED, enabled = canStop) {
                    stopAlarm()
                })
                addView(pillButton("Snooze 5 Minutes", ButtonStyle.GHOST_DARK) {
                    snoozeAlarm()
                    showHome()
                })
            }
        )
    }

    private fun snoozeAlarm() {
        val alarm = triggeredAlarm ?: return stopAlarm()
        scheduler.scheduleSnooze(alarm)
        ringtone?.stop()
        ringtone = null
        AlarmNotifier(this).cancel(alarm.id)
        stopChallengeSensors()
        triggeredAlarm = null
    }

    private fun stopAlarm() {
        triggeredAlarm?.let { AlarmNotifier(this).cancel(it.id) }
        ringtone?.stop()
        ringtone = null
        stopChallengeSensors()
        triggeredAlarm = null
        showHome()
    }

    private fun stopChallengeSensors() {
        handler.removeCallbacks(wifiTicker)
        sensorManager.unregisterListener(this)
    }

    private fun playAlarmSound() {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        ringtone = RingtoneManager.getRingtone(this, uri).also { it.play() }
    }

    private fun refreshWifiSignal() {
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiSignal = wifi.connectionInfo?.rssi ?: -100
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_NOTIFICATIONS
            )
        }
    }

    private fun typeHelp(type: AlarmType): String = when (type) {
        AlarmType.SIMPLE -> "Standard stop button alarm"
        AlarmType.WIFI -> "Move close to WiFi to disable"
        AlarmType.MOTION -> "Walk or shake to disable"
    }

    private fun nextSensitivity(current: Sensitivity): Sensitivity = when (current) {
        Sensitivity.LOW -> Sensitivity.MEDIUM
        Sensitivity.MEDIUM -> Sensitivity.HIGH
        Sensitivity.HIGH -> Sensitivity.LOW
    }

    private fun wifiThreshold(sensitivity: Sensitivity): Int = when (sensitivity) {
        Sensitivity.LOW -> -55
        Sensitivity.MEDIUM -> -50
        Sensitivity.HIGH -> -45
    }

    private fun wifiProgress(alarm: WakeAlarm): Int {
        val threshold = wifiThreshold(alarm.config.wifiSensitivity)
        return (((wifiSignal + 100).toFloat() / (threshold + 100).toFloat()) * 100).toInt().coerceIn(0, 100)
    }

    private fun toDisplayHour(hour: Int): Int = when {
        hour == 0 -> 12
        hour > 12 -> hour - 12
        else -> hour
    }

    private fun fromDisplayHour(hour: Int, isPm: Boolean): Int = when {
        isPm && hour != 12 -> hour + 12
        !isPm && hour == 12 -> 0
        else -> hour
    }

    private fun page(block: LinearLayout.() -> Unit): LinearLayout =
        vertical(color = SURFACE, padding = 20).apply {
            applyLightChrome()
            clipToPadding = false
            block()
        }

    private fun appTitle(title: String, subtitle: String): LinearLayout =
        vertical(color = SURFACE).apply {
            addView(label(title, 28, DARK, bold = true))
            addView(label(subtitle, 14, GRAY))
            addView(space(16))
        }

    private fun emptyState(): View =
        card(padding = 22, radius = 24).apply {
            gravity = Gravity.CENTER
            addView(label("No alarms yet", 22, DARK, bold = true, gravity = Gravity.CENTER))
            addView(label("Create one and WakeUp Pro will handle the rest.", 14, GRAY, gravity = Gravity.CENTER))
        }

    private fun iconBadge(type: AlarmType, large: Boolean = false): TextView {
        val (text, color) = when (type) {
            AlarmType.SIMPLE -> "A" to 0xFFFFB74D.toInt()
            AlarmType.WIFI -> "W" to ACCENT_BLUE
            AlarmType.MOTION -> "M" to ACCENT_PURPLE
        }
        return label(text, if (large) 26 else 20, Color.WHITE, bold = true, gravity = Gravity.CENTER).apply {
            background = rounded(color, if (large) 22 else 18)
        }
    }

    private fun chip(text: String, textColor: Int, backgroundColor: Int): TextView =
        label(text, 12, textColor, bold = true, gravity = Gravity.CENTER).apply {
            background = rounded(backgroundColor, 18)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).withMargin(0, 0, 0, 0).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }

    private fun repeatSummary(days: Set<Int>): String {
        if (days.size == 7) return "Every day"
        if (days == defaultRepeatDays()) return "Weekdays"
        val order = listOf(
            Calendar.MONDAY to "Mon",
            Calendar.TUESDAY to "Tue",
            Calendar.WEDNESDAY to "Wed",
            Calendar.THURSDAY to "Thu",
            Calendar.FRIDAY to "Fri",
            Calendar.SATURDAY to "Sat",
            Calendar.SUNDAY to "Sun"
        )
        return order.filter { days.contains(it.first) }.joinToString(", ") { it.second }.ifBlank { "Once" }
    }

    private fun vertical(color: Int = Color.WHITE, padding: Int = 0, block: LinearLayout.() -> Unit = {}): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color)
            setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
            block()
        }

    private fun horizontal(block: LinearLayout.() -> Unit = {}): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            block()
        }

    private fun card(color: Int = Color.WHITE, padding: Int = 14, radius: Int = 20): LinearLayout =
        vertical(padding = padding).apply {
            background = rounded(color, radius, if (color == Color.WHITE) BORDER else null)
            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).withMargin(0, 8, 0, 8)
            elevation = dp(2).toFloat()
        }

    private enum class ButtonStyle {
        DARK,
        LIGHT,
        GHOST,
        GHOST_DARK,
        DANGER,
        SUCCESS,
        DISABLED
    }

    private fun pillButton(text: String, style: ButtonStyle, enabled: Boolean = true, onClick: () -> Unit): Button {
        val backgroundColor = when (style) {
            ButtonStyle.DARK -> DARK
            ButtonStyle.LIGHT -> LIGHT
            ButtonStyle.GHOST -> Color.TRANSPARENT
            ButtonStyle.GHOST_DARK -> 0x18FFFFFF
            ButtonStyle.DANGER -> 0xFFFFE8E8.toInt()
            ButtonStyle.SUCCESS -> SUCCESS
            ButtonStyle.DISABLED -> 0xFF252735.toInt()
        }
        val textColor = when (style) {
            ButtonStyle.LIGHT, ButtonStyle.GHOST -> DARK
            ButtonStyle.DANGER -> DANGER
            ButtonStyle.DISABLED -> MUTED
            else -> Color.WHITE
        }
        return Button(this).apply {
            this.text = text
            isAllCaps = false
            this.isEnabled = enabled
            setTextColor(textColor)
            typeface = Typeface.DEFAULT_BOLD
            background = rounded(backgroundColor, 18, if (style == ButtonStyle.GHOST) BORDER else null)
            minHeight = dp(50)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setOnClickListener { onClick() }
            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).withMargin(0, 8, 0, 8)
        }
    }

    private fun button(text: String, enabled: Boolean = true, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            isAllCaps = false
            isEnabled = enabled
            setTextColor(if (enabled) Color.WHITE else GRAY)
            background = rounded(if (enabled) DARK else LIGHT, 16)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnClickListener { onClick() }
        }

    private fun smallButton(text: String, selected: Boolean, onClick: () -> Unit): Button =
        button(text, enabled = true, onClick).apply {
            setTextColor(if (selected) Color.WHITE else GRAY)
            background = rounded(if (selected) DARK else LIGHT, 21)
        }

    private fun dayButton(text: String, selected: Boolean, onClick: () -> Unit): Button =
        pillButton(text, if (selected) ButtonStyle.DARK else ButtonStyle.LIGHT, onClick = onClick).apply {
            minWidth = 0
            minHeight = 0
            setPadding(0, 0, 0, 0)
        }

    private fun titleRow(title: String, action: View): LinearLayout =
        horizontal {
            addView(label(title, 25, DARK, bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(action, LinearLayout.LayoutParams(dp(126), ViewGroup.LayoutParams.WRAP_CONTENT))
        }

    private fun sectionTitle(text: String): TextView = label(text, 14, GRAY, bold = true).apply {
        setPadding(0, dp(14), 0, dp(6))
    }

    private fun configChoice(title: String, value: String, onClick: () -> Unit): View =
        card(color = LIGHT, padding = 12).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(label(title, 15, DARK), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(chip(value, DARK, Color.WHITE))
            setOnClickListener { onClick() }
        }

    private fun label(text: String, size: Int, color: Int, bold: Boolean = false, gravity: Int = Gravity.START): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size.toFloat()
            setTextColor(color)
            this.gravity = gravity
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun scroll(content: View): ScrollView = ScrollView(this).apply { addView(content) }

    private fun progressBar(progress: Int, color: Int): ProgressBar =
        ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            this.progress = progress
            progressDrawable = rounded(color, 6)
            background = rounded(0x18FFFFFF, 6)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)).withMargin(0, 14, 0, 8)
        }

    private fun space(height: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(height))
    }

    private fun rounded(color: Int, radius: Int, strokeColor: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            strokeColor?.let { setStroke(dp(1), it) }
        }

    private fun pickerParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

    private fun weightParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun ViewGroup.MarginLayoutParams.withMargin(all: Int): ViewGroup.MarginLayoutParams =
        withMargin(all, all, all, all)

    private fun ViewGroup.MarginLayoutParams.withMargin(left: Int, top: Int, right: Int, bottom: Int): ViewGroup.MarginLayoutParams =
        apply { setMargins(dp(left), dp(top), dp(right), dp(bottom)) }

    private fun ViewGroup.replaceWith(view: View) {
        removeAllViews()
        addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun applyLightChrome() {
        window.statusBarColor = SURFACE
        window.navigationBarColor = SURFACE
        var flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) flags = flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        window.decorView.systemUiVisibility = flags
    }

    private fun applyDarkChrome() {
        window.statusBarColor = DARK
        window.navigationBarColor = DARK
        window.decorView.systemUiVisibility = 0
    }

    private data class DayOption(val label: String, val calendarDay: Int)

    private val dayOptions = listOf(
        DayOption("M", Calendar.MONDAY),
        DayOption("T", Calendar.TUESDAY),
        DayOption("W", Calendar.WEDNESDAY),
        DayOption("T", Calendar.THURSDAY),
        DayOption("F", Calendar.FRIDAY),
        DayOption("S", Calendar.SATURDAY),
        DayOption("S", Calendar.SUNDAY)
    )

    companion object {
        private const val REQUEST_NOTIFICATIONS = 1001
        private const val DARK = 0xFF0A0A14.toInt()
        private const val SURFACE = 0xFFF7F7FA.toInt()
        private const val LIGHT = 0xFFF2F3F5.toInt()
        private const val GRAY = 0xFF687080.toInt()
        private const val SOFT_TEXT = 0xFF9AA1AF.toInt()
        private const val MUTED = 0xFFADB2C0.toInt()
        private const val BORDER = 0xFFE7E9EF.toInt()
        private const val SUCCESS = 0xFF18A957.toInt()
        private const val DANGER = 0xFFE5484D.toInt()
        private const val ACCENT_BLUE = 0xFF5AA7FF.toInt()
        private const val ACCENT_PURPLE = 0xFFB58CFF.toInt()
    }
}
