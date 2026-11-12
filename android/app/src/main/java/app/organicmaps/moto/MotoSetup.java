package app.organicmaps.moto;

import android.Manifest;
import android.app.role.RoleManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import androidx.annotation.NonNull;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import java.util.ArrayList;
import java.util.List;

public final class MotoSetup
{
  public enum Item
  {
    HOME("Home screen", "Make Moto Nav your home app so it opens on wake", true),
    LOCATION("Location", "Needed for the map, speed and navigation", true),
    NOTIFICATIONS("Notifications", "Map download and update progress", false),
    VOICE("Guidance voice", "Download a voice to hear turn-by-turn directions", true),
    MICROPHONE("Microphone", "Needed for voice commands", true),
    VOICE_INPUT("Voice commands model", "Download Whisper to give voice commands", true),
    MEDIA("Music controls", "Shows the song playing and lets you skip tracks", false),
    NEARBY("Phone link (Chronos)", "Nearby devices permission to connect your main phone", false),
    INTERCOM("Intercom voice button", "Use your intercom's redial to start voice commands", false);

    public final String title;
    public final String description;
    public final boolean required;

    Item(@NonNull String title, @NonNull String description, boolean required)
    {
      this.title = title;
      this.description = description;
      this.required = required;
    }
  }

  private MotoSetup() {}

  @NonNull
  public static List<Item> items(@NonNull Context c)
  {
    List<Item> out = new ArrayList<>();
    for (Item i : Item.values())
    {
      if (i == Item.NOTIFICATIONS && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU)
        continue;
      if (i == Item.NEARBY && Build.VERSION.SDK_INT < Build.VERSION_CODES.S)
        continue;
      if (i == Item.INTERCOM && !MotoCallRedirect.isEnabled(c))
        continue;
      out.add(i);
    }
    return out;
  }

  public static boolean isDone(@NonNull Context c, @NonNull Item item)
  {
    switch (item)
    {
    case HOME: return isDefaultHome(c);
    case LOCATION: return granted(c, Manifest.permission.ACCESS_FINE_LOCATION);
    case NOTIFICATIONS:
      return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || granted(c, Manifest.permission.POST_NOTIFICATIONS);
    case VOICE: return MotoVoices.selected(c) != null;
    case MICROPHONE: return granted(c, Manifest.permission.RECORD_AUDIO);
    case VOICE_INPUT: return MotoAsr.selected(c) != null;
    case MEDIA: return NotificationManagerCompat.getEnabledListenerPackages(c).contains(c.getPackageName());
    case NEARBY: return granted(c, nearbyPermissions());
    case INTERCOM: return granted(c, callLogPermissions()) && holdsRole(c, RoleManager.ROLE_CALL_REDIRECTION);
    }
    return true;
  }

  public static int missingRequired(@NonNull Context c)
  {
    int n = 0;
    for (Item i : items(c))
      if (i.required && !isDone(c, i))
        n++;
    return n;
  }

  @NonNull
  public static String[] permissionsFor(@NonNull Item item)
  {
    switch (item)
    {
    case LOCATION: return new String[] {Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION};
    case NOTIFICATIONS:
      return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
               ? new String[] {Manifest.permission.POST_NOTIFICATIONS} : new String[0];
    case MICROPHONE: return new String[] {Manifest.permission.RECORD_AUDIO};
    case NEARBY: return nearbyPermissions();
    case INTERCOM: return callLogPermissions();
    default: return new String[0];
    }
  }

  @NonNull
  static String[] nearbyPermissions()
  {
    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
             ? new String[] {Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE}
             : new String[0];
  }

  @NonNull
  static String[] callLogPermissions()
  {
    return new String[] {Manifest.permission.READ_CALL_LOG, Manifest.permission.WRITE_CALL_LOG};
  }

  public static boolean granted(@NonNull Context c, @NonNull String... permissions)
  {
    for (String p : permissions)
      if (ContextCompat.checkSelfPermission(c, p) != PackageManager.PERMISSION_GRANTED)
        return false;
    return true;
  }

  public static boolean holdsRole(@NonNull Context c, @NonNull String role)
  {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q)
      return false;
    RoleManager rm = c.getSystemService(RoleManager.class);
    return rm != null && rm.isRoleAvailable(role) && rm.isRoleHeld(role);
  }

  public static boolean isDefaultHome(@NonNull Context c)
  {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && holdsRole(c, RoleManager.ROLE_HOME))
      return true;
    Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
    ResolveInfo ri = c.getPackageManager().resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
    return ri != null && ri.activityInfo != null && c.getPackageName().equals(ri.activityInfo.packageName);
  }
}
