package ru.unrealsoftware.velocounter;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.location.GpsStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Date;
import java.util.Timer;
import java.util.TimerTask;

import ru.unrealsoftware.velocounter.gps.GPSSatelliteCounter;
import ru.unrealsoftware.velocounter.gps.NmeaListener;
import ru.unrealsoftware.velocounter.speed.MedianGPSSpeedCounter;

public class MainActivity extends AppCompatActivity {

    private static final int requestGpsPermissionsCallback = 4242;

    private LocationManager locationManager;
    private TextView sensorTick;
    private TextView speedLabel0;
    private TextView speedLabel1;
    private TextView speedLabel2;
    private TextView speedLabelDec;
    private TextView speedShadowLabel0;
    private TextView speedShadowLabel1;
    private TextView speedShadowLabel2;
    private TextView speedShadowLabelDec;
    private TextView dotLabel;
    private TextView distanceLabel;
    private TextView maxLabel;
    private TextView avsLabel;
    private TextView tmLabel;
    private TextView satellitesCount;
    private TextView instantSpeedLabel;

    private ImageView gpsImage;
    private ImageView speedImage;

    private int ticksSensor;
    private boolean firstTick;

    private long lastTick;
    private double maxSpeed;
    private double totalDistance;
    private long lastSensor;
    private long lastSpeed;
    private long tripTime;

    private long tripTimeBegin;

    private int setupMode;
    private boolean dstIsBlinking;
    private boolean blinkState;
    private boolean mxsIsBlinking;

    private GPSSatelliteCounter gpsSatelliteCounter;
    private NmeaListener nmeaListener;
    private MedianGPSSpeedCounter medianGPSSpeedCounter;

    private static Location lastLocation;

    private static final String PREFS_FILE = "last_state";
    private SharedPreferences settings;

    private void findLabels() {

        dotLabel = findViewById(R.id.speedLabelDot);
        sensorTick = findViewById(R.id.sensorTick);
        speedLabel0 = findViewById(R.id.speedLabel0);
        speedLabel1 = findViewById(R.id.speedLabel1);
        speedLabel2 = findViewById(R.id.speedLabel2);
        speedLabelDec = findViewById(R.id.speedLabelDec);
        speedShadowLabel0 = findViewById(R.id.speedShadowLabel0);
        speedShadowLabel1 = findViewById(R.id.speedShadowLabel1);
        speedShadowLabel2 = findViewById(R.id.speedShadowLabel2);
        speedShadowLabelDec = findViewById(R.id.speedShadowLabelDec);
        distanceLabel = findViewById(R.id.distanceLabel);

        maxLabel = findViewById(R.id.maxLabel);
        avsLabel = findViewById(R.id.avsLabel);
        tmLabel = findViewById(R.id.tmLabel);

        gpsImage = findViewById(R.id.imgGps);
        gpsImage.setVisibility(View.INVISIBLE);

        speedImage = findViewById(R.id.imgSpeed);
        speedImage.setVisibility(View.INVISIBLE);

        satellitesCount = findViewById(R.id.satelitesCount);
        instantSpeedLabel = findViewById(R.id.instantSpeedLabel);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        this.requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.activity_main);

        settings = getSharedPreferences(PREFS_FILE, MODE_PRIVATE);

        Typeface face = Typeface.createFromAsset(getAssets(), "17372.otf");

        lastTick = new Date().getTime();

        findLabels();
        dotLabel.setTypeface(face);
        sensorTick.setTypeface(face);
        speedLabel0.setTypeface(face);
        speedLabel1.setTypeface(face);
        speedLabel2.setTypeface(face);
        speedLabelDec.setTypeface(face);
        speedShadowLabel0.setTypeface(face);
        speedShadowLabel1.setTypeface(face);
        speedShadowLabel2.setTypeface(face);
        speedShadowLabelDec.setTypeface(face);
        distanceLabel.setTypeface(face);
        maxLabel.setTypeface(face);
        avsLabel.setTypeface(face);
        tmLabel.setTypeface(face);
        satellitesCount.setTypeface(face);

        totalDistance = settings.getFloat("dist", 0);

        setupMode = 0;
        tripTime = 0;
        firstTick = true;
        startGpsTracker();

        var mTimer = new Timer();
        MyTimerTask mMyTimerTask = new MyTimerTask();

        medianGPSSpeedCounter = new MedianGPSSpeedCounter(4);

        mTimer.schedule(mMyTimerTask, 1000, 1000);
    }

    private String getSegmentedSpeed(BigDecimal value) {

        if (value == null || value.compareTo(BigDecimal.ZERO) == 0) {

            return "  00";
        }

        String r = "";
        String ss = "";
        var cc = value.setScale(1, RoundingMode.HALF_DOWN).toString().toCharArray();
        for (var c : cc) {

            if (c != '.') {
                ss = ss + c;
            }
        }
        if (!ss.isEmpty()) {

            if (ss.length() < 4)
                r = " ".repeat(4 - ss.length());
            r = r + ss;
        }

        return r;
    }

    @Override
    public void onResume() {
        super.onResume();

        findLabels();
    }

    @Override
    public void onDestroy() {

        writeState();
        super.onDestroy();
    }

    private void writeState() {

        SharedPreferences.Editor prefEditor = settings.edit();
        prefEditor.putFloat("dist", (float) totalDistance).apply();
    }

    private String formatDecimalValue(BigDecimal value, int scale) {

        if (value == null) return "  . ";
        if (value.compareTo(BigDecimal.TEN) < 0) {
            return " " + value.setScale(scale, RoundingMode.HALF_UP).toString();
        } else {
            return value.setScale(scale, RoundingMode.HALF_UP).toString();
        }
    }

    private String formatIntValue(long value) {

        return value < 10 ? "0" + value : Long.toString(value);
    }

    @SuppressLint("DefaultLocale")
    private String formatTimeValue(long millis) {

        String value;
        long h = millis / 1000 / 3600;
        long m = (millis - (h * 3600000)) / 60000;
        long s = (millis - (m * 60000) - (h * 3600000)) / 1000;

        value = String.format("%d:%s:%s", h, formatIntValue(m), formatIntValue(s));
        return value;
    }

    @SuppressLint("SetTextI18n")
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            //Обрабатываем нажатие кнопки увеличения громкости:
            case KeyEvent.KEYCODE_VOLUME_UP:

                setupMode++;
                if (setupMode > 5) setupMode = 0;
                switch (setupMode) {

                    case 0:
                        dstIsBlinking = false;
                        mxsIsBlinking = false;
                        break;

                    case 1:
                        dstIsBlinking = true;
                        mxsIsBlinking = false;
                        break;

                    case 2:
                        dstIsBlinking = false;
                        mxsIsBlinking = true;
                        break;

                    default:
                        dstIsBlinking = false;
                        mxsIsBlinking = false;
                        break;
                }

                return true;

            case KeyEvent.KEYCODE_VOLUME_DOWN:

                if (setupMode != 0) {

                    if (dstIsBlinking) {
                        totalDistance = 0;
                        if (distanceLabel != null) {
                            distanceLabel.setText("0");
                        }
                    }
                    if (mxsIsBlinking) {
                        maxSpeed = 0;
                        if (maxLabel != null) {
                            maxLabel.setText("0.0");
                        }
                    }
                }

                return true;

            default:
                break;
        }
        return super.onKeyDown(keyCode, event);
    }

    @SuppressLint("DefaultLocale")
    private void startGpsTracker() {
        // Использование GPS
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (locationManager != null) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) !=
                    PackageManager.PERMISSION_GRANTED) {
                return;
            }
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER,
                    800, 20, locationListener);

            locationManager.addNmeaListener((GpsStatus.NmeaListener) (timestamp, nmea) -> {

                if (!TextUtils.isEmpty(nmea)) {
                    ///parseNMEA(nmea);
                }
            });
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {

            nmeaListener = new NmeaListener((message, timestamp) -> {

                if (!TextUtils.isEmpty(message)) {
                    ///parseNMEA(nmea);
                }
            });

            gpsSatelliteCounter = new GPSSatelliteCounter(count -> satellitesCount.setText(Integer.toString(count)));
        } else {
            satellitesCount.setVisibility(View.INVISIBLE);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        requestPermissions();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {

        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        boolean success = true;
        for (int grantResult : grantResults) {
            if (grantResult != PackageManager.PERMISSION_GRANTED) {
                success = false;
                break;
            }
        }
        if (success)
            requestPermissions();
        else
            showAlertAboutPermissions();
    }

    private void requestPermissions() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            locationManager.requestLocationUpdates("gps", 1000, 0, locationListener);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                locationManager.registerGnssStatusCallback(gpsSatelliteCounter);
            }
        } else ActivityCompat.requestPermissions(this, new String[]{
                Manifest.permission.ACCESS_FINE_LOCATION
        }, requestGpsPermissionsCallback);
    }

    private void showAlertAboutPermissions() {
        new AlertDialog.Builder(this)
                .setTitle("Нет разрешений")
                .setMessage("Приложению требуется разрешение.")
                .setPositiveButton("Разрешить", (dialog, which) -> requestPermissions())
                .setNegativeButton("Запретить", (dialog, which) -> finish())
                .setOnCancelListener(dialog -> finish()).show();
    }

    @SuppressLint("SetTextI18n")
    private void resetScreen() {

        displaySpeedToSegment(null);
        if (sensorTick != null) {
            ///sensorTick.setText(Integer.toString(ticksSensor));
        }
        if (maxLabel != null) {
            maxLabel.setText("0.0");
        }
        if (distanceLabel != null) {
            distanceLabel.setText("0");
        }
        if (avsLabel != null) {
            avsLabel.setText("0.0");
        }
        if (tmLabel != null) {
            tmLabel.setText("0:00:00");
        }
    }

    private void displaySpeedToSegment(String speedStr) {

        if (speedStr == null) speedStr = "  00";

        speedLabel0.setText(Character.toString(speedStr.charAt(0)));
        speedLabel1.setText(Character.toString(speedStr.charAt(1)));
        speedLabel2.setText(Character.toString(speedStr.charAt(2)));
        speedLabelDec.setText(Character.toString(speedStr.charAt(3)));
    }

    @SuppressLint("SetTextI18n")
    private void calcParameters(double distance, long timeGap) {

        var speed = 0.0;
        if (!(distance == 0 || timeGap == 0)) {
            speed = (distance / timeGap) * 3600;
        }

        if (speed > 1 && speed < 200) {

            lastSpeed = new Date().getTime();
            if (speedImage != null) {
                speedImage.setVisibility(View.VISIBLE);
            }
            if (speedLabel0 != null) {
                instantSpeedLabel.setText(formatDecimalValue(BigDecimal.valueOf(speed), 1));
                double spMedian = medianGPSSpeedCounter.getSpeed(speed);
                var speedStr = getSegmentedSpeed(BigDecimal.valueOf(spMedian));
                displaySpeedToSegment(speedStr);
            }
            if (sensorTick != null) {
                ///sensorTick.setText(Integer.toString(ticksSensor));
            }
            if (maxSpeed < speed) {
                maxSpeed = speed;
                maxLabel.setText(formatDecimalValue(BigDecimal.valueOf(maxSpeed), 1));
            }
            if (distanceLabel != null) {
                distanceLabel.setText(formatDecimalValue(BigDecimal.valueOf(totalDistance / 1000), 2));
            }
            if (avsLabel != null) {

            }
            if (tmLabel != null) {
                tmLabel.setText(formatTimeValue(new Date().getTime() - tripTimeBegin));
            }
        }
    }

    static double haversine(double lat1, double lon1, double lat2, double lon2) {

        // расстояние между широтой и долготой
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);

        // преобразовать в радианы
        lat1 = Math.toRadians(lat1);
        lat2 = Math.toRadians(lat2);

        // применить формулы
        double a = Math.pow(Math.sin(dLat / 2), 2) + Math.pow(Math.sin(dLon / 2), 2) *
                Math.cos(lat1) * Math.cos(lat2);

        double rad = 6371210;
        double c = 2 * Math.asin(Math.sqrt(a));
        return rad * c;
    }

    LocationListener locationListener = new LocationListener() {

        @Override
        public void onLocationChanged(@NonNull Location location) {

            ticksSensor++;
            lastSensor = new Date().getTime();
            gpsImage.setVisibility(View.VISIBLE);

            if (firstTick) {
                tripTimeBegin = new Date().getTime();
                resetScreen();
            }
            if (lastLocation != null) {
                double distance = haversine(location.getLatitude(), location.getLongitude(),
                        lastLocation.getLatitude(), lastLocation.getLongitude());

                totalDistance += distance;

                long timeGap = location.getTime() - lastTick;

                calcParameters(distance, timeGap);
            }

            if (sensorTick != null) {
                sensorTick.setText(Integer.toString(ticksSensor));
            }

            lastLocation = new Location("last");
            lastLocation.setLongitude(location.getLongitude());
            lastLocation.setLatitude(location.getLatitude());

            lastTick = location.getTime();
            firstTick = false;
        }

        @Override
        public void onStatusChanged(String s, int i, Bundle bundle) {

        }

        @Override
        public void onProviderEnabled(String s) {

        }

        @Override
        public void onProviderDisabled(String s) {

        }
    };

    class MyTimerTask extends TimerTask {

        @Override
        public void run() {

            runOnUiThread(() -> {

                if (setupMode != 0) {

                    blinkState = !blinkState;
                    if (dstIsBlinking) {
                        if (blinkState) {
                            distanceLabel.setVisibility(View.VISIBLE);
                        } else {
                            distanceLabel.setVisibility(View.INVISIBLE);
                        }
                    } else {
                        distanceLabel.setVisibility(View.VISIBLE);
                    }
                    if (mxsIsBlinking) {
                        if (blinkState) {
                            maxLabel.setVisibility(View.VISIBLE);
                        } else {
                            maxLabel.setVisibility(View.INVISIBLE);
                        }
                    } else {
                        maxLabel.setVisibility(View.VISIBLE);
                    }
                }

                boolean stateSaveTime = new Date().getTime() - lastSpeed > 10000;
                if (stateSaveTime) {

                    writeState();
                }

                boolean hideSpeed = new Date().getTime() - lastSpeed > 3000;
                if (hideSpeed) {

                    if (speedImage != null) {
                        speedImage.setVisibility(View.INVISIBLE);
                    }

                    if (speedLabel0 != null) {
                        instantSpeedLabel.setText(formatDecimalValue(BigDecimal.valueOf(0), 1));
                        displaySpeedToSegment("  00");
                    }
                }

                boolean enabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
                if (!enabled) {
                    if (gpsImage != null) {
                        gpsImage.setVisibility(View.INVISIBLE);
                    }
                }
            });
        }
    }
}