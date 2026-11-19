package app.luno.home;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bluetooth devices connected to the TV (headphones, speakers, gamepads, keyboards), for the top
 * bar. Read once at start, then kept by the system's connect and disconnect broadcasts.
 */
final class Bluetooth extends BroadcastReceiver {
    /** Audio, headsets and input devices (the last is the hidden HID host profile). */
    private static final int[] PROFILES = {BluetoothProfile.A2DP, BluetoothProfile.HEADSET, 4};

    private final Context ctx;
    private final Runnable changed;
    private final Map<String, String> devices = new LinkedHashMap<>(); // address → name
    private boolean on;

    Bluetooth(Context ctx, Runnable changed) {
        this.ctx = ctx;
        this.changed = changed;
    }

    void start() {
        IntentFilter f = new IntentFilter(BluetoothDevice.ACTION_ACL_CONNECTED);
        f.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        f.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        try {
            ctx.registerReceiver(this, f);
            on = true;
        } catch (Exception ignored) { return; }
        BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
        if (a == null || !a.isEnabled()) return;
        for (int profile : PROFILES) {
            try {
                a.getProfileProxy(ctx, new BluetoothProfile.ServiceListener() {
                    @Override public void onServiceConnected(int p, BluetoothProfile proxy) {
                        for (BluetoothDevice d : proxy.getConnectedDevices()) devices.put(d.getAddress(), name(d));
                        a.closeProfileProxy(p, proxy);
                        changed.run();
                    }
                    @Override public void onServiceDisconnected(int p) { }
                }, profile);
            } catch (Exception ignored) { }
        }
    }

    void stop() {
        if (!on) return;
        on = false;
        try { ctx.unregisterReceiver(this); } catch (Exception ignored) { }
    }

    /** "Name", "Name +1" for more devices, or null when none is connected. */
    String label() {
        if (devices.isEmpty()) return null;
        String first = devices.values().iterator().next();
        return devices.size() == 1 ? first : first + " +" + (devices.size() - 1);
    }

    private static String name(BluetoothDevice d) {
        String n = null;
        try { n = d.getName(); } catch (Exception ignored) { }
        return n == null || n.isEmpty() ? "Bluetooth" : n;
    }

    @Override
    public void onReceive(Context c, Intent i) {
        String action = String.valueOf(i.getAction());
        if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
            if (i.getIntExtra(BluetoothAdapter.EXTRA_STATE, 0) != BluetoothAdapter.STATE_ON) devices.clear();
        } else {
            BluetoothDevice d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (d == null) return;
            if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)) devices.put(d.getAddress(), name(d));
            else devices.remove(d.getAddress());
        }
        changed.run();
    }
}
