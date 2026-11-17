package com.myapp.mywifidirect.ui;

import android.net.wifi.p2p.WifiP2pDevice;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.myapp.mywifidirect.R;

import java.util.ArrayList;
import java.util.List;

/** Simple RecyclerView adapter for discovered Wi-Fi Direct peers. */
public final class PeerAdapter extends RecyclerView.Adapter<PeerAdapter.Holder> {

    public interface OnConnect {
        void onConnect(WifiP2pDevice device);
    }

    private final List<WifiP2pDevice> data = new ArrayList<>();
    private final OnConnect onConnect;
    /** 全局已连接时禁用所有「连接」按钮，避免重复发起连接。 */
    private boolean connectEnabled = true;

    public PeerAdapter(OnConnect cb) {
        onConnect = cb;
    }

    /** 跟随全局连接状态：已连接时禁用列表项的连接按钮。 */
    public void setConnectEnabled(boolean enabled) {
        if (connectEnabled == enabled) return;
        connectEnabled = enabled;
        notifyDataSetChanged();
    }

    public void submit(List<WifiP2pDevice> peers) {
        data.clear();
        if (peers != null) data.addAll(peers);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_peer, parent, false);
        return new Holder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int pos) {
        WifiP2pDevice d = data.get(pos);
        String name = d.deviceName == null || d.deviceName.isEmpty()
                ? "未知设备" : d.deviceName;
        h.name.setText(name);
        h.info.setText(d.deviceAddress + " · " + status(d.status));
        // 全局已连接时禁用「连接」按钮；单个设备自身已连接时也禁用。
        boolean usable = connectEnabled && d.status != WifiP2pDevice.CONNECTED;
        h.connect.setEnabled(usable);
        h.connect.setAlpha(usable ? 1.0f : 0.5f);
        h.connect.setOnClickListener(v -> {
            if (usable) onConnect.onConnect(d);
        });
    }

    @Override
    public int getItemCount() {
        return data.size();
    }

    private static String status(int s) {
        switch (s) {
            case WifiP2pDevice.CONNECTED: return "已连接";
            case WifiP2pDevice.INVITED: return "邀请中";
            case WifiP2pDevice.FAILED: return "不可用";
            case WifiP2pDevice.AVAILABLE: return "可连接";
            case WifiP2pDevice.UNAVAILABLE: return "不可用";
            default: return "状态 " + s;
        }
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final TextView name, info;
        final Button connect;

        Holder(View v) {
            super(v);
            name = v.findViewById(R.id.peer_name);
            info = v.findViewById(R.id.peer_info);
            connect = v.findViewById(R.id.btn_connect);
        }
    }
}
