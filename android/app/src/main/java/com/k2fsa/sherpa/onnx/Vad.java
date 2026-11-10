package com.k2fsa.sherpa.onnx;

import android.content.res.AssetManager;

public class Vad
{
  static
  {
    System.loadLibrary("sherpa-onnx-jni");
  }

  private long mPtr;

  public Vad(AssetManager assetManager, VadModelConfig config)
  {
    mPtr = assetManager != null ? newFromAsset(assetManager, config) : newFromFile(config);
    if (mPtr == 0)
      throw new IllegalStateException("Invalid VadModelConfig");
  }

  public void acceptWaveform(float[] samples)
  {
    acceptWaveform(mPtr, samples);
  }

  public boolean empty()
  {
    return empty(mPtr);
  }

  public void pop()
  {
    pop(mPtr);
  }

  public SpeechSegment front()
  {
    return front(mPtr);
  }

  public boolean isSpeechDetected()
  {
    return isSpeechDetected(mPtr);
  }

  public void reset()
  {
    reset(mPtr);
  }

  public void flush()
  {
    flush(mPtr);
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

  private native long newFromAsset(AssetManager assetManager, VadModelConfig config);

  private native long newFromFile(VadModelConfig config);

  private native void acceptWaveform(long ptr, float[] samples);

  private native float compute(long ptr, float[] samples);

  private native boolean empty(long ptr);

  private native void pop(long ptr);

  private native void clear(long ptr);

  private native SpeechSegment front(long ptr);

  private native boolean isSpeechDetected(long ptr);

  private native void reset(long ptr);

  private native void flush(long ptr);
}
