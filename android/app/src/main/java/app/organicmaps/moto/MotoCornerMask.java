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
  private float mPanel;
  private float mGap;
  private float mMapRight = -1;

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

  public void setHoles(float panelRight, float gap, float mapRight)
  {
    mPanel = panelRight;
    mGap = gap;
    mMapRight = mapRight;
    invalidate();
  }

  @Override
  protected void onDraw(@NonNull Canvas canvas)
  {
    mPath.reset();
    mPath.setFillType(Path.FillType.EVEN_ODD);
    mPath.addRect(0, 0, getWidth(), getHeight(), Path.Direction.CW);
    float right = mMapRight < 0 ? getWidth() : Math.min(mMapRight, getWidth());
    float left = 0;
    if (mPanel > 1)
    {
      mRect.set(0, 0, mPanel, getHeight());
      mPath.addRoundRect(mRect, mRadius, mRadius, Path.Direction.CW);
      left = mPanel + mGap;
    }
    mRect.set(left, 0, right, getHeight());
    mPath.addRoundRect(mRect, mRadius, mRadius, Path.Direction.CW);
    canvas.drawPath(mPath, mPaint);
  }
}
