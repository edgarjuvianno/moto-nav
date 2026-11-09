package app.organicmaps.moto;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.drawable.GradientDrawable;
import androidx.annotation.NonNull;

public final class MotoColors
{
  public final int screen;
  public final int card;
  public final int background;
  public final int surfaceHigh;
  public final int text;
  public final int textDim;
  public final int accent;
  public final int onAccent;
  public final int ok;
  public final int warn;
  public final int bad;

  private MotoColors(boolean dark)
  {
    screen = dark ? 0xFF05070A : 0xFFDCE2E9;
    card = dark ? 0xFF151A21 : 0xFFFFFFFF;
    background = dark ? 0xFF12161B : 0xFFEFF2F5;
    surfaceHigh = dark ? 0xFF1F252D : 0xFFDDE2E8;
    text = dark ? 0xFFFFFFFF : 0xFF000000;
    textDim = dark ? 0xFFB4BCC8 : 0xFF353B44;
    accent = dark ? 0xFFF01826 : 0xFFD8101C;
    onAccent = 0xFFFFFFFF;
    ok = dark ? 0xFF3DDC84 : 0xFF00702A;
    warn = dark ? 0xFFFFB300 : 0xFF9A5B00;
    bad = dark ? 0xFFFF5252 : 0xFFC00000;
  }

  @NonNull
  public static MotoColors of(@NonNull Context context)
  {
    int mode = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
    return new MotoColors(mode == Configuration.UI_MODE_NIGHT_YES);
  }

  @NonNull
  public static GradientDrawable oval(int color)
  {
    GradientDrawable d = new GradientDrawable();
    d.setShape(GradientDrawable.OVAL);
    d.setColor(color);
    return d;
  }

  @NonNull
  public static GradientDrawable rounded(int color, float radius)
  {
    GradientDrawable d = new GradientDrawable();
    d.setShape(GradientDrawable.RECTANGLE);
    d.setCornerRadius(radius);
    d.setColor(color);
    return d;
  }
}
