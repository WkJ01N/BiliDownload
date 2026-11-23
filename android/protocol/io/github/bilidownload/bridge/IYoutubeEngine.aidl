package io.github.bilidownload.bridge;
import android.os.ParcelFileDescriptor;

interface IYoutubeEngine {
    int version();
    ParcelFileDescriptor run(String requestId, String taskId, String json, IBinder owner);
    void cancel(String requestId);
    ParcelFileDescriptor openTrack(String taskId, boolean audio);
    void clean(String taskId);
}
