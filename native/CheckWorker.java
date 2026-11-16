package de.fox.testpilot;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

/** Läuft regelmäßig im Hintergrund (auch wenn die App geschlossen ist). */
public class CheckWorker extends Worker {

    public CheckWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        if (!PilotChecker.backgroundOn(getApplicationContext())) return Result.success();
        try {
            PilotChecker.run(getApplicationContext(), false);
        } catch (Exception ignored) {
        }
        return Result.success();
    }
}
