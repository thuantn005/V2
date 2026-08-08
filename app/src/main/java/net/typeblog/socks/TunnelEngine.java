package net.typeblog.socks;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;

/**
 * Manages the embedded Brainfuck-Psiphon tunnel engine (libbrainfuck.so), a Go
 * program adapted from aztecrabbit/brainfuck-psiphon-pro-go.
 *
 * The engine exposes a local SOCKS5 proxy (default 127.0.0.1:3080) backed by one
 * or more Psiphon tunnels. SocksDroid's tun2socks then forwards all VPN traffic
 * to that port, so the whole device is tunnelled through Psiphon.
 */
public class TunnelEngine {
    private static final String TAG = "TunnelEngine";

    public static final String LOCAL_ADDR = "127.0.0.1";
    public static final int LOCAL_PORT = 3080;

    private final Context mContext;
    private Process mProcess;

    public TunnelEngine(Context context) {
        mContext = context;
    }

    /** Copy the bundled Psiphon embedded server list out of assets, overwriting
     *  any older copy so app updates ship a fresh server list. It is imported by
     *  psiphon-tunnel-core via its -serverList flag. */
    private String prepareServerList() {
        File out = new File(mContext.getFilesDir(), "server_list");
        AssetManager am = mContext.getAssets();
        try (InputStream in = am.open("server_list");
             OutputStream os = new FileOutputStream(out)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
            }
        } catch (Exception e) {
            Log.w(TAG, "could not copy bundled server list: " + e.getMessage());
            return "";
        }
        return out.getAbsolutePath();
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) {
            return;
        }
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) {
                deleteRecursive(k);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    /**
     * Start the engine.
     *
     * @param payload   HTTP injector payload ("bug host"), empty to disable
     * @param sni       Server Name Indication host, empty for default
     * @param region    Psiphon egress region (e.g. "sg"), empty for auto
     * @param cores     number of parallel Psiphon tunnels
     * @param injectType injector type (0 = direct, 2 = SNI/payload rewrite)
     * @param protocols comma separated LimitTunnelProtocols, empty for default
     * @return true if the engine process started and the SOCKS port came up
     */
    public boolean start(String payload, String sni, String region, int cores,
                         int injectType, String protocols, String whitelist, String front) {
        String bin = mContext.getApplicationInfo().nativeLibraryDir + "/libbrainfuck.so";
        if (!new File(bin).exists()) {
            Log.e(TAG, "engine binary missing: " + bin);
            return false;
        }

        // Kill any engine/psiphon process left over from a previous session,
        // otherwise the injector fails with "bind: address already in use".
        killStale();

        File filesDir = mContext.getFilesDir();

        // Wipe any stale per-core datastore so Psiphon re-seeds from the current
        // bundled server list (otherwise an old, dead server list would linger
        // across app updates and the tunnel would never connect).
        deleteRecursive(new File(filesDir, "brainfuck-psiphon-pro-go"));

        String serverList = prepareServerList();

        ProcessBuilder pb = new ProcessBuilder(bin);
        pb.directory(filesDir);
        pb.redirectErrorStream(true);

        Map<String, String> env = pb.environment();
        env.put("HOME", filesDir.getAbsolutePath());
        env.put("BF_CONFIG_HOME", filesDir.getAbsolutePath());
        if (!serverList.isEmpty()) {
            env.put("BF_SERVERLIST", serverList);
        }
        env.put("BF_CORE_NAME", "libpsiphon.so");
        env.put("BF_ROTATOR_PORT", String.valueOf(LOCAL_PORT));
        env.put("BF_CORES", String.valueOf(Math.max(1, cores)));
        env.put("BF_INJECT_TYPE", String.valueOf(injectType));
        if (payload != null && !payload.isEmpty()) {
            env.put("BF_PAYLOAD", payload);
        }
        if (sni != null && !sni.isEmpty()) {
            env.put("BF_SNI", sni);
        }
        if (region != null && !region.isEmpty()) {
            env.put("BF_REGION", region);
        }
        if (protocols != null && !protocols.isEmpty()) {
            env.put("BF_PROTOCOLS", protocols);
        }
        if (whitelist != null && !whitelist.isEmpty()) {
            env.put("BF_WHITELIST", whitelist);
        }
        if (front != null && !front.isEmpty()) {
            env.put("BF_FRONT", front);
        }

        try {
            mProcess = pb.start();
        } catch (Exception e) {
            Log.e(TAG, "failed to start engine: " + e.getMessage());
            return false;
        }

        // Drain output into a log file so the process never blocks on a full pipe.
        final Process p = mProcess;
        new Thread(() -> {
            File log = new File(filesDir, "engine.log");
            try (InputStream in = p.getInputStream();
                 OutputStream os = new FileOutputStream(log)) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) {
                    os.write(buf, 0, n);
                    os.flush();
                }
            } catch (Exception ignored) {
            }
        }, "engine-log").start();

        // Wait for Psiphon to actually establish a tunnel, NOT just for the SOCKS
        // port to open. This matters on Android: if the VPN/tun2socks is brought
        // up while Psiphon is still connecting, Psiphon's own bootstrap traffic to
        // the bug host gets captured by the tun (which has no working proxy yet)
        // and dies with "unexpected EOF" — a deadlock. By waiting for "Connected"
        // first, the tunnel is established with no VPN in the way (exactly like the
        // successful GitHub runner test), and only then does the VpnService start
        // routing.
        return waitForTunnel(150000);
    }

    /** Wait until Psiphon logs an active tunnel ("Connected") or we time out. */
    private boolean waitForTunnel(int timeoutMs) {
        File log = new File(mContext.getFilesDir(), "engine.log");
        long deadline = java.lang.System.currentTimeMillis() + timeoutMs;
        while (java.lang.System.currentTimeMillis() < deadline) {
            if (mProcess != null && !mProcess.isAlive()) {
                Log.e(TAG, "engine exited early; see engine.log");
                return false;
            }
            if (logContains(log, "Connected")) {
                return true;
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ie) {
                return false;
            }
        }
        return false;
    }

    /** True if any line of the file contains the marker. */
    private boolean logContains(File f, String marker) {
        if (!f.exists()) {
            return false;
        }
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = r.readLine()) != null) {
                // "Connecting"/"Reconnecting" do not contain the exact word.
                if (line.contains(marker)) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    public void stop() {
        if (mProcess != null) {
            try {
                mProcess.destroy();
            } catch (Exception ignored) {
            }
            mProcess = null;
        }
        // Reap any child psiphon processes the engine spawned.
        killStale();
    }

    /**
     * Kill leftover engine / psiphon processes belonging to this app. Under the
     * app UID we can only see (and kill) our own processes in /proc, which is
     * exactly what we want.
     */
    private void killStale() {
        int myPid = android.os.Process.myPid();
        File proc = new File("/proc");
        File[] pids = proc.listFiles();
        if (pids == null) {
            return;
        }
        for (File p : pids) {
            String name = p.getName();
            int pid;
            try {
                pid = Integer.parseInt(name);
            } catch (NumberFormatException e) {
                continue; // not a pid directory
            }
            if (pid == myPid) {
                continue;
            }
            String cmdline = readSmall(new File(p, "cmdline"));
            if (cmdline == null) {
                continue;
            }
            if (cmdline.contains("libbrainfuck.so") || cmdline.contains("libpsiphon.so")) {
                try {
                    android.os.Process.killProcess(pid);
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static String readSmall(File f) {
        try (InputStream in = new java.io.FileInputStream(f)) {
            byte[] buf = new byte[4096];
            int n = in.read(buf);
            if (n <= 0) {
                return null;
            }
            // cmdline is NUL-separated; turn NULs into spaces for matching.
            for (int i = 0; i < n; i++) {
                if (buf[i] == 0) {
                    buf[i] = ' ';
                }
            }
            return new String(buf, 0, n);
        } catch (Exception e) {
            return null;
        }
    }
}
