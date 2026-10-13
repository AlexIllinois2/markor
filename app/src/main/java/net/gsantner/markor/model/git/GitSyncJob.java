/*#######################################################
 *
 *   Background git sync job.
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.model.git;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import net.gsantner.markor.model.AppSettings;

import java.util.concurrent.TimeUnit;

/**
 * 应用没在前台时的同步入口。系统把周期任务的最短间隔压到 15 分钟，
 * 所以比 15 分钟更短的设置在 Markor 关闭时只能按 15 分钟跑。
 */
public class GitSyncJob extends Worker {

    private static final String UNIQUE_NAME = "markor-git-sync";
    private static final long SYSTEM_MINIMUM_MINUTES = 15;

    public GitSyncJob(@NonNull final Context context, @NonNull final WorkerParameters parameters) {
        super(context, parameters);
    }

    @NonNull
    @Override
    public Result doWork() {
        final AppSettings settings = AppSettings.get(getApplicationContext());
        if (!GitNotebook.isAvailable(settings)) {
            return Result.success();
        }
        try {
            // 前台正在同步时让位，稍后由退避重试
            return GitNotebook.runBackgroundSync(settings) ? Result.success() : Result.retry();
        } catch (Exception e) {
            settings.setGitLastSyncInfo(GitNotebook.shortText(e));
            return Result.retry();
        }
    }

    /** 按当前设置重排或取消后台任务；界面里改完设置、以及应用启动时调用 */
    public static void schedule(final Context context, final AppSettings settings) {
        final WorkManager workManager = WorkManager.getInstance(context);
        final int minutes = settings.getGitSyncIntervalMinutes();
        if (!GitNotebook.isAvailable(settings) || minutes <= 0) {
            workManager.cancelUniqueWork(UNIQUE_NAME);
            return;
        }
        final Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED).build();
        final PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                GitSyncJob.class, Math.max(SYSTEM_MINIMUM_MINUTES, minutes), TimeUnit.MINUTES)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, SYSTEM_MINIMUM_MINUTES, TimeUnit.MINUTES)
                .build();
        workManager.enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.UPDATE, request);
    }
}
