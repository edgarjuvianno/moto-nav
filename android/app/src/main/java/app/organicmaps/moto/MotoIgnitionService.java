package app.organicmaps.moto;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import app.organicmaps.R;

public final class MotoIgnitionService extends Service
{
  private static final String TAG = "MotoIgnition";
  private static final String CHANNEL = "moto_ignition";
  private static final int NOTIFICATION_ID = 7301;
  private static final long OFF_DELAY_MS = 15_000L;
  private static final long SHUTDOWN_DELAY_MS = 48L * 60 * 60 * 1000;
  static final String ACTION_SHUTDOWN = "id.moto.nav.IGNITION_SHUTDOWN";
  private static final String PREFS = "moto";
  private static final String KEY_ENABLED = "ignition";

  private final Handler mHandler = new Handler(Looper.getMainLooper());
  @Nullable
  private PowerManager.WakeLock mWakeLock;

  private final Runnable mParkRunnable = this::park;

  private final BroadcastReceiver mPowerReceiver = new BroadcastReceiver() {
    @Override
    public void onReceive(Context context, Intent intent)
    {
      if (Intent.ACTION_POWER_CONNECTED.equals(intent.getAction()))
        onIgnitionOn();
      else if (Intent.ACTION_POWER_DISCONNECTED.equals(intent.getAction()))
        onIgnitionOff();
    }
  };

  public static boolean isEnabled(@NonNull Context context)
  {
    return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true);
  }

  public static void setEnabled(@NonNull Context context, boolean enabled)
  {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply();
    if (enabled)
      start(context);
    else
      context.stopService(new Intent(context, MotoIgnitionService.class));
  }

  public static void start(@NonNull Context context)
  {
    if (!isEnabled(context))
      return;
    try
    {
      ContextCompat.startForegroundService(context, new Intent(context, MotoIgnitionService.class));
    }
    catch (RuntimeException e)
    {
      Log.w(TAG, "Unable to start ignition service", e);
    }
  }

  @Override
  public void onCreate()
  {
    super.onCreate();
    NotificationManager nm = getSystemService(NotificationManager.class);
    if (nm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
      nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Ignition", NotificationManager.IMPORTANCE_MIN));
    Notification n = new NotificationCompat.Builder(this, CHANNEL)
                         .setSmallIcon(R.drawable.ic_moto_bolt)
                         .setContentTitle("Moto Nav")
                         .setContentText("Watching the ignition")
                         .setOngoing(true)
                         .setPriority(NotificationCompat.PRIORITY_MIN)
                         .build();
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
      startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
    else
      startForeground(NOTIFICATION_ID, n);
    IntentFilter filter = new IntentFilter();
    filter.addAction(Intent.ACTION_POWER_CONNECTED);
    filter.addAction(Intent.ACTION_POWER_DISCONNECTED);
    ContextCompat.registerReceiver(this, mPowerReceiver, filter, ContextCompat.RECEIVER_EXPORTED);
    Log.i(TAG, "Ignition service started");
    MotoRoot.run("id -u");
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId)
  {
    if (intent != null && ACTION_SHUTDOWN.equals(intent.getAction()))
    {
      if (!isPlugged())
      {
        Log.i(TAG, "Unplugged for 48 hours, powering off");
        MotoRoot.run("svc power shutdown");
      }
    }
    return START_STICKY;
  }

  @Override
  public void onDestroy()
  {
    mHandler.removeCallbacks(mParkRunnable);
    try
    {
      unregisterReceiver(mPowerReceiver);
    }
    catch (IllegalArgumentException ignored)
    {
    }
    releaseWakeLock();
    super.onDestroy();
  }

  @Nullable
  @Override
  public IBinder onBind(Intent intent)
  {
    return null;
  }

  private void onIgnitionOn()
  {
    Log.i(TAG, "Ignition on");
    mHandler.removeCallbacks(mParkRunnable);
    releaseWakeLock();
    cancelShutdown();
    MotoRoot.run("cmd location set-location-enabled true", "cmd connectivity airplane-mode disable", "sleep 1",
                 "input keyevent 224", "am start -a android.intent.action.MAIN -c android.intent.category.HOME");
  }

  private void onIgnitionOff()
  {
    Log.i(TAG, "Ignition off: screen off now, parking in " + OFF_DELAY_MS / 1000 + " s");
    mHandler.removeCallbacks(mParkRunnable);
    acquireWakeLock();
    MotoRoot.run("input keyevent 223");
    mHandler.postDelayed(mParkRunnable, OFF_DELAY_MS);
    scheduleShutdown();
  }

  private void park()
  {
    releaseWakeLock();
    if (isPlugged())
      return;
    Log.i(TAG, "Parked: location off, airplane on");
    MotoTrips.get(this).finish();
    MotoRoot.run("cmd location set-location-enabled false", "cmd connectivity airplane-mode enable");
  }

  private boolean isPlugged()
  {
    Intent battery = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
    return battery != null && battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
  }

  @NonNull
  private PendingIntent shutdownIntent()
  {
    Intent i = new Intent(this, MotoIgnitionService.class).setAction(ACTION_SHUTDOWN);
    return PendingIntent.getForegroundService(this, 1, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
  }

  private void scheduleShutdown()
  {
    AlarmManager am = getSystemService(AlarmManager.class);
    if (am != null)
      am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + SHUTDOWN_DELAY_MS,
                              shutdownIntent());
  }

  private void cancelShutdown()
  {
    AlarmManager am = getSystemService(AlarmManager.class);
    if (am != null)
      am.cancel(shutdownIntent());
  }

  private void acquireWakeLock()
  {
    if (mWakeLock == null)
    {
      PowerManager pm = getSystemService(PowerManager.class);
      if (pm == null)
        return;
      mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MotoNav:ignition");
      mWakeLock.setReferenceCounted(false);
    }
    mWakeLock.acquire(OFF_DELAY_MS + 10_000L);
  }

  private void releaseWakeLock()
  {
    if (mWakeLock != null && mWakeLock.isHeld())
      mWakeLock.release();
  }
}
