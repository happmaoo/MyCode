package com.myapp.mygps;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Environment;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

public class GPXTracker {
    private static final int REQUEST_LOCATION_PERMISSION = 1;
    private LocationManager locationManager;
    private StringBuilder gpxData;
    private Context context;

    public GPXTracker(Context context) {
        this.context = context;
        gpxData = new StringBuilder();
        gpxData.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        gpxData.append("<gpx version=\"1.1\" creator=\"MyGPXApp\">\n");
        locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
    }

    public void startTracking() {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions((MainActivity) context, new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQUEST_LOCATION_PERMISSION);
        } else {
            requestLocationUpdates();
        }
    }

    private void requestLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            // 间隔毫秒数，最短距离
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 1, locationListener);
        }
    }

    private final LocationListener locationListener = new LocationListener() {
        private boolean isFirstLocationUpdate = true;
        @Override
        public void onLocationChanged(@NonNull Location location) {
            double latitude = location.getLatitude();
            double longitude = location.getLongitude();
            gpxData.append("<wpt lat=\"").append(latitude).append("\" lon=\"").append(longitude).append("\">\n");
            gpxData.append("<name>Point</name>\n");
            gpxData.append("</wpt>\n");

            // 仅在第一次获取到位置时提示
            if (isFirstLocationUpdate) {
                String message = "定位成功！\n纬度: " + latitude + "\n经度: " + longitude;
                Toast.makeText(context, message, Toast.LENGTH_LONG).show();
                isFirstLocationUpdate = false; // 设置为false，表示已经提示过
            }

            // 获取速度
            float speed = location.getSpeed();
            float speedKmh = speed * 3.6f;
            Toast.makeText(context, "当前速度: " + speedKmh + " km/h", Toast.LENGTH_SHORT).show();

        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {}

        @Override
        public void onProviderEnabled(@NonNull String provider) {}

        @Override
        public void onProviderDisabled(@NonNull String provider) {}
    };

    public void stopTracking() {
        gpxData.append("</gpx>");
        saveGPXFile();
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            locationManager.removeUpdates(locationListener);
        }
    }

    private void saveGPXFile() {
        File gpxFile = new File(Environment.getExternalStorageDirectory(), "track.gpx");
        try (FileWriter writer = new FileWriter(gpxFile)) {
            writer.write(gpxData.toString());
            Toast.makeText(context, "GPX file saved: " + gpxFile.getAbsolutePath(), Toast.LENGTH_LONG).show();
        } catch (IOException e) {
            e.printStackTrace();
            Toast.makeText(context, "Error saving GPX file", Toast.LENGTH_SHORT).show();
        }
    }

    
}
