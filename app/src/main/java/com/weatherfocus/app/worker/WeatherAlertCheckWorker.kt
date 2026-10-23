package com.weatherfocus.app.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.weatherfocus.app.data.prefs.SettingsRepository
import com.weatherfocus.app.data.repository.WeatherRepository
import kotlinx.coroutines.flow.first

/** Re-checks the user's custom weather alert rules in the background and notifies about anything inside the lead-time window. */
class WeatherAlertCheckWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            val settings = SettingsRepository(applicationContext).settingsFlow.first()
            if (!settings.customAlertRules.enabled) return Result.success()
            // Lightweight: only the hourly forecast is needed. A failed download is retried (it used to be treated as "no alerts").
            val repo = WeatherRepository()
            val hourly = repo.loadAlertForecast(settings)
                ?: return if (runAttemptCount < 3) Result.retry() else Result.success()
            val flood = if (settings.customAlertRules.floodEnabled) repo.loadFloodContext(settings) else null
            AlertChecker.run(applicationContext, settings, hourly, flood)
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.success()
        }
    }
}
