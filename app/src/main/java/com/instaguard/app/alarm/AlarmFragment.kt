package com.instaguard.app.alarm

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import android.widget.ToggleButton
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.instaguard.app.R
import com.instaguard.app.databinding.DialogEditAlarmBinding
import com.instaguard.app.databinding.FragmentAlarmBinding
import com.instaguard.app.databinding.ItemSmartAlarmBinding
import com.instaguard.app.service.AlarmRingingService
import java.util.Calendar

/**
 * Tab 3: Alarm Fragment (v13.0)
 * Hardcore Smart Alarm Manager with:
 * - 12+ Mathematical Sound Synthesizers
 * - Repeat Days & Delete After Ringing toggle
 * - Snooze & Pro Wake-Up Dismiss Missions (Math / Rapid Tap)
 * - Live Instant Testing
 */
class AlarmFragment : Fragment() {

    private var _binding: FragmentAlarmBinding? = null
    private val binding get() = _binding!!

    private var alarmsAdapter: SmartAlarmAdapter? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAlarmBinding.inflate(inflater, container, false)
        return binding.root
    }

    private val alarmListChangedReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshAlarmsUI()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        try {
            val filter = android.content.IntentFilter("com.instaguard.app.ACTION_ALARM_LIST_CHANGED")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requireContext().registerReceiver(alarmListChangedReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                requireContext().registerReceiver(alarmListChangedReceiver, filter)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        setupRecyclerView()
        setupHeroButtons()
        refreshAlarmsUI()
    }

    override fun onResume() {
        super.onResume()
        refreshAlarmsUI()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        try {
            requireContext().unregisterReceiver(alarmListChangedReceiver)
        } catch (e: Exception) {
            // Ignored
        }
        HardAlarmSoundEngine.stopPlaying(context)
        _binding = null
    }

    private fun setupRecyclerView() {
        binding.rvAlarms.layoutManager = LinearLayoutManager(requireContext())
        alarmsAdapter = SmartAlarmAdapter(
            onToggleEnabled = { alarm, isChecked ->
                alarm.enabled = isChecked
                AlarmManagerHelper.saveAlarm(requireContext(), alarm)
                updateHeroCard()
            },
            onTest = { alarm ->
                testAlarmDirectly(alarm)
            },
            onEdit = { alarm ->
                showEditAlarmDialog(alarm)
            },
            onDelete = { alarm ->
                confirmDeleteAlarm(alarm)
            }
        )
        binding.rvAlarms.adapter = alarmsAdapter
    }

    private fun setupHeroButtons() {
        binding.btnCreateAlarm.setOnClickListener {
            showEditAlarmDialog(null)
        }

        binding.btnTestEverythingNow.setOnClickListener {
            val alarms = AlarmManagerHelper.loadAlarms(requireContext())
            val targetAlarm = alarms.firstOrNull() ?: SmartAlarm(
                label = "Hardcore Wake Up Test",
                soundId = 2,
                snoozeMinutes = 0,
                dismissMissionType = 1
            )
            testAlarmDirectly(targetAlarm)
        }
    }

    fun refreshAlarmsUI() {
        val ctx = context ?: return
        if (_binding == null) return

        val alarms = AlarmManagerHelper.loadAlarms(ctx)
        alarmsAdapter?.submitList(alarms)

        if (alarms.isEmpty()) {
            binding.layoutEmptyAlarms.visibility = View.VISIBLE
            binding.rvAlarms.visibility = View.GONE
        } else {
            binding.layoutEmptyAlarms.visibility = View.GONE
            binding.rvAlarms.visibility = View.VISIBLE
        }

        updateHeroCard()
    }

    private fun updateHeroCard() {
        val ctx = context ?: return
        if (_binding == null) return

        val alarms = AlarmManagerHelper.loadAlarms(ctx)
        val enabledAlarms = alarms.filter { it.enabled }
        if (enabledAlarms.isEmpty()) {
            binding.tvHeroAlarmStatus.text = "No active alarms scheduled"
            binding.tvHeroAlarmStatus.setTextColor(Color.parseColor("#8B949E"))
        } else {
            // Find alarm with nearest trigger time
            val nearest = enabledAlarms.minByOrNull { AlarmManagerHelper.calculateNextTriggerMs(it) }
            val countStr = if (enabledAlarms.size == 1) "1 Active Alarm" else "${enabledAlarms.size} Active Alarms"
            val nearestCountdown = nearest?.let { AlarmManagerHelper.formatNextRingCountdown(it) } ?: ""
            binding.tvHeroAlarmStatus.text = "$countStr • $nearestCountdown"
            binding.tvHeroAlarmStatus.setTextColor(Color.parseColor("#00E5FF"))
        }
    }

    private fun testAlarmDirectly(alarm: SmartAlarm) {
        val ctx = requireContext()
        if (ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.CAMERA), 102)
        }
        AlarmRingingService.startAlarm(
            context = ctx,
            alarmId = alarm.id,
            isTest = true,
            isSnooze = false,
            deleteAfter = alarm.deleteAfterRinging
        )
        Toast.makeText(ctx, "⚡ Launching Live Alarm Test...", Toast.LENGTH_SHORT).show()
    }

    private fun confirmDeleteAlarm(alarm: SmartAlarm) {
        AlertDialog.Builder(requireContext())
            .setTitle("Delete Alarm?")
            .setMessage("Are you sure you want to delete '${alarm.label}' (${AlarmManagerHelper.formatTimeAmPm(alarm.hour, alarm.minute)})?")
            .setPositiveButton("Delete") { dialog, _ ->
                AlarmManagerHelper.deleteAlarm(requireContext(), alarm.id)
                refreshAlarmsUI()
                Toast.makeText(requireContext(), "🗑️ Alarm deleted", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showEditAlarmDialog(existingAlarm: SmartAlarm?) {
        val ctx = requireContext()
        val dialogBinding = DialogEditAlarmBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(ctx)
            .setView(dialogBinding.root)
            .setCancelable(false)
            .create()

        val isEditing = existingAlarm != null
        val alarm = if (isEditing) {
            existingAlarm!!.copy()
        } else {
            val cal = Calendar.getInstance()
            SmartAlarm(
                hour = cal.get(Calendar.HOUR_OF_DAY),
                minute = cal.get(Calendar.MINUTE),
                soundId = 2,
                snoozeMinutes = 0,
                dismissMissionType = 1
            )
        }

        dialogBinding.tvDialogTitle.text = if (isEditing) "✏️ Edit Hard Alarm" else "⏰ Create New Hard Alarm"

        // 1. TimePicker: Force 12-hour AM/PM view
        dialogBinding.timePickerAlarm.setIs24HourView(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            dialogBinding.timePickerAlarm.hour = alarm.hour
            dialogBinding.timePickerAlarm.minute = alarm.minute
        } else {
            @Suppress("DEPRECATION")
            dialogBinding.timePickerAlarm.currentHour = alarm.hour
            @Suppress("DEPRECATION")
            dialogBinding.timePickerAlarm.currentMinute = alarm.minute
        }

        // 2. Label
        dialogBinding.etAlarmLabel.setText(alarm.label)

        // 3. Repeat Days Chips
        val dayToggles = listOf(
            dialogBinding.toggleMon to 1,
            dialogBinding.toggleTue to 2,
            dialogBinding.toggleWed to 3,
            dialogBinding.toggleThu to 4,
            dialogBinding.toggleFri to 5,
            dialogBinding.toggleSat to 6,
            dialogBinding.toggleSun to 7
        )

        fun updateDayToggleButtons(activeDays: Set<Int>) {
            for ((toggle, dayNum) in dayToggles) {
                toggle.isChecked = activeDays.contains(dayNum)
                toggle.setTextColor(if (toggle.isChecked) Color.parseColor("#00E5FF") else Color.parseColor("#8B949E"))
            }
        }

        updateDayToggleButtons(alarm.repeatDays)

        for ((toggle, dayNum) in dayToggles) {
            toggle.setOnCheckedChangeListener { _, isChecked ->
                val newDays = alarm.repeatDays.toMutableSet()
                if (isChecked) newDays.add(dayNum) else newDays.remove(dayNum)
                alarm.repeatDays = newDays
                toggle.setTextColor(if (isChecked) Color.parseColor("#00E5FF") else Color.parseColor("#8B949E"))
            }
        }

        dialogBinding.btnQuickOneTime.setOnClickListener {
            alarm.repeatDays = emptySet()
            updateDayToggleButtons(alarm.repeatDays)
        }
        dialogBinding.btnQuickWeekdays.setOnClickListener {
            alarm.repeatDays = setOf(1, 2, 3, 4, 5)
            updateDayToggleButtons(alarm.repeatDays)
        }
        dialogBinding.btnQuickEveryday.setOnClickListener {
            alarm.repeatDays = setOf(1, 2, 3, 4, 5, 6, 7)
            updateDayToggleButtons(alarm.repeatDays)
        }

        // 4. Delete After Ringing Switch
        dialogBinding.switchDeleteAfterRinging.isChecked = alarm.deleteAfterRinging

        // 5. Sound Selector Spinner
        val soundTitles = HardAlarmSound.entries.map { it.title }
        val soundAdapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, soundTitles)
        dialogBinding.spinnerAlarmSound.adapter = soundAdapter
        dialogBinding.spinnerAlarmSound.setSelection(alarm.soundId.coerceIn(0, soundTitles.size - 1))

        var isPreviewing = false
        dialogBinding.btnPreviewSound.setOnClickListener {
            val selectedSoundId = dialogBinding.spinnerAlarmSound.selectedItemPosition
            if (!isPreviewing) {
                HardAlarmSoundEngine.previewSound(ctx, selectedSoundId, 4000L)
                dialogBinding.btnPreviewSound.text = "⏹️ Stop"
                isPreviewing = true
                dialogBinding.btnPreviewSound.postDelayed({
                    if (dialogBinding.btnPreviewSound.isAttachedToWindow) {
                        dialogBinding.btnPreviewSound.text = "▶️ Preview"
                        isPreviewing = false
                    }
                }, 4000L)
            } else {
                HardAlarmSoundEngine.stopPlaying(ctx)
                dialogBinding.btnPreviewSound.text = "▶️ Preview"
                isPreviewing = false
            }
        }

        // 6. Snooze Spinner
        val snoozeOptions = listOf(
            0 to "Off (No Snooze)",
            2 to "2 Minutes",
            5 to "5 Minutes",
            10 to "10 Minutes",
            15 to "15 Minutes",
            30 to "30 Minutes"
        )
        val snoozeAdapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, snoozeOptions.map { it.second })
        dialogBinding.spinnerSnooze.adapter = snoozeAdapter
        val initialSnoozeIndex = snoozeOptions.indexOfFirst { it.first == alarm.snoozeMinutes }.let { if (it >= 0) it else 0 }
        dialogBinding.spinnerSnooze.setSelection(initialSnoozeIndex)

        // 7. Mission Spinner
        val missionOptions = listOf(
            0 to "Standard Tap Dismiss",
            1 to "🧠 5 Hard Math Questions (Random)",
            2 to "⚡ Rapid 15-Tap Wake Check"
        )
        val missionAdapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, missionOptions.map { it.second })
        dialogBinding.spinnerMission.adapter = missionAdapter
        dialogBinding.spinnerMission.setSelection(alarm.dismissMissionType.coerceIn(0, 2))

        // 7.1 Camera Scan Target Item Spinner
        val scanTargetOptions = listOf(-1 to "🎲 Random Item (Laptop, Mouse, Mug, etc.)") +
            AlarmScanItem.entries.map { it.id to it.displayName }
        val scanTargetAdapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, scanTargetOptions.map { it.second })
        dialogBinding.spinnerScanTargetItem.adapter = scanTargetAdapter
        val initialScanTargetIndex = scanTargetOptions.indexOfFirst { it.first == alarm.scanTargetItemId }.let { if (it >= 0) it else 0 }
        dialogBinding.spinnerScanTargetItem.setSelection(initialScanTargetIndex)

        // 8. Switches
        dialogBinding.switchForceMaxVolume.isChecked = alarm.forceMaxVolume
        dialogBinding.switchHeavyVibration.isChecked = alarm.heavyVibration
        dialogBinding.switchAiVoice.isChecked = alarm.aiVoiceAnnouncement

        // 9. Buttons
        dialogBinding.btnCancelDialog.setOnClickListener {
            HardAlarmSoundEngine.stopPlaying(ctx)
            dialog.dismiss()
        }

        dialogBinding.btnTestCurrentSettings.setOnClickListener {
            HardAlarmSoundEngine.stopPlaying(ctx)
            val testAlarm = alarm.copy(
                hour = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) dialogBinding.timePickerAlarm.hour else dialogBinding.timePickerAlarm.currentHour,
                minute = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) dialogBinding.timePickerAlarm.minute else dialogBinding.timePickerAlarm.currentMinute,
                label = dialogBinding.etAlarmLabel.text.toString().trim().ifBlank { "Test Wake-Up Mission" },
                deleteAfterRinging = dialogBinding.switchDeleteAfterRinging.isChecked,
                soundId = dialogBinding.spinnerAlarmSound.selectedItemPosition,
                snoozeMinutes = snoozeOptions[dialogBinding.spinnerSnooze.selectedItemPosition].first,
                dismissMissionType = missionOptions[dialogBinding.spinnerMission.selectedItemPosition].first,
                forceMaxVolume = dialogBinding.switchForceMaxVolume.isChecked,
                heavyVibration = dialogBinding.switchHeavyVibration.isChecked,
                aiVoiceAnnouncement = dialogBinding.switchAiVoice.isChecked,
                scanTargetItemId = scanTargetOptions[dialogBinding.spinnerScanTargetItem.selectedItemPosition].first
            )
            testAlarmDirectly(testAlarm)
        }

        dialogBinding.btnSaveAlarm.setOnClickListener {
            HardAlarmSoundEngine.stopPlaying(ctx)

            val chosenHour = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) dialogBinding.timePickerAlarm.hour else dialogBinding.timePickerAlarm.currentHour
            val chosenMinute = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) dialogBinding.timePickerAlarm.minute else dialogBinding.timePickerAlarm.currentMinute

            alarm.hour = chosenHour
            alarm.minute = chosenMinute
            alarm.label = dialogBinding.etAlarmLabel.text.toString().trim().ifBlank { "Wake Up Mission" }
            alarm.deleteAfterRinging = dialogBinding.switchDeleteAfterRinging.isChecked
            alarm.soundId = dialogBinding.spinnerAlarmSound.selectedItemPosition
            alarm.snoozeMinutes = snoozeOptions[dialogBinding.spinnerSnooze.selectedItemPosition].first
            alarm.dismissMissionType = missionOptions[dialogBinding.spinnerMission.selectedItemPosition].first
            alarm.forceMaxVolume = dialogBinding.switchForceMaxVolume.isChecked
            alarm.heavyVibration = dialogBinding.switchHeavyVibration.isChecked
            alarm.aiVoiceAnnouncement = dialogBinding.switchAiVoice.isChecked
            alarm.scanTargetItemId = scanTargetOptions[dialogBinding.spinnerScanTargetItem.selectedItemPosition].first
            alarm.enabled = true

            AlarmManagerHelper.saveAlarm(ctx, alarm)
            refreshAlarmsUI()

            val countdown = AlarmManagerHelper.formatNextRingCountdown(alarm)
            Toast.makeText(ctx, "✅ Alarm saved! $countdown", Toast.LENGTH_LONG).show()
            dialog.dismiss()
        }

        dialog.show()
    }

    /**
     * RecyclerView Adapter for displaying Smart Alarms.
     */
    private inner class SmartAlarmAdapter(
        private val onToggleEnabled: (SmartAlarm, Boolean) -> Unit,
        private val onTest: (SmartAlarm) -> Unit,
        private val onEdit: (SmartAlarm) -> Unit,
        private val onDelete: (SmartAlarm) -> Unit
    ) : RecyclerView.Adapter<SmartAlarmAdapter.AlarmViewHolder>() {

        private val items = mutableListOf<SmartAlarm>()

        fun submitList(newItems: List<SmartAlarm>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AlarmViewHolder {
            val itemBinding = ItemSmartAlarmBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return AlarmViewHolder(itemBinding)
        }

        override fun onBindViewHolder(holder: AlarmViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        inner class AlarmViewHolder(private val b: ItemSmartAlarmBinding) : RecyclerView.ViewHolder(b.root) {
            fun bind(alarm: SmartAlarm) {
                b.tvAlarmTime.text = AlarmManagerHelper.formatTimeAmPm(alarm.hour, alarm.minute)
                b.tvAlarmLabel.text = "🏷️ " + alarm.label
                b.tvAlarmSound.text = HardAlarmSound.fromId(alarm.soundId).title

                b.tvCountdownBadge.text = AlarmManagerHelper.formatNextRingCountdown(alarm)
                if (alarm.enabled) {
                    b.tvCountdownBadge.setTextColor(Color.parseColor("#00E5FF"))
                    b.tvAlarmTime.alpha = 1.0f
                } else {
                    b.tvCountdownBadge.text = "Disabled"
                    b.tvCountdownBadge.setTextColor(Color.parseColor("#8B949E"))
                    b.tvAlarmTime.alpha = 0.5f
                }

                // Master Switch
                b.switchAlarmEnabled.setOnCheckedChangeListener(null)
                b.switchAlarmEnabled.isChecked = alarm.enabled
                b.switchAlarmEnabled.setOnCheckedChangeListener { _, isChecked ->
                    onToggleEnabled(alarm, isChecked)
                    bind(alarm)
                }

                // Days pills
                val dayViews = listOf(
                    b.pillMon to 1,
                    b.pillTue to 2,
                    b.pillWed to 3,
                    b.pillThu to 4,
                    b.pillFri to 5,
                    b.pillSat to 6,
                    b.pillSun to 7
                )
                for ((view, dayNum) in dayViews) {
                    val isActive = alarm.repeatDays.contains(dayNum)
                    view.alpha = if (isActive) 1.0f else 0.25f
                    view.setTextColor(if (isActive) Color.parseColor("#00E5FF") else Color.parseColor("#8B949E"))
                }

                // Badges
                b.tvBadgeAutoDelete.visibility = if (alarm.deleteAfterRinging) View.VISIBLE else View.GONE
                b.tvBadgeSnooze.text = if (alarm.snoozeMinutes > 0) "💤 ${alarm.snoozeMinutes}m" else "💤 Off"
                b.tvBadgeMission.text = when (alarm.dismissMissionType) {
                    1 -> "🧠 Math"
                    2 -> "⚡ 15-Tap"
                    else -> "Standard"
                }
                b.tvBadgeVolume.visibility = if (alarm.forceMaxVolume) View.VISIBLE else View.GONE
                b.tvBadgeVoice.visibility = if (alarm.aiVoiceAnnouncement) View.VISIBLE else View.GONE

                // Action buttons
                b.btnTestAlarm.setOnClickListener { onTest(alarm) }
                b.btnEditAlarm.setOnClickListener { onEdit(alarm) }
                b.btnDeleteAlarm.setOnClickListener { onDelete(alarm) }
            }
        }
    }
}
