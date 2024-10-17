package com.myapp.mygps;

import android.Manifest;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.IBinder;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

public class GPXTracker extends Service {
    private static final int REQUEST_LOCATION_PERMISSION = 1;
    private LocationManager locationManager;
    private StringBuilder gpxData;

    @Override
    public void onCreate() {
        super.onCreate();
        gpxData = new StringBuilder();
        gpxData.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        gpxData.append("<gpx version=\"1.1\" creator=\"MyGPXApp\">\n");
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "STOP_SERVICE".equals(intent.getAction())) {
            stopSelf(); // 停止服务
            return START_NOT_STICKY;
        }
        startTracking();
        return START_STICKY;
    }

    private void startTracking() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            // 这里可以考虑使用通知请求权限
            Toast.makeText(this, "请授予位置权限", Toast.LENGTH_SHORT).show();
            stopSelf();
            return;
        }
        requestLocationUpdates();
    }

    private void requestLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 1, locationListener);
        }
    }

    private final LocationListener locationListener = new LocationListener() {
        private boolean isFirstLocationUpdate = true;
        private long lastToastTime = 0; // 记录上次显示 Toast 的时间
        @Override
        public void onLocationChanged(@NonNull Location location) {
            double latitude = location.getLatitude();
            double longitude = location.getLongitude();
            gpxData.append("<wpt lat=\"").append(latitude).append("\" lon=\"").append(longitude).append("\">\n");
            gpxData.append("<name>Point</name>\n");
            gpxData.append("</wpt>\n");

            if (isFirstLocationUpdate) {
                String message = "定位成功！\n纬度: " + latitude + "\n经度: " + longitude;
                Toast.makeText(GPXTracker.this, message, Toast.LENGTH_LONG).show();
                isFirstLocationUpdate = false;
            }

            float speed = location.getSpeed();
            float speedKmh = speed * 3.6f;


            long currentTime = System.currentTimeMillis();
            if (currentTime - lastToastTime > 5000) {
                Toast.makeText(GPXTracker.this, "当前速度: " + speedKmh + " km/h", Toast.LENGTH_SHORT).show();
                lastToastTime = currentTime;
            }

        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {}

        @Override
        public void onProviderEnabled(@NonNull String provider) {}

        @Override
        public void onProviderDisabled(@NonNull String provider) {}
    };

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopTracking();
    }


    private void stopTracking() {
        gpxData.append("</gpx>");
        saveGPXFile();
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            locationManager.removeUpdates(locationListener);
        }
    }

    private void saveGPXFile() {
        File gpxFile = new File(Environment.getExternalStorageDirectory(), "track.gpx");
        try (FileWriter writer = new FileWriter(gpxFile)) {
            writer.write(gpxData.toString());
            Toast.makeText(this, "GPX file saved: " + gpxFile.getAbsolutePath(), Toast.LENGTH_LONG).show();
        } catch (IOException e) {
            e.printStackTrace();
            Toast.makeText(this, "Error saving GPX file", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null; // 这个服务不支持绑定
    }
}
