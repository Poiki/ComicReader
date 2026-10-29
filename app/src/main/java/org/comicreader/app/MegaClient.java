package org.comicreader.app;

import android.content.*;
import android.os.*;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import nz.mega.sdk.*;

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
    private MegaClient(Context context) { this.context = context; }
    private SharedPreferences preferences() { return context.getSharedPreferences("mega", 0); }
    boolean available() { return Build.VERSION.SDK_INT >= 28 && unavailable == null; }
    boolean loggedIn() { return preferences().getBoolean("connected", false); }
    synchronized String account() throws Exception {
        connected();
        String owner = api.getMyUserHandle();
        if (owner == null || !owner.matches("[A-Za-z0-9_-]{1,64}")) throw new IOException("MEGA no devolvió la identidad de la cuenta");
        return owner;
    }
    String status() {
        if (Build.VERSION.SDK_INT < 28) return "El SDK de MEGA necesita Android 9 o superior";
        if (unavailable != null) return unavailable;
        return loggedIn() ? "Sesión de MEGA guardada" : "SDK oficial de MEGA incluido; inicia sesión";
    }
    private static void worker() {
        if (Looper.myLooper() == Looper.getMainLooper())
            throw new IllegalStateException("Las operaciones de MEGA deben ejecutarse fuera del hilo de interfaz");
    }
    private MegaApiJava api() throws IOException {
        worker();
        if (!available()) throw new IOException(status());
        if (pending != null) throw new IOException("MEGA todavía está cancelando la transferencia anterior");
        if (pendingRequest != null) throw new IOException("MEGA todavía procesa la petición anterior; espera antes de reintentar");
        if (api == null) {
            try {
                System.loadLibrary("mega");
                File cache = new File(context.getNoBackupFilesDir(), "mega-sdk");
                if (!cache.isDirectory() && !cache.mkdirs()) throw new IOException("No se puede crear la caché privada de MEGA");
                // The current SDK maps a null basePath to cwd, which is not writable on Android.
                api = new MegaApiJava(null, "ComicReader/0.3", cache.getAbsolutePath(), null);
                MegaApiJava.setLogLevel(MegaApiJava.LOG_LEVEL_FATAL);
            } catch (LinkageError e) {
                unavailable = "No se puede cargar el SDK de MEGA para la arquitectura de este dispositivo";
                throw new IOException(unavailable, e);
            }
        }
        return api;
    }
    synchronized void login(String email, String password, String otp) throws Exception {
        worker();
        if (email == null || email.isBlank() || email.length() > 320 || password == null || password.isEmpty()
                || password.length() > 4096 || (otp != null && !otp.isBlank() && !otp.matches("[0-9]{6}")))
            throw new IOException("Introduce correo, contraseña y, si corresponde, un código de seis dígitos");
        MegaApiJava sdk = api();
        if (loggedIn() || sdk.isLoggedIn() != 0) throw new IOException("Cierra la sesión actual antes de conectar otra cuenta");
        if (otp == null || otp.isBlank()) request(listener -> sdk.login(email, password, listener));
        else request(listener -> sdk.multiFactorAuthLogin(email, password, otp, listener));
        String session = sdk.dumpSession();
        try {
            if (session == null || session.isBlank()) throw new IOException("MEGA no devolvió una sesión que pueda guardarse");
            Secrets.put(context, SESSION, session);
            if (!preferences().edit().putBoolean("connected", true).commit()) throw new IOException("No se puede guardar el estado de la cuenta MEGA");
        } catch (Exception e) {
            try { Secrets.remove(context, SESSION); } catch (Exception cleanup) { e.addSuppressed(cleanup); }
            try { request(sdk::localLogout); } catch (Exception cleanup) { e.addSuppressed(cleanup); }
            throw e;
        }
        // Keep a valid encrypted session if fetching the account tree fails; report that failure to the caller.
        request(sdk::fetchNodes);
        ready = true;
    }
    private boolean connected() throws Exception {
        MegaApiJava sdk = api();
        if (ready && sdk.isLoggedIn() != 0) return false;
        if (sdk.isLoggedIn() == 0) {
            String session = Secrets.get(context, SESSION);
            if (session == null || session.isEmpty()) throw new IOException("Inicia sesión en MEGA");
            request(listener -> sdk.fastLogin(session, listener));
        }
        request(sdk::fetchNodes);
        ready = true;
        return true;
    }
    synchronized List<Node> list(long parent) throws Exception {
        connected();
        MegaNode folder = parent == ROOT ? api.getRootNode() : api.getNodeByHandle(parent);
        if (folder == null || !folder.isFolder()) throw new IOException("La carpeta de MEGA ya no está disponible");
        List<Node> entries = new ArrayList<>();
        for (MegaNode node : api.getChildren(folder)) {
            if (entries.size() >= Progress.MAX_BOOKS) throw new IOException("La carpeta supera los 50.000 elementos");
            if (node.getName() == null) throw new IOException("MEGA devolvió un nombre no válido");
            entries.add(new Node(node.getHandle(), node.getName(), node.isFolder(), node.getSize()));
        }
        entries.sort((a, b) -> a.directory != b.directory ? (a.directory ? -1 : 1) : ComicFiles.naturalCompare(a.name, b.name));
        return entries;
    }
    synchronized File download(Node node) throws Exception {
        connected();
        if (node == null || node.directory || !ComicFiles.supported(node.name)) throw new IOException("Selecciona un CBZ, ZIP o PDF de MEGA");
        MegaNode remote = api.getNodeByHandle(node.handle);
        return receive(remote, ComicFiles.MAX_COMIC);
    }
    private File receive(MegaNode node, long limit) throws Exception {
        if (node == null || !node.isFile() || node.getSize() < 0 || node.getSize() > limit)
            throw new IOException("Archivo de MEGA no disponible o demasiado grande");
        File target = File.createTempFile("mega-", ".part", context.getCacheDir());
        Transfer transfer = new Transfer(target, limit, false);
        pending = transfer;
        try {
            api.startDownload(node, target.getAbsolutePath(), null, null, false, transfer.token,
                    MegaTransfer.COLLISION_CHECK_ASSUMEDIFFERENT, MegaTransfer.COLLISION_RESOLUTION_OVERWRITE, transfer);
            transfer.await();
            if (target.length() != node.getSize()) throw new IOException("La descarga de MEGA está incompleta");
            return target;
        } catch (Exception e) {
            transfer.cancel();
            throw e;
        }
    }
    synchronized int sync(Context ignored) throws Exception {
        if (!connected()) request(api::fetchNodes);
        MegaNode root = api.getRootNode();
        if (root == null) throw new IOException("MEGA no devolvió la raíz de la cuenta");
        MegaNode folder = stateFolder(root);
        if (folder == null) {
            long handle = request(listener -> api.createFolder(STATE_FOLDER, root, listener));
            request(api::fetchNodes);
            folder = stateFolder(root);
            if (folder == null || folder.getHandle() != handle) throw new IOException("No se pudo confirmar la carpeta de progreso de MEGA");
        }
        Map<String, Progress> merged = new HashMap<>();
        int devices = 0; long transferred = 0;
        for (MegaNode node : api.getChildren(folder)) {
            if (!node.isFile() || node.getName() == null || !node.getName().matches("[0-9a-f-]{36}\\.crp")) continue;
            if (++devices > 128) throw new IOException("Más de 128 instantáneas MEGA; archiva las antiguas");
            transferred += node.getSize();
            if (transferred > 64L * 1024 * 1024) throw new IOException("Las instantáneas MEGA superan 64 MiB por sincronización");
            File downloaded = receive(node, Progress.MAX_BYTES);
            try {
                for (Progress state : Progress.decode(Files.readAllBytes(downloaded.toPath()))) {
                    Progress old = merged.get(state.id);
                    merged.put(state.id, old == null ? state : Progress.latest(old, state));
                    if (merged.size() > Progress.MAX_BOOKS) throw new IOException("Demasiados cómics en el progreso remoto");
                }
            } finally { eraseTemporary(downloaded); }
        }
        Library library = Library.get(context);
        library.merge(merged.values());
        File snapshot = File.createTempFile("mega-state-", ".crp", context.getCacheDir());
        Transfer transfer = null;
        try {
            try (FileOutputStream out = new FileOutputStream(snapshot)) {
                out.write(Progress.encode(library.states())); out.getFD().sync();
            }
            MegaNode destination = folder;
            transfer = new Transfer(snapshot, Progress.MAX_BYTES, true);
            pending = transfer;
            try {
                MegaUploadOptions options = MegaUploadOptions.createInstance();
                options.setFileName(library.device + ".crp");
                api.startUpload(snapshot.getAbsolutePath(), destination, transfer.token, options, transfer);
                transfer.await();
            } catch (Exception e) { transfer.cancel(); throw e; }
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
                throw new IOException("Hay nombres duplicados o incompatibles en la carpeta de progreso MEGA; corrígelos antes de sincronizar");
            found = node;
        }
        return found;
    }
    synchronized void logout() throws Exception {
        worker();
        Exception revocation = null;
        try {
            if (pending != null) { pending.cancel(); throw new IOException("Transferencia MEGA en proceso de cancelación"); }
            MegaApiJava sdk = api();
            if (sdk.isLoggedIn() == 0) {
                String session = Secrets.get(context, SESSION);
                if (session != null && !session.isEmpty()) request(listener -> sdk.fastLogin(session, listener));
            }
            if (sdk.isLoggedIn() != 0) request(sdk::logout);
        } catch (Exception e) { revocation = e; }
        ready = false;
        Secrets.remove(context, SESSION);
        if (!preferences().edit().clear().commit()) throw new IOException("No se pudo eliminar el estado local de MEGA");
        if (revocation != null) {
            if (api != null) api.localLogout();
            throw new IOException("Sesión local de MEGA eliminada; no se pudo confirmar la revocación remota", revocation);
        }
    }
    synchronized void suspend() throws Exception {
        worker();
        if (api == null) return;
        if (pending != null || pendingRequest != null) throw new IOException("MEGA no puede desconectarse mientras termina una operación");
        if (api.isLoggedIn() != 0) request(api::localLogout);
        ready = false;
    }
    private long request(Consumer<MegaRequestListenerInterface> start) throws Exception {
        Request request = new Request();
        pendingRequest = request;
        try { start.accept(request); }
        catch (RuntimeException e) { pendingRequest = null; throw e; }
        try {
            if (!request.done.await(120, TimeUnit.SECONDS))
                throw new IOException("MEGA no respondió en dos minutos" + request.temporary());
            if (request.error != MegaError.API_OK) throw error(request.error);
            return request.handle;
        } catch (Exception e) { request.abandon(); throw e; }
    }
    private static IOException error(int code) {
        return new IOException(switch (code) {
            case MegaError.API_EMFAREQUIRED -> "MEGA requiere el código de verificación de dos factores";
            case MegaError.API_ESID -> "La sesión de MEGA ha caducado; cierra sesión y vuelve a entrar";
            case MegaError.API_EACCESS -> "MEGA denegó el acceso; comprueba tus credenciales o permisos";
            case MegaError.API_EOVERQUOTA -> "Se ha agotado la cuota de transferencia de MEGA";
            default -> "MEGA rechazó la operación (código " + code + ")";
        });
    }
    private static void eraseTemporary(File file) {
        if (file.exists() && !file.delete()) android.util.Log.w("ComicReader", "No se pudo borrar un temporal de MEGA");
    }
    private final class Request implements MegaRequestListenerInterface {
        final CountDownLatch done = new CountDownLatch(1);
        int error; volatile int lastTemporary; long handle;
        private boolean finished, abandoned, successfulLogin;
        synchronized void abandon() {
            abandoned = true;
            if (finished && successfulLogin) disconnectLateLogin();
        }
        private void disconnectLateLogin() {
            ready = false;
            Request cleanup = new Request(); pendingRequest = cleanup;
            api.localLogout(cleanup);
        }
        String temporary() { return lastTemporary == 0 ? "" : " (último error temporal " + lastTemporary + ")"; }
        @Override public void onRequestStart(MegaApiJava api, MegaRequest request) { }
        @Override public void onRequestUpdate(MegaApiJava api, MegaRequest request) { }
        @Override public void onRequestTemporaryError(MegaApiJava api, MegaRequest request, MegaError error) { lastTemporary = error.getErrorCode(); }
        @Override public synchronized void onRequestFinish(MegaApiJava sdk, MegaRequest request, MegaError result) {
            error = result.getErrorCode(); handle = request.getNodeHandle(); finished = true;
            successfulLogin = request.getType() == MegaRequest.TYPE_LOGIN && error == MegaError.API_OK;
            if (pendingRequest == this) pendingRequest = null;
            if (abandoned && successfulLogin) disconnectLateLogin();
            done.countDown();
        }
    }
    private final class Transfer implements MegaTransferListenerInterface {
        final CountDownLatch done = new CountDownLatch(1);
        final MegaCancelToken token = MegaCancelToken.createInstance();
        final TransferFile lifetime; final long limit;
        volatile boolean tooLarge;
        int result; volatile int lastTemporary;
        Transfer(File file, long limit, boolean upload) { this.lifetime = new TransferFile(file, upload); this.limit = limit; }
        void await() throws Exception {
            // shortcut: a transfer has ten minutes, use a foreground resumable download service for longer jobs.
            if (!done.await(10, TimeUnit.MINUTES)) throw new IOException("MEGA superó los diez minutos de transferencia (último error temporal " + lastTemporary + ")");
            if (tooLarge) throw new IOException("La transferencia de MEGA supera el tamaño permitido");
            if (result != MegaError.API_OK) throw error(result);
        }
        void cancel() {
            lifetime.discard(); token.cancel();
        }
        @Override public void onTransferStart(MegaApiJava api, MegaTransfer transfer) { }
        @Override public void onTransferUpdate(MegaApiJava api, MegaTransfer transfer) {
            if (transfer.getTransferredBytes() > limit || transfer.getTotalBytes() > limit) { tooLarge = true; token.cancel(); }
        }
        @Override public void onTransferTemporaryError(MegaApiJava api, MegaTransfer transfer, MegaError error) { lastTemporary = error.getErrorCode(); }
        @Override public void onTransferFinish(MegaApiJava api, MegaTransfer transfer, MegaError error) {
            result = error.getErrorCode();
            lifetime.complete(result == MegaError.API_OK);
            pending = null; done.countDown();
        }
        @Override public boolean onTransferData(MegaApiJava api, MegaTransfer transfer, byte[] buffer) { return false; }
        @Override public void onFolderTransferUpdate(MegaApiJava api, MegaTransfer transfer, int stage,
                long folderCount, long createdFolderCount, long fileCount, String currentFolder, String currentFileLeafName) { }
        @Override public void onTransferQuotaState(MegaApiJava api, MegaTransfer transfer, int state) { }
    }
    record Node(long handle, String name, boolean directory, long size) { }
}
