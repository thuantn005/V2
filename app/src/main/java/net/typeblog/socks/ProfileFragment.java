package net.typeblog.socks;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Color;
import android.net.VpnService;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.preference.CheckBoxPreference;
import android.preference.EditTextPreference;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.preference.ListPreference;
import android.text.InputType;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MenuInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

import net.typeblog.socks.util.Profile;
import net.typeblog.socks.util.ProfileManager;
import net.typeblog.socks.util.Utility;

import java.util.Locale;

import static net.typeblog.socks.util.Constants.*;

public class ProfileFragment extends PreferenceFragment implements Preference.OnPreferenceClickListener, Preference.OnPreferenceChangeListener {
    private ProfileManager mManager;
    private Profile mProfile;

    private View mPowerButton;
    private TextView mStatusText, mDuration, mDown, mUp;
    private long mConnectTime = 0L;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private boolean mRunning = false;
    private boolean mTunnelUp = false;
    private boolean mStarting = false, mStopping = false;
    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName p1, IBinder binder) {
            mBinder = IVpnService.Stub.asInterface(binder);

            try {
                mRunning = mBinder.isRunning();
            } catch (Exception e) {
                e.printStackTrace();
            }

            if (mRunning) {
                updateState();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName p1) {
            mBinder = null;
        }
    };
    private final Runnable mStateRunnable = new Runnable() {
        @Override
        public void run() {
            updateState();
            mHandler.postDelayed(this, 1000);
        }
    };
    private IVpnService mBinder;

    private ListPreference mPrefProfile, mPrefRoutes, mPrefBfInjectType;
    private EditTextPreference mPrefServer, mPrefPort, mPrefUsername, mPrefPassword,
            mPrefDns, mPrefDnsPort, mPrefAppList, mPrefUDPGW,
            mPrefBfPayload, mPrefBfSni, mPrefBfRegion, mPrefBfCores, mPrefBfProtocols,
            mPrefBfWhitelist, mPrefBfFront;
    private CheckBoxPreference mPrefUserpw, mPrefPerApp, mPrefAppBypass, mPrefIPv6, mPrefUDP, mPrefAuto;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.settings);
        setHasOptionsMenu(true);
        mManager = new ProfileManager(getActivity().getApplicationContext());
        initPreferences();
        reload();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View prefView = super.onCreateView(inflater, container, savedInstanceState);

        LinearLayout root = new LinearLayout(getActivity());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0B0B0D);

        View header = inflater.inflate(R.layout.home, root, false);
        mStatusText = header.findViewById(R.id.txt_status);
        mDuration = header.findViewById(R.id.txt_duration);
        mDown = header.findViewById(R.id.txt_down);
        mUp = header.findViewById(R.id.txt_up);
        mPowerButton = header.findViewById(R.id.btn_power);
        mPowerButton.setOnClickListener(v -> onConnectClicked());
        root.addView(header);

        prefView.setBackgroundColor(0xFF0B0B0D);
        prefView.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(prefView);
        return root;
    }

    @Override
    public void onResume() {
        super.onResume();
        checkState();
        mHandler.removeCallbacks(mStateRunnable);
        mHandler.post(mStateRunnable);
    }

    @Override
    public void onPause() {
        super.onPause();
        mHandler.removeCallbacks(mStateRunnable);
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
        inflater.inflate(R.menu.main, menu);
        MenuItem nobug = menu.findItem(R.id.action_nobug);
        if (nobug != null) {
            nobug.setChecked(isNoBug());
        }
    }

    private boolean isNoBug() {
        return getActivity()
                .getSharedPreferences("bf_settings", Activity.MODE_PRIVATE)
                .getBoolean("no_bug", false);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.prof_add) {
            addProfile();
            return true;
        } else if (id == R.id.prof_del) {
            removeProfile();
            return true;
        } else if (id == R.id.action_log) {
            showLog();
            return true;
        } else if (id == R.id.action_nobug) {
            boolean next = !isNoBug();
            getActivity().getSharedPreferences("bf_settings", Activity.MODE_PRIVATE)
                    .edit().putBoolean("no_bug", next).apply();
            item.setChecked(next);
            Toast.makeText(getActivity(),
                    next ? R.string.nobug_on : R.string.nobug_off,
                    Toast.LENGTH_SHORT).show();
            return true;
        } else {
            return super.onOptionsItemSelected(item);
        }
    }

    @Override
    public boolean onPreferenceClick(Preference p) {
        // TODO: Implement this method
        return false;
    }

    @Override
    public boolean onPreferenceChange(Preference p, Object newValue) {
        if (p == mPrefProfile) {
            String name = newValue.toString();
            mProfile = mManager.getProfile(name);
            mManager.switchDefault(name);
            reload();
            return true;
        } else if (p == mPrefServer) {
            mProfile.setServer(newValue.toString());
            resetTextN(mPrefServer, newValue);
            return true;
        } else if (p == mPrefPort) {
            if (TextUtils.isEmpty(newValue.toString()))
                return false;

            mProfile.setPort(Integer.parseInt(newValue.toString()));
            resetTextN(mPrefPort, newValue);
            return true;
        } else if (p == mPrefUserpw) {
            mProfile.setIsUserpw(Boolean.parseBoolean(newValue.toString()));
            return true;
        } else if (p == mPrefUsername) {
            mProfile.setUsername(newValue.toString());
            resetTextN(mPrefUsername, newValue);
            return true;
        } else if (p == mPrefPassword) {
            mProfile.setPassword(newValue.toString());
            resetTextN(mPrefPassword, newValue);
            return true;
        } else if (p == mPrefRoutes) {
            mProfile.setRoute(newValue.toString());
            resetListN(mPrefRoutes, newValue);
            return true;
        } else if (p == mPrefDns) {
            mProfile.setDns(newValue.toString());
            resetTextN(mPrefDns, newValue);
            return true;
        } else if (p == mPrefDnsPort) {
            if (TextUtils.isEmpty(newValue.toString()))
                return false;

            mProfile.setDnsPort(Integer.parseInt(newValue.toString()));
            resetTextN(mPrefDnsPort, newValue);
            return true;
        } else if (p == mPrefPerApp) {
            mProfile.setIsPerApp(Boolean.parseBoolean(newValue.toString()));
            return true;
        } else if (p == mPrefAppBypass) {
            mProfile.setIsBypassApp(Boolean.parseBoolean(newValue.toString()));
            return true;
        } else if (p == mPrefAppList) {
            mProfile.setAppList(newValue.toString());
            return true;
        } else if (p == mPrefIPv6) {
            mProfile.setHasIPv6(Boolean.parseBoolean(newValue.toString()));
            return true;
        } else if (p == mPrefUDP) {
            mProfile.setHasUDP(Boolean.parseBoolean(newValue.toString()));
            return true;
        } else if (p == mPrefUDPGW) {
            mProfile.setUDPGW(newValue.toString());
            resetTextN(mPrefUDPGW, newValue);
            return true;
        } else if (p == mPrefAuto) {
            mProfile.setAutoConnect(Boolean.parseBoolean(newValue.toString()));
            return true;
        } else if (p == mPrefBfPayload) {
            mProfile.setBfPayload(newValue.toString());
            resetTextN(mPrefBfPayload, newValue);
            return true;
        } else if (p == mPrefBfSni) {
            mProfile.setBfSni(newValue.toString());
            resetTextN(mPrefBfSni, newValue);
            return true;
        } else if (p == mPrefBfRegion) {
            mProfile.setBfRegion(newValue.toString());
            resetTextN(mPrefBfRegion, newValue);
            return true;
        } else if (p == mPrefBfCores) {
            if (TextUtils.isEmpty(newValue.toString()))
                return false;
            mProfile.setBfCores(newValue.toString());
            resetTextN(mPrefBfCores, newValue);
            return true;
        } else if (p == mPrefBfProtocols) {
            mProfile.setBfProtocols(newValue.toString());
            resetTextN(mPrefBfProtocols, newValue);
            return true;
        } else if (p == mPrefBfWhitelist) {
            mProfile.setBfWhitelist(newValue.toString());
            resetTextN(mPrefBfWhitelist, newValue);
            return true;
        } else if (p == mPrefBfFront) {
            mProfile.setBfFront(newValue.toString());
            resetTextN(mPrefBfFront, newValue);
            return true;
        } else if (p == mPrefBfInjectType) {
            mProfile.setBfInjectType(newValue.toString());
            resetListN(mPrefBfInjectType, mPrefBfInjectType.getEntries()[
                    mPrefBfInjectType.findIndexOfValue(newValue.toString())]);
            return true;
        } else {
            return false;
        }
    }

    private void onConnectClicked() {
        if (mRunning || mStarting) {
            stopVpn();
        } else {
            startVpn();
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (resultCode == Activity.RESULT_OK) {
            Utility.startVpn(getActivity(), mProfile);
            checkState();
            // Pop the live log right away so the user watches the connection
            // progress on screen without opening the menu.
            showLog();
        }
    }

    private void initPreferences() {
        mPrefProfile = (ListPreference) findPreference(PREF_PROFILE);
        mPrefServer = (EditTextPreference) findPreference(PREF_SERVER_IP);
        mPrefPort = (EditTextPreference) findPreference(PREF_SERVER_PORT);
        mPrefUserpw = (CheckBoxPreference) findPreference(PREF_AUTH_USERPW);
        mPrefUsername = (EditTextPreference) findPreference(PREF_AUTH_USERNAME);
        mPrefPassword = (EditTextPreference) findPreference(PREF_AUTH_PASSWORD);
        mPrefRoutes = (ListPreference) findPreference(PREF_ADV_ROUTE);
        mPrefDns = (EditTextPreference) findPreference(PREF_ADV_DNS);
        mPrefDnsPort = (EditTextPreference) findPreference(PREF_ADV_DNS_PORT);
        mPrefPerApp = (CheckBoxPreference) findPreference(PREF_ADV_PER_APP);
        mPrefAppBypass = (CheckBoxPreference) findPreference(PREF_ADV_APP_BYPASS);
        mPrefAppList = (EditTextPreference) findPreference(PREF_ADV_APP_LIST);
        mPrefIPv6 = (CheckBoxPreference) findPreference(PREF_IPV6_PROXY);
        mPrefUDP = (CheckBoxPreference) findPreference(PREF_UDP_PROXY);
        mPrefUDPGW = (EditTextPreference) findPreference(PREF_UDP_GW);
        mPrefAuto = (CheckBoxPreference) findPreference(PREF_ADV_AUTO_CONNECT);
        mPrefBfPayload = (EditTextPreference) findPreference(PREF_BF_PAYLOAD);
        mPrefBfSni = (EditTextPreference) findPreference(PREF_BF_SNI);
        mPrefBfRegion = (EditTextPreference) findPreference(PREF_BF_REGION);
        mPrefBfCores = (EditTextPreference) findPreference(PREF_BF_CORES);
        mPrefBfProtocols = (EditTextPreference) findPreference(PREF_BF_PROTOCOLS);
        mPrefBfInjectType = (ListPreference) findPreference(PREF_BF_INJECT_TYPE);
        mPrefBfWhitelist = (EditTextPreference) findPreference(PREF_BF_WHITELIST);
        mPrefBfFront = (EditTextPreference) findPreference(PREF_BF_FRONT);

        mPrefProfile.setOnPreferenceChangeListener(this);
        mPrefServer.setOnPreferenceChangeListener(this);
        mPrefPort.setOnPreferenceChangeListener(this);
        mPrefUserpw.setOnPreferenceChangeListener(this);
        mPrefUsername.setOnPreferenceChangeListener(this);
        mPrefPassword.setOnPreferenceChangeListener(this);
        mPrefRoutes.setOnPreferenceChangeListener(this);
        mPrefDns.setOnPreferenceChangeListener(this);
        mPrefDnsPort.setOnPreferenceChangeListener(this);
        mPrefPerApp.setOnPreferenceChangeListener(this);
        mPrefAppBypass.setOnPreferenceChangeListener(this);
        mPrefAppList.setOnPreferenceChangeListener(this);
        mPrefIPv6.setOnPreferenceChangeListener(this);
        mPrefUDP.setOnPreferenceChangeListener(this);
        mPrefUDPGW.setOnPreferenceChangeListener(this);
        mPrefAuto.setOnPreferenceChangeListener(this);
        mPrefBfPayload.setOnPreferenceChangeListener(this);
        mPrefBfSni.setOnPreferenceChangeListener(this);
        mPrefBfRegion.setOnPreferenceChangeListener(this);
        mPrefBfCores.setOnPreferenceChangeListener(this);
        mPrefBfProtocols.setOnPreferenceChangeListener(this);
        mPrefBfInjectType.setOnPreferenceChangeListener(this);
        mPrefBfWhitelist.setOnPreferenceChangeListener(this);
        mPrefBfFront.setOnPreferenceChangeListener(this);
    }

    private void reload() {
        if (mProfile == null) {
            mProfile = mManager.getDefault();
        }

        mPrefProfile.setEntries(mManager.getProfiles());
        mPrefProfile.setEntryValues(mManager.getProfiles());
        mPrefProfile.setValue(mProfile.getName());
        mPrefRoutes.setValue(mProfile.getRoute());
        resetList(mPrefProfile, mPrefRoutes);

        mPrefUserpw.setChecked(mProfile.isUserPw());
        mPrefPerApp.setChecked(mProfile.isPerApp());
        mPrefAppBypass.setChecked(mProfile.isBypassApp());
        mPrefIPv6.setChecked(mProfile.hasIPv6());
        mPrefUDP.setChecked(mProfile.hasUDP());
        mPrefAuto.setChecked(mProfile.autoConnect());

        mPrefServer.setText(mProfile.getServer());
        mPrefPort.setText(String.valueOf(mProfile.getPort()));
        mPrefUsername.setText(mProfile.getUsername());
        mPrefPassword.setText(mProfile.getPassword());
        mPrefDns.setText(mProfile.getDns());
        mPrefDnsPort.setText(String.valueOf(mProfile.getDnsPort()));
        mPrefUDPGW.setText(mProfile.getUDPGW());
        mPrefBfPayload.setText(mProfile.getBfPayload());
        mPrefBfSni.setText(mProfile.getBfSni());
        mPrefBfRegion.setText(mProfile.getBfRegion());
        mPrefBfCores.setText(String.valueOf(mProfile.getBfCores()));
        mPrefBfProtocols.setText(mProfile.getBfProtocols());
        mPrefBfWhitelist.setText(mProfile.getBfWhitelist());
        mPrefBfFront.setText(mProfile.getBfFront());
        resetText(mPrefServer, mPrefPort, mPrefUsername, mPrefPassword, mPrefDns, mPrefDnsPort, mPrefUDPGW,
                mPrefBfPayload, mPrefBfSni, mPrefBfRegion, mPrefBfCores, mPrefBfProtocols,
                mPrefBfWhitelist, mPrefBfFront);

        mPrefBfInjectType.setValue(String.valueOf(mProfile.getBfInjectType()));
        resetList(mPrefBfInjectType);

        mPrefAppList.setText(mProfile.getAppList());
    }

    private void resetList(ListPreference... pref) {
        for (ListPreference p : pref)
            p.setSummary(p.getEntry());
    }

    private void resetListN(ListPreference pref, Object newValue) {
        pref.setSummary(newValue.toString());
    }

    private void resetText(EditTextPreference... pref) {
        for (EditTextPreference p : pref) {
            if ((p.getEditText().getInputType() & InputType.TYPE_TEXT_VARIATION_PASSWORD) != InputType.TYPE_TEXT_VARIATION_PASSWORD) {
                p.setSummary(p.getText());
            } else {
                if (p.getText().length() > 0)
                    p.setSummary(String.format(Locale.US,
                            String.format(Locale.US, "%%0%dd", p.getText().length()), 0)
                            .replace("0", "*"));
                else
                    p.setSummary("");
            }
        }
    }

    private void resetTextN(EditTextPreference pref, Object newValue) {
        if ((pref.getEditText().getInputType() & InputType.TYPE_TEXT_VARIATION_PASSWORD) != InputType.TYPE_TEXT_VARIATION_PASSWORD) {
            pref.setSummary(newValue.toString());
        } else {
            String text = newValue.toString();
            if (text.length() > 0)
                pref.setSummary(String.format(Locale.US,
                        String.format(Locale.US, "%%0%dd", text.length()), 0)
                        .replace("0", "*"));
            else
                pref.setSummary("");
        }
    }

    private void addProfile() {
        final EditText e = new EditText(getActivity());
        e.setSingleLine(true);

        new AlertDialog.Builder(getActivity())
                .setTitle(R.string.prof_add)
                .setView(e)
                .setPositiveButton(android.R.string.ok, (d, which) -> {
                    String name = e.getText().toString().trim();

                    if (!TextUtils.isEmpty(name)) {
                        Profile p = mManager.addProfile(name);

                        if (p != null) {
                            mProfile = p;
                            reload();
                            return;
                        }
                    }

                    Toast.makeText(getActivity(),
                            String.format(getString(R.string.err_add_prof), name),
                            Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(android.R.string.cancel, (d, which) -> {

                })
                .create().show();
    }

    private void removeProfile() {
        new AlertDialog.Builder(getActivity())
                .setTitle(R.string.prof_del)
                .setMessage(String.format(getString(R.string.prof_del_confirm), mProfile.getName()))
                .setPositiveButton(android.R.string.ok, (d, which) -> {
                    if (!mManager.removeProfile(mProfile.getName())) {
                        Toast.makeText(getActivity(),
                                getString(R.string.err_del_prof, mProfile.getName()),
                                Toast.LENGTH_SHORT).show();
                    } else {
                        mProfile = mManager.getDefault();
                        reload();
                    }
                })
                .setNegativeButton(android.R.string.cancel, (d, which) -> {

                })
                .create().show();
    }

    private String readLog() {
        File f = new File(getActivity().getFilesDir(), "engine.log");
        if (!f.exists() || f.length() == 0) {
            return getString(R.string.log_empty);
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } catch (Exception e) {
            return "Error reading log: " + e.getMessage();
        }
        // The Go engine emits ANSI colour codes; strip them for readability.
        return sb.toString().replaceAll("\\[[;\\d]*m", "");
    }

    private void showLog() {
        final TextView tv = new TextView(getActivity());
        tv.setText(readLog());
        tv.setTextSize(11);
        tv.setPadding(24, 24, 24, 24);
        tv.setTextIsSelectable(true);

        final ScrollView sv = new ScrollView(getActivity());
        sv.addView(tv);

        final AlertDialog dialog = new AlertDialog.Builder(getActivity())
                .setTitle(R.string.log_title)
                .setView(sv)
                .setNegativeButton(R.string.log_close, null)
                .create();

        // Live tail: re-read the log every second and keep it scrolled to the
        // bottom, so it updates on its own with no button to press.
        final Runnable refresh = new Runnable() {
            @Override
            public void run() {
                tv.setText(readLog());
                sv.post(() -> sv.fullScroll(ScrollView.FOCUS_DOWN));
                mHandler.postDelayed(this, 1000);
            }
        };
        dialog.setOnShowListener(d -> mHandler.post(refresh));
        dialog.setOnDismissListener(d -> mHandler.removeCallbacks(refresh));

        dialog.show();
        sv.post(() -> sv.fullScroll(ScrollView.FOCUS_DOWN));
    }

    private void checkState() {
        if (mBinder == null) {
            getActivity().bindService(new Intent(getActivity(), SocksVpnService.class), mConnection, 0);
        }
    }

    /** True once Psiphon reports an active tunnel (logged as "Connected"). */
    private boolean tunnelConnected() {
        File f = new File(getActivity().getFilesDir(), "engine.log");
        if (!f.exists() || f.length() == 0) {
            return false;
        }
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = r.readLine()) != null) {
                // "Connecting" / "Reconnecting" do not contain the exact word.
                if (line.contains("Connected")) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private void updateState() {
        if (mPowerButton == null || mStatusText == null) {
            return;
        }

        if (mBinder == null) {
            mRunning = false;
        } else {
            try {
                mRunning = mBinder.isRunning();
            } catch (Exception e) {
                mRunning = false;
            }
        }

        if (!mRunning) {
            mTunnelUp = false;
        } else if (!mTunnelUp) {
            mTunnelUp = tunnelConnected();
        }

        String status;
        int color;
        int ring;

        if (mStarting && !mRunning) {
            status = getString(R.string.state_starting);
            color = 0xFFF5A623;
            ring = R.drawable.power_ring_connecting;
        } else if (mRunning) {
            if (mTunnelUp) {
                status = getString(R.string.state_connected);
                color = 0xFF22E06B;
                ring = R.drawable.power_ring_on;
            } else {
                status = getString(R.string.state_connecting);
                color = 0xFFF5A623;
                ring = R.drawable.power_ring_connecting;
            }
        } else if (mStopping) {
            status = getString(R.string.state_stopping);
            color = 0xFFF5A623;
            ring = R.drawable.power_ring_connecting;
        } else {
            status = getString(R.string.state_off);
            color = 0xFF8A8A90;
            ring = R.drawable.power_ring_off;
        }

        mStatusText.setText(status);
        mStatusText.setTextColor(color);
        mPowerButton.setBackgroundResource(ring);

        // Duration timer, running from the moment the tunnel is up.
        boolean connected = mRunning && mTunnelUp;
        if (connected) {
            if (mConnectTime == 0L) {
                mConnectTime = java.lang.System.currentTimeMillis();
            }
        } else {
            mConnectTime = 0L;
        }
        if (mDuration != null) {
            if (mConnectTime > 0L) {
                long s = (java.lang.System.currentTimeMillis() - mConnectTime) / 1000L;
                mDuration.setText(String.format(Locale.US, "%02d:%02d:%02d",
                        s / 3600, (s % 3600) / 60, s % 60));
            } else {
                mDuration.setText("00:00:00");
            }
        }

        // Traffic counters (this app's UID = the engine's real tunnel traffic).
        if (mDown != null && mUp != null) {
            long rx = android.net.TrafficStats.getUidRxBytes(android.os.Process.myUid());
            long tx = android.net.TrafficStats.getUidTxBytes(android.os.Process.myUid());
            mDown.setText("↓ " + humanBytes(rx));
            mUp.setText("↑ " + humanBytes(tx));
        }

        if (mStarting && mRunning) {
            mStarting = false;
        }
        if (mStopping && !mRunning) {
            mStopping = false;
        }
    }

    private static String humanBytes(long b) {
        if (b < 1024) {
            return b + " B";
        }
        double kb = b / 1024.0;
        if (kb < 1024) {
            return String.format(Locale.US, "%.1f KB", kb);
        }
        double mb = kb / 1024.0;
        if (mb < 1024) {
            return String.format(Locale.US, "%.1f MB", mb);
        }
        return String.format(Locale.US, "%.2f GB", mb / 1024.0);
    }

    private void startVpn() {
        mStarting = true;
        mTunnelUp = false;
        Intent i = VpnService.prepare(getActivity());

        if (i != null) {
            startActivityForResult(i, 0);
        } else {
            onActivityResult(0, Activity.RESULT_OK, null);
        }
    }

    private void stopVpn() {
        mStopping = true;
        mTunnelUp = false;

        if (mBinder != null) {
            try {
                mBinder.stop();
            } catch (Exception e) {
                e.printStackTrace();
            }
            mBinder = null;
            try {
                getActivity().unbindService(mConnection);
            } catch (Exception ignored) {
            }
        }

        checkState();
    }
}
