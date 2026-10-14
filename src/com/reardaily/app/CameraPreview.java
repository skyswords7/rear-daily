package com.reardaily.app;

import android.content.Context;
import android.graphics.*;
import android.widget.ImageView;

final class CameraPreview extends ImageView {
  CameraPreview(Context c) {
    super(c);
  }

  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    canvas.save();
    canvas.scale(getWidth() / 976f, getHeight() / 596f);
    Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    p.setColor(0xED101A1D);
    for (float y : new float[] {148, 446}) {
      canvas.drawCircle(140, y, 112, p);
      p.setColor(0xFF344548);
      p.setStyle(Paint.Style.STROKE);
      p.setStrokeWidth(4);
      canvas.drawCircle(140, y, 112, p);
      p.setStyle(Paint.Style.FILL);
      p.setColor(0xFF1B2C30);
      canvas.drawCircle(140, y, 70, p);
      p.setColor(0xFF304F55);
      canvas.drawCircle(120, y - 20, 15, p);
      p.setColor(0xED101A1D);
    }
    canvas.restore();
  }
}
