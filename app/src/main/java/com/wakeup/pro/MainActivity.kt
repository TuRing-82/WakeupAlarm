package com.wakeup.pro

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
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
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

class MainActivity : AppCompatActivity(), SensorEventListener {
    private lateinit var root: FrameLayout
    private lateinit var repository: AlarmRepository
    private lateinit var scheduler: AlarmScheduler
    private lateinit var sensorManager: SensorManager

    private var alarms: List<WakeAlarm> = emptyList()
    private var editAlarm: WakeAlarm? = null
    private var triggeredAlarm: WakeAlarm? = null
    private var triggeredSnoozeCount = 0
    private var currentTab = Tab.ALARM
    private var ringtone: Ringtone? = null
    private var stepsTaken = 0
    private var lastAcceleration = 0f
    private var wifiSignal = -100
    private var stopwatchRunning = false
    private var stopwatchStartedAt = 0L
    private var stopwatchElapsedBeforeStart = 0L
    private var timerRunning = false
    private var timerDurationMs = 5 * 60_000L
    private var timerEndsAt = 0L
    private var timerRemainingMs = timerDurationMs
    private var timerAlertActive = false
    private var editorSource = EditorSource.HOME
    private var launchedFromAlarmIntent = false

    private val handler = Handler(Looper.getMainLooper())
    private val screenTicker = object : Runnable {
        override fun run() {
            when (currentTab) {
                Tab.WORLD -> showWorldClock()
                Tab.STOPWATCH -> showStopwatch()
                Tab.TIMER -> showTimer()
                Tab.ALARM -> Unit
            }
            handler.postDelayed(this, 1000)
        }
    }
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
            launchedFromAlarmIntent = true
            triggeredSnoozeCount = intent.getIntExtra(AlarmScheduler.EXTRA_SNOOZE_COUNT, 0)
            startTriggered(alarmId)
        } else {
            launchedFromAlarmIntent = false
            showAlarmHome()
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(AlarmScheduler.EXTRA_ALARM_ID)?.let {
            launchedFromAlarmIntent = true
            triggeredSnoozeCount = intent.getIntExtra(AlarmScheduler.EXTRA_SNOOZE_COUNT, 0)
            startTriggered(it)
        } ?: run {
            launchedFromAlarmIntent = false
        }
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

    private fun showAlarmHome() {
        currentTab = Tab.ALARM
        stopChallengeSensors()
        alarms = repository.getAlarms()
        showShell(Tab.ALARM) {
            val next = alarms.filter { it.enabled }
                .minByOrNull { AlarmScheduler.nextTriggerTime(it) ?: Long.MAX_VALUE }
            addView(chip("WAKEUP PRO", SAND, 0x14FFFFFF))
            addView(space(10))
            addView(label("Wake better.", 36, Color.WHITE, bold = true))
            addView(label("A calmer alarm app with challenges built in.", 14, TEXT_MUTED))
            addView(space(22))
            addView(nextAlarmHero(next))
            addView(sectionHeader("Your Alarms", "${alarms.count { it.enabled }} active"))

            if (alarms.isEmpty()) {
                addView(emptyDarkCard("No alarms yet", "Tap + to create your first alarm."))
            } else {
                alarms.forEach { addView(alarmRow(it)) }
            }
        }
    }

    private fun nextAlarmHero(next: WakeAlarm?): View =
        card(DARK_CARD, 24, 30).apply {
            background = gradientCard()
            addView(horizontal {
                addView(vertical(Color.TRANSPARENT).apply {
                    addView(chip("NEXT CYCLE", SAND, 0x20FFFFFF))
                    addView(space(14))
                    addView(label(next?.displayTime ?: "--:--", 48, Color.WHITE, bold = true))
                    addView(label(next?.label ?: "No active alarm", 16, TEXT_MUTED))
                    addView(space(8))
                    addView(label(next?.let { repeatSummary(it.repeatDays) } ?: "Add an alarm to start", 13, TEXT_SOFT))
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(accentOrb(), LinearLayout.LayoutParams(dp(88), dp(88)).withMargin(10, 0, 0, 0))
            })
        }

    private fun alarmRow(alarm: WakeAlarm): View =
        card(CARD, 16, 24).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { openEditAlarm(alarm) }

            addView(iconBadge(alarm.type), LinearLayout.LayoutParams(dp(52), dp(52)).withMargin(0, 0, 14, 0))
            addView(vertical(CARD).apply {
                addView(label(alarm.displayTime, 24, Color.WHITE, bold = true))
                addView(label(alarm.label.ifBlank { "Wake Up" }, 14, TEXT_MUTED))
                addView(label("${alarm.typeLabel} - ${repeatSummary(alarm.repeatDays)}", 12, TEXT_SOFT))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(Switch(this@MainActivity).apply {
                isChecked = alarm.enabled
                setOnCheckedChangeListener { _, checked ->
                    val updated = alarm.copy(enabled = checked)
                    repository.saveAlarm(updated)
                    if (checked) scheduler.schedule(updated) else scheduler.cancel(alarm.id)
                    alarms = repository.getAlarms()
                    refreshAlarmHomeIfVisible()
                }
            })
        }

    private fun showWorldClock() {
        currentTab = Tab.WORLD
        showShell(Tab.WORLD) {
            addView(label("World Clock", 34, Color.WHITE, bold = true))
            addView(label("Track time zones across your team and routine.", 14, TEXT_MUTED))
            addView(space(20))
            addView(ClockFaceView(this@MainActivity, ClockFaceMode.CLOCK), LinearLayout.LayoutParams(dp(250), dp(250)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            })
            addView(space(22))
            addView(cityTimeCard("Lucknow", "Asia/Kolkata"))
            addView(cityTimeCard("London", "Europe/London"))
            addView(cityTimeCard("New York", "America/New_York"))
            addView(cityTimeCard("Tokyo", "Asia/Tokyo"))
        }
    }

    private fun showStopwatch() {
        currentTab = Tab.STOPWATCH
        val elapsed = currentStopwatchElapsed()
        showShell(Tab.STOPWATCH) {
            addView(label("Stopwatch", 34, Color.WHITE, bold = true))
            addView(label("A clean timing surface for workouts and drills.", 14, TEXT_MUTED))
            addView(space(20))
            addView(ClockFaceView(this@MainActivity, ClockFaceMode.STOPWATCH, elapsed), LinearLayout.LayoutParams(dp(250), dp(250)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            })
            addView(label(formatElapsed(elapsed), 40, TEAL, bold = true, gravity = Gravity.CENTER))
            addView(space(24))
            addView(horizontal {
                addView(roundAction(if (stopwatchRunning) "Pause" else "Start", TEAL) {
                    if (stopwatchRunning) pauseStopwatch() else startStopwatch()
                }, LinearLayout.LayoutParams(0, dp(58), 1f).withMargin(6))
                addView(roundAction("Reset", 0xFF45474D.toInt()) {
                    stopwatchRunning = false
                    stopwatchStartedAt = 0L
                    stopwatchElapsedBeforeStart = 0L
                    showStopwatch()
                }, LinearLayout.LayoutParams(0, dp(58), 1f).withMargin(6))
            })
        }
    }

    private fun showTimer() {
        currentTab = Tab.TIMER
        if (timerAlertActive) return showTimerAlert()
        if (timerRunning) {
            timerRemainingMs = (timerEndsAt - System.currentTimeMillis()).coerceAtLeast(0L)
            if (timerRemainingMs == 0L) {
                timerRunning = false
                return triggerTimerAlert()
            }
        }
        showShell(Tab.TIMER) {
            addView(label("Timer", 34, Color.WHITE, bold = true))
            addView(label("Build focus blocks and cooldowns without friction.", 14, TEXT_MUTED))
            addView(space(24))
            addView(card(DARK_CARD, 24, 32).apply {
                background = gradientCard()
                addView(label(formatTimer(timerRemainingMs), 48, Color.WHITE, bold = true, gravity = Gravity.CENTER))
                addView(space(14))
                addView(progressBar(timerProgress(), TEAL))
            })
            addView(space(16))
            addView(horizontal {
                listOf(1, 5, 10).forEach { minutes ->
                    addView(roundAction("${minutes}m", CARD) {
                        timerAlertActive = false
                        ringtone?.stop()
                        ringtone = null
                        timerDurationMs = minutes * 60_000L
                        timerRemainingMs = timerDurationMs
                        timerRunning = false
                        showTimer()
                    }, LinearLayout.LayoutParams(0, dp(52), 1f).withMargin(6))
                }
            })
            addView(space(18))
            addView(horizontal {
                addView(roundAction(if (timerRunning) "Pause" else "Start", TEAL) {
                    if (timerRunning) {
                        timerRemainingMs = (timerEndsAt - System.currentTimeMillis()).coerceAtLeast(0L)
                        timerRunning = false
                    } else {
                        timerAlertActive = false
                        timerEndsAt = System.currentTimeMillis() + timerRemainingMs
                        timerRunning = true
                    }
                    showTimer()
                }, LinearLayout.LayoutParams(0, dp(58), 1f).withMargin(6))
                addView(roundAction("Reset", 0xFF45474D.toInt()) {
                    timerAlertActive = false
                    ringtone?.stop()
                    ringtone = null
                    timerRunning = false
                    timerRemainingMs = timerDurationMs
                    showTimer()
                }, LinearLayout.LayoutParams(0, dp(58), 1f).withMargin(6))
            })
        }
    }

    private fun triggerTimerAlert() {
        if (timerAlertActive) return
        timerAlertActive = true
        timerRemainingMs = 0L
        playAlarmSound()
        showTimerAlert()
    }

    private fun showTimerAlert() {
        currentTab = Tab.TIMER
        applyDarkChrome()
        handler.removeCallbacks(screenTicker)
        root.replaceWith(vertical(BG, 24) {
            gravity = Gravity.CENTER
            addView(label("Timer Complete", 34, Color.WHITE, bold = true, gravity = Gravity.CENTER))
            addView(label("Your countdown is done.", 16, TEXT_MUTED, gravity = Gravity.CENTER))
            addView(space(24))
            addView(card(DARK_CARD, 24, 32).apply {
                background = gradientCard()
                addView(label("00:00", 54, Color.WHITE, bold = true, gravity = Gravity.CENTER))
                addView(space(12))
                addView(label("Time's up.", 15, TEXT_SOFT, gravity = Gravity.CENTER))
            })
            addView(space(18))
            addView(fullButton("Stop Timer", COPPER) {
                stopTimerAlert()
            })
            addView(space(10))
            addView(fullButton("Start 1 Minute Again", CARD) {
                stopTimerAlert()
                timerDurationMs = 60_000L
                timerRemainingMs = timerDurationMs
                timerEndsAt = System.currentTimeMillis() + timerRemainingMs
                timerRunning = true
                showTimer()
            })
        })
    }

    private fun stopTimerAlert() {
        timerAlertActive = false
        ringtone?.stop()
        ringtone = null
        timerRunning = false
        timerRemainingMs = timerDurationMs
        showTimer()
    }

    private fun openNewAlarm() {
        editorSource = EditorSource.HOME
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
        editorSource = EditorSource.HOME
        editAlarm = alarm
        showAlarmEditor()
    }

    private fun showTypePicker() {
        showStandaloneDark {
            addView(topActionRow("Add Alarm", "Back", { showAlarmHome() }))
            addView(label("Pick the wake-up style that fits the routine.", 14, TEXT_MUTED))
            addView(space(18))
            listOf(
                AlarmType.SIMPLE to "Standard stop button alarm",
                AlarmType.WIFI to "Move close to WiFi to disable",
                AlarmType.MOTION to "Walk or shake to disable"
            ).forEach { (type, subtitle) ->
                addView(card(CARD, 18, 24).apply {
                    background = elevatedCard()
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(iconBadge(type), LinearLayout.LayoutParams(dp(58), dp(58)).withMargin(0, 0, 14, 0))
                    addView(vertical(CARD).apply {
                        addView(label("${type.typeTitle()} Alarm", 22, Color.WHITE, bold = true))
                        addView(label(subtitle, 14, TEXT_MUTED))
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(label("->", 18, TEXT_SOFT, bold = true))
                    setOnClickListener {
                        editorSource = EditorSource.TYPE_PICKER
                        editAlarm = editAlarm?.copy(type = type)
                        showAlarmEditor()
                    }
                })
            }
        }
    }

    private fun showAlarmEditor() {
        val alarm = editAlarm ?: return showAlarmHome()
        val repeat = alarm.repeatDays.toMutableSet()
        var selectedHour = toDisplayHour(alarm.hour)
        var selectedMinute = alarm.minute
        var isPm = alarm.hour >= 12
        val labelInput = EditText(this).apply {
            hint = "Alarm name"
            setText(alarm.label)
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
            setTextColor(Color.WHITE)
            setHintTextColor(TEXT_SOFT)
            textSize = 16f
            background = rounded(CARD, 18, BORDER_DARK)
            setPadding(dp(16), dp(15), dp(16), dp(15))
        }

        fun redraw() {
            val previewHour = fromDisplayHour(selectedHour, isPm)
            editAlarm = alarm.copy(hour = previewHour, minute = selectedMinute, repeatDays = repeat)
            showAlarmEditor()
        }

        fun saveCurrentAlarm() {
            val draft = editAlarm ?: alarm
            val existing = repository.getAlarm(draft.id)
            val saved = draft.copy(
                hour = fromDisplayHour(selectedHour, isPm),
                minute = selectedMinute,
                label = labelInput.text.toString().ifBlank { "Wake Up" },
                repeatDays = repeat,
                enabled = existing?.enabled ?: true
            )
            val updatedAlarms = repository.getAlarms().toMutableList()
            val existingIndex = updatedAlarms.indexOfFirst { it.id == saved.id }
            if (existingIndex >= 0) {
                updatedAlarms[existingIndex] = saved
            } else {
                updatedAlarms.add(saved)
            }
            alarms = updatedAlarms.sortedWith(compareBy<WakeAlarm> { it.hour }.thenBy { it.minute })
            repository.updateAlarms(alarms)
            scheduler.scheduleAll(alarms)
            editAlarm = null
            editorSource = EditorSource.HOME
            currentTab = Tab.ALARM
            showAlarmHome()
        }

        showStandaloneDark {
            addView(
                topActionRow(
                    "New alarm",
                    if (editorSource == EditorSource.TYPE_PICKER) "Back" else "Cancel",
                    {
                        if (editorSource == EditorSource.TYPE_PICKER) showTypePicker() else showAlarmHome()
                    },
                    "Done",
                    { saveCurrentAlarm() }
                )
            )
            addView(label("Triggers in ${nextAlarmDistanceText(alarm)}", 13, TEXT_SOFT, gravity = Gravity.CENTER))
            addView(space(18))
            addView(timePickerCard(alarm, { selectedHour = it; redraw() }, { selectedMinute = it; redraw() }, { isPm = it; redraw() }))
            addView(space(12))
            addView(segmentedRepeat(repeat) { newRepeat ->
                editAlarm = alarm.copy(repeatDays = newRepeat)
                showAlarmEditor()
            })
            addView(space(12))
            addView(settingsCard {
                addView(formRow("Alarm name", labelInput))
                addView(divider())
                addView(clickRow("Ringtone", "Default alarm") { })
                addView(divider())
                addView(switchRow("Vibrate", true))
                addView(divider())
                addView(clickRow("Snooze", snoozeSummary(alarm)) {
                    showSnoozeEditor(alarm)
                })
                when (alarm.type) {
                    AlarmType.WIFI -> {
                        addView(divider())
                        addView(clickRow("WiFi challenge", alarm.config.wifiSensitivity.name) {
                            showAlarmEditorWith(alarm.copy(config = alarm.config.copy(wifiSensitivity = nextSensitivity(alarm.config.wifiSensitivity))))
                        })
                    }
                    AlarmType.MOTION -> {
                        addView(divider())
                        addView(clickRow("Motion challenge", "${alarm.config.motionSteps} steps") {
                            val next = when (alarm.config.motionSteps) {
                                10 -> 20
                                20 -> 30
                                else -> 10
                            }
                            showAlarmEditorWith(alarm.copy(config = alarm.config.copy(motionSteps = next)))
                        })
                    }
                    AlarmType.SIMPLE -> Unit
                }
            })
            if (repository.getAlarm(alarm.id) != null) {
                addView(space(12))
                addView(fullButton("Delete Alarm", DANGER) {
                    repository.updateAlarms(repository.getAlarms().filterNot { it.id == alarm.id })
                    scheduler.cancel(alarm.id)
                    showAlarmHome()
                })
            }
            addView(View(this@MainActivity), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(fullButton("Done", COPPER) { saveCurrentAlarm() })
        }
    }

    private fun showSnoozeEditor(alarm: WakeAlarm) {
        val minuteOptions = listOf(3, 5, 10, 15)
        val repeatOptions = listOf(1, 2, 3, 5)
        showStandaloneDark {
            addView(topActionRow("Snooze", "Back", { showAlarmEditorWith(alarm) }))
            addView(label("Choose how long to snooze and how many times it can repeat.", 14, TEXT_MUTED))
            addView(space(22))
            addView(label("Minutes", 13, SAND, bold = true))
            addView(space(8))
            addView(horizontal {
                minuteOptions.forEach { minutes ->
                    addView(
                        segmentButton("${minutes} min", alarm.config.snoozeMinutes == minutes) {
                            showSnoozeEditor(alarm.copy(config = alarm.config.copy(snoozeMinutes = minutes)))
                        },
                        LinearLayout.LayoutParams(0, dp(44), 1f).withMargin(5)
                    )
                }
            })
            addView(space(18))
            addView(label("Repeats", 13, SAND, bold = true))
            addView(space(8))
            addView(horizontal {
                repeatOptions.forEach { repeats ->
                    addView(
                        segmentButton("${repeats}x", alarm.config.snoozeRepeatCount == repeats) {
                            showSnoozeEditor(alarm.copy(config = alarm.config.copy(snoozeRepeatCount = repeats)))
                        },
                        LinearLayout.LayoutParams(0, dp(44), 1f).withMargin(5)
                    )
                }
            })
            addView(View(this@MainActivity), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(fullButton("Done", COPPER) { showAlarmEditorWith(alarm) })
        }
    }

    private fun timePickerCard(
        alarm: WakeAlarm,
        onHour: (Int) -> Unit,
        onMinute: (Int) -> Unit,
        onPeriod: (Boolean) -> Unit
    ): View {
        val hour = toDisplayHour(alarm.hour)
        val minute = alarm.minute
        val isPm = alarm.hour >= 12
        return card(0xFF262B34.toInt(), 18, 28).apply {
            background = gradientPanel()
            addView(label("TIME", 12, SAND, bold = true, gravity = Gravity.CENTER))
            addView(space(14))
            addView(horizontal {
                gravity = Gravity.CENTER
                addView(numberColumn("hour", hour, 1, 12, onHour), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(label(":", 38, Color.WHITE, bold = true, gravity = Gravity.CENTER), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).withMargin(6, 0, 6, 0))
                addView(numberColumn("minute", minute, 0, 59, onMinute), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(periodColumn(isPm, onPeriod), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).withMargin(12, 0, 0, 0))
            })
        }
    }

    private fun numberColumn(suffix: String, value: Int, min: Int, max: Int, onChange: (Int) -> Unit): View =
        vertical(Color.TRANSPARENT).apply {
            gravity = Gravity.CENTER
            addView(miniTextButton("+") { onChange(if (value == max) min else value + 1) })
            addView(label("%02d".format(value), 36, Color.WHITE, bold = true, gravity = Gravity.CENTER))
            addView(label(suffix.uppercase(), 11, TEXT_SOFT, bold = true, gravity = Gravity.CENTER))
            addView(miniTextButton("-") { onChange(if (value == min) max else value - 1) })
        }

    private fun periodColumn(isPm: Boolean, onChange: (Boolean) -> Unit): View =
        vertical(Color.TRANSPARENT).apply {
            gravity = Gravity.CENTER
            addView(periodPill("AM", !isPm) { onChange(false) })
            addView(space(8))
            addView(periodPill("PM", isPm) { onChange(true) })
        }

    private fun showAlarmEditorWith(alarm: WakeAlarm) {
        editAlarm = alarm
        showAlarmEditor()
    }

    private fun startTriggered(alarmId: String) {
        triggeredAlarm = repository.getAlarm(alarmId) ?: alarms.firstOrNull()
        stepsTaken = 0
        prepareAlarmWindow()
        playAlarmSound()
        if (triggeredAlarm?.type == AlarmType.MOTION) {
            sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
            }
        }
        if (triggeredAlarm?.type == AlarmType.WIFI) handler.post(wifiTicker)
        showTriggered()
    }

    private fun showTriggered() {
        val alarm = triggeredAlarm ?: return showAlarmHome()
        val canStop = when (alarm.type) {
            AlarmType.SIMPLE -> true
            AlarmType.WIFI -> wifiSignal >= wifiThreshold(alarm.config.wifiSensitivity)
            AlarmType.MOTION -> stepsTaken >= alarm.config.motionSteps
        }
        val canSnooze = triggeredSnoozeCount < alarm.config.snoozeRepeatCount
        applyDarkChrome()
        root.replaceWith(vertical(BG, 24) {
            gravity = Gravity.CENTER
            addView(iconBadge(alarm.type, large = true), LinearLayout.LayoutParams(dp(86), dp(86)).withMargin(0, 0, 0, 18))
            addView(label(alarm.displayTime, 54, Color.WHITE, bold = true, gravity = Gravity.CENTER))
            addView(label(alarm.label.ifBlank { "Wake Up" }, 20, TEXT_MUTED, gravity = Gravity.CENTER))
            addView(space(22))
            addView(card(DARK_CARD, 24, 28).apply {
                when (alarm.type) {
                    AlarmType.SIMPLE -> addView(label("Tap below to stop the alarm.", 18, Color.WHITE, gravity = Gravity.CENTER))
                    AlarmType.WIFI -> {
                        addView(label("Move closer to your WiFi router.", 18, Color.WHITE, gravity = Gravity.CENTER))
                        addView(space(10))
                        addView(label("$wifiSignal dBm", 42, TEAL, bold = true, gravity = Gravity.CENTER))
                        addView(progressBar(wifiProgress(alarm), TEAL))
                        addView(label("Target: ${wifiThreshold(alarm.config.wifiSensitivity)} dBm or stronger", 13, TEXT_MUTED, gravity = Gravity.CENTER))
                    }
                    AlarmType.MOTION -> {
                        addView(label("Complete the motion challenge.", 18, Color.WHITE, gravity = Gravity.CENTER))
                        addView(space(10))
                        addView(label("$stepsTaken / ${alarm.config.motionSteps}", 42, PURPLE, bold = true, gravity = Gravity.CENTER))
                        addView(progressBar((stepsTaken * 100 / alarm.config.motionSteps).coerceIn(0, 100), PURPLE))
                    }
                }
            })
            addView(space(14))
            addView(fullButton(if (canStop) "Stop Alarm" else "Challenge In Progress", if (canStop) COPPER else CARD, enabled = canStop) {
                stopAlarm()
            })
            addView(fullButton(if (canSnooze) "Snooze ${alarm.config.snoozeMinutes} Minutes" else "Snooze Limit Reached", CARD, enabled = canSnooze) {
                snoozeAlarm()
            })
        })
    }

    private fun snoozeAlarm() {
        val alarm = triggeredAlarm ?: return stopAlarm()
        scheduler.scheduleSnooze(
            alarm = alarm,
            delayMinutes = alarm.config.snoozeMinutes,
            snoozeCount = triggeredSnoozeCount + 1
        )
        ringtone?.stop()
        ringtone = null
        AlarmNotifier(this).cancel(alarm.id)
        stopChallengeSensors()
        triggeredAlarm = null
        triggeredSnoozeCount = 0
        dismissTriggeredExperience()
    }

    private fun stopAlarm() {
        triggeredAlarm?.let { AlarmNotifier(this).cancel(it.id) }
        ringtone?.stop()
        ringtone = null
        stopChallengeSensors()
        triggeredAlarm = null
        triggeredSnoozeCount = 0
        dismissTriggeredExperience()
    }

    private fun dismissTriggeredExperience() {
        if (launchedFromAlarmIntent) {
            launchedFromAlarmIntent = false
            finish()
        } else {
            showAlarmHome()
        }
    }

    private fun stopChallengeSensors() {
        handler.removeCallbacks(wifiTicker)
        sensorManager.unregisterListener(this)
    }

    private fun refreshAlarmHomeIfVisible() {
        if (currentTab == Tab.ALARM && triggeredAlarm == null) {
            handler.post { showAlarmHome() }
        }
    }

    private fun prepareAlarmWindow() {
        setShowWhenLocked(true)
        setTurnScreenOn(true)
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
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
    }

    private fun showShell(tab: Tab, content: LinearLayout.() -> Unit) {
        applyDarkChrome()
        handler.removeCallbacks(screenTicker)
        if (tab != Tab.ALARM) handler.postDelayed(screenTicker, 1000)
        val shell = FrameLayout(this).apply {
            background = shellGradient()
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        val contentColumn = vertical(BG).apply {
            setBackgroundColor(Color.TRANSPARENT)
            addView(
                scroll(vertical(BG, 20).apply {
                    setBackgroundColor(Color.TRANSPARENT)
                    setPadding(dp(20), dp(22), dp(20), dp(96))
                    content()
                }),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
        }

        shell.addView(contentColumn)
        shell.addView(bottomNav(tab), FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM
            leftMargin = dp(14)
            rightMargin = dp(14)
            bottomMargin = dp(10)
        })
        shell.addView(fab(tab))
        root.replaceWith(shell)
    }

    private fun showStandaloneDark(content: LinearLayout.() -> Unit) {
        applyDarkChrome()
        handler.removeCallbacks(screenTicker)
        root.replaceWith(vertical(BG, 14).apply {
            setPadding(dp(14), dp(22), dp(14), dp(14))
            content()
        })
    }

    private fun fab(tab: Tab): View =
        TextView(this).apply {
            text = when (tab) {
                Tab.ALARM -> "+"
                Tab.WORLD -> "+"
                Tab.STOPWATCH -> if (stopwatchRunning) "||" else ">"
                Tab.TIMER -> if (timerRunning) "||" else ">"
            }
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = rounded(COPPER, 22)
            elevation = dp(8).toFloat()
            setOnClickListener {
                when (tab) {
                    Tab.ALARM -> openNewAlarm()
                    Tab.WORLD -> showWorldClock()
                    Tab.STOPWATCH -> if (stopwatchRunning) pauseStopwatch() else startStopwatch()
                    Tab.TIMER -> {
                        if (timerRunning) {
                            timerRemainingMs = (timerEndsAt - System.currentTimeMillis()).coerceAtLeast(0L)
                            timerRunning = false
                        } else {
                            timerEndsAt = System.currentTimeMillis() + timerRemainingMs
                            timerRunning = true
                        }
                        showTimer()
                    }
                }
            }
            layoutParams = FrameLayout.LayoutParams(dp(64), dp(56)).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(58)
            }
        }

    private fun bottomNav(active: Tab): View =
        horizontal {
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = rounded(0xFF191E28.toInt(), 22, BORDER_DARK)
            Tab.values().forEach { tab ->
                addView(navItem(tab, active == tab), LinearLayout.LayoutParams(0, dp(56), 1f))
            }
        }

    private fun navItem(tab: Tab, selected: Boolean): View =
        vertical(Color.TRANSPARENT).apply {
            gravity = Gravity.CENTER
            if (selected) background = rounded(0xFF242B38.toInt(), 16)
            addView(label(tab.icon, 16, if (selected) SAND else TEXT_SOFT, bold = true, gravity = Gravity.CENTER))
            addView(label(tab.title, 11, if (selected) Color.WHITE else TEXT_SOFT, gravity = Gravity.CENTER))
            setOnClickListener {
                when (tab) {
                    Tab.ALARM -> showAlarmHome()
                    Tab.WORLD -> showWorldClock()
                    Tab.STOPWATCH -> showStopwatch()
                    Tab.TIMER -> showTimer()
                }
            }
        }

    private fun sectionHeader(title: String, subtitle: String): View =
        horizontal {
            addView(label(title, 22, Color.WHITE, bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(label(subtitle, 13, TEXT_MUTED))
        }.apply { setPadding(0, dp(18), 0, dp(6)) }

    private fun topActionRow(
        title: String,
        leftText: String,
        leftAction: () -> Unit,
        rightText: String = "",
        rightAction: (() -> Unit)? = null
    ): View =
        horizontal {
            addView(textButton(leftText, SAND) { leftAction() }, LinearLayout.LayoutParams(dp(86), dp(44)))
            addView(vertical(BG).apply {
                gravity = Gravity.CENTER
                addView(label(title, 18, Color.WHITE, bold = true, gravity = Gravity.CENTER))
            }, LinearLayout.LayoutParams(0, dp(44), 1f))
            addView(
                textButton(rightText, COPPER) { rightAction?.invoke() },
                LinearLayout.LayoutParams(dp(86), dp(44))
            )
        }

    private fun segmentedRepeat(repeat: MutableSet<Int>, onChange: (Set<Int>) -> Unit): View =
        horizontal {
            val options = listOf(
                "Ring once" to emptySet<Int>(),
                "Weekdays" to defaultRepeatDays(),
                "Every day" to allRepeatDays()
            )
            options.forEach { (title, days) ->
                val selected = repeat == days || (title == "Every day" && repeat.size == 7)
                addView(segmentButton(title, selected) { onChange(days) }, LinearLayout.LayoutParams(0, dp(42), 1f).withMargin(5))
            }
        }

    private fun settingsCard(block: LinearLayout.() -> Unit): View = card(CARD, 0, 16).apply { block() }

    private fun formRow(title: String, input: EditText): View =
        vertical(CARD).apply {
            setPadding(dp(16), dp(14), dp(16), dp(14))
            addView(label(title, 14, TEXT_MUTED))
            addView(space(8))
            addView(input)
        }

    private fun clickRow(title: String, value: String, onClick: () -> Unit): View =
        horizontal {
            setPadding(dp(16), dp(14), dp(16), dp(14))
            addView(vertical(CARD).apply {
                addView(label(title, 15, Color.WHITE))
                addView(label(value, 13, TEXT_MUTED))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(label(">", 20, TEXT_SOFT))
            setOnClickListener { onClick() }
        }

    private fun switchRow(title: String, checked: Boolean): View =
        horizontal {
            setPadding(dp(16), dp(12), dp(16), dp(12))
            addView(label(title, 15, Color.WHITE), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(Switch(this@MainActivity).apply { isChecked = checked })
        }

    private fun cityTimeCard(city: String, zone: String): View {
        val format = SimpleDateFormat("hh:mm a", Locale.getDefault()).apply { timeZone = TimeZone.getTimeZone(zone) }
        return card(CARD, 16, 22).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(vertical(CARD).apply {
                addView(label(city, 18, Color.WHITE, bold = true))
                addView(label(zone.substringAfter('/').replace('_', ' '), 13, TEXT_MUTED))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(label(format.format(Calendar.getInstance().time), 22, Color.WHITE, bold = true))
        }
    }

    private fun startStopwatch() {
        stopwatchRunning = true
        stopwatchStartedAt = System.currentTimeMillis()
        showStopwatch()
    }

    private fun pauseStopwatch() {
        stopwatchElapsedBeforeStart = currentStopwatchElapsed()
        stopwatchRunning = false
        showStopwatch()
    }

    private fun currentStopwatchElapsed(): Long =
        if (stopwatchRunning) stopwatchElapsedBeforeStart + (System.currentTimeMillis() - stopwatchStartedAt) else stopwatchElapsedBeforeStart

    private fun timerProgress(): Int {
        if (timerDurationMs <= 0L) return 0
        return ((timerRemainingMs.toFloat() / timerDurationMs.toFloat()) * 100).toInt().coerceIn(0, 100)
    }

    private fun formatElapsed(ms: Long): String {
        val minutes = ms / 60_000
        val seconds = (ms / 1000) % 60
        val centis = (ms / 10) % 100
        return "%02d:%02d.%02d".format(minutes, seconds, centis)
    }

    private fun formatTimer(ms: Long): String {
        val totalSeconds = if (ms <= 0L) 0L else ceil(ms / 1000.0).toLong()
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "%02d:%02d".format(minutes, seconds)
    }

    private fun nextAlarmDistanceText(alarm: WakeAlarm): String {
        val trigger = AlarmScheduler.nextTriggerTime(alarm) ?: return "soon"
        val diff = ((trigger - System.currentTimeMillis()) / 60_000).coerceAtLeast(1)
        val hours = diff / 60
        val minutes = diff % 60
        return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
    }

    private fun typeHelp(type: AlarmType): String = when (type) {
        AlarmType.SIMPLE -> "Standard stop button alarm"
        AlarmType.WIFI -> "Move close to WiFi to disable"
        AlarmType.MOTION -> "Walk or shake to disable"
    }

    private fun AlarmType.typeTitle(): String = name.lowercase().replaceFirstChar { it.uppercase() }

    private fun snoozeSummary(alarm: WakeAlarm): String =
        "${alarm.config.snoozeMinutes} minutes, ${alarm.config.snoozeRepeatCount} times"

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

    private fun repeatSummary(days: Set<Int>): String {
        if (days.isEmpty()) return "Once"
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
        return order.filter { days.contains(it.first) }.joinToString(", ") { it.second }
    }

    private fun vertical(color: Int = BG, padding: Int = 0, block: LinearLayout.() -> Unit = {}): LinearLayout =
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

    private fun card(color: Int = CARD, padding: Int = 14, radius: Int = 20): LinearLayout =
        vertical(color, padding).apply {
            background = rounded(color, radius, BORDER_DARK)
            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).withMargin(0, 8, 0, 8)
            elevation = dp(2).toFloat()
        }

    private fun label(text: String, size: Int, color: Int, bold: Boolean = false, gravity: Int = Gravity.START): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size.toFloat()
            setTextColor(color)
            this.gravity = gravity
            includeFontPadding = true
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun fullButton(text: String, color: Int, enabled: Boolean = true, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            isAllCaps = false
            isEnabled = enabled
            typeface = Typeface.DEFAULT_BOLD
            textSize = 15f
            setTextColor(if (enabled) Color.WHITE else TEXT_MUTED)
            background = rounded(if (enabled) color else CARD, 18)
            minHeight = dp(52)
            setOnClickListener { onClick() }
        }

    private fun textButton(text: String, color: Int, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            isAllCaps = false
            setTextColor(color)
            textSize = 14f
            background = rounded(Color.TRANSPARENT, 18)
            setOnClickListener { onClick() }
        }

    private fun segmentButton(text: String, selected: Boolean, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            isAllCaps = false
            setTextColor(if (selected) Color.WHITE else TEXT_MUTED)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            background = rounded(if (selected) COPPER else CARD, 18)
            setOnClickListener { onClick() }
        }

    private fun miniTextButton(text: String, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            isAllCaps = false
            setTextColor(TEXT_SOFT)
            textSize = 14f
            background = rounded(Color.TRANSPARENT, 10)
            minHeight = dp(32)
            setOnClickListener { onClick() }
        }

    private fun roundAction(text: String, color: Int, onClick: () -> Unit): Button =
        fullButton(text, color, true, onClick)

    private fun periodPill(text: String, selected: Boolean, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            isAllCaps = false
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (selected) Color.WHITE else TEXT_MUTED)
            background = rounded(if (selected) COPPER else CARD, 16)
            setOnClickListener { onClick() }
        }

    private fun iconBadge(type: AlarmType, large: Boolean = false): TextView {
        val (text, color) = when (type) {
            AlarmType.SIMPLE -> "A" to AMBER
            AlarmType.WIFI -> "W" to BLUE
            AlarmType.MOTION -> "M" to PURPLE
        }
        return label(text, if (large) 26 else 20, Color.WHITE, bold = true, gravity = Gravity.CENTER).apply {
            background = rounded(color, if (large) 24 else 18)
        }
    }

    private fun chip(text: String, textColor: Int, backgroundColor: Int): TextView =
        label(text, 11, textColor, bold = true, gravity = Gravity.CENTER).apply {
            background = rounded(backgroundColor, 18)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }

    private fun emptyDarkCard(title: String, subtitle: String): View =
        card(CARD, 22, 24).apply {
            gravity = Gravity.CENTER
            addView(label(title, 22, Color.WHITE, bold = true, gravity = Gravity.CENTER))
            addView(label(subtitle, 14, TEXT_MUTED, gravity = Gravity.CENTER))
        }

    private fun divider(): View = View(this).apply {
        setBackgroundColor(BORDER_DARK)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).withMargin(16, 0, 16, 0)
    }

    private fun progressBar(progress: Int, color: Int): ProgressBar =
        ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            this.progress = progress
            progressDrawable = rounded(color, 6)
            background = rounded(0x22FFFFFF, 6)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)).withMargin(0, 14, 0, 8)
        }

    private fun scroll(content: View): ScrollView = ScrollView(this).apply {
        isFillViewport = false
        addView(content)
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

    private fun shellGradient(): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0xFF0E1622.toInt(), 0xFF12141B.toInt(), BG)
        ).apply { cornerRadius = 0f }

    private fun gradientCard(): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(0xFF132033.toInt(), 0xFF1A2031.toInt(), 0xFF251A18.toInt())
        ).apply {
            cornerRadius = dp(30).toFloat()
            setStroke(dp(1), BORDER_DARK)
        }

    private fun gradientPanel(): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(0xFF1C2432.toInt(), 0xFF2A2330.toInt())
        ).apply {
            cornerRadius = dp(28).toFloat()
            setStroke(dp(1), 0xFF353D4A.toInt())
        }

    private fun elevatedCard(): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(0xFF202633.toInt(), 0xFF282C34.toInt())
        ).apply {
            cornerRadius = dp(24).toFloat()
            setStroke(dp(1), 0xFF394150.toInt())
        }

    private fun accentOrb(): View =
        View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF2A5877.toInt(), 0xFFBC6F46.toInt())
            ).apply { shape = GradientDrawable.OVAL }
            alpha = 0.9f
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun ViewGroup.MarginLayoutParams.withMargin(all: Int): ViewGroup.MarginLayoutParams =
        withMargin(all, all, all, all)

    private fun ViewGroup.MarginLayoutParams.withMargin(left: Int, top: Int, right: Int, bottom: Int): ViewGroup.MarginLayoutParams =
        apply { setMargins(dp(left), dp(top), dp(right), dp(bottom)) }

    private fun ViewGroup.replaceWith(view: View) {
        removeAllViews()
        addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun applyDarkChrome() {
        window.statusBarColor = BG
        window.navigationBarColor = BG
        window.decorView.systemUiVisibility = 0
    }

    private enum class Tab(val title: String, val icon: String) {
        ALARM("Alarm", "AL"),
        WORLD("World", "WC"),
        STOPWATCH("Stopwatch", "SW"),
        TIMER("Timer", "TM")
    }

    private enum class EditorSource {
        HOME,
        TYPE_PICKER
    }

    private enum class ClockFaceMode { CLOCK, STOPWATCH }

    private class ClockFaceView(
        context: Context,
        private val mode: ClockFaceMode,
        private val elapsedMs: Long = 0L
    ) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val size = min(width, height).toFloat()
            val cx = width / 2f
            val cy = height / 2f
            val radius = size * 0.43f
            paint.style = Paint.Style.FILL
            paint.color = 0xFF10141A.toInt()
            canvas.drawCircle(cx, cy, radius, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = size * 0.035f
            paint.color = 0x55C27A48
            canvas.drawCircle(cx, cy, radius + size * 0.025f, paint)

            for (i in 0 until 60) {
                val angle = (i * 6 - 90) * PI / 180
                val inner = if (i % 5 == 0) radius * 0.82f else radius * 0.90f
                paint.color = if (i % 5 == 0) Color.WHITE else 0xFF676767.toInt()
                paint.strokeWidth = if (i % 5 == 0) 3f else 1.5f
                canvas.drawLine(
                    cx + cos(angle).toFloat() * inner,
                    cy + sin(angle).toFloat() * inner,
                    cx + cos(angle).toFloat() * radius * 0.96f,
                    cy + sin(angle).toFloat() * radius * 0.96f,
                    paint
                )
            }

            if (mode == ClockFaceMode.CLOCK) drawClockHands(canvas, cx, cy, radius) else drawStopwatchHand(canvas, cx, cy, radius)
        }

        private fun drawClockHands(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
            val now = Calendar.getInstance()
            val minute = now.get(Calendar.MINUTE)
            val hour = now.get(Calendar.HOUR)
            drawHand(canvas, cx, cy, ((hour + minute / 60f) * 30f) - 90f, radius * 0.48f, Color.WHITE, 8f)
            drawHand(canvas, cx, cy, minute * 6f - 90f, radius * 0.72f, COPPER, 5f)
            paint.style = Paint.Style.FILL
            paint.color = Color.WHITE
            canvas.drawCircle(cx, cy, 7f, paint)
        }

        private fun drawStopwatchHand(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
            val seconds = (elapsedMs / 1000f) % 60f
            drawHand(canvas, cx, cy, seconds * 6f - 90f, radius * 0.78f, TEAL, 5f)
            paint.style = Paint.Style.FILL
            paint.color = Color.WHITE
            canvas.drawCircle(cx, cy, 7f, paint)
        }

        private fun drawHand(canvas: Canvas, cx: Float, cy: Float, angleDeg: Float, length: Float, color: Int, stroke: Float) {
            val angle = angleDeg * PI / 180
            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeWidth = stroke
            paint.color = color
            canvas.drawLine(cx, cy, cx + cos(angle).toFloat() * length, cy + sin(angle).toFloat() * length, paint)
        }
    }

    companion object {
        private const val REQUEST_NOTIFICATIONS = 1001
        private const val BG = 0xFF11141B.toInt()
        private const val DARK_CARD = 0xFF141A24.toInt()
        private const val CARD = 0xFF222834.toInt()
        private const val BORDER_DARK = 0xFF343C49.toInt()
        private const val TEXT_MUTED = 0xFFB2BAC7.toInt()
        private const val TEXT_SOFT = 0xFF7B8493.toInt()
        private const val TEAL = 0xFF39B7A6.toInt()
        private const val BLUE = 0xFF5E92F3.toInt()
        private const val PURPLE = 0xFF9C83FF.toInt()
        private const val AMBER = 0xFFDB9A4A.toInt()
        private const val COPPER = 0xFFC27643.toInt()
        private const val SAND = 0xFFE6C89A.toInt()
        private const val DANGER = 0xFFE5484D.toInt()
    }
}
