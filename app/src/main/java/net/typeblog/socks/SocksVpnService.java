package net.typeblog.socks;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.text.TextUtils;
import android.util.Log;

import net.typeblog.socks.util.Routes;
import net.typeblog.socks.util.Utility;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Objects;

import static net.typeblog.socks.util.Constants.*;
import static net.typeblog.socks.BuildConfig.DEBUG;

public class SocksVpnService extends VpnService {
    class VpnBinder extends IVpnService.Stub {
        @Override
        public boolean isRunning() {
            return mRunning;
        }

        @Override
        public void stop() {
            stopMe();
        }
    }

    private static final String TAG = SocksVpnService.class.getSimpleName();

    private ParcelFileDescriptor mInterface;
    private boolean mRunning = false;
    private boolean mStarting = false;
    private TunnelEngine mEngine;
    private final IBinder mBinder = new VpnBinder();

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        if (DEBUG) {
            Log.d(TAG, "starting");
        }

        if (intent == null) {
            return START_STICKY;
        }

        if (mRunning || mStarting) {
            return START_STICKY;
        }
        mStarting = true;

        final String name = intent.getStringExtra(INTENT_NAME);
        // The embedded Brainfuck-Psiphon engine always exposes its SOCKS5 proxy
        // locally, so tun2socks is pointed at 127.0.0.1:3080 regardless of the
        // server/port fields in the UI.
        final String server = TunnelEngine.LOCAL_ADDR;
        final int port = TunnelEngine.LOCAL_PORT;
        final String username = intent.getStringExtra(INTENT_USERNAME);
        final String passwd = intent.getStringExtra(INTENT_PASSWORD);
        final String route = intent.getStringExtra(INTENT_ROUTE);
        final String dns = intent.getStringExtra(INTENT_DNS);
        final int dnsPort = intent.getIntExtra(INTENT_DNS_PORT, 53);
        final boolean perApp = intent.getBooleanExtra(INTENT_PER_APP, false);
        final boolean appBypass = intent.getBooleanExtra(INTENT_APP_BYPASS, false);
        final String[] appList = intent.getStringArrayExtra(INTENT_APP_LIST);
        final boolean ipv6 = intent.getBooleanExtra(INTENT_IPV6_PROXY, false);
        final String udpgw = intent.getStringExtra(INTENT_UDP_GW);

        final String bfPayload = intent.getStringExtra(INTENT_BF_PAYLOAD);
        final String bfSni = intent.getStringExtra(INTENT_BF_SNI);
        final String bfRegion = intent.getStringExtra(INTENT_BF_REGION);
        final int bfCores = intent.getIntExtra(INTENT_BF_CORES, 2);
        final int bfInjectType = intent.getIntExtra(INTENT_BF_INJECT_TYPE, 2);
        final String bfProtocols = intent.getStringExtra(INTENT_BF_PROTOCOLS);
        final String bfWhitelist = intent.getStringExtra(INTENT_BF_WHITELIST);
        final String bfFront = intent.getStringExtra(INTENT_BF_FRONT);

        // Notifications on Oreo and above need a channel
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= 26) {
            String NOTIFICATION_CHANNEL_ID = "net.typeblog.socks";
            NotificationChannel channel = new NotificationChannel(NOTIFICATION_CHANNEL_ID,
                    getString(R.string.channel_name), NotificationManager.IMPORTANCE_NONE);
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            Objects.requireNonNull(notificationManager).createNotificationChannel(channel);
            builder = new Notification.Builder(this, NOTIFICATION_CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        // Create the notification
        int NOTIFICATION_ID = 1;
        int intentFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            intentFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent contentIntent;
        contentIntent = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), intentFlags);
        startForeground(NOTIFICATION_ID, builder
                .setContentTitle(getString(R.string.notify_title))
                .setContentText(String.format(getString(R.string.notify_msg), name))
                .setPriority(Notification.PRIORITY_MIN)
                .setSmallIcon(R.drawable.ic_vpn)
                .setContentIntent(contentIntent)
                .build());

        // Bringing up the Psiphon tunnel can take tens of seconds, so do all of
        // the heavy lifting off the main thread to avoid an ANR.
        new Thread(() -> {
            mEngine = new TunnelEngine(SocksVpnService.this);
            boolean noBug = getSharedPreferences("bf_settings", MODE_PRIVATE)
                    .getBoolean("no_bug", false);
            boolean up = mEngine.start(bfPayload, bfSni, bfRegion, bfCores, bfInjectType, bfProtocols, bfWhitelist, bfFront, noBug);
            if (!up) {
                Log.e(TAG, "tunnel engine failed to start");
                stopMe();
                return;
            }

            // Create the tun fd.
            configure(name, route, perApp, appBypass, appList, ipv6);

            if (DEBUG)
                Log.d(TAG, "fd: " + mInterface.getFd());

            if (mInterface != null)
                start(mInterface.getFd(), server, port, username, passwd, dns, dnsPort, ipv6, udpgw);

            mStarting = false;
        }, "vpn-startup").start();

        return START_STICKY;
    }

    @Override
    public void onRevoke() {
        super.onRevoke();
        stopMe();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        stopMe();
    }

    private void stopMe() {
        mStarting = false;
        stopForeground(true);

        // Stop the tun2socks core first so it lets go of the tun fd.
        try {
            HevTunnel.TProxyStopService();
        } catch (Throwable t) {
            Log.w(TAG, "hev stop: " + t.getMessage());
        }

        if (mEngine != null) {
            mEngine.stop();
            mEngine = null;
        }

        try {
            if (mInterface != null) {
                System.jniclose(mInterface.getFd());
                mInterface.close();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        stopSelf();
    }

    private void configure(String name, String route, boolean perApp, boolean bypass, String[] apps, boolean ipv6) {
        Builder b = new Builder();
        // A large tun MTU lets the local stack hand hev fewer, bigger packets,
        // cutting per-packet overhead and raising throughput (same trick the
        // fast sing-box configs use). hev's tunnel mtu below must match.
        b.setMtu(8500)
                .setSession(name)
                .addAddress("26.26.26.1", 24)
                .addDnsServer("8.8.8.8");

        if (ipv6) {
            // Route all IPv6 traffic
            b.addAddress("fdfe:dcba:9876::1", 126)
                    .addRoute("::", 0);
        }

        Routes.addRoutes(this, b, route);

        // Add the default DNS. This is just a stub the system sends queries to;
        // hev-socks5-tunnel's mapdns intercepts them and resolves names at the
        // proxy exit (over TCP), so no real UDP DNS is needed.
        b.addRoute("8.8.8.8", 32);
        // The mapdns fake-IP pool (240.0.0.0/4, class E — unused on the public
        // internet) must also route into the tun so connections to the mapped
        // addresses reach hev and get turned back into hostname CONNECTs.
        b.addRoute("240.0.0.0", 4);

        // Do app routing
        if (!perApp) {
            // Just bypass myself
            try {
                b.addDisallowedApplication("net.typeblog.socks");
            } catch (Exception e) {
                e.printStackTrace();
            }
        } else {
            if (bypass) {
                // First, bypass myself
                try {
                    b.addDisallowedApplication("net.typeblog.socks");
                } catch (Exception e) {
                    e.printStackTrace();
                }

                for (String p : apps) {
                    if (TextUtils.isEmpty(p))
                        continue;

                    try {
                        b.addDisallowedApplication(p.trim());
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            } else {
                for (String p : apps) {
                    if (TextUtils.isEmpty(p) || p.trim().equals("net.typeblog.socks")) {
                        continue;
                    }

                    try {
                        b.addAllowedApplication(p.trim());
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
        }

        mInterface = b.establish();
    }

    private void start(int fd, String server, int port, String user, String passwd, String dns, int dnsPort, boolean ipv6, String udpgw) {
        // Drive the hev-socks5-tunnel core in-process. Unlike the old badvpn
        // binary this needs no exec(), no unix-socket fd passing and no separate
        // pdnsd daemon: the fd is handed to the native library directly and DNS
        // is resolved through the proxy via mapdns.
        StringBuilder conf = new StringBuilder();
        conf.append("misc:\n")
                .append("  task-stack-size: 20480\n")
                .append("  log-level: none\n")
                .append("tunnel:\n")
                .append("  mtu: 8500\n")
                .append("socks5:\n")
                .append("  port: ").append(port).append("\n")
                .append("  address: '").append(server).append("'\n")
                .append("  udp: 'udp'\n");
        if (user != null && passwd != null) {
            conf.append("  username: '").append(user).append("'\n")
                    .append("  password: '").append(passwd).append("'\n");
        }
        // mapdns must match the stub DNS server advertised in configure()
        // (8.8.8.8:53) and its fake-IP pool the 240.0.0.0/4 route added there.
        conf.append("mapdns:\n")
                .append("  address: 8.8.8.8\n")
                .append("  port: 53\n")
                .append("  network: 240.0.0.0\n")
                .append("  netmask: 240.0.0.0\n")
                .append("  cache-size: 10000\n");

        File confFile = new File(getFilesDir(), "tproxy.conf");
        try (FileOutputStream fos = new FileOutputStream(confFile, false)) {
            fos.write(conf.toString().getBytes());
        } catch (Exception e) {
            Log.e(TAG, "failed to write hev config: " + e.getMessage());
            stopMe();
            return;
        }

        if (DEBUG) {
            Log.d(TAG, "hev config:\n" + conf);
        }

        if (!HevTunnel.TProxyStartService(confFile.getAbsolutePath(), fd)) {
            Log.e(TAG, "hev-socks5-tunnel failed to start");
            stopMe();
            return;
        }

        mRunning = true;
    }
}
