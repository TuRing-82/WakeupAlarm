package com.wakeup.pro

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.InputType
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
import android.widget.Toast
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
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class MainActivity : AppCompatActivity(), SensorEventListener {
    private lateinit var root: FrameLayout
    private lateinit var repository: AlarmRepository
    private lateinit var scheduler: AlarmScheduler
    private lateinit var wrappedStore: WakeWrappedStore
    private lateinit var sensorManager: SensorManager
    private lateinit var vibrator: Vibrator

    private var alarms: List<WakeAlarm> = emptyList()
    private var editAlarm: WakeAlarm? = null
    private var triggeredAlarm: WakeAlarm? = null
    private var triggeredSnoozeCount = 0
    private var currentTab = Tab.ALARM
    private var alarmPlayer: MediaPlayer? = null
    private var stepsTaken = 0
    private var stepCounterBaseline: Float? = null
    private var stepSensorActive = false
    private var wifiSignal = -100
    private var wifiRawSignal = -100
    private var wifiConnectedSsid = ""
    private var wifiConnectedBssid = ""
    private var wifiStatus = "Checking WiFi..."
    private var stopwatchRunning = false
    private var stopwatchStartedAt = 0L
    private var stopwatchElapsedBeforeStart = 0L
    private var timerRunning = false
    private var timerDurationMs = 5 * 60_000L
    private var timerEndsAt = 0L
    private var timerRemainingMs = timerDurationMs
    private var timerAlertActive = false
    private var timerInputError: String? = null
    private var editorSource = EditorSource.HOME
    private var launchedFromAlarmIntent = false
    private var stopwatchFaceView: ClockFaceView? = null
    private var stopwatchTimeView: TextView? = null

    private val handler = Handler(Looper.getMainLooper())
    private val screenTicker = object : Runnable {
        override fun run() {
            when (currentTab) {
                Tab.WRAPPED -> showWrapped()
                Tab.STOPWATCH -> showStopwatch()
                Tab.TIMER -> showTimer()
                Tab.ALARM -> Unit
            }
            handler.postDelayed(this, 1000)
        }
    }
    private val stopwatchTicker = object : Runnable {
        override fun run() {
            if (!stopwatchRunning || currentTab != Tab.STOPWATCH) return
            val elapsed = currentStopwatchElapsed()
            stopwatchFaceView?.setElapsed(elapsed)
            stopwatchTimeView?.text = formatElapsed(elapsed)
            handler.postDelayed(this, 33)
        }
    }
    private val wifiTicker = object : Runnable {
        override fun run() {
            refreshWifiSignal()
            if (triggeredAlarm?.type == AlarmType.WIFI) {
                showTriggered()
                handler.postDelayed(this, 750)
            }
        }
    }
    private val alarmAudioWatchdog = object : Runnable {
        override fun run() {
            if (triggeredAlarm == null) return
            val player = alarmPlayer
            val isPlaying = runCatching { player?.isPlaying == true }.getOrDefault(false)
            if (!isPlaying) {
                runCatching { player?.start() }
            }
            handler.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        root = findViewById(android.R.id.content)
        repository = AlarmRepository(this)
        scheduler = AlarmScheduler(this)
        wrappedStore = WakeWrappedStore(this)
        sensorManager = getSystemService(SensorManager::class.java)
        vibrator = getSystemService(Vibrator::class.java)
        requestNotificationPermissionIfNeeded()
        requestActivityRecognitionPermissionIfNeeded()

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

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_RINGTONE_PICKER || resultCode != RESULT_OK) return
        val pickedUri = data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        val alarm = editAlarm ?: return
        val updatedConfig = if (pickedUri == null) {
            alarm.config.copy(ringtoneSource = RINGTONE_DEVICE_DEFAULT, ringtoneUri = "")
        } else {
            alarm.config.copy(ringtoneSource = RINGTONE_DEVICE_PICKED, ringtoneUri = pickedUri.toString())
        }
        showAlarmEditorWith(alarm.copy(config = updatedConfig))
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(stopwatchTicker)
        if (triggeredAlarm?.type == AlarmType.MOTION) {
            sensorManager.unregisterListener(this)
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (triggeredAlarm?.type != AlarmType.MOTION) return
        when (event.sensor.type) {
            Sensor.TYPE_STEP_COUNTER -> {
                val totalSteps = event.values.getOrNull(0) ?: return
                val baseline = stepCounterBaseline ?: totalSteps.also { stepCounterBaseline = it }
                stepsTaken = (totalSteps - baseline).toInt().coerceAtLeast(0)
            }
            Sensor.TYPE_STEP_DETECTOR -> {
                val detected = (event.values.getOrNull(0) ?: 1f).toInt().coerceAtLeast(1)
                stepsTaken += detected
            }
            else -> return
        }
        showTriggered()
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

    private fun showWrapped() {
        currentTab = Tab.WRAPPED
        val stats = wrappedStore.snapshot()
        val alarmsById = repository.getAlarms().associateBy { it.id }
        val mostSnoozed = stats.snoozeCounts.maxByOrNull { it.value }?.key?.let { alarmsById[it] }
        val topMotionAlarm = stats.motionStepsByAlarm.maxByOrNull { it.value }?.key?.let { alarmsById[it] }
        val lastWake = stats.lastRingAlarmId?.let { alarmsById[it] }
        val cleanRate = stats.cleanWakeRate()
        val wakeStyle = when {
            cleanRate >= 75 -> "First-try hero"
            cleanRate >= 45 -> "Balanced sleeper"
            stats.totalSnoozes >= stats.totalRings -> "Certified snoozer"
            else -> "Wake-up in progress"
        }
        showShell(Tab.WRAPPED) {
            addView(chip("WAKEUP WRAPPED", SAND, 0x18FFFFFF))
            addView(space(10))
            addView(label("Your mornings, wrapped.", 34, Color.WHITE, bold = true))
            addView(label("A tiny recap of the way you actually wake up.", 14, TEXT_MUTED))
            addView(space(22))
            addView(card(DARK_CARD, 22, 28).apply {
                background = gradientCard()
                addView(label("Wake style", 12, SAND, bold = true))
                addView(space(10))
                addView(label(wakeStyle, 30, Color.WHITE, bold = true))
                addView(space(8))
                addView(label("Clean wake rate: $cleanRate%", 14, TEXT_MUTED))
                addView(space(8))
                addView(progressBar(cleanRate, COPPER))
            })
            addView(space(14))
            addView(horizontal {
                addView(metricCard("Rings", "${stats.totalRings}", "Times an alarm actually fired", BLUE), LinearLayout.LayoutParams(0, dp(112), 1f).withMargin(6))
                addView(metricCard("Snoozes", "${stats.totalSnoozes}", "Times you asked for more time", COPPER), LinearLayout.LayoutParams(0, dp(112), 1f).withMargin(6))
            })
            addView(horizontal {
                addView(metricCard("First-try wins", "${stats.firstTryWins}", "Stopped without snoozing", TEAL), LinearLayout.LayoutParams(0, dp(112), 1f).withMargin(6))
                addView(metricCard("Avg snooze", averageSnoozeText(stats), "Snoozes per wake-up", PURPLE), LinearLayout.LayoutParams(0, dp(112), 1f).withMargin(6))
            })
            addView(horizontal {
                addView(metricCard("Challenge steps", "${stats.totalMotionSteps}", "Steps taken to stop motion alarms", PURPLE), LinearLayout.LayoutParams(0, dp(112), 1f).withMargin(6))
                addView(metricCard("Avg steps", "${stats.averageMotionSteps()}", "Per completed motion alarm", TEAL), LinearLayout.LayoutParams(0, dp(112), 1f).withMargin(6))
            })
            addView(space(6))
            addView(sectionHeader("Wrapped stories", "What the data says"))
            addView(infoCard(
                "Most snoozed",
                mostSnoozed?.label ?: "No snoozes yet",
                mostSnoozed?.let { "The one you keep negotiating with." } ?: "Once you snooze, this will show up here."
            ))
            addView(infoCard(
                "Last wake-up",
                lastWake?.label ?: "Nothing yet",
                if (stats.lastRingAt > 0L) "Last seen at ${formatWrappedTime(stats.lastRingAt)}" else "Set an alarm and let the story begin."
            ))
            addView(infoCard(
                "Most walked-off",
                topMotionAlarm?.label ?: "No motion steps yet",
                topMotionAlarm?.let { "${stats.motionStepsByAlarm[it.id] ?: 0} total challenge steps for this alarm." }
                    ?: "Complete a motion alarm and your steps will show here."
            ))
        }
    }

    private fun showStopwatch() {
        currentTab = Tab.STOPWATCH
        val elapsed = currentStopwatchElapsed()
        showShell(Tab.STOPWATCH) {
            addView(label("Stopwatch", 34, Color.WHITE, bold = true))
            addView(label("A clean timing surface for workouts and drills.", 14, TEXT_MUTED))
            addView(space(20))
            stopwatchFaceView = ClockFaceView(this@MainActivity, ClockFaceMode.STOPWATCH, elapsed)
            addView(stopwatchFaceView, LinearLayout.LayoutParams(dp(250), dp(250)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            })
            stopwatchTimeView = label(formatElapsed(elapsed), 40, TEAL, bold = true, gravity = Gravity.CENTER)
            addView(stopwatchTimeView)
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
        handler.removeCallbacks(stopwatchTicker)
        if (stopwatchRunning) handler.post(stopwatchTicker)
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

        val totalSeconds = timerRemainingMs / 1000
        val hoursPicker = numberPicker((totalSeconds / 3600).toInt(), 0, 23) { timerInputError = null }
        val minutesPicker = numberPicker(((totalSeconds / 60) % 60).toInt(), 0, 59) { timerInputError = null }
        val secondsPicker = numberPicker((totalSeconds % 60).toInt(), 0, 59) { timerInputError = null }
        listOf(hoursPicker, minutesPicker, secondsPicker).forEach { it.isEnabled = !timerRunning }

        showShell(Tab.TIMER) {
            addView(label("Timer", 34, Color.WHITE, bold = true))
            addView(label("Set hours, minutes, and seconds with the wheels.", 14, TEXT_MUTED))
            addView(space(24))
            addView(card(DARK_CARD, 24, 32).apply {
                background = gradientCard()
                addView(label(formatTimer(timerRemainingMs), 48, Color.WHITE, bold = true, gravity = Gravity.CENTER))
                addView(space(14))
                addView(progressBar(timerProgress(), TEAL))
            })
            addView(space(16))
            addView(card(CARD, 12, 22).apply {
                addView(horizontal {
                    addView(timerPickerColumn("Hours", hoursPicker), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(timerPickerColumn("Min", minutesPicker), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(timerPickerColumn("Sec", secondsPicker), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                })
                timerInputError?.let {
                    addView(space(8))
                    addView(label(it, 13, DANGER, gravity = Gravity.CENTER))
                }
            })
            addView(space(18))
            addView(horizontal {
                addView(roundAction(if (timerRunning) "Pause" else "Start", TEAL) {
                    startOrPauseTimer(hoursPicker, minutesPicker, secondsPicker)
                }, LinearLayout.LayoutParams(0, dp(58), 1f).withMargin(6))
                addView(roundAction("Reset", 0xFF45474D.toInt()) {
                    timerAlertActive = false
                    timerInputError = null
                    stopAlarmAudio()
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
            addView(fullButton("Start Again", CARD) {
                stopTimerAlert()
                timerRemainingMs = timerDurationMs
                timerEndsAt = System.currentTimeMillis() + timerRemainingMs
                timerRunning = true
                showTimer()
            })
        })
    }

    private fun stopTimerAlert() {
        timerAlertActive = false
        stopAlarmAudio()
        timerRunning = false
        timerRemainingMs = timerDurationMs
        showTimer()
    }

    private fun setCustomTimerDuration(hoursPicker: NumberPicker, minutesPicker: NumberPicker, secondsPicker: NumberPicker): Boolean {
        val duration = parseTimerDuration(hoursPicker, minutesPicker, secondsPicker) ?: return false
        timerDurationMs = duration
        timerRemainingMs = duration
        timerRunning = false
        timerAlertActive = false
        timerInputError = null
        showTimer()
        return true
    }

    private fun startOrPauseTimer(hoursPicker: NumberPicker, minutesPicker: NumberPicker, secondsPicker: NumberPicker) {
        if (timerRunning) {
            timerRemainingMs = (timerEndsAt - System.currentTimeMillis()).coerceAtLeast(0L)
            timerRunning = false
            showTimer()
            return
        }
        if (setCustomTimerDuration(hoursPicker, minutesPicker, secondsPicker)) {
            timerEndsAt = System.currentTimeMillis() + timerRemainingMs
            timerRunning = true
            showTimer()
        }
    }

    private fun parseTimerDuration(hoursPicker: NumberPicker, minutesPicker: NumberPicker, secondsPicker: NumberPicker): Long? {
        val totalSeconds = hoursPicker.value * 3600 + minutesPicker.value * 60 + secondsPicker.value
        if (totalSeconds <= 0) {
            timerInputError = "Set at least 1 second."
            showTimer()
            return null
        }
        return totalSeconds * 1000L
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
                AlarmType.MOTION to "Walk the required steps to disable"
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
        var repeat = alarm.repeatDays.toMutableSet()
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
                repeatDays = draft.repeatDays,
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

        val isExistingAlarm = repository.getAlarm(alarm.id) != null
        applyDarkChrome()
        handler.removeCallbacks(screenTicker)
        handler.removeCallbacks(stopwatchTicker)
        root.replaceWith(vertical(BG).apply {
            setPadding(dp(14), dp(22), dp(14), dp(14))
            addView(
                topActionRow(
                    if (isExistingAlarm) "Edit alarm" else "New alarm",
                    if (editorSource == EditorSource.TYPE_PICKER) "Back" else "Cancel",
                    {
                        if (editorSource == EditorSource.TYPE_PICKER) showTypePicker() else showAlarmHome()
                    },
                    "Done",
                    { saveCurrentAlarm() }
                )
            )
            addView(label("Triggers in ${nextAlarmDistanceText(alarm)}", 13, TEXT_SOFT, gravity = Gravity.CENTER))
            addView(space(12))
            addView(scroll(vertical(BG).apply {
                setPadding(0, 0, 0, dp(8))
                addView(timePickerCard(
                    alarm,
                    {
                        selectedHour = it
                        editAlarm = (editAlarm ?: alarm).copy(hour = fromDisplayHour(selectedHour, isPm), minute = selectedMinute)
                    },
                    {
                        selectedMinute = it
                        editAlarm = (editAlarm ?: alarm).copy(hour = fromDisplayHour(selectedHour, isPm), minute = selectedMinute)
                    },
                    {
                        isPm = it
                        redraw()
                    }
                ))
                addView(space(12))
                addView(segmentedRepeat(repeat) { newRepeat ->
                    repeat = newRepeat.toMutableSet()
                    editAlarm = (editAlarm ?: alarm).copy(repeatDays = repeat)
                    showAlarmEditor()
                })
                addView(space(12))
                addView(settingsCard {
                    addView(formRow("Alarm name", labelInput))
                    addView(divider())
                    addView(clickRow("Ringtone", ringtoneSummary(alarm)) {
                        showRingtoneEditor(alarm)
                    })
                    addView(divider())
                    addView(switchRow("Vibrate", alarm.config.vibrate) { checked ->
                        val draft = editAlarm ?: alarm
                        showAlarmEditorWith(draft.copy(config = draft.config.copy(vibrate = checked)))
                    })
                    addView(divider())
                    addView(clickRow("Snooze", snoozeSummary(alarm)) {
                        showSnoozeEditor(alarm)
                    })
                    when (alarm.type) {
                        AlarmType.WIFI -> {
                            addView(divider())
                            addView(clickRow("WiFi challenge", wifiChallengeSummary(alarm)) {
                                showWifiChallengeEditor(alarm)
                            })
                        }
                        AlarmType.MOTION -> {
                            addView(divider())
                            addView(clickRow("Step challenge", "${alarm.config.motionSteps} steps") {
                                showStepLimitEditor(alarm)
                            })
                        }
                        AlarmType.SIMPLE -> Unit
                    }
                })
            }), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(vertical(BG).apply {
                setPadding(0, dp(8), 0, 0)
                if (isExistingAlarm) {
                    addView(fullButton("Delete Alarm", DANGER) {
                        repository.updateAlarms(repository.getAlarms().filterNot { it.id == alarm.id })
                        scheduler.cancel(alarm.id)
                        showAlarmHome()
                    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).withMargin(0, 0, 0, 8))
                }
                addView(fullButton("Done", COPPER) { saveCurrentAlarm() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)))
            })
        })
    }

    private fun showRingtoneEditor(alarm: WakeAlarm) {
        val draft = editAlarm ?: alarm
        showStandaloneDark {
            addView(topActionRow("Ringtone", "Back", { showAlarmEditorWith(draft) }))
            addView(label("Choose a device alarm tone or one of the built-in wake-up melodies.", 14, TEXT_MUTED))
            addView(space(22))
            addView(settingsCard {
                addView(clickRow("Device default", if (draft.config.ringtoneSource == RINGTONE_DEVICE_DEFAULT) "Selected" else "Use phone alarm sound") {
                    showAlarmEditorWith(draft.copy(config = draft.config.copy(ringtoneSource = RINGTONE_DEVICE_DEFAULT, ringtoneUri = "")))
                })
                addView(divider())
                addView(clickRow("Select from device", deviceRingtoneName(draft), ::openDeviceRingtonePicker))
            })
            addView(space(16))
            addView(label("Wake-up melodies", 13, SAND, bold = true))
            addView(space(8))
            addView(settingsCard {
                appRingtoneOptions().forEachIndexed { index, option ->
                    if (index > 0) addView(divider())
                    addView(clickRow(option.title, option.subtitle + if (draft.config.ringtoneSource == RINGTONE_APP && draft.config.appRingtone == option.id) " - Selected" else "") {
                        showAlarmEditorWith(draft.copy(config = draft.config.copy(ringtoneSource = RINGTONE_APP, appRingtone = option.id)))
                    })
                }
            })
        }
    }

    private fun openDeviceRingtonePicker() {
        val alarm = editAlarm ?: return
        val currentUri = alarm.config.ringtoneUri.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Select alarm sound")
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, currentUri)
        }
        startActivityForResult(intent, REQUEST_RINGTONE_PICKER)
    }
    private fun showStepLimitEditor(alarm: WakeAlarm, error: String? = null) {
        val stepInput = EditText(this).apply {
            hint = "Steps"
            setText(alarm.config.motionSteps.toString())
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_NUMBER
            setTextColor(Color.WHITE)
            setHintTextColor(TEXT_SOFT)
            textSize = 16f
            background = rounded(CARD, 18, BORDER_DARK)
            setPadding(dp(16), dp(15), dp(16), dp(15))
        }
        showStandaloneDark {
            addView(topActionRow("Step challenge", "Back", { showAlarmEditorWith(alarm) }))
            addView(label("Choose how many steps are required to stop this alarm.", 14, TEXT_MUTED))
            addView(space(22))
            addView(formRow("Required steps", stepInput))
            error?.let {
                addView(space(8))
                addView(label(it, 13, DANGER))
            }
            addView(space(14))
            addView(horizontal {
                listOf(10, 20, 50).forEach { steps ->
                    addView(segmentButton("$steps", alarm.config.motionSteps == steps) {
                        showStepLimitEditor(alarm.copy(config = alarm.config.copy(motionSteps = steps)))
                    }, LinearLayout.LayoutParams(0, dp(44), 1f).withMargin(5))
                }
            })
            addView(View(this@MainActivity), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(fullButton("Done", COPPER) {
                val steps = stepInput.text.toString().trim().toIntOrNull()
                if (steps == null || steps !in 1..999) {
                    showStepLimitEditor(alarm, "Enter a step count from 1 to 999.")
                } else {
                    showAlarmEditorWith(alarm.copy(config = alarm.config.copy(motionSteps = steps)))
                }
            })
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
                addView(pickerColumn("hour", hour, 1, 12, onHour), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(label(":", 38, Color.WHITE, bold = true, gravity = Gravity.CENTER), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).withMargin(6, 0, 6, 0))
                addView(pickerColumn("minute", minute, 0, 59, onMinute), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(periodColumn(isPm, onPeriod), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).withMargin(12, 0, 0, 0))
            })
        }
    }

    private fun timerPickerColumn(title: String, picker: NumberPicker): View =
        vertical(Color.TRANSPARENT).apply {
            gravity = Gravity.CENTER
            addView(picker, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(150)))
            addView(label(title.uppercase(), 11, TEXT_SOFT, bold = true, gravity = Gravity.CENTER))
        }
    private fun pickerColumn(suffix: String, value: Int, min: Int, max: Int, onChange: (Int) -> Unit): View =
        vertical(Color.TRANSPARENT).apply {
            gravity = Gravity.CENTER
            addView(numberPicker(value, min, max, onChange), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(132)))
            addView(label(suffix.uppercase(), 11, TEXT_SOFT, bold = true, gravity = Gravity.CENTER))
        }

    private fun numberPicker(value: Int, min: Int, max: Int, onChange: (Int) -> Unit): NumberPicker =
        NumberPicker(this).apply {
            minValue = min
            maxValue = max
            displayedValues = (min..max).map { "%02d".format(it) }.toTypedArray()
            this.value = value.coerceIn(min, max)
            wrapSelectorWheel = true
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            setOnValueChangedListener { _, _, newValue -> onChange(newValue) }
            setPickerTextColor(this)
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
        wifiSignal = -100
        wifiRawSignal = -100
        wifiConnectedSsid = ""
        wifiConnectedBssid = ""
        wifiStatus = "Checking WiFi..."
        prepareAlarmWindow()
        if (triggeredAlarm?.config?.vibrate == true) startAlarmVibration()
        if (triggeredAlarm?.type == AlarmType.MOTION) {
            requestActivityRecognitionPermissionIfNeeded()
            stepCounterBaseline = null
            val stepSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
                ?: sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
            stepSensorActive = stepSensor != null
            stepSensor?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
            }
        }
        if (triggeredAlarm?.type == AlarmType.WIFI) handler.post(wifiTicker)
        if (triggeredAlarm?.type == AlarmType.WIFI) requestWifiLocationPermissionIfNeeded()
        showTriggered()
    }

    private fun showTriggered() {
        val alarm = triggeredAlarm ?: return showAlarmHome()
        val challengeComplete = when (alarm.type) {
            AlarmType.SIMPLE -> true
            AlarmType.WIFI -> wifiChallengeComplete(alarm)
            AlarmType.MOTION -> stepsTaken >= alarm.config.motionSteps
        }
        val canSnooze = challengeComplete && triggeredSnoozeCount < alarm.config.snoozeRepeatCount
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
                        addView(label(wifiPrompt(alarm), 18, Color.WHITE, gravity = Gravity.CENTER))
                        addView(space(10))
                        addView(label(wifiSignalText(), 42, TEAL, bold = true, gravity = Gravity.CENTER))
                        addView(label(wifiSignalDetailText(alarm), 13, TEXT_MUTED, gravity = Gravity.CENTER))
                        addView(wifiStrengthMeter(alarm))
                        addView(progressBar(wifiProgress(alarm), TEAL))
                        addView(label(wifiDistanceText(alarm), 13, TEXT_MUTED, gravity = Gravity.CENTER))
                        addView(label(wifiStatus, 13, TEXT_MUTED, gravity = Gravity.CENTER))
                    }
                    AlarmType.MOTION -> {
                        addView(label(if (stepSensorActive) "Walk to complete the step challenge." else "Step sensor unavailable on this device.", 18, Color.WHITE, gravity = Gravity.CENTER))
                        addView(space(10))
                        addView(label("$stepsTaken / ${alarm.config.motionSteps}", 42, PURPLE, bold = true, gravity = Gravity.CENTER))
                        addView(progressBar((stepsTaken * 100 / alarm.config.motionSteps).coerceIn(0, 100), PURPLE))
                    }
                }
            })
            addView(space(14))
            if (challengeComplete) {
                addView(fullButton("Stop Alarm", COPPER) { stopAlarm() })
                addView(fullButton(if (canSnooze) "Snooze ${alarm.config.snoozeMinutes} Minutes" else "Snooze Limit Reached", CARD, enabled = canSnooze) {
                    snoozeAlarm()
                })
            } else {
                addView(label("Stop and snooze unlock only after the challenge is complete.", 14, TEXT_MUTED, gravity = Gravity.CENTER))
            }
        })
    }

    private fun snoozeAlarm() {
        val alarm = triggeredAlarm ?: return stopAlarm()
        val nextRingAt = System.currentTimeMillis() + alarm.config.snoozeMinutes * 60_000L
        wrappedStore.recordSnooze(alarm.id)
        scheduler.scheduleSnooze(
            alarm = alarm,
            delayMinutes = alarm.config.snoozeMinutes,
            snoozeCount = triggeredSnoozeCount + 1
        )
        Toast.makeText(this, "Snoozed until ${shortClockTime(nextRingAt)}", Toast.LENGTH_LONG).show()
        AlarmSoundService.stop(this)
        stopAlarmAudio()
        stopAlarmVibration()
        AlarmNotifier(this).cancel(alarm.id)
        stopChallengeSensors()
        triggeredAlarm = null
        triggeredSnoozeCount = 0
        dismissTriggeredExperience()
    }

    private fun stopAlarm() {
        val alarm = triggeredAlarm
        if (alarm != null && triggeredSnoozeCount == 0) {
            wrappedStore.recordFirstTryWin()
        }
        if (alarm?.type == AlarmType.MOTION) {
            wrappedStore.recordMotionCompletion(alarm.id, stepsTaken.coerceAtLeast(alarm.config.motionSteps))
        }
        alarm?.let { AlarmNotifier(this).cancel(it.id) }
        AlarmSoundService.stop(this)
        stopAlarmAudio()
        stopAlarmVibration()
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
        stepCounterBaseline = null
        stepSensorActive = false
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
        stopAlarmAudio()
        val alarm = triggeredAlarm
        alarmPlayer = if (alarm?.config?.ringtoneSource == RINGTONE_APP) {
            MediaPlayer.create(this, appRingtoneRes(alarm.config.appRingtone))
        } else {
            val uri = alarm?.config?.ringtoneUri
                ?.takeIf { alarm.config.ringtoneSource == RINGTONE_DEVICE_PICKED && it.isNotBlank() }
                ?.let { Uri.parse(it) }
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            mediaPlayerFromUri(uri) ?: MediaPlayer.create(this, R.raw.wake_gradual_rise)
        }?.apply {
            isLooping = true
            setOnCompletionListener {
                if (triggeredAlarm != null) runCatching { seekTo(0); start() }
            }
            start()
        }
        handler.postDelayed(alarmAudioWatchdog, 2000)
    }

    private fun stopAlarmAudio() {
        handler.removeCallbacks(alarmAudioWatchdog)
        alarmPlayer?.stop()
        alarmPlayer?.release()
        alarmPlayer = null
    }

    private fun mediaPlayerFromUri(uri: Uri?): MediaPlayer? {
        if (uri == null) return null
        return runCatching {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                setDataSource(this@MainActivity, uri)
                prepare()
            }
        }.getOrNull()
    }

    private fun startAlarmVibration() {
        val activeVibrator = alarmVibrator()
        if (!activeVibrator.hasVibrator()) return
        val pattern = longArrayOf(0, 900, 350, 900, 350, 1200)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            activeVibrator.vibrate(VibrationEffect.createWaveform(pattern, 0))
        } else {
            @Suppress("DEPRECATION")
            activeVibrator.vibrate(pattern, 0)
        }
    }

    private fun stopAlarmVibration() {
        alarmVibrator().cancel()
    }

    private fun alarmVibrator(): Vibrator =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            vibrator
        }

    private fun refreshWifiSignal() {
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val info = wifi.connectionInfo
        wifiRawSignal = info?.rssi?.takeIf { it in -99..-1 } ?: -100
        wifiSignal = if (wifiSignal <= -99) wifiRawSignal else ((wifiSignal * 3) + (wifiRawSignal * 2)) / 5
        wifiConnectedSsid = cleanSsid(info?.ssid.orEmpty())
        wifiConnectedBssid = info?.bssid.orEmpty().lowercase(Locale.US)
        wifiStatus = when {
            !wifi.isWifiEnabled -> "WiFi is off."
            wifiConnectedSsid.isBlank() -> "Connect to the saved WiFi/hotspot."
            else -> "Connected to $wifiConnectedSsid. Signal updates twice per second."
        }
    }

    private fun showWifiChallengeEditor(alarm: WakeAlarm) {
        editAlarm = alarm
        val draft = alarm
        requestWifiLocationPermissionIfNeeded()
        refreshWifiSignal()
        showStandaloneDark {
            addView(topActionRow("WiFi Challenge", "Back", { showAlarmEditorWith(draft) }))
            addView(label("Save the hotspot or router while you are standing near the place where the alarm should unlock.", 14, TEXT_MUTED))
            addView(space(22))
            addView(settingsCard {
                addView(clickRow("Saved WiFi", wifiSavedNetworkText(draft)) {
                    val latest = editAlarm ?: draft
                    requestWifiLocationPermissionIfNeeded()
                    refreshWifiSignal()
                    val updatedConfig = latest.config.copy(
                        wifiLocationSet = wifiConnectedSsid.isNotBlank(),
                        wifiSsid = wifiConnectedSsid,
                        wifiBssid = wifiConnectedBssid
                    )
                    showWifiChallengeEditor(latest.copy(config = updatedConfig))
                })
                addView(divider())
                addView(clickRow("Current WiFi", wifiLiveNetworkText()) {
                    requestWifiLocationPermissionIfNeeded()
                    showWifiChallengeEditor(editAlarm ?: draft)
                })
                addView(divider())
                addView(clickRow("Required signal", wifiSensitivityText(draft.config.wifiSensitivity)) {
                    showWifiChallengeEditor(draft.copy(config = draft.config.copy(wifiSensitivity = nextSensitivity(draft.config.wifiSensitivity))))
                })
            })
            addView(space(12))
            addView(label("The alarm unlocks only when this phone is connected to the saved WiFi and the RSSI reaches the selected strength.", 13, TEXT_MUTED))
            addView(View(this@MainActivity), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(fullButton("Done", COPPER) { showAlarmEditorWith(editAlarm ?: draft) })
        }
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

    private fun requestActivityRecognitionPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACTIVITY_RECOGNITION
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACTIVITY_RECOGNITION), REQUEST_ACTIVITY_RECOGNITION)
        }
    }

    private fun requestWifiLocationPermissionIfNeeded(): Boolean {
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), REQUEST_WIFI_LOCATION)
        }
        return granted
    }

    private fun showShell(tab: Tab, content: LinearLayout.() -> Unit) {
        applyDarkChrome()
        handler.removeCallbacks(screenTicker)
        handler.removeCallbacks(stopwatchTicker)
        if (tab == Tab.TIMER && (timerRunning || timerAlertActive)) handler.postDelayed(screenTicker, 1000)
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
        fab(tab)?.let { shell.addView(it) }
        root.replaceWith(shell)
    }

    private fun showStandaloneDark(content: LinearLayout.() -> Unit) {
        applyDarkChrome()
        handler.removeCallbacks(screenTicker)
        handler.removeCallbacks(stopwatchTicker)
        root.replaceWith(vertical(BG, 14).apply {
            setPadding(dp(14), dp(22), dp(14), dp(14))
            content()
        })
    }

    private fun fab(tab: Tab): View? {
        if (tab == Tab.WRAPPED) return null
        return TextView(this).apply {
            text = when (tab) {
                Tab.ALARM -> "+"
                Tab.STOPWATCH -> if (stopwatchRunning) "||" else ">"
                Tab.TIMER -> if (timerRunning) "||" else ">"
                Tab.WRAPPED -> ""
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
                    Tab.WRAPPED -> showWrapped()
                    Tab.STOPWATCH -> if (stopwatchRunning) pauseStopwatch() else startStopwatch()
                    Tab.TIMER -> {
                        if (timerRunning) {
                            timerRemainingMs = (timerEndsAt - System.currentTimeMillis()).coerceAtLeast(0L)
                            timerRunning = false
                        } else if (timerRemainingMs > 0L) {
                            timerInputError = null
                            timerEndsAt = System.currentTimeMillis() + timerRemainingMs
                            timerRunning = true
                        } else {
                            timerInputError = "Enter a timer duration first."
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
                    Tab.WRAPPED -> showWrapped()
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

    private fun metricCard(title: String, value: String, subtitle: String, accent: Int): View =
        card(CARD, 16, 22).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF1A2230.toInt(), accent, 0xFF1A2230.toInt())
            ).apply {
                cornerRadius = dp(22).toFloat()
                setStroke(dp(1), BORDER_DARK)
            }
            addView(label(title, 12, TEXT_SOFT, bold = true))
            addView(space(8))
            addView(label(value, 28, Color.WHITE, bold = true))
            addView(space(4))
            addView(label(subtitle, 12, TEXT_MUTED))
        }

    private fun infoCard(title: String, value: String, subtitle: String): View =
        card(CARD, 16, 22).apply {
            addView(label(title, 12, SAND, bold = true))
            addView(space(8))
            addView(label(value, 24, Color.WHITE, bold = true))
            addView(space(4))
            addView(label(subtitle, 12, TEXT_MUTED))
        }

    private fun averageSnoozeText(stats: WakeWrappedSnapshot): String =
        if (stats.totalRings <= 0) "0.0" else String.format(Locale.getDefault(), "%.1f", stats.totalSnoozes.toFloat() / stats.totalRings.toFloat())

    private fun formatWrappedTime(timestamp: Long): String =
        SimpleDateFormat("EEE, h:mm a", Locale.getDefault()).format(timestamp)

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

    private fun timerNumberInput(value: String, hint: String, enabled: Boolean): EditText =
        EditText(this).apply {
            this.hint = hint
            setText(value)
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_NUMBER
            isEnabled = enabled
            setTextColor(Color.WHITE)
            setHintTextColor(TEXT_SOFT)
            textSize = 16f
            gravity = Gravity.CENTER
            background = rounded(if (enabled) CARD else DARK_CARD, 18, BORDER_DARK)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
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

    private fun switchRow(title: String, checked: Boolean, onChange: ((Boolean) -> Unit)? = null): View =
        horizontal {
            setPadding(dp(16), dp(12), dp(16), dp(12))
            addView(label(title, 15, Color.WHITE), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(Switch(this@MainActivity).apply {
                isChecked = checked
                setOnCheckedChangeListener { _, value -> onChange?.invoke(value) }
            })
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
        handler.removeCallbacks(stopwatchTicker)
        stopwatchRunning = true
        stopwatchStartedAt = System.currentTimeMillis()
        showStopwatch()
    }

    private fun pauseStopwatch() {
        handler.removeCallbacks(stopwatchTicker)
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

    private fun shortClockTime(timestamp: Long): String =
        SimpleDateFormat("h:mm a", Locale.getDefault()).format(timestamp)

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
        AlarmType.MOTION -> "Walk the required steps to disable"
    }

    private fun AlarmType.typeTitle(): String = name.lowercase().replaceFirstChar { it.uppercase() }

    private fun snoozeSummary(alarm: WakeAlarm): String =
        "${alarm.config.snoozeMinutes} minutes, ${alarm.config.snoozeRepeatCount} times"

    private fun ringtoneSummary(alarm: WakeAlarm): String = when (alarm.config.ringtoneSource) {
        RINGTONE_APP -> appRingtoneOptions().firstOrNull { it.id == alarm.config.appRingtone }?.title ?: "Wake melody"
        RINGTONE_DEVICE_PICKED -> deviceRingtoneName(alarm)
        else -> "Device default"
    }

    private fun deviceRingtoneName(alarm: WakeAlarm): String {
        val uri = alarm.config.ringtoneUri.takeIf { it.isNotBlank() }?.let { Uri.parse(it) } ?: return "Choose from phone"
        return RingtoneManager.getRingtone(this, uri)?.getTitle(this) ?: "Device ringtone"
    }

    private fun appRingtoneRes(id: String): Int = when (id) {
        "wake_morning_chime" -> R.raw.wake_morning_chime
        "wake_bright_pulse" -> R.raw.wake_bright_pulse
        "wake_gentle_ascend" -> R.raw.wake_gentle_ascend
        "wake_focus_bells" -> R.raw.wake_focus_bells
        else -> R.raw.wake_gradual_rise
    }

    private fun appRingtoneOptions(): List<AppRingtoneOption> = listOf(
        AppRingtoneOption("wake_gradual_rise", "Gradual Rise", "Gentle ascending melody with a soft fade-in"),
        AppRingtoneOption("wake_morning_chime", "Morning Chime", "Bright consonant tones without a harsh start"),
        AppRingtoneOption("wake_bright_pulse", "Bright Pulse", "Clear repeating pattern for attention"),
        AppRingtoneOption("wake_gentle_ascend", "Gentle Ascend", "Low-to-high contour to reduce abrupt waking"),
        AppRingtoneOption("wake_focus_bells", "Focus Bells", "Distinct bell-like intervals for alertness")
    )
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

    private fun wifiChallengeComplete(alarm: WakeAlarm): Boolean {
        val connectedToSavedNetwork = if (alarm.config.wifiLocationSet) {
            val ssidMatches = alarm.config.wifiSsid.isNotBlank() && wifiConnectedSsid == alarm.config.wifiSsid
            val bssidMatches = alarm.config.wifiBssid.isNotBlank() && wifiConnectedBssid == alarm.config.wifiBssid.lowercase(Locale.US)
            ssidMatches && (alarm.config.wifiBssid.isBlank() || bssidMatches)
        } else {
            wifiConnectedSsid.isNotBlank()
        }
        return connectedToSavedNetwork && wifiSignal >= wifiThreshold(alarm.config.wifiSensitivity)
    }

    private fun wifiPrompt(alarm: WakeAlarm): String = when {
        !alarm.config.wifiLocationSet -> "Set a WiFi hotspot for this alarm."
        wifiConnectedSsid.isBlank() -> "Connect to ${alarm.config.wifiSsid}."
        wifiConnectedSsid != alarm.config.wifiSsid -> "Connect to ${alarm.config.wifiSsid} to unlock."
        alarm.config.wifiBssid.isNotBlank() && wifiConnectedBssid != alarm.config.wifiBssid.lowercase(Locale.US) -> "Move to the saved hotspot."
        wifiSignal < wifiThreshold(alarm.config.wifiSensitivity) -> "Move closer to the saved WiFi."
        else -> "WiFi proximity confirmed."
    }

    private fun wifiSignalText(): String =
        if (wifiConnectedSsid.isBlank() || wifiRawSignal <= -99) "-- dBm" else "$wifiSignal dBm avg"

    private fun wifiSignalDetailText(alarm: WakeAlarm): String =
        if (wifiConnectedSsid.isBlank() || wifiRawSignal <= -99) {
            "Waiting for a WiFi signal"
        } else {
            "Live $wifiRawSignal dBm - Target ${wifiThreshold(alarm.config.wifiSensitivity)} dBm"
        }

    private fun wifiProgress(alarm: WakeAlarm): Int {
        val threshold = wifiThreshold(alarm.config.wifiSensitivity)
        val floor = -90
        return (((wifiSignal - floor).toFloat() / (threshold - floor).toFloat()) * 100).toInt().coerceIn(0, 100)
    }

    private fun wifiDistanceText(alarm: WakeAlarm): String {
        if (wifiConnectedSsid.isBlank() || wifiRawSignal <= -99) return "Target: ${wifiThreshold(alarm.config.wifiSensitivity)} dBm or stronger"
        val threshold = wifiThreshold(alarm.config.wifiSensitivity)
        val remaining = threshold - wifiSignal
        return when {
            remaining <= 0 -> "Unlocked - signal is strong enough"
            remaining <= 3 -> "Almost there - move a little closer"
            remaining <= 8 -> "$remaining dB away - keep moving closer"
            else -> "$remaining dB away - get closer to the hotspot"
        }
    }

    private fun wifiStrengthMeter(alarm: WakeAlarm): View =
        horizontal {
            gravity = Gravity.CENTER
            val activeBars = ((wifiProgress(alarm) + 19) / 20).coerceIn(0, 5)
            repeat(5) { index ->
                addView(View(this@MainActivity).apply {
                    background = rounded(if (index < activeBars) TEAL else 0x33FFFFFF, 5)
                }, LinearLayout.LayoutParams(dp(18), dp(7 + (index * 5))).withMargin(4, 10, 4, 0))
            }
        }

    private fun wifiChallengeSummary(alarm: WakeAlarm): String =
        "${wifiSavedNetworkText(alarm)} - ${wifiSensitivityText(alarm.config.wifiSensitivity)}"

    private fun wifiSavedNetworkText(alarm: WakeAlarm): String =
        if (alarm.config.wifiLocationSet && alarm.config.wifiSsid.isNotBlank()) alarm.config.wifiSsid else "Tap to save current WiFi"

    private fun wifiLiveNetworkText(): String =
        if (wifiConnectedSsid.isNotBlank()) "$wifiConnectedSsid (${wifiSignalText()})" else "Not connected or permission needed"

    private fun wifiSensitivityText(sensitivity: Sensitivity): String =
        "${sensitivity.name.lowercase().replaceFirstChar { it.uppercase() }} (${wifiThreshold(sensitivity)} dBm)"

    private fun cleanSsid(raw: String): String =
        raw.trim().removePrefix("\"").removeSuffix("\"").takeUnless { it == "<unknown ssid>" }.orEmpty()

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

    private fun setPickerTextColor(picker: NumberPicker) {
        for (index in 0 until picker.childCount) {
            val child = picker.getChildAt(index)
            if (child is EditText) {
                child.setTextColor(Color.WHITE)
                child.textSize = 24f
                child.gravity = Gravity.CENTER
            }
        }
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
        WRAPPED("Wrapped", "WR"),
        STOPWATCH("Stopwatch", "SW"),
        TIMER("Timer", "TM")
    }

    private enum class EditorSource {
        HOME,
        TYPE_PICKER
    }

    private enum class ClockFaceMode { CLOCK, STOPWATCH }

    private data class AppRingtoneOption(val id: String, val title: String, val subtitle: String)

    private class ClockFaceView(
        context: Context,
        private val mode: ClockFaceMode,
        private var elapsedMs: Long = 0L
    ) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        fun setElapsed(value: Long) {
            elapsedMs = value
            invalidate()
        }

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
        private const val REQUEST_ACTIVITY_RECOGNITION = 1002
        private const val REQUEST_RINGTONE_PICKER = 1003
        private const val REQUEST_WIFI_LOCATION = 1004
        private const val RINGTONE_DEVICE_DEFAULT = "DEVICE_DEFAULT"
        private const val RINGTONE_DEVICE_PICKED = "DEVICE_PICKED"
        private const val RINGTONE_APP = "APP"
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
