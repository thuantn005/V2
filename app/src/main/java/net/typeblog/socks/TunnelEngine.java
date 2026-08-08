package net.typeblog.socks;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
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

    /** Copy the bundled Psiphon server-list datastore out of assets (once). */
    private String prepareBoltdb() {
        File out = new File(mContext.getFilesDir(), "psiphon.boltdb");
        if (!out.exists() || out.length() == 0) {
            AssetManager am = mContext.getAssets();
            try (InputStream in = am.open("psiphon.boltdb");
                 OutputStream os = new FileOutputStream(out)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    os.write(buf, 0, n);
                }
            } catch (Exception e) {
                Log.w(TAG, "could not copy bundled boltdb: " + e.getMessage());
                return "";
            }
        }
        return out.getAbsolutePath();
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
                         int injectType, String protocols) {
        String bin = mContext.getApplicationInfo().nativeLibraryDir + "/libbrainfuck.so";
        if (!new File(bin).exists()) {
            Log.e(TAG, "engine binary missing: " + bin);
            return false;
        }

        String boltdb = prepareBoltdb();
        File filesDir = mContext.getFilesDir();

        ProcessBuilder pb = new ProcessBuilder(bin);
        pb.directory(filesDir);
        pb.redirectErrorStream(true);

        Map<String, String> env = pb.environment();
        env.put("HOME", filesDir.getAbsolutePath());
        env.put("BF_CONFIG_HOME", filesDir.getAbsolutePath());
        if (!boltdb.isEmpty()) {
            env.put("BF_BOLTDB", boltdb);
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

        return waitForSocks(60000);
    }

    /** Poll the local SOCKS port until it accepts a connection or we time out. */
    private boolean waitForSocks(int timeoutMs) {
        long deadline = java.lang.System.currentTimeMillis() + timeoutMs;
        while (java.lang.System.currentTimeMillis() < deadline) {
            if (mProcess != null && !mProcess.isAlive()) {
                Log.e(TAG, "engine exited early; see engine.log");
                return false;
            }
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(LOCAL_ADDR, LOCAL_PORT), 1000);
                return true;
            } catch (Exception e) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ie) {
                    return false;
                }
            }
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
    }
}
