package app.organicmaps.moto;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;
import androidx.annotation.NonNull;

@SuppressLint("ViewConstructor")
public final class MotoCornerMask extends View
{
  private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Path mPath = new Path();
  private final RectF mRect = new RectF();
  private final float mRadius;

  public MotoCornerMask(@NonNull Context context, float radius)
  {
    super(context);
    mRadius = radius;
    setClickable(false);
    setFocusable(false);
  }

  public void setColor(int color)
  {
    mPaint.setColor(color);
    invalidate();
  }

  @Override
  protected void onDraw(@NonNull Canvas canvas)
  {
    mPath.reset();
    mPath.setFillType(Path.FillType.EVEN_ODD);
    mPath.addRect(0, 0, getWidth(), getHeight(), Path.Direction.CW);
    mRect.set(0, 0, getWidth(), getHeight());
    mPath.addRoundRect(mRect, mRadius, mRadius, Path.Direction.CW);
    canvas.drawPath(mPath, mPaint);
  }
}
