package app.organicmaps.moto;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.display.DisplayManager;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;
import app.organicmaps.R;

// Keeps GPS fixes and the phone's tilt flowing into MotoTrips while a trip records, also behind other apps.
public final class MotoTripService extends Service
{
  private static final String TAG = "MotoTripService";
  private static final String CHANNEL = "moto_trip";
  private static final int NOTIFICATION_ID = 7302;
  private static final long TICK_MS = 30_000L;

  private static boolean sRunning;

  private final Handler mHandler = new Handler(Looper.getMainLooper());
  @Nullable
  private LocationManager mLocationManager;
  @Nullable
  private SensorManager mSensorManager;
  @Nullable
  private Display mDisplay;

  // Feeds the lean chart; gravity is the sensor fusion's estimate, so engine vibration barely shows in it.
  private final SensorEventListener mGravityListener = new SensorEventListener() {
    @Override
    public void onSensorChanged(SensorEvent event)
    {
      int rotation = mDisplay == null ? Surface.ROTATION_0 : mDisplay.getRotation();
      MotoTrips.get(MotoTripService.this).onGravity(event.values[0], event.values[1], rotation);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}
  };

  private final LocationListener mListener = location -> {
    MotoTrips.get(this).onLocation(location);
    stopIfFinished();
  };

  private final Runnable mTick = new Runnable() {
    @Override
    public void run()
    {
      // No fixes arrive without a GPS signal, so the trip has to be closed by time as well.
      MotoTrips.get(MotoTripService.this).tick();
      if (!stopIfFinished())
        mHandler.postDelayed(this, TICK_MS);
    }
  };

  static void ensureRunning(@NonNull Context context)
  {
    if (sRunning)
      return;
    try
    {
      ContextCompat.startForegroundService(context, new Intent(context, MotoTripService.class));
      sRunning = true;
    }
    catch (RuntimeException e)
    {
      Log.w(TAG, "Unable to start trip service", e);
    }
  }

  @Override
  @SuppressLint("MissingPermission")
  public void onCreate()
  {
    super.onCreate();
    sRunning = true;
    NotificationManager nm = getSystemService(NotificationManager.class);
    if (nm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
      nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Trip recording", NotificationManager.IMPORTANCE_MIN));
    Notification n = new NotificationCompat.Builder(this, CHANNEL)
                         .setSmallIcon(R.drawable.ic_moto_bolt)
                         .setContentTitle("Moto Nav")
                         .setContentText("Recording trip")
                         .setOngoing(true)
                         .setPriority(NotificationCompat.PRIORITY_MIN)
                         .build();
    mLocationManager = getSystemService(LocationManager.class);
    try
    {
      ServiceCompat.startForeground(this, NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
      if (mLocationManager == null
          || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                 != PackageManager.PERMISSION_GRANTED)
        throw new SecurityException("No location access");
      mLocationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, mListener,
                                              Looper.getMainLooper());
      startGravity();
    }
    catch (RuntimeException e)
    {
      Log.w(TAG, "Unable to record trip in the background", e);
      stopSelf();
      return;
    }
    mHandler.postDelayed(mTick, TICK_MS);
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId)
  {
    stopIfFinished();
    return START_NOT_STICKY;
  }

  @Override
  public void onDestroy()
  {
    mHandler.removeCallbacks(mTick);
    if (mLocationManager != null)
      mLocationManager.removeUpdates(mListener);
    if (mSensorManager != null)
      mSensorManager.unregisterListener(mGravityListener);
    sRunning = false;
    super.onDestroy();
  }

  private void startGravity()
  {
    mSensorManager = getSystemService(SensorManager.class);
    DisplayManager displays = getSystemService(DisplayManager.class);
    mDisplay = displays == null ? null : displays.getDisplay(Display.DEFAULT_DISPLAY);
    Sensor gravity = mSensorManager == null ? null : mSensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY);
    if (gravity != null)
      mSensorManager.registerListener(mGravityListener, gravity, SensorManager.SENSOR_DELAY_UI, mHandler);
  }

  private boolean stopIfFinished()
  {
    if (MotoTrips.get(this).isRecording())
      return false;
    stopSelf();
    return true;
  }

  @Nullable
  @Override
  public IBinder onBind(Intent intent)
  {
    return null;
  }
}
