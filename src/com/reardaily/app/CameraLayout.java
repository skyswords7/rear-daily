package com.reardaily.app;

import android.graphics.*;

final class CameraLayout {
  // Full-screen center crop. Lens circles are a preview overlay, never baked into media.
  static Bitmap compose(Bitmap original) {
    Bitmap result = Bitmap.createBitmap(976, 596, Bitmap.Config.ARGB_8888);
    Canvas canvas = new Canvas(result);
    float scale = Math.max(976f / original.getWidth(), 596f / original.getHeight());
    float w = original.getWidth() * scale, h = original.getHeight() * scale;
    canvas.drawBitmap(
        original,
        null,
        new RectF((976 - w) / 2, (596 - h) / 2, (976 + w) / 2, (596 + h) / 2),
        new Paint(Paint.FILTER_BITMAP_FLAG));
    return result;
  }
}
