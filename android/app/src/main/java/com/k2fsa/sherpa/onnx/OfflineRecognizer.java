package com.k2fsa.sherpa.onnx;

import android.content.res.AssetManager;

public class OfflineRecognizer
{
  static
  {
    System.loadLibrary("sherpa-onnx-jni");
  }

  private long mPtr;

  public OfflineRecognizer(AssetManager assetManager, OfflineRecognizerConfig config)
  {
    mPtr = assetManager != null ? newFromAsset(assetManager, config) : newFromFile(config);
    if (mPtr == 0)
      throw new IllegalStateException("Invalid OfflineRecognizerConfig");
  }

  public OfflineStream createStream()
  {
    return new OfflineStream(createStream(mPtr));
  }

  public void decode(OfflineStream stream)
  {
    decode(mPtr, stream.ptr);
  }

  public OfflineRecognizerResult getResult(OfflineStream stream)
  {
    return getResult(stream.ptr);
  }

  public void setConfig(OfflineRecognizerConfig config)
  {
    setConfig(mPtr, config);
  }

  public void release()
  {
    if (mPtr != 0)
    {
      delete(mPtr);
      mPtr = 0;
    }
  }

  private native void delete(long ptr);

  private native long createStream(long ptr);

  private native long createStreamWithHotwords(long ptr, String hotwords);

  private native void setConfig(long ptr, OfflineRecognizerConfig config);

  private native long newFromAsset(AssetManager assetManager, OfflineRecognizerConfig config);

  private native long newFromFile(OfflineRecognizerConfig config);

  private native void decode(long ptr, long streamPtr);

  private native OfflineRecognizerResult getResult(long streamPtr);
}
