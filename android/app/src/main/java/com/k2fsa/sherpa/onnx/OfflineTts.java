package com.k2fsa.sherpa.onnx;

import android.content.res.AssetManager;

public class OfflineTts
{
  static
  {
    System.loadLibrary("sherpa-onnx-jni");
  }

  private long mPtr;

  public OfflineTts(AssetManager assetManager, OfflineTtsConfig config)
  {
    mPtr = assetManager != null ? newFromAsset(assetManager, config) : newFromFile(config);
    if (mPtr == 0)
      throw new IllegalStateException("Invalid OfflineTtsConfig");
  }

  public int sampleRate()
  {
    return getSampleRate(mPtr);
  }

  public GeneratedAudio generate(String text, int sid, float speed)
  {
    return generateImpl(mPtr, text, sid, speed);
  }

  public void release()
  {
    if (mPtr != 0)
    {
      delete(mPtr);
      mPtr = 0;
    }
  }

  private native long newFromAsset(AssetManager assetManager, OfflineTtsConfig config);

  private native long newFromFile(OfflineTtsConfig config);

  private native void delete(long ptr);

  private native int getSampleRate(long ptr);

  private native int getNumSpeakers(long ptr);

  private native GeneratedAudio generateImpl(long ptr, String text, int sid, float speed);
}
