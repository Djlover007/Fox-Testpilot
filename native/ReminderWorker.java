package de.fox.testpilot;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

/** Zeigt eine geplante Erinnerung (10 Min. vor dem Start, Bewertungs-Frist). */
public class ReminderWorker extends Worker {

    public ReminderWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        try {
            PilotChecker.showNotification(getApplicationContext(), getInputData().getInt("id", 900),
                    getInputData().getString("title"), getInputData().getString("body"),
                    getInputData().getBoolean("alarm", false), getInputData().getString("target"));
        } catch (Exception ignored) {
        }
        return Result.success();
    }
}
