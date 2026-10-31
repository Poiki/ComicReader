package org.comicreader.app;

import android.content.*;
import android.os.*;

import nz.mega.sdk.*;

import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

final class MegaClient {
    static final long ROOT = -1;
    private static final String SESSION = "mega.session", STATE_FOLDER = ".comicreader-state-v1";
    private static MegaClient instance;

    static synchronized MegaClient instance(Context context) {
        if (instance == null) instance = new MegaClient(context.getApplicationContext());
        return instance;
    }

    private final Context context;
    private MegaApiJava api;
    private volatile boolean ready;
    private volatile String unavailable;
    private volatile Transfer pending;
    private volatile Request pendingRequest;
    private final AtomicInteger browsers = new AtomicInteger();

    private MegaClient(Context context) {
        this.context = context;
    }

    private SharedPreferences preferences() {
        return context.getSharedPreferences("mega", 0);
    }

    boolean available() {
        return Build.VERSION.SDK_INT >= 28 && unavailable == null;
    }

    boolean loggedIn() {
        return preferences().getBoolean("connected", false);
    }

    synchronized String account() throws Exception {
        connected();
        String owner = api.getMyUserHandle();
        if (owner == null || !owner.matches("[A-Za-z0-9_-]{1,64}"))
            throw new IOException("MEGA returned no account identity");
        return owner;
    }

    String status() {
        if (Build.VERSION.SDK_INT < 28) return "The MEGA SDK requires Android 9 or later";
        if (unavailable != null) return unavailable;
        Context display = AppLanguage.attach(context);
        return display.getString(
                loggedIn() ? R.string.mega_session_saved : R.string.mega_sdk_signin);
    }

    private static void worker() {
        if (Looper.myLooper() == Looper.getMainLooper())
            throw new IllegalStateException("MEGA operations must run off the UI thread");
    }

    private MegaApiJava api() throws IOException {
        worker();
        if (!available()) throw new IOException(status());
        if (api == null) {
            try {
                System.loadLibrary("mega");
                File cache = new File(context.getNoBackupFilesDir(), "mega-sdk");
                if (!cache.isDirectory() && !cache.mkdirs())
                    throw new IOException("Cannot create the private MEGA cache");
                // The current SDK maps a null basePath to cwd, which is not writable on Android.
                api = new MegaApiJava(null, "ComicReader Android", cache.getAbsolutePath(), null);
                MegaApiJava.setLogLevel(MegaApiJava.LOG_LEVEL_FATAL);
            } catch (LinkageError e) {
                unavailable = "Cannot load the MEGA SDK for this device architecture";
                throw new IOException(unavailable, e);
            }
        }
        return api;
    }

    synchronized void login(String email, String password, String otp) throws Exception {
        worker();
        if (email == null
                || email.isBlank()
                || email.length() > 320
                || password == null
                || password.isEmpty()
                || password.length() > 4096
                || (otp != null && !otp.isBlank() && !otp.matches("[0-9]{6}")))
            throw new IOException("Enter an email, password and, if required, a six-digit code");
        MegaApiJava sdk = api();
        awaitRequests();
        if (loggedIn() || sdk.isLoggedIn() != 0)
            throw new IOException("Sign out before connecting another account");
        if (otp == null || otp.isBlank()) request(listener -> sdk.login(email, password, listener));
        else request(listener -> sdk.multiFactorAuthLogin(email, password, otp, listener));
        String session = sdk.dumpSession();
        try {
            if (session == null || session.isBlank())
                throw new IOException("MEGA returned no session to save");
            Secrets.put(context, SESSION, session);
            if (!preferences().edit().putBoolean("connected", true).commit())
                throw new IOException("Cannot save the MEGA account state");
        } catch (Exception e) {
            try {
                Secrets.remove(context, SESSION);
            } catch (Exception cleanup) {
                e.addSuppressed(cleanup);
            }
            try {
                request(sdk::localLogout);
            } catch (Exception cleanup) {
                e.addSuppressed(cleanup);
            }
            throw e;
        }
        // Keep a valid encrypted session if fetching the account tree fails; report that failure to
        // the caller.
        request(sdk::fetchNodes);
        ready = true;
    }

    private boolean connected() throws Exception {
        MegaApiJava sdk = api();
        if (ready && sdk.isLoggedIn() != 0) return false;
        awaitRequests();
        if (sdk.isLoggedIn() == 0) {
            String session = Secrets.get(context, SESSION);
            if (session == null || session.isEmpty()) throw new IOException("Sign in to MEGA");
            request(listener -> sdk.fastLogin(session, listener));
        }
        request(sdk::fetchNodes);
        ready = true;
        return true;
    }

    synchronized List<Node> list(long parent) throws Exception {
        boolean fetched = connected();
        MegaNode folder = parent == ROOT ? api.getRootNode() : api.getNodeByHandle(parent);
        if ((folder == null || !folder.isFolder()) && !fetched) {
            ready = false;
            connected();
            folder = parent == ROOT ? api.getRootNode() : api.getNodeByHandle(parent);
        }
        if (folder == null || !folder.isFolder())
            throw new IOException("The MEGA folder is no longer available");
        List<Node> entries = new ArrayList<>();
        for (MegaNode node : api.getChildren(folder)) {
            if (entries.size() >= Progress.MAX_BOOKS)
                throw new IOException("The folder exceeds 50,000 entries");
            if (node.getName() == null) throw new IOException("MEGA returned an invalid name");
            entries.add(
                    new Node(node.getHandle(), node.getName(), node.isFolder(), node.getSize()));
        }
        entries.sort(
                (a, b) ->
                        a.directory != b.directory
                                ? (a.directory ? -1 : 1)
                                : ComicFiles.naturalCompare(a.name, b.name));
        return entries;
    }

    synchronized File download(Node node) throws Exception {
        connected();
        if (node == null || node.directory || !ComicFiles.supported(node.name))
            throw new IOException("Select a CBZ/ZIP, CBR/RAR or PDF from MEGA");
        MegaNode remote = api.getNodeByHandle(node.handle);
        return receive(remote, ComicFiles.MAX_COMIC);
    }

    synchronized File thumbnail(Node node) throws Exception {
        connected();
        MegaNode remote = api.getNodeByHandle(node.handle);
        if (remote == null || !remote.isFile()) throw new IOException("MEGA file is unavailable");
        if (!remote.hasThumbnail()) return null;
        awaitRequests();
        File target = File.createTempFile("mega-thumbnail-", ".jpg", context.getCacheDir());
        try {
            request(
                    listener -> api.getThumbnail(remote, target.getAbsolutePath(), listener),
                    target);
        } catch (InterruptedException error) {
            api.cancelGetThumbnail(remote);
            throw error;
        }
        if (target.length() == 0 || target.length() > 5 * 1024 * 1024) {
            eraseTemporary(target);
            throw new IOException("MEGA returned an invalid thumbnail");
        }
        return target;
    }

    private File receive(MegaNode node, long limit) throws Exception {
        awaitTransfer();
        if (node == null || !node.isFile() || node.getSize() < 0 || node.getSize() > limit)
            throw new IOException("MEGA file is unavailable or too large");
        File target = File.createTempFile("mega-", ".part", context.getCacheDir());
        Transfer transfer = new Transfer(target, limit, false);
        pending = transfer;
        try {
            api.startDownload(
                    node,
                    target.getAbsolutePath(),
                    null,
                    null,
                    false,
                    transfer.token,
                    MegaTransfer.COLLISION_CHECK_ASSUMEDIFFERENT,
                    MegaTransfer.COLLISION_RESOLUTION_OVERWRITE,
                    transfer);
            transfer.await();
            if (target.length() != node.getSize())
                throw new IOException("The MEGA download is incomplete");
            return target;
        } catch (Exception e) {
            transfer.cancel();
            throw e;
        }
    }

    synchronized int sync(Context ignored) throws Exception {
        if (!connected()) request(api::fetchNodes);
        MegaNode root = api.getRootNode();
        if (root == null) throw new IOException("MEGA returned no account root");
        MegaNode folder = stateFolder(root);
        if (folder == null) {
            long handle = request(listener -> api.createFolder(STATE_FOLDER, root, listener));
            request(api::fetchNodes);
            folder = stateFolder(root);
            if (folder == null || folder.getHandle() != handle)
                throw new IOException("Could not confirm the MEGA progress folder");
        }
        Map<String, Progress> merged = new HashMap<>();
        int devices = 0;
        long transferred = 0;
        for (MegaNode node : api.getChildren(folder)) {
            if (!node.isFile()
                    || node.getName() == null
                    || !node.getName().matches("[0-9a-f-]{36}\\.crp")) continue;
            if (++devices > 128)
                throw new IOException("More than 128 MEGA snapshots; archive older ones");
            transferred += node.getSize();
            if (transferred > 64L * 1024 * 1024)
                throw new IOException("MEGA snapshots exceed 64 MiB per sync");
            File downloaded = receive(node, Progress.MAX_BYTES);
            try {
                for (Progress state : Progress.decode(Files.readAllBytes(downloaded.toPath()))) {
                    Progress old = merged.get(state.id);
                    merged.put(state.id, old == null ? state : Progress.latest(old, state));
                    if (merged.size() > Progress.MAX_BOOKS)
                        throw new IOException("Too many comics in remote progress");
                }
            } finally {
                eraseTemporary(downloaded);
            }
        }
        Library library = Library.get(context);
        library.merge(merged.values());
        File snapshot = File.createTempFile("mega-state-", ".crp", context.getCacheDir());
        Transfer transfer = null;
        try {
            try (FileOutputStream out = new FileOutputStream(snapshot)) {
                out.write(Progress.encode(library.states()));
                out.getFD().sync();
            }
            MegaNode destination = folder;
            awaitTransfer();
            transfer = new Transfer(snapshot, Progress.MAX_BYTES, true);
            pending = transfer;
            try {
                MegaUploadOptions options = MegaUploadOptions.createInstance();
                options.setFileName(library.device + ".crp");
                api.startUpload(
                        snapshot.getAbsolutePath(), destination, transfer.token, options, transfer);
                transfer.await();
            } catch (Exception e) {
                transfer.cancel();
                throw e;
            }
        } catch (Exception e) {
            if (transfer == null) eraseTemporary(snapshot);
            throw e;
        }
        return merged.size();
    }

    private MegaNode stateFolder(MegaNode root) throws IOException {
        MegaNode found = null;
        for (MegaNode node : api.getChildren(root)) {
            if (!STATE_FOLDER.equals(node.getName())) continue;
            if (!node.isFolder() || found != null)
                throw new IOException(
                        "The MEGA progress folder has duplicate or incompatible names; resolve them before syncing");
            found = node;
        }
        return found;
    }

    synchronized void logout() throws Exception {
        worker();
        Exception revocation = null;
        try {
            if (pending != null) pending.cancel();
            awaitTransfer();
            awaitRequests();
            MegaApiJava sdk = api();
            if (sdk.isLoggedIn() == 0) {
                String session = Secrets.get(context, SESSION);
                if (session != null && !session.isEmpty())
                    request(listener -> sdk.fastLogin(session, listener));
            }
            if (sdk.isLoggedIn() != 0) request(sdk::logout);
        } catch (Exception e) {
            revocation = e;
        }
        ready = false;
        Secrets.remove(context, SESSION);
        if (!preferences().edit().clear().commit())
            throw new IOException("Could not remove local MEGA state");
        if (revocation != null) {
            if (api != null) api.localLogout();
            throw new IOException(
                    "Local MEGA session removed; remote revocation could not be confirmed",
                    revocation);
        }
    }

    void browsing(boolean active) {
        if (browsers.addAndGet(active ? 1 : -1) < 0)
            throw new IllegalStateException("Unbalanced MEGA browser lifecycle");
    }

    synchronized void suspend() throws Exception {
        worker();
        if (api == null
                || browsers.get() != 0
                || pending != null
                || pendingRequest != null
                || Thread.currentThread().isInterrupted()) return;
        if (api.isLoggedIn() != 0) request(api::localLogout);
        ready = false;
    }

    // Metadata reads can continue while JNI finishes cancelling a cover transfer.
    private void awaitTransfer() throws IOException, InterruptedException {
        Transfer finishing = pending;
        if (finishing != null) awaitCompletion(finishing.done);
    }

    private void awaitRequests() throws IOException, InterruptedException {
        Request finishing;
        while ((finishing = pendingRequest) != null) awaitCompletion(finishing.done);
    }

    static void awaitCompletion(CountDownLatch done) throws IOException, InterruptedException {
        if (!done.await(30, TimeUnit.SECONDS))
            throw new IOException("MEGA took too long to finish the previous operation; try again");
    }

    private long request(Consumer<MegaRequestListenerInterface> start) throws Exception {
        return request(start, null);
    }

    private long request(Consumer<MegaRequestListenerInterface> start, File target)
            throws Exception {
        awaitRequests();
        Request request = new Request(target);
        pendingRequest = request;
        try {
            start.accept(request);
        } catch (RuntimeException e) {
            pendingRequest = null;
            request.abandon();
            throw e;
        }
        try {
            if (!request.done.await(120, TimeUnit.SECONDS))
                throw new IOException(
                        "MEGA did not respond within two minutes" + request.temporary());
            if (request.error != MegaError.API_OK) throw error(request.error);
            return request.handle;
        } catch (Exception e) {
            request.abandon();
            throw e;
        }
    }

    private static IOException error(int code) {
        return new IOException(
                switch (code) {
                    case MegaError.API_EMFAREQUIRED ->
                            "MEGA requires a two-factor verification code";
                    case MegaError.API_ESID ->
                            "The MEGA session expired; sign out and sign in again";
                    case MegaError.API_EACCESS ->
                            "MEGA denied access; check your credentials or permissions";
                    case MegaError.API_EOVERQUOTA -> "The MEGA transfer quota is exhausted";
                    default -> "MEGA rejected the operation (code " + code + ")";
                });
    }

    private static void eraseTemporary(File file) {
        if (file.exists() && !file.delete())
            android.util.Log.w("ComicReader", "Could not remove a MEGA temporary file");
    }

    private final class Request implements MegaRequestListenerInterface {
        final CountDownLatch done = new CountDownLatch(1);
        final TransferFile file;
        int error;
        volatile int lastTemporary;
        long handle;
        private boolean finished, abandoned, successfulLogin;

        Request() {
            this(null);
        }

        Request(File target) {
            file = target == null ? null : new TransferFile(target, false);
        }

        synchronized void abandon() {
            abandoned = true;
            if (file != null) file.discard();
            if (finished && successfulLogin) disconnectLateLogin();
        }

        private void disconnectLateLogin() {
            ready = false;
            Request cleanup = new Request();
            pendingRequest = cleanup;
            api.localLogout(cleanup);
        }

        String temporary() {
            return lastTemporary == 0 ? "" : " (last temporary error " + lastTemporary + ")";
        }

        @Override
        public void onRequestStart(MegaApiJava api, MegaRequest request) {}

        @Override
        public void onRequestUpdate(MegaApiJava api, MegaRequest request) {}

        @Override
        public void onRequestTemporaryError(MegaApiJava api, MegaRequest request, MegaError error) {
            lastTemporary = error.getErrorCode();
        }

        @Override
        public synchronized void onRequestFinish(
                MegaApiJava sdk, MegaRequest request, MegaError result) {
            error = result.getErrorCode();
            if (file != null) file.complete(error == MegaError.API_OK);
            handle = request.getNodeHandle();
            finished = true;
            successfulLogin =
                    request.getType() == MegaRequest.TYPE_LOGIN && error == MegaError.API_OK;
            if (abandoned && successfulLogin) disconnectLateLogin();
            else if (pendingRequest == this) pendingRequest = null;
            done.countDown();
        }
    }

    private final class Transfer implements MegaTransferListenerInterface {
        final CountDownLatch done = new CountDownLatch(1);
        final MegaCancelToken token = MegaCancelToken.createInstance();
        final TransferFile lifetime;
        final long limit;
        volatile boolean tooLarge;
        int result;
        volatile int lastTemporary;

        Transfer(File file, long limit, boolean upload) {
            this.lifetime = new TransferFile(file, upload);
            this.limit = limit;
        }

        void await() throws Exception {
            // shortcut: a transfer has ten minutes, use a foreground resumable download service for
            // longer jobs.
            if (!done.await(10, TimeUnit.MINUTES))
                throw new IOException(
                        "MEGA transfer exceeded ten minutes (last temporary error "
                                + lastTemporary
                                + ")");
            if (tooLarge) throw new IOException("The MEGA transfer exceeds the size limit");
            if (result != MegaError.API_OK) throw error(result);
        }

        void cancel() {
            lifetime.discard();
            token.cancel();
        }

        @Override
        public void onTransferStart(MegaApiJava api, MegaTransfer transfer) {}

        @Override
        public void onTransferUpdate(MegaApiJava api, MegaTransfer transfer) {
            if (transfer.getTransferredBytes() > limit || transfer.getTotalBytes() > limit) {
                tooLarge = true;
                token.cancel();
            }
        }

        @Override
        public void onTransferTemporaryError(
                MegaApiJava api, MegaTransfer transfer, MegaError error) {
            lastTemporary = error.getErrorCode();
        }

        @Override
        public void onTransferFinish(MegaApiJava api, MegaTransfer transfer, MegaError error) {
            result = error.getErrorCode();
            lifetime.complete(result == MegaError.API_OK);
            if (pending == this) pending = null;
            done.countDown();
        }

        @Override
        public boolean onTransferData(MegaApiJava api, MegaTransfer transfer, byte[] buffer) {
            return false;
        }

        @Override
        public void onFolderTransferUpdate(
                MegaApiJava api,
                MegaTransfer transfer,
                int stage,
                long folderCount,
                long createdFolderCount,
                long fileCount,
                String currentFolder,
                String currentFileLeafName) {}

        @Override
        public void onTransferQuotaState(MegaApiJava api, MegaTransfer transfer, int state) {}
    }

    record Node(long handle, String name, boolean directory, long size) {}
}
