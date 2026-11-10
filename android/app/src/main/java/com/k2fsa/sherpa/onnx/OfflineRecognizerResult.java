package com.k2fsa.sherpa.onnx;

public class OfflineRecognizerResult
{
  public final String text;
  public final String[] tokens;
  public final float[] timestamps;
  public final String lang;
  public final String emotion;
  public final String event;
  public final float[] durations;

  public OfflineRecognizerResult(String text, String[] tokens, float[] timestamps, String lang, String emotion,
                                 String event, float[] durations)
  {
    this.text = text;
    this.tokens = tokens;
    this.timestamps = timestamps;
    this.lang = lang;
    this.emotion = emotion;
    this.event = event;
    this.durations = durations;
  }
}
