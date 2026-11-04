import android.bluetooth.BluetoothAdapter;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.os.Looper;
import android.os.Process;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 最小可行性探针：不打包 APK，直接用 app_process 加载 dex，
 * 验证进程身份（root uid=0 / 切换成蓝牙 uid=1002）能否起「可连接广播」。
 *
 * 用法: app_process ... BleSmoke [uid]   // uid 缺省表示不降权
 */
public class BleSmoke {
    public static void main(String[] args) throws Exception {
        System.out.println("[smoke] pid=" + Process.myPid() + " uid=" + android.system.Os.getuid()
                + " gid=" + android.system.Os.getgid());

        if (args.length >= 1) {
            int uid = Integer.parseInt(args[0]);
            android.system.Os.setgid(uid);
            android.system.Os.setuid(uid);
            System.out.println("[smoke] after setuid -> uid=" + android.system.Os.getuid()
                    + " gid=" + android.system.Os.getgid());
        }

        Looper.prepareMainLooper();

        BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
        if (a == null) {
            System.out.println("[smoke] FAIL: getDefaultAdapter()==null (bluetooth_manager 不可达)");
            System.exit(2);
        }
        System.out.println("[smoke] name=" + a.getName() + " addr=" + a.getAddress()
                + " enabled=" + a.isEnabled() + " state=" + a.getState());

        BluetoothLeAdvertiser adv = a.getBluetoothLeAdvertiser();
        if (adv == null) { System.out.println("[smoke] FAIL: advertiser==null"); System.exit(3); }

        AdvertiseSettings st = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setConnectable(true)
                .setTimeout(0)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .build();
        AdvertiseData data = new AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .addManufacturerData(0xABCD, new byte[]{1, 2, 3, 4, 5, 6})
                .build();

        final CountDownLatch failed = new CountDownLatch(1);
        AdvertiseCallback cb = new AdvertiseCallback() {
            @Override public void onStartSuccess(AdvertiseSettings s) {
                System.out.println("[smoke] ADVERTISE_START_SUCCESS mode=" + s.getMode()
                        + " tx=" + s.getTxPowerLevel() + " timeout=" + s.getTimeout());
            }
            @Override public void onStartFailure(int errorCode) {
                System.out.println("[smoke] ADVERTISE_START_FAILURE code=" + errorCode);
                failed.countDown();
            }
        };

        try {
            adv.startAdvertising(st, data, cb);
            System.out.println("[smoke] startAdvertising() 返回，无异常");
        } catch (Throwable t) {
            System.out.println("[smoke] EXCEPTION: " + t);
            t.printStackTrace(System.out);
            System.exit(4);
        }

        boolean f = failed.await(5, TimeUnit.SECONDS);
        if (!f) {
            System.out.println("[smoke] 广播疑似 ACTIVE，保持 3s 后停止");
            Thread.sleep(3000);
        }
        adv.stopAdvertising(cb);
        System.out.println("[smoke] stopped");
        System.exit(0);
    }
}
