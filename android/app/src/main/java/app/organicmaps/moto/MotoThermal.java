package app.organicmaps.moto;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import androidx.annotation.NonNull;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

public final class MotoThermal
{
  public static final float BATTERY_HOT = 43f;
  public static final float CPU_HOT = 80f;
  public static final float COOL_MARGIN = 2f;

  private MotoThermal() {}

  public static float batteryTemp(@NonNull Context context)
  {
    Intent b = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
    return b == null ? 0 : b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f;
  }

  public static float cpuTemp()
  {
    float max = 0;
    File[] zones = new File("/sys/class/thermal").listFiles();
    if (zones == null)
      return 0;
    for (File z : zones)
    {
      if (!z.getName().startsWith("thermal_zone"))
        continue;
      String type = readLine(new File(z, "type"));
      if (type == null || !(type.contains("cpu") || type.contains("tsens")))
        continue;
      String t = readLine(new File(z, "temp"));
      if (t == null)
        continue;
      try
      {
        float v = Float.parseFloat(t.trim());
        if (v > 1000)
          v /= 1000f;
        if (v > 0 && v < 150)
          max = Math.max(max, v);
      }
      catch (NumberFormatException ignored)
      {
      }
    }
    return max;
  }

  private static String readLine(@NonNull File f)
  {
    try (BufferedReader r = new BufferedReader(new FileReader(f)))
    {
      return r.readLine();
    }
    catch (Exception e)
    {
      return null;
    }
  }
}
