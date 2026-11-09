package app.organicmaps.moto;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.provider.Settings;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public final class MotoApps
{
  public static final class Shortcut
  {
    public final String label;
    public final List<String> packages;
    public final boolean browser;

    Shortcut(String label, boolean browser, String... packages)
    {
      this.label = label;
      this.browser = browser;
      this.packages = Arrays.asList(packages);
    }
  }

  public static final class Entry
  {
    public final String label;
    public final String packageName;
    public final ComponentName component;
    public final Drawable icon;

    Entry(String label, String packageName, ComponentName component, Drawable icon)
    {
      this.label = label;
      this.packageName = packageName;
      this.component = component;
      this.icon = icon;
    }
  }

  public static final List<Shortcut> BAR = Arrays.asList(
      new Shortcut("Bluetooth", false, "android.settings.BLUETOOTH_SETTINGS"),
      new Shortcut("Files", false, "com.android.documentsui", "com.google.android.documentsui"),
      new Shortcut("Music", false, "in.krosbits.musicolet", "org.lineageos.twelve", "org.lineageos.eleven", "com.android.music",
                   "org.videolan.vlc", "code.name.monkey.retromusic"),
      new Shortcut("Settings", false, "com.android.settings"));

  public static final int DOCK_MAX = 6;
  public static final String BLUETOOTH = "android.settings.BLUETOOTH_SETTINGS";
  private static final String PREFS = "moto";
  private static final String DOCK_KEY = "dock";

  private MotoApps() {}

  @NonNull
  public static List<String> dock(@NonNull Context context)
  {
    String saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(DOCK_KEY, null);
    List<String> result = new ArrayList<>();
    if (saved == null)
    {
      for (Shortcut s : BAR)
      {
        String pkg = installed(context, s);
        if (pkg != null)
          result.add(pkg);
      }
      return result;
    }
    PackageManager pm = context.getPackageManager();
    for (String item : saved.split(","))
      if (!item.isEmpty() && (item.startsWith("android.settings.") || pm.getLaunchIntentForPackage(item) != null))
        result.add(item);
    return result;
  }

  public static void saveDock(@NonNull Context context, @NonNull List<String> items)
  {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(DOCK_KEY, String.join(",", items)).apply();
  }

  public static void setDockSlot(@NonNull Context context, int slot, @NonNull String item)
  {
    List<String> items = dock(context);
    int existing = items.indexOf(item);
    if (existing >= 0)
    {
      items.remove(existing);
      if (existing < slot)
        slot--;
    }
    if (slot >= 0 && slot < items.size())
      items.set(slot, item);
    else if (items.size() < DOCK_MAX)
      items.add(item);
    saveDock(context, items);
  }

  public static boolean addToDock(@NonNull Context context, @NonNull String item)
  {
    List<String> items = dock(context);
    if (items.contains(item))
      return true;
    if (items.size() >= DOCK_MAX)
      return false;
    items.add(item);
    saveDock(context, items);
    return true;
  }

  public static void resetDock(@NonNull Context context)
  {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(DOCK_KEY).apply();
  }

  public static boolean isAction(@NonNull String item)
  {
    return item.startsWith("android.settings.");
  }

  @NonNull
  public static String label(@NonNull Context context, @NonNull String item)
  {
    if (BLUETOOTH.equals(item))
      return "Bluetooth";
    PackageManager pm = context.getPackageManager();
    try
    {
      return pm.getApplicationLabel(pm.getApplicationInfo(item, 0)).toString();
    }
    catch (PackageManager.NameNotFoundException e)
    {
      return item;
    }
  }

  public static boolean isSettingsAction(@NonNull Shortcut shortcut)
  {
    return shortcut.packages.size() == 1 && shortcut.packages.get(0).startsWith("android.settings.");
  }

  @Nullable
  public static String installed(@NonNull Context context, @NonNull Shortcut shortcut)
  {
    PackageManager pm = context.getPackageManager();
    if (isSettingsAction(shortcut))
      return shortcut.packages.get(0);
    if (shortcut.browser)
    {
      ResolveInfo ri = pm.resolveActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://")), 0);
      if (ri != null && ri.activityInfo != null && !"android".equals(ri.activityInfo.packageName)
          && pm.getLaunchIntentForPackage(ri.activityInfo.packageName) != null)
        return ri.activityInfo.packageName;
    }
    for (String p : shortcut.packages)
      if (pm.getLaunchIntentForPackage(p) != null)
        return p;
    return null;
  }

  @Nullable
  public static Drawable icon(@NonNull Context context, @NonNull String packageName)
  {
    try
    {
      return context.getPackageManager().getApplicationIcon(packageName);
    }
    catch (PackageManager.NameNotFoundException e)
    {
      return null;
    }
  }

  @NonNull
  public static List<Entry> load(@NonNull Context context)
  {
    PackageManager pm = context.getPackageManager();
    Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
    List<Entry> result = new ArrayList<>();
    for (ResolveInfo ri : pm.queryIntentActivities(intent, 0))
    {
      String pkg = ri.activityInfo.packageName;
      if (pkg.equals(context.getPackageName()))
        continue;
      result.add(new Entry(ri.loadLabel(pm).toString(), pkg, new ComponentName(pkg, ri.activityInfo.name),
                           ri.loadIcon(pm)));
    }
    result.sort((a, b) -> a.label.toLowerCase(Locale.ROOT).compareTo(b.label.toLowerCase(Locale.ROOT)));
    return result;
  }

  public static boolean launchPackage(@NonNull Context context, @NonNull String packageName)
  {
    Intent intent = context.getPackageManager().getLaunchIntentForPackage(packageName);
    return intent != null && start(context, intent);
  }

  public static boolean launch(@NonNull Context context, @NonNull ComponentName component)
  {
    return start(context,
                 new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(component));
  }

  public static void openAppInfo(@NonNull Context context, @NonNull String packageName)
  {
    start(context, new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)));
  }

  public static boolean start(@NonNull Context context, @NonNull Intent intent)
  {
    try
    {
      context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
      return true;
    }
    catch (ActivityNotFoundException | SecurityException e)
    {
      return false;
    }
  }
}
