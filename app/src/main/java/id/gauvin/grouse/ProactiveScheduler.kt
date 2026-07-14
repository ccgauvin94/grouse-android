package id.gauvin.grouse

import android.content.Context
import androidx.work.*
import java.util.Calendar
import java.util.concurrent.TimeUnit

/** Schedules the proactive check via WorkManager (survives reboots). */
object ProactiveScheduler {
    private const val NAME = "proactive-check"

    private val netConstraint = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun reschedule(context: Context) {
        val wm = WorkManager.getInstance(context)
        val store = SecureStore(context)
        if (!store.proactiveEnabled) { wm.cancelUniqueWork(NAME); return }
        val req = PeriodicWorkRequestBuilder<ProactiveWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(msUntilNext(store.proactiveTime), TimeUnit.MILLISECONDS)
            .setConstraints(netConstraint)
            .build()
        wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    /** Fire a check right now (for testing from the settings screen). */
    fun runNow(context: Context) {
        WorkManager.getInstance(context).enqueue(
            OneTimeWorkRequestBuilder<ProactiveWorker>().setConstraints(netConstraint).build())
    }

    private fun msUntilNext(hhmm: String): Long {
        val parts = hhmm.split(":")
        val hour = parts.getOrNull(0)?.trim()?.toIntOrNull()?.coerceIn(0, 23) ?: 8
        val minute = parts.getOrNull(1)?.trim()?.toIntOrNull()?.coerceIn(0, 59) ?: 0
        val now = Calendar.getInstance()
        val next = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= now.timeInMillis) add(Calendar.DAY_OF_MONTH, 1)
        }
        return next.timeInMillis - now.timeInMillis
    }
}
