package com.opendroid.ai.actions

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.provider.ContactsContract
import android.provider.Settings
import android.util.Log
import com.opendroid.ai.accessibility.OpenDroidAccessibilityService
import com.opendroid.ai.actions.base.Action
import com.opendroid.ai.actions.base.ActionResult
import com.opendroid.ai.core.agent.AgentLoop
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SystemActions @Inject constructor(
    private val agentLoop: dagger.Lazy<AgentLoop>,
    private val visionEngine: dagger.Lazy<com.opendroid.ai.core.agent.VisionEngine>
) {

    fun getActions(): List<Action> = listOf(
        ToggleWifiAction(),
        ToggleFlashlightAction(),
        SetVolumeAction(),
        SetBrightnessAction(),
        OpenAppAction(),
        LockScreenAction(),
        RestartDeviceAction(),
        ToggleBluetoothAction(),
        ToggleDndAction(),
        TakeScreenshotAction(),
        ConfirmAction(agentLoop),
        CheckAppAction(),
        ShowWarningAction(agentLoop),
        ToggleMobileDataAction(),
        ToggleHotspotAction(),
        SetWallpaperAction(),
        RecordScreenAction(),
        InstallAppAction(),
        CheckHardwareAction(),
        CheckPermissionAction(),
        VerifyContactAction(),
        PromptUserSelectionAction(agentLoop),
        GetSystemInfoAction(),
        SetRingerModeAction(),
        AskUserAction(agentLoop),
        AnalyzeScreenshotAction(visionEngine)
    )

    private class ToggleWifiAction : Action {
        override val name: String = "TOGGLE_WIFI"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val on = params["on"]?.toBoolean() ?: true
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            return try {
                @Suppress("DEPRECATION")
                wifiManager.isWifiEnabled = on
                val stateWord = if (on) "on" else "off"
                ActionResult(true, "WiFi's $stateWord now!", null)
            } catch (e: Exception) {
                // Fallback: Launch wifi settings panel so user can toggle manually
                val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                ActionResult(false, "Couldn't toggle WiFi directly, but I opened the settings for you.", e.localizedMessage, true)
            }
        }
    }

    private class ToggleFlashlightAction : Action {
        companion object {
            @Volatile
            var isFlashlightOn: Boolean = false
            private var callbackRegistered: Boolean = false
        }

        override val name: String = "TOGGLE_FLASHLIGHT"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            // Read "state" param — default to "toggle" if missing
            val requestedState = (params["state"] ?: params["on"] ?: "toggle")
                .lowercase().trim()

            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

            // Register torch callback to track actual state (once)
            if (!callbackRegistered) {
                try {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                        cameraManager.registerTorchCallback(object : CameraManager.TorchCallback() {
                            override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                                isFlashlightOn = enabled
                            }
                        }, null)
                        callbackRegistered = true
                    }
                } catch (_: Exception) {}
            }

            return try {
                var foundCameraId: String? = null
                for (id in cameraManager.cameraIdList) {
                    val characteristics = cameraManager.getCameraCharacteristics(id)
                    val hasFlash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
                    if (hasFlash) {
                        foundCameraId = id
                        break
                    }
                }
                val cameraId = foundCameraId ?: cameraManager.cameraIdList.firstOrNull()
                if (cameraId != null) {
                    // Determine target state — NEVER return NeedsInput
                    val targetOn = when (requestedState) {
                        "on", "true"   -> true
                        "off", "false" -> false
                        "toggle"       -> !isFlashlightOn
                        else           -> !isFlashlightOn  // unknown = toggle
                    }
                    cameraManager.setTorchMode(cameraId, targetOn)
                    isFlashlightOn = targetOn
                    val stateWord = if (targetOn) "on" else "off"
                    ActionResult(true, "Flashlight's $stateWord!", null)
                } else {
                    ActionResult(false, null, "No camera with flashlight support was found.")
                }
            } catch (e: Exception) {
                Log.e("Flashlight", "Toggle failed: ${e.localizedMessage}")
                ActionResult(false, null, "Couldn't toggle the flashlight.")
            }
        }
    }

    private class SetVolumeAction : Action {
        override val name: String = "SET_VOLUME"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val typeStr = params["type"] ?: "media"
            val level = params["level"]?.toIntOrNull()?.coerceIn(0, 100) ?: 50
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val streamType = when (typeStr.lowercase().trim()) {
                "ring", "ringtone", "ringer" -> AudioManager.STREAM_RING
                "alarm" -> AudioManager.STREAM_ALARM
                "notification", "notif" -> AudioManager.STREAM_NOTIFICATION
                "system" -> AudioManager.STREAM_SYSTEM
                else -> AudioManager.STREAM_MUSIC
            }
            val streamLabel = when (streamType) {
                AudioManager.STREAM_RING -> "Ringtone"
                AudioManager.STREAM_ALARM -> "Alarm"
                AudioManager.STREAM_NOTIFICATION -> "Notification"
                AudioManager.STREAM_SYSTEM -> "System"
                else -> "Media"
            }
            return try {
                val maxVolume = audioManager.getStreamMaxVolume(streamType)
                val targetVolume = (level * maxVolume) / 100
                audioManager.setStreamVolume(streamType, targetVolume, AudioManager.FLAG_SHOW_UI)
                ActionResult(true, "$streamLabel volume set to $level%.", null)
            } catch (e: SecurityException) {
                Log.e("SetVolume", "Volume permission denied: ${e.localizedMessage}")
                ActionResult(false, null, "I don't have permission to change the $streamLabel volume. Please check your Do Not Disturb settings.")
            } catch (e: Exception) {
                Log.e("SetVolume", "Volume failed: ${e.localizedMessage}")
                ActionResult(false, null, "Couldn't change the volume right now. Please try again.")
            }
        }
    }

    private class SetBrightnessAction : Action {
        override val name: String = "SET_BRIGHTNESS"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val level = params["level"]?.toIntOrNull()?.coerceIn(0, 100) ?: 50
            val targetVal = (level * 255) / 100
            return try {
                if (Settings.System.canWrite(context)) {
                    // Disable auto-brightness so manual level takes effect
                    Settings.System.putInt(
                        context.contentResolver,
                        Settings.System.SCREEN_BRIGHTNESS_MODE,
                        Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                    )
                    Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, targetVal)
                    ActionResult(true, "Done! Brightness is set to $level%.", null)
                } else {
                    val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                        data = Uri.parse("package:${context.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    ActionResult(false, "I need your permission to change system settings. I've opened the settings page — just flip the switch to allow OpenDroid, then try again!", null, true)
                }
            } catch (e: Exception) {
                Log.e("SetBrightness", "Brightness failed: ${e.localizedMessage}")
                ActionResult(false, null, "Couldn't change the brightness right now. Please try again.")
            }
        }
    }

    private class OpenAppAction : Action {
        override val name: String = "OPEN_APP"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val appName = params["appName"] ?: return ActionResult(false, null, "appName parameter missing")
            val pm = context.packageManager
            val packages = pm.getInstalledApplications(0)
            val appPackage = packages.find {
                val label = pm.getApplicationLabel(it).toString()
                label.contains(appName, ignoreCase = true) || it.packageName.contains(appName, ignoreCase = true)
            }?.packageName
            return if (appPackage != null) {
                val intent = pm.getLaunchIntentForPackage(appPackage)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    ActionResult(true, "$appName is open!", null)
                } else {
                    ActionResult(false, null, "Launcher intent not found for $appPackage")
                }
            } else {
                ActionResult(false, null, "App '$appName' not installed.")
            }
        }
    }

    private class LockScreenAction : Action {
        override val name: String = "LOCK_SCREEN"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val service = OpenDroidAccessibilityService.getInstance()
            return if (service != null) {
                val success = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
                ActionResult(success, if (success) "Device locked" else "Failed to lock", null)
            } else {
                ActionResult(false, null, "Accessibility Service is not running or active.")
            }
        }
    }

    private class RestartDeviceAction : Action {
        override val name: String = "RESTART_DEVICE"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val service = OpenDroidAccessibilityService.getInstance()
            return if (service != null) {
                val success = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG)
                ActionResult(success, if (success) "Power dialog opened. Action pending user touch." else "Failed to open dialog", null)
            } else {
                ActionResult(false, null, "Accessibility Service not running to trigger power dialog.")
            }
        }
    }

    private class ToggleBluetoothAction : Action {
        override val name: String = "TOGGLE_BLUETOOTH"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val on = params["on"]?.toBoolean() ?: true
            return try {
                val adapter = BluetoothAdapter.getDefaultAdapter()
                @Suppress("DEPRECATION")
                if (on) adapter.enable() else adapter.disable()
                val stateWord = if (on) "on" else "off"
                ActionResult(true, "Bluetooth is $stateWord now!", null)
            } catch (e: Exception) {
                val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                ActionResult(false, "Couldn't toggle Bluetooth directly, but I opened the settings for you.", e.localizedMessage, true)
            }
        }
    }

    private class ToggleDndAction : Action {
        override val name: String = "TOGGLE_DND"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val on = params["on"]?.toBoolean() ?: true
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            return try {
                if (notificationManager.isNotificationPolicyAccessGranted) {
                    val filter = if (on) NotificationManager.INTERRUPTION_FILTER_NONE else NotificationManager.INTERRUPTION_FILTER_ALL
                    notificationManager.setInterruptionFilter(filter)
                    val stateWord = if (on) "on" else "off"
                    ActionResult(true, "Do Not Disturb is $stateWord.", null)
                } else {
                    val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    ActionResult(false, "DND policy permission not granted. Prompted user.", "Permission required", true)
                }
            } catch (e: Exception) {
                Log.e("ToggleDND", "DND failed: ${e.localizedMessage}")
                ActionResult(false, null, "Couldn't change Do Not Disturb.")
            }
        }
    }

    private class TakeScreenshotAction : Action {
        override val name: String = "TAKE_SCREENSHOT"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val service = OpenDroidAccessibilityService.getInstance()
            return if (service != null) {
                val success = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
                ActionResult(success, if (success) "Screenshot taken!" else "Couldn't capture screenshot.", null)
            } else {
                ActionResult(false, null, "Accessibility Service is not running to trigger screenshot.")
            }
        }
    }

    private class ConfirmAction(
        private val agentLoop: dagger.Lazy<AgentLoop>
    ) : Action {
        override val name: String = "CONFIRM_ACTION"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val message = params["message"] ?: "Do you want to proceed with this action?"
            
            // Speak the confirmation warning
            agentLoop.get().onSpeakCallback?.invoke(message)
            
            // Display Toast on main thread
            try {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                // Ignore toast errors if any
            }
            
            val response = agentLoop.get().awaitUserResponse().trim().lowercase()
            
            val positiveWords = listOf("yes", "yep", "sure", "ok", "okay", "confirm", "proceed", "do it", "yeah", "agree")
            val negativeWords = listOf("no", "nope", "cancel", "stop", "dont", "don't", "deny", "abort")
            
            val isPositive = positiveWords.any { response.contains(it) }
            val isNegative = negativeWords.any { response.contains(it) }
            
            return if (isPositive && !isNegative) {
                ActionResult(true, "User confirmed: $response", null)
            } else {
                ActionResult(false, null, "Action cancelled by user response: $response")
            }
        }
    }

    private class CheckAppAction : Action {
        override val name: String = "CHECK_APP"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val appName = params["appName"] ?: return ActionResult(false, null, "appName parameter missing")
            val pm = context.packageManager
            val packages = pm.getInstalledApplications(0)
            val appPackage = packages.find {
                val label = pm.getApplicationLabel(it).toString()
                label.contains(appName, ignoreCase = true) || it.packageName.contains(appName, ignoreCase = true)
            }?.packageName
            return if (appPackage != null) {
                ActionResult(true, "App '$appName' ($appPackage) is installed and functional.", null)
            } else {
                ActionResult(false, null, "App '$appName' is not installed.")
            }
        }
    }

    private class ShowWarningAction(
        private val agentLoop: dagger.Lazy<AgentLoop>
    ) : Action {
        override val name: String = "SHOW_WARNING"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val message = params["message"] ?: "Warning: Please check your actions."
            
            // Speak the warning
            agentLoop.get().onSpeakCallback?.invoke(message)
            
            // Display Toast on main thread
            try {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                // Ignore toast errors if any
            }
            
            return ActionResult(true, "Warning shown: $message", null)
        }
    }

    private class ToggleMobileDataAction : Action {
        override val name: String = "TOGGLE_MOBILE_DATA"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val on = params["on"]?.toBoolean() ?: true
            return try {
                val intent = Intent(Settings.ACTION_WIRELESS_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                val stateWord = if (on) "on" else "off"
                ActionResult(true, "Opened mobile data settings to turn it $stateWord.", null)
            } catch (e: Exception) {
                Log.e("MobileData", "Settings failed: ${e.localizedMessage}")
                ActionResult(false, null, "Couldn't open network settings.")
            }
        }
    }

    private class ToggleHotspotAction : Action {
        override val name: String = "TOGGLE_HOTSPOT"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val on = params["on"]?.toBoolean() ?: true
            return try {
                val intent = Intent().apply {
                    action = "android.settings.TETHER_SETTINGS"
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                val stateWord = if (on) "on" else "off"
                ActionResult(true, "Opened hotspot settings to turn it $stateWord.", null)
            } catch (e: Exception) {
                try {
                    val intent = Intent(Settings.ACTION_WIRELESS_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    ActionResult(true, "Opened wireless settings as fallback for hotspot toggle.", null)
                } catch (ex: Exception) {
                    ActionResult(false, null, "Failed to open settings: ${ex.localizedMessage}")
                }
            }
        }
    }

    private class SetWallpaperAction : Action {
        override val name: String = "SET_WALLPAPER"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            return try {
                val intent = Intent(Intent.ACTION_SET_WALLPAPER).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                ActionResult(true, "Opened the wallpaper picker for you.", null)
            } catch (e: Exception) {
                Log.e("SetWallpaper", "Wallpaper failed: ${e.localizedMessage}")
                ActionResult(false, null, "Couldn't open the wallpaper picker.")
            }
        }
    }

    private class RecordScreenAction : Action {
        override val name: String = "RECORD_SCREEN"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val start = params["start"]?.toBoolean() ?: true
            return ActionResult(true, if (start) "Screen recording started (simulated)" else "Screen recording stopped (simulated)", null)
        }
    }

    private class InstallAppAction : Action {
        override val name: String = "INSTALL_APP"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val appName = params["appName"] ?: return ActionResult(false, null, "appName parameter missing")
            return try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=${Uri.encode(appName)}")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                ActionResult(true, "Opening Play Store to get '$appName' for you.", null)
            } catch (e: Exception) {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/search?q=${Uri.encode(appName)}")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    ActionResult(true, "Opened web browser for Google Play Store search for '$appName'", null)
                } catch (ex: Exception) {
                    ActionResult(false, null, "Failed to open Play Store: ${ex.localizedMessage}")
                }
            }
        }
    }

    private class CheckHardwareAction : Action {
        override val name: String = "CHECK_HARDWARE"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val hardwareFeature = params["hardwareFeature"] ?: params["feature"] ?: return ActionResult(false, null, "hardwareFeature parameter is missing")
            return try {
                val pm = context.packageManager
                val systemFeature = when (hardwareFeature.uppercase()) {
                    "CAMERA" -> PackageManager.FEATURE_CAMERA_ANY
                    "FLASHLIGHT" -> PackageManager.FEATURE_CAMERA_FLASH
                    "BLUETOOTH" -> PackageManager.FEATURE_BLUETOOTH
                    "WIFI" -> PackageManager.FEATURE_WIFI
                    "GPS", "LOCATION" -> PackageManager.FEATURE_LOCATION_GPS
                    "MICROPHONE" -> PackageManager.FEATURE_MICROPHONE
                    "FINGERPRINT" -> PackageManager.FEATURE_FINGERPRINT
                    else -> null
                }
                if (systemFeature != null) {
                    val hasFeature = pm.hasSystemFeature(systemFeature)
                    if (hasFeature) {
                        ActionResult(true, "Device supports hardware feature: $hardwareFeature", null)
                    } else {
                        ActionResult(false, null, "Device does not support hardware feature: $hardwareFeature")
                    }
                } else {
                    val hasFeature = pm.hasSystemFeature("android.hardware.${hardwareFeature.lowercase()}")
                    ActionResult(true, "Hardware feature '$hardwareFeature' check returned: $hasFeature", null)
                }
            } catch (e: Exception) {
                ActionResult(true, "Hardware feature '$hardwareFeature' assumed supported (fallback).", null)
            }
        }
    }

    private class CheckPermissionAction : Action {
        override val name: String = "CHECK_PERMISSION"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val permissionName = params["permission"] ?: return ActionResult(false, null, "permission parameter is missing")
            return try {
                val manifestPermission = when (permissionName.uppercase()) {
                    "CAMERA" -> Manifest.permission.CAMERA
                    "RECORD_AUDIO", "MICROPHONE" -> Manifest.permission.RECORD_AUDIO
                    "READ_CONTACTS" -> Manifest.permission.READ_CONTACTS
                    "WRITE_CONTACTS" -> Manifest.permission.WRITE_CONTACTS
                    "ACCESS_FINE_LOCATION" -> Manifest.permission.ACCESS_FINE_LOCATION
                    "ACCESS_COARSE_LOCATION" -> Manifest.permission.ACCESS_COARSE_LOCATION
                    "READ_SMS" -> Manifest.permission.READ_SMS
                    "SEND_SMS" -> Manifest.permission.SEND_SMS
                    "RECEIVE_SMS" -> Manifest.permission.RECEIVE_SMS
                    "CALL_PHONE" -> Manifest.permission.CALL_PHONE
                    "READ_PHONE_STATE" -> Manifest.permission.READ_PHONE_STATE
                    "READ_EXTERNAL_STORAGE" -> Manifest.permission.READ_EXTERNAL_STORAGE
                    "WRITE_EXTERNAL_STORAGE" -> Manifest.permission.WRITE_EXTERNAL_STORAGE
                    else -> {
                        if (permissionName.contains(".")) permissionName else "android.permission.$permissionName"
                    }
                }
                val granted = androidx.core.content.ContextCompat.checkSelfPermission(context, manifestPermission) == PackageManager.PERMISSION_GRANTED
                if (granted) {
                    ActionResult(true, "Permission $permissionName is granted.", null)
                } else {
                    ActionResult(false, "Permission $permissionName is not granted.", "Permission missing", true)
                }
            } catch (e: Exception) {
                Log.e("CheckPermission", "Permission check failed: ${e.localizedMessage}")
                ActionResult(false, null, "Couldn't check that permission.")
            }
        }
    }

    private class VerifyContactAction : Action {
        override val name: String = "VERIFY_CONTACT"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val contactName = params["contactName"] ?: params["contact"]
                ?: return ActionResult(false, null, "contactName parameter is missing")

            val contacts = mutableListOf<Pair<String, String>>()
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )
            try {
                context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                    val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    if (nameIndex != -1 && numberIndex != -1) {
                        while (cursor.moveToNext()) {
                            val name = cursor.getString(nameIndex) ?: ""
                            val number = cursor.getString(numberIndex) ?: ""
                            if (name.contains(contactName, ignoreCase = true)) {
                                contacts.add(name to number)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("ReadContacts", "Contacts failed: ${e.localizedMessage}")
                return ActionResult(false, null, "Couldn't read your contacts.")
            }

            val uniqueContacts = contacts.distinctBy { it.first + it.second }

            return when {
                uniqueContacts.isEmpty() -> {
                    ActionResult(false, null, "No contacts found matching '$contactName'")
                }
                uniqueContacts.size == 1 -> {
                    ActionResult(true, uniqueContacts.first().second, null)
                }
                else -> {
                    val optionsJson = uniqueContacts.joinToString(prefix = "[", postfix = "]") { (name, num) ->
                        "{\"name\":\"${name.replace("\\", "\\\\").replace("\"", "\\\"")}\",\"number\":\"${num.replace("\\", "\\\\").replace("\"", "\\\"")}\"}"
                    }
                    ActionResult(true, optionsJson, null)
                }
            }
        }
    }

    private class PromptUserSelectionAction(
        private val agentLoop: dagger.Lazy<AgentLoop>
    ) : Action {
        override val name: String = "PROMPT_USER_SELECTION"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val optionsStr = params["options"] ?: return ActionResult(false, null, "options parameter is missing")
            
            val options = parseOptions(optionsStr)
            if (options.isEmpty()) {
                return ActionResult(false, null, "No valid options to select from")
            }

            val speakText = StringBuilder("I found multiple matches. Which one would you like to choose? ")
            options.forEachIndexed { index, option ->
                speakText.append("${index + 1}: ${option.name}. ")
            }
            val promptText = speakText.toString()

            agentLoop.get().onSpeakCallback?.invoke(promptText)

            try {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    android.widget.Toast.makeText(context, promptText, android.widget.Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                // Ignore toast errors
            }

            val userResponse = agentLoop.get().awaitUserResponse()

            val selectedOption = matchResponseToOption(userResponse, options)
                ?: return ActionResult(false, null, "Could not understand your selection: '$userResponse'")

            return ActionResult(true, selectedOption.number, null)
        }

        private data class ContactOption(val name: String, val number: String)

        private fun parseOptions(optionsStr: String): List<ContactOption> {
            val list = mutableListOf<ContactOption>()
            val regex = """\{"name":"(.*?)","number":"(.*?)"\}""".toRegex()
            val matches = regex.findAll(optionsStr)
            for (match in matches) {
                val name = match.groups[1]?.value ?: ""
                val number = match.groups[2]?.value ?: ""
                list.add(ContactOption(name, number))
            }
            if (list.isEmpty()) {
                val items = optionsStr.split(",")
                for (item in items) {
                    val trimmed = item.trim()
                    if (trimmed.isEmpty()) continue
                    val numRegex = """(.*)\((.*?)\)""".toRegex()
                    val numMatch = numRegex.find(trimmed)
                    if (numMatch != null) {
                        val name = numMatch.groups[1]?.value?.trim() ?: trimmed
                        val number = numMatch.groups[2]?.value?.trim() ?: ""
                        list.add(ContactOption(name, number))
                    } else {
                        list.add(ContactOption(trimmed, trimmed))
                    }
                }
            }
            return list
        }

        private fun matchResponseToOption(response: String, options: List<ContactOption>): ContactOption? {
            val cleanResponse = response.trim().lowercase()

            val indexMap = mapOf(
                "1" to 0, "one" to 0, "first" to 0,
                "2" to 1, "two" to 1, "second" to 1,
                "3" to 2, "three" to 2, "third" to 2,
                "4" to 3, "four" to 3, "fourth" to 3,
                "5" to 4, "five" to 4, "fifth" to 4
            )
            for ((key, value) in indexMap) {
                if (cleanResponse.contains(key) && value < options.size) {
                    return options[value]
                }
            }

            for (option in options) {
                val cleanName = option.name.lowercase()
                if (cleanResponse.contains(cleanName) || cleanName.contains(cleanResponse)) {
                    return option
                }
            }

            val digit = cleanResponse.firstOrNull { it.isDigit() }?.toString()
            if (digit != null) {
                val idx = digit.toInt() - 1
                if (idx in options.indices) {
                    return options[idx]
                }
            }

            return null
        }
    }

    private class GetSystemInfoAction : Action {
        override val name: String = "GET_SYSTEM_INFO"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val buildInfo = "OS Version: ${android.os.Build.VERSION.RELEASE}, Model: ${android.os.Build.MODEL}"
            return ActionResult(true, "Here's your system info: $buildInfo", null)
        }
    }

    private class AskUserAction(
        private val agentLoop: dagger.Lazy<AgentLoop>
    ) : Action {
        override val name: String = "ASK_USER"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val question = params["question"] ?: params["message"] ?: "Please provide the required information."
            agentLoop.get().onSpeakCallback?.invoke(question)
            try {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    android.widget.Toast.makeText(context, question, android.widget.Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {}
            val response = agentLoop.get().awaitUserResponse().trim()
            return ActionResult(true, response, null)
        }
    }

    private class AnalyzeScreenshotAction(
        private val visionEngine: dagger.Lazy<com.opendroid.ai.core.agent.VisionEngine>
    ) : Action {
        override val name: String = "ANALYZE_SCREENSHOT"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val question = params["question"] ?: "What do you see on this screen?"
            return try {
                val analysis = visionEngine.get().analyzeCurrentScreen(question)
                ActionResult(true, analysis, null)
            } catch (e: Exception) {
                Log.e("ScreenshotAnalysis", "Analysis failed: ${e.localizedMessage}")
                ActionResult(false, null, "Couldn't analyze the screen right now.")
            }
        }
    }

    private class SetRingerModeAction : Action {
        override val name: String = "SET_RINGER_MODE"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val mode = params["mode"]?.lowercase()?.trim() ?: "normal"
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            return try {
                val ringerMode = when (mode) {
                    "silent", "mute" -> AudioManager.RINGER_MODE_SILENT
                    "vibrate", "vibration" -> AudioManager.RINGER_MODE_VIBRATE
                    else -> AudioManager.RINGER_MODE_NORMAL
                }
                audioManager.ringerMode = ringerMode
                val label = when (ringerMode) {
                    AudioManager.RINGER_MODE_SILENT -> "silent"
                    AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
                    else -> "normal"
                }
                ActionResult(true, "Phone is now on $label mode.", null)
            } catch (e: SecurityException) {
                Log.e("SetRingerMode", "Permission denied: ${e.localizedMessage}")
                ActionResult(false, null, "I need Do Not Disturb access to change the ringer mode. Please grant it in Settings.")
            } catch (e: Exception) {
                Log.e("SetRingerMode", "Ringer mode failed: ${e.localizedMessage}")
                ActionResult(false, null, "Couldn't change the ringer mode right now.")
            }
        }
    }
}
