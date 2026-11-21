package com.android.wificalllocal.files;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.android.wificalllocal.ServiceLocator;

public class FileTransferWorker extends Worker {

    public FileTransferWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        String transferId = getInputData().getString("transferId");
        if (transferId == null || transferId.isEmpty()) {
            return Result.failure();
        }
        FileTransferManager manager = ServiceLocator.getInstance(getApplicationContext()).getFileTransferManager();
        boolean ok = manager.downloadSynchronously(transferId);
        return ok ? Result.success() : Result.retry();
    }
}
