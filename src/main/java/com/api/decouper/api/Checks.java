package com.api.decouper.api;

public final class Checks {
  private Checks() {}

  public static String text(String v, int max, String name) {
    if (v == null || v.trim().isEmpty() || v.length() > max)
      throw new IllegalArgumentException(name);
    return v;
  }

  public static int positive(int v, String name) {
    if (v < 1) throw new IllegalArgumentException(name);
    return v;
  }
}
