package app.organicmaps.moto;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import app.organicmaps.R;
import java.util.Random;

@SuppressLint("ViewConstructor")
public final class MotoSplashView extends View
{
  private static final float BAR_L = 1267f;
  private static final float BAR_T = 699f;
  private static final float BAR_R = 1973f;
  private static final float BAR_B = 725f;
  private static final int PETALS = 22;
  private static final float[][] NO_PETALS = new float[0][];

  private final Bitmap mArt;
  private final Bitmap mBackdrop;
  private final RectF mBackdropDst = new RectF();
  private final int mBg;
  private final boolean mPetalsEnabled;
  private final boolean mDark;
  private final Paint mBitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
  private final Paint mFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint mGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint mShinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint mFadePaint = new Paint();
  private final Paint mPetalPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final RectF mDst = new RectF();
  private final RectF mBar = new RectF();
  private final Path mPetal = new Path();
  private final float[][] mPetals = new float[PETALS][6];
  private final Random mRandom = new Random(7);
  private float mProgress;
  private float mIntro;
  private float mPan;
  private long mStart;

  public MotoSplashView(@NonNull Context context)
  {
    super(context);
    int mode = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
    mDark = mode != Configuration.UI_MODE_NIGHT_NO;
    Bitmap customArt = loadCustom(context, mDark ? "moto_splash_dark" : "moto_splash_light");
    Bitmap customBackdrop = loadCustom(context, mDark ? "moto_splash_dark_bg" : "moto_splash_light_bg");
    mPetalsEnabled = customArt != null;
    mArt = customArt != null ? customArt
                             : BitmapFactory.decodeResource(getResources(),
                                                            mDark ? R.drawable.moto_splash_dark : R.drawable.moto_splash_light);
    mBg = mDark ? 0xFF0B0B0C : 0xFFF5F5F7;
    mBackdrop = customBackdrop != null ? customBackdrop
                                       : BitmapFactory.decodeResource(getResources(), mDark ? R.drawable.moto_splash_dark_bg
                                                                                            : R.drawable.moto_splash_light_bg);
    mGlowPaint.setMaskFilter(new BlurMaskFilter(18f, BlurMaskFilter.Blur.NORMAL));
    mGlowPaint.setColor(0xCCFF1F2D);
    mShinePaint.setColor(0x66FFFFFF);
    mPetal.addOval(new RectF(-1f, -0.45f, 1f, 0.45f), Path.Direction.CW);
    for (float[] p : mPetals)
      seedPetal(p, true);
    setLayerType(LAYER_TYPE_SOFTWARE, null);
  }

  @Nullable
  private static Bitmap loadCustom(@NonNull Context context, @NonNull String name)
  {
    java.io.File dir = context.getExternalFilesDir("splash");
    if (dir == null)
      return null;
    for (String ext : new String[] {".webp", ".png", ".jpg"})
    {
      java.io.File f = new java.io.File(dir, name + ext);
      if (f.isFile())
        return BitmapFactory.decodeFile(f.getAbsolutePath());
    }
    return null;
  }

  private void seedPetal(float[] p, boolean anywhere)
  {
    p[0] = anywhere ? mRandom.nextFloat() : 1.05f + mRandom.nextFloat() * 0.2f;
    p[1] = mRandom.nextFloat() * 0.9f;
    p[2] = 0.05f + mRandom.nextFloat() * 0.08f;
    p[3] = 0.02f + mRandom.nextFloat() * 0.05f;
    p[4] = mRandom.nextFloat() * 360f;
    p[5] = 6f + mRandom.nextFloat() * 10f;
  }

  public void start(long durationMs)
  {
    mStart = System.nanoTime();
    ValueAnimator intro = ValueAnimator.ofFloat(0f, 1f);
    intro.setDuration(450);
    intro.setInterpolator(new DecelerateInterpolator());
    intro.addUpdateListener(a -> mIntro = (float) a.getAnimatedValue());
    intro.start();
    ValueAnimator pan = ValueAnimator.ofFloat(0f, 1f);
    pan.setStartDelay(150);
    pan.setDuration(Math.max(600, durationMs - 350));
    pan.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
    pan.addUpdateListener(a -> mPan = (float) a.getAnimatedValue());

    ValueAnimator bar = ValueAnimator.ofFloat(0f, 1f);
    bar.setStartDelay(200);
    bar.setDuration(Math.max(400, durationMs - 200));
    bar.setInterpolator(new DecelerateInterpolator(1.4f));
    bar.addUpdateListener(a -> {
      mProgress = (float) a.getAnimatedValue();
      invalidate();
    });
    bar.start();
  }

  @Override
  protected void onDraw(@NonNull Canvas canvas)
  {
    int w = getWidth();
    int h = getHeight();
    canvas.drawColor(mBg);
    float bs = Math.max(w / (float) mBackdrop.getWidth(), h / (float) mBackdrop.getHeight()) * (1.08f - 0.04f * mIntro);
    float bw = mBackdrop.getWidth() * bs;
    float bh = mBackdrop.getHeight() * bs;
    mBackdropDst.set((w - bw) / 2f, (h - bh) / 2f, (w + bw) / 2f, (h + bh) / 2f);
    mBitmapPaint.setAlpha(Math.round(255 * mIntro));
    canvas.drawBitmap(mBackdrop, null, mBackdropDst, mBitmapPaint);
    float scale = Math.max(w / (float) mArt.getWidth(), h / (float) mArt.getHeight());
    float zoom = 1.03f - 0.03f * mIntro;
    float dw = mArt.getWidth() * scale * zoom;
    float dh = mArt.getHeight() * scale * zoom;
    float left = (w - dw) / 2f;
    float top = (h - dh) / 2f;
    mDst.set(left, top, left + dw, top + dh);
    float edge = 1f;
    int layer = canvas.saveLayer(0, top - 1, w, top + dh + 1, null);
    mBitmapPaint.setAlpha(Math.round(255 * mIntro));
    canvas.drawBitmap(mArt, null, mDst, mBitmapPaint);
    mFadePaint.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN));
    mFadePaint.setShader(new LinearGradient(0, top, 0, top + dh,
        new int[] {0x00000000, 0xFF000000, 0xFF000000, 0x00000000},
        new float[] {0f, edge / dh, 1f - edge / dh, 1f}, Shader.TileMode.CLAMP));
    canvas.drawRect(0, top - 1, w, top + dh + 1, mFadePaint);
    mFadePaint.setShader(null);
    mFadePaint.setXfermode(null);
    canvas.restoreToCount(layer);

    float k = dw / mArt.getWidth();
    float bl = left + BAR_L * k;
    float bt = top + BAR_T * k;
    float br = left + BAR_R * k;
    float bb = top + BAR_B * k;
    float fillR = bl + (br - bl) * mProgress;
    float radius = (bb - bt) / 2f;
    if (fillR - bl > radius)
    {
      mBar.set(bl, bt, fillR, bb);
      canvas.drawRoundRect(mBar, radius, radius, mGlowPaint);
      mFillPaint.setShader(new LinearGradient(bl, 0, fillR, 0, 0xFFC4050C, 0xFFFF2A36, Shader.TileMode.CLAMP));
      canvas.drawRoundRect(mBar, radius, radius, mFillPaint);
      mBar.set(bl + radius * 0.6f, bt + (bb - bt) * 0.18f, fillR - radius * 0.6f, bt + (bb - bt) * 0.42f);
      canvas.drawRoundRect(mBar, radius, radius, mShinePaint);
    }

    float t = (System.nanoTime() - mStart) / 1e9f;
    for (float[] p : mPetalsEnabled ? mPetals : NO_PETALS)
    {
      float x = (p[0] - p[2] * t) * w;
      float y = (p[1] + p[3] * t + 0.02f * (float) Math.sin(t * 2 + p[4])) * h;
      if (x < -40 || y > h + 40)
        continue;
      canvas.save();
      canvas.translate(x, y);
      canvas.rotate(p[4] + t * 90f);
      canvas.scale(p[5], p[5]);
      mPetalPaint.setColor(mDark ? 0xCCE0202C : 0xCCE53935);
      mPetalPaint.setAlpha(Math.round(200 * mIntro));
      canvas.drawPath(mPetal, mPetalPaint);
      canvas.restore();
    }
    if (mProgress < 1f || t < 3f)
      postInvalidateOnAnimation();
  }
}
