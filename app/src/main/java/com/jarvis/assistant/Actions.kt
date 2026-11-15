package com.jarvis.assistant

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.AlarmClock
import org.json.JSONObject
import java.util.Locale

/** Jarvis's hands: things he can actually do on the phone. */
class Actions(private val ctx: Context) {

    /** Actions that open another app (Jarvis shouldn't keep listening after these). */
    val leavesApp = setOf("open_app", "web_search", "dial", "sms")

    fun run(a: JSONObject): String = try {
        when (a.optString("action")) {
            "torch_on" -> torch(true)
            "torch_off" -> torch(false)
            "battery" -> battery()
            "clipboard" -> clipboard()
            "vibrate" -> vibrate()
            "open_app" -> openApp(a.optString("name"))
            "set_alarm" -> setAlarm(a.getInt("hour"), a.optInt("minute", 0), a.optString("label", "Jarvis alarm"))
            "set_timer" -> setTimer(a.getInt("seconds"), a.optString("label", "Jarvis timer"))
            "web_search" -> webSearch(a.optString("query"))
            "dial" -> dial(a.optString("number"))
            "sms" -> sms(a.optString("number"), a.optString("message"))
            else -> "I don't know how to do that yet."
        }
    } catch (e: Exception) {
        "I couldn't do that. ${e.message ?: ""}".trim()
    }

    private fun torch(on: Boolean): String {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return "This phone doesn't have a torch."
        cm.setTorchMode(id, on)
        return if (on) "Torch is on." else "Torch is off."
    }

    private fun battery(): String {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return "Battery is at $pct percent" + if (bm.isCharging) " and charging." else "."
    }

    private fun clipboard(): String {
        val cb = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cb.primaryClip
        val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(ctx).toString() else ""
        return if (text.isBlank()) "Your clipboard is empty." else "Your clipboard says: $text"
    }

    private fun vibrate(): String {
        val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= 31) {
            (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        vibrator.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
        return "Done."
    }

    private fun openApp(name: String): String {
        val pm = ctx.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcher, 0)
        val target = name.lowercase(Locale.ROOT).trim()
        val match = apps.firstOrNull { it.loadLabel(pm).toString().lowercase(Locale.ROOT) == target }
            ?: apps.firstOrNull { it.loadLabel(pm).toString().lowercase(Locale.ROOT).contains(target) }
            ?: return "I couldn't find an app called $name."
        val intent = pm.getLaunchIntentForPackage(match.activityInfo.packageName)
            ?: return "I can't open $name."
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
        return "Opening ${match.loadLabel(pm)}."
    }

    private fun setAlarm(hour: Int, minute: Int, label: String): String {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_MESSAGE, label)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
        return String.format(Locale.ROOT, "Alarm set for %02d:%02d.", hour, minute)
    }

    private fun setTimer(seconds: Int, label: String): String {
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_MESSAGE, label)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
        val mins = seconds / 60
        val secs = seconds % 60
        return when {
            mins == 0 -> "Timer set for $secs seconds."
            secs == 0 -> "Timer set for $mins minutes."
            else -> "Timer set for $mins minutes and $secs seconds."
        }
    }

    private fun webSearch(query: String): String {
        val uri = Uri.parse("https://www.google.com/search?q=" + Uri.encode(query))
        ctx.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return "Searching for $query."
    }

    private fun dial(number: String): String {
        ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return "Opening the dialer."
    }

    private fun sms(number: String, message: String): String {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number)))
            .putExtra("sms_body", message)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
        return "Your message is ready. Just tap send."
    }
}
