package net.typeblog.socks;

/**
 * Thin Java binding to the hev-socks5-tunnel native library
 * (libhev-socks5-tunnel.so). This replaces the old badvpn tun2socks executable:
 * instead of exec()'ing a binary and passing the VPN tun fd over a unix socket,
 * hev-socks5-tunnel runs in-process and takes the fd directly.
 *
 * The native methods are registered by the library's own JNI_OnLoad against the
 * class named by the -DPKGNAME / -DCLSNAME build flags (see jni/Application.mk),
 * so the method names and signatures below must match src/hev-jni.c exactly.
 */
public final class HevTunnel {
    static {
        // Explicit java.lang.System: net.typeblog.socks.System shadows it here.
        java.lang.System.loadLibrary("hev-socks5-tunnel");
    }

    private HevTunnel() {
    }

    /** Start tun2socks with the given YAML config file, reading/writing the VPN
     *  tun interface via {@code fd}. Runs on an internal worker thread and
     *  returns immediately. */
    public static native boolean TProxyStartService(String configPath, int fd);

    /** Stop the running tunnel (unblocks the native worker thread). */
    public static native boolean TProxyStopService();

    /** True while a tunnel worker thread is running. */
    public static native boolean TProxyIsRunning();

    /** {tx_packets, tx_bytes, rx_packets, rx_bytes}. */
    public static native long[] TProxyGetStats();
}
