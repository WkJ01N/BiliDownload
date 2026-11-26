package io.github.bilidownload.bridge;
import android.os.ParcelFileDescriptor;

interface IAudioProcessor {
    int version();
    int convert(String id, in ParcelFileDescriptor[] sources, in ParcelFileDescriptor destination,
                String extension, int mp3Kbps, int channels, IBinder owner);
    void cancel(String id);
    int convertGif(String id, in ParcelFileDescriptor source, in ParcelFileDescriptor destination, IBinder owner);
}
