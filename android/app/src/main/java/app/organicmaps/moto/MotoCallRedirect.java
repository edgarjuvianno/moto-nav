package app.organicmaps.moto;

import android.Manifest;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.CallLog;
import android.telecom.CallRedirectionService;
import android.telecom.PhoneAccountHandle;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import app.organicmaps.MwmActivity;

public final class MotoCallRedirect extends CallRedirectionService
{
  public static final String MAGIC_NUMBER = "*#668#";
  private static final String TAG = "MotoRedial";
  private static final String PREFS = "moto";
  private static final String KEY_ENABLED = "intercom_redial";

  @Nullable
  private static Runnable sTrigger;
  private static boolean sPendingVoice;
  @Nullable
  private static ContentObserver sObserver;

  public static void setTrigger(@Nullable Runnable trigger)
  {
    sTrigger = trigger;
  }

  public static boolean consumePendingVoice()
  {
    boolean pending = sPendingVoice;
    sPendingVoice = false;
    return pending;
  }

  @Override
  public void onPlaceCall(@NonNull Uri handle, @NonNull PhoneAccountHandle initialPhoneAccount,
                          boolean allowInteractiveResponse)
  {
    String number = handle.getSchemeSpecificPart();
    if (number == null || !normalize(number).equals(normalize(MAGIC_NUMBER)) || !isEnabled(this))
    {
      placeCallUnmodified();
      return;
    }
    cancelCall();
    Log.i(TAG, "Intercom redial intercepted, starting voice command");
    new Handler(Looper.getMainLooper()).post(() -> {
      Runnable trigger = sTrigger;
      if (trigger != null)
        trigger.run();
      else
      {
        sPendingVoice = true;
        Intent intent = new Intent(this, MwmActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        try
        {
          startActivity(intent);
        }
        catch (RuntimeException e)
        {
          Log.w(TAG, "Unable to bring launcher to front", e);
        }
      }
    });
  }

  @NonNull
  private static String normalize(@NonNull String number)
  {
    return number.replace("%23", "#").replace(" ", "");
  }

  public static boolean isEnabled(@NonNull Context c)
  {
    SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    // Riders who set it up before the switch existed keep it on.
    if (!p.contains(KEY_ENABLED))
      return MotoSetup.isDone(c, MotoSetup.Item.INTERCOM);
    return p.getBoolean(KEY_ENABLED, false);
  }

  public static void setEnabled(@NonNull Context c, boolean enabled)
  {
    c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply();
    install(c);
  }

  public static void install(@NonNull Context context)
  {
    Context app = context.getApplicationContext();
    if (!hasPermissions(app))
      return;
    if (!isEnabled(app))
    {
      uninstall(app);
      return;
    }
    seed(app);
    if (sObserver != null)
      return;
    sObserver = new ContentObserver(new Handler(Looper.getMainLooper())) {
      @Override
      public void onChange(boolean selfChange)
      {
        seed(app);
      }
    };
    app.getContentResolver().registerContentObserver(CallLog.Calls.CONTENT_URI, true, sObserver);
  }

  // Drops the seeded entry so the intercom redials the real last number again.
  private static void uninstall(@NonNull Context app)
  {
    if (sObserver != null)
    {
      app.getContentResolver().unregisterContentObserver(sObserver);
      sObserver = null;
    }
    try
    {
      if (app.getContentResolver().delete(CallLog.Calls.CONTENT_URI, CallLog.Calls.NUMBER + "=?",
                                          new String[] {MAGIC_NUMBER})
          > 0)
        Log.i(TAG, "Removed intercom redial entry");
    }
    catch (RuntimeException e)
    {
      Log.w(TAG, "Call log delete failed", e);
    }
  }

  private static boolean hasPermissions(@NonNull Context context)
  {
    return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG)
            == PackageManager.PERMISSION_GRANTED
        && ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALL_LOG)
               == PackageManager.PERMISSION_GRANTED;
  }

  private static void seed(@NonNull Context context)
  {
    try (Cursor c = context.getContentResolver().query(
             CallLog.Calls.CONTENT_URI, new String[] {CallLog.Calls.NUMBER},
             CallLog.Calls.TYPE + "=" + CallLog.Calls.OUTGOING_TYPE, null, CallLog.Calls.DATE + " DESC"))
    {
      if (c != null && c.moveToFirst() && MAGIC_NUMBER.equals(c.getString(0)))
        return;
    }
    catch (RuntimeException e)
    {
      Log.w(TAG, "Call log query failed", e);
      return;
    }
    try
    {
      context.getContentResolver().delete(CallLog.Calls.CONTENT_URI, CallLog.Calls.NUMBER + "=?",
                                          new String[] {MAGIC_NUMBER});
      ContentValues v = new ContentValues();
      v.put(CallLog.Calls.NUMBER, MAGIC_NUMBER);
      v.put(CallLog.Calls.TYPE, CallLog.Calls.OUTGOING_TYPE);
      v.put(CallLog.Calls.DATE, System.currentTimeMillis());
      v.put(CallLog.Calls.DURATION, 0);
      v.put(CallLog.Calls.NEW, 0);
      v.put(CallLog.Calls.CACHED_NAME, "Moto Nav voice");
      context.getContentResolver().insert(CallLog.Calls.CONTENT_URI, v);
      Log.i(TAG, "Seeded intercom redial entry");
    }
    catch (RuntimeException e)
    {
      Log.w(TAG, "Call log insert failed", e);
    }
  }
}
