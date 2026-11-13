package app.organicmaps.moto;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.View;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.Locale;

// A per-second ride chart: one bar per pixel column holding the column's range, so short peaks survive
// squeezing a long ride into the card. Signed charts grow from a zero line, the others from the bottom.
@SuppressLint("ViewConstructor")
final class MotoChartView extends View
{
  private final Paint mBar = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint mAxis = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint mMark = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint mTitle = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint mLabel = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint mMarkLabel = new Paint(Paint.ANTI_ALIAS_FLAG);

  private String mTitleText = "";
  private String mUnit = "";
  private float[] mValues = new float[0];
  private boolean mSigned;
  @Nullable
  private String mTopTag;
  @Nullable
  private String mBottomTag;
  private int mDecimals;

  MotoChartView(@NonNull Context context, @NonNull MotoColors colors)
  {
    super(context);
    mBar.setStrokeWidth(dp(1.5f));
    mBar.setColor(colors.textDim);
    mAxis.setStrokeWidth(dp(1));
    mAxis.setColor(colors.textDim);
    mMark.setStrokeWidth(dp(1.5f));
    mMark.setColor(colors.accent);
    mTitle.setColor(colors.text);
    mTitle.setTextSize(sp(16));
    mTitle.setTypeface(Typeface.DEFAULT_BOLD);
    mTitle.setTextAlign(Paint.Align.CENTER);
    mLabel.setColor(colors.textDim);
    mLabel.setTextSize(sp(12));
    mLabel.setTextAlign(Paint.Align.RIGHT);
    mMarkLabel.setColor(colors.accent);
    mMarkLabel.setTextSize(sp(13));
    mMarkLabel.setTypeface(Typeface.DEFAULT_BOLD);
  }

  // Signed charts mark their largest swing either way; tags name the top and bottom halves (e.g. L and R).
  void setData(@NonNull String title, @NonNull float[] values, @NonNull String unit, boolean signed, int decimals,
               @Nullable String topTag, @Nullable String bottomTag)
  {
    mTitleText = title;
    mValues = values;
    mUnit = unit;
    mSigned = signed;
    mDecimals = decimals;
    mTopTag = topTag;
    mBottomTag = bottomTag;
    invalidate();
  }

  @Override
  protected void onDraw(@NonNull Canvas canvas)
  {
    float left = dp(52);
    float top = dp(44);
    float right = getWidth() - dp(12);
    float bottom = getHeight() - dp(12);
    canvas.drawText(mTitleText, getWidth() / 2f, dp(24), mTitle);
    if (right <= left || bottom <= top)
      return;

    float min = Float.POSITIVE_INFINITY;
    float max = Float.NEGATIVE_INFINITY;
    int markIndex = -1;
    for (int i = 0; i < mValues.length; i++)
    {
      float v = mValues[i];
      if (Float.isNaN(v))
        continue;
      min = Math.min(min, v);
      max = Math.max(max, v);
      float best = markIndex < 0 ? 0 : mValues[markIndex];
      if (markIndex < 0 || (mSigned ? Math.abs(v) > Math.abs(best) : v > best))
        markIndex = i;
    }
    if (markIndex < 0)
    {
      mLabel.setTextAlign(Paint.Align.CENTER);
      canvas.drawText("No data", (left + right) / 2, (top + bottom) / 2, mLabel);
      mLabel.setTextAlign(Paint.Align.RIGHT);
      return;
    }

    float lo;
    float hi;
    if (mSigned)
    {
      hi = Math.max(1f, Math.max(Math.abs(min), Math.abs(max)));
      lo = -hi;
    }
    else
    {
      // Altitude rarely starts at sea level, so the axis hugs the ride instead of starting at zero.
      float span = Math.max(1f, max - min);
      lo = min > 0 && min > span ? min - span * 0.1f : Math.min(0, min);
      hi = max + span * 0.1f;
    }
    float base = mSigned ? 0 : lo;

    canvas.drawLine(left, top, left, bottom, mAxis);
    float baseY = y(base, lo, hi, top, bottom);
    canvas.drawLine(left, baseY, right, baseY, mAxis);
    canvas.drawText(format(hi), left - dp(8), top + dp(4), mLabel);
    canvas.drawText(format(mSigned ? 0 : lo), left - dp(8), mSigned ? baseY + dp(4) : bottom, mLabel);
    if (mSigned)
      canvas.drawText(format(lo), left - dp(8), bottom, mLabel);
    if (mTopTag != null)
      canvas.drawText(mTopTag, left - dp(36), top + dp(4), mMarkLabel);
    if (mBottomTag != null)
      canvas.drawText(mBottomTag, left - dp(36), bottom, mMarkLabel);

    int columns = Math.max(1, (int) ((right - left) / dp(2)));
    float step = (right - left) / columns;
    int n = mValues.length;
    for (int c = 0; c < columns; c++)
    {
      int from = (int) ((long) c * n / columns);
      int to = Math.max(from + 1, (int) ((long) (c + 1) * n / columns));
      float colLo = Float.POSITIVE_INFINITY;
      float colHi = Float.NEGATIVE_INFINITY;
      for (int i = from; i < Math.min(to, n); i++)
      {
        float v = mValues[i];
        if (Float.isNaN(v))
          continue;
        colLo = Math.min(colLo, v);
        colHi = Math.max(colHi, v);
      }
      if (colLo > colHi)
        continue;
      float x = left + (c + 0.5f) * step;
      float y1 = y(Math.min(colLo, base), lo, hi, top, bottom);
      float y2 = y(Math.max(colHi, base), lo, hi, top, bottom);
      canvas.drawLine(x, y1, x, y2, mBar);
    }

    float markValue = mValues[markIndex];
    float markX = left + (markIndex + 0.5f) / n * (right - left);
    float markY = y(markValue, lo, hi, top, bottom);
    canvas.drawLine(markX, top, markX, bottom, mMark);
    canvas.drawLine(left, markY, right, markY, mMark);
    String label = format(markValue) + unitSuffix(mUnit);
    float labelWidth = mMarkLabel.measureText(label);
    float labelX = markX + dp(6) + labelWidth > right ? markX - dp(6) - labelWidth : markX + dp(6);
    float labelY = markY - dp(6) < top + dp(12) ? markY + dp(16) : markY - dp(6);
    canvas.drawText(label, labelX, labelY, mMarkLabel);
  }

  // Degrees sit right against the number; other units get a space.
  @NonNull
  static String unitSuffix(@NonNull String unit)
  {
    return unit.isEmpty() || unit.equals("°") ? unit : " " + unit;
  }

  private static float y(float v, float lo, float hi, float top, float bottom)
  {
    return bottom - (v - lo) / (hi - lo) * (bottom - top);
  }

  @NonNull
  private String format(float v)
  {
    return mDecimals == 0 ? String.valueOf(Math.round(v)) : String.format(Locale.US, "%." + mDecimals + "f", v);
  }

  private float dp(float value)
  {
    return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
  }

  private float sp(float value)
  {
    return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, getResources().getDisplayMetrics());
  }
}
