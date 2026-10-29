package org.comicreader.app;

import java.io.File;

/** JNI can still own a file after its waiting Java caller has been cancelled. */
final class TransferFile {
    private final File file;
    private final boolean deleteOnSuccess;
    private boolean finished, discarded;

    TransferFile(File file, boolean deleteOnSuccess) { this.file = file; this.deleteOnSuccess = deleteOnSuccess; }
    synchronized void discard() {
        discarded = true;
        if (finished) erase();
    }
    synchronized void complete(boolean success) {
        finished = true;
        if (!success || discarded || deleteOnSuccess) erase();
    }
    private void erase() {
        if (file.exists() && !file.delete()) android.util.Log.w("ComicReader", "No se pudo eliminar un temporal de transferencia");
    }
}
