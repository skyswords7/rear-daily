package com.reardaily.app;

import android.app.*;
import android.content.*;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.*;
import android.view.*;
import android.widget.*;

/** Shared native UI tokens. No dependency or downloaded font required. */
final class Ui {
  static final int PAPER = 0xFFF5F7F6,
      INK = 0xFF192E29,
      MUTED = 0xFF63746D,
      ACCENT = 0xFF087E72,
      LINE = 0xFFDFE8E3,
      TINT = 0xFFE6F1EC;

  static int dp(Context c, int value) {
    return Math.round(value * c.getResources().getDisplayMetrics().density);
  }

  static GradientDrawable shape(Context c, int color, int radius, boolean border) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(color);
    d.setCornerRadius(dp(c, radius));
    if (border) d.setStroke(dp(c, 1), LINE);
    return d;
  }

  static Drawable touch(Context c, int color, int radius) {
    return new RippleDrawable(
        ColorStateList.valueOf(0x22087E72), shape(c, color, radius, false), null);
  }

  static TextView label(Context c, String text, int size, int color) {
    TextView t = new TextView(c);
    t.setText(text);
    t.setTextSize(size);
    t.setTextColor(color);
    t.setFontFeatureSettings("kern");
    t.setLineSpacing(dp(c, 3), 1f);
    return t;
  }

  static void styleButton(Button b, boolean primary) {
    Context c = b.getContext();
    b.setAllCaps(false);
    b.setTextSize(15);
    b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    b.setTextColor(
        new ColorStateList(
            new int[][] {new int[] {-android.R.attr.state_enabled}, new int[0]},
            new int[] {0xFF9AA9A2, primary ? Color.WHITE : ACCENT}));
    b.setBackground(touch(c, primary ? ACCENT : Color.WHITE, 14));
    b.setBackgroundTintList(null);
    b.setElevation(0);
    b.setStateListAnimator(null);
    b.setMinHeight(dp(c, 50));
    b.setMinimumHeight(dp(c, 50));
    b.setPadding(dp(c, 12), dp(c, 8), dp(c, 12), dp(c, 8));
  }

  static void field(EditText e) {
    Context c = e.getContext();
    e.setTextColor(INK);
    e.setHintTextColor(MUTED);
    e.setTextSize(15);
    e.setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12));
    e.setBackground(shape(c, PAPER, 12, true));
    e.setLineSpacing(dp(c, 4), 1f);
  }

  static void segment(RadioButton b) {
    Context c = b.getContext();
    b.setButtonDrawable((Drawable) null);
    b.setGravity(Gravity.CENTER);
    b.setTextSize(14);
    b.setMinHeight(dp(c, 46));
    b.setTextColor(
        new ColorStateList(
            new int[][] {new int[] {android.R.attr.state_checked}, new int[0]},
            new int[] {ACCENT, MUTED}));
    StateListDrawable states = new StateListDrawable();
    states.addState(new int[] {android.R.attr.state_checked}, shape(c, Color.WHITE, 10, false));
    states.addState(new int[0], shape(c, Color.TRANSPARENT, 10, false));
    b.setBackground(states);
  }

  static void insets(Activity a, View root, int horizontal) {
    a.getWindow().setStatusBarColor(PAPER);
    a.getWindow().setNavigationBarColor(PAPER);
    root.setOnApplyWindowInsetsListener(
        (v, w) -> {
          Insets bars =
              w.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
          v.setPadding(
              dp(a, horizontal) + bars.left, bars.top, dp(a, horizontal) + bars.right, bars.bottom);
          return w;
        });
    root.requestApplyInsets();
  }

  static void finishDialog(AlertDialog d, boolean tall) {
    Window w = d.getWindow();
    if (w == null) return;
    Context c = d.getContext();
    w.setBackgroundDrawable(shape(c, Color.WHITE, 24, false));
    android.graphics.Rect bounds = w.getWindowManager().getCurrentWindowMetrics().getBounds();
    int width = Math.min(bounds.width() - dp(c, 32), dp(c, 560));
    w.setLayout(width, tall ? (int) (bounds.height() * 0.83f) : -2);
    if (tall) {
      final int normalHeight = (int) (bounds.height() * .83f);
      w.getDecorView()
          .getViewTreeObserver()
          .addOnGlobalLayoutListener(
              () -> {
                Rect visible = new Rect();
                w.getDecorView().getWindowVisibleDisplayFrame(visible);
                int desired =
                    Math.min(normalHeight, Math.max(dp(c, 200), visible.height() - dp(c, 24)));
                if (w.getAttributes().height != desired) w.setLayout(width, desired);
              });
    }
    w.setDimAmount(.32f);
    w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    for (int which : new int[] {-1, -2, -3}) {
      Button b = d.getButton(which);
      if (b != null) {
        b.setTextColor(ACCENT);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setMinHeight(dp(c, 48));
      }
    }
  }

  static AlertDialog show(AlertDialog.Builder builder) {
    AlertDialog d = builder.create();
    d.show();
    finishDialog(d, false);
    return d;
  }

  /** Five small line icons keep navigation readable without an icon-font dependency. */
  static final class NavIcon extends Drawable {
    final Paint p = new Paint(3);
    final String name;
    final int color;

    NavIcon(String name, int color) {
      this.name = name;
      this.color = color;
    }

    public void draw(Canvas c) {
      c.save();
      Rect b = getBounds();
      c.translate(b.left, b.top);
      c.scale(b.width() / 24f, b.height() / 24f);
      p.setColor(color);
      p.setStyle(Paint.Style.STROKE);
      p.setStrokeWidth(1.7f);
      p.setStrokeCap(Paint.Cap.ROUND);
      p.setStrokeJoin(Paint.Join.ROUND);
      Path path = new Path();
      switch (name) {
        case "today":
          c.drawRoundRect(3, 4, 21, 20, 4, 4, p);
          c.drawCircle(7, 8, 1.3f, p);
          c.drawCircle(7, 15, 1.3f, p);
          c.drawLine(12, 16, 18, 9, p);
          break;
        case "create":
          path.moveTo(5, 19);
          path.lineTo(6, 14);
          path.lineTo(17, 3);
          path.lineTo(21, 7);
          path.lineTo(10, 18);
          path.close();
          c.drawPath(path, p);
          c.drawLine(14, 6, 18, 10, p);
          c.drawLine(14, 21, 21, 21, p);
          break;
        case "favorites":
          path.moveTo(12, 20);
          path.cubicTo(-4, 10, 5, -1, 12, 7);
          path.cubicTo(19, -1, 28, 10, 12, 20);
          c.drawPath(path, p);
          break;
        case "history":
          c.drawArc(4, 3, 22, 21, -135, 300, false, p);
          path.moveTo(3, 3);
          path.lineTo(3, 9);
          path.lineTo(8, 9);
          c.drawPath(path, p);
          c.drawLine(13, 7, 13, 12, p);
          c.drawLine(13, 12, 17, 14, p);
          break;
        default:
          for (int x : new int[] {6, 12, 18}) c.drawLine(x, 3, x, 21, p);
          p.setStyle(Paint.Style.FILL);
          p.setColor(PAPER);
          c.drawCircle(6, 9, 2.6f, p);
          c.drawCircle(12, 16, 2.6f, p);
          c.drawCircle(18, 7, 2.6f, p);
          p.setStyle(Paint.Style.STROKE);
          p.setColor(color);
          c.drawCircle(6, 9, 2.6f, p);
          c.drawCircle(12, 16, 2.6f, p);
          c.drawCircle(18, 7, 2.6f, p);
      }
      c.restore();
    }

    public void setAlpha(int a) {
      p.setAlpha(a);
    }

    public void setColorFilter(ColorFilter f) {
      p.setColorFilter(f);
    }

    public int getOpacity() {
      return PixelFormat.TRANSLUCENT;
    }
  }

  /** Original illustration of the rear display and its two camera cutouts. */
  static final class RearIllustration extends View {
    final Paint p = new Paint(3);

    RearIllustration(Context c) {
      super(c);
      setContentDescription("双镜头背屏示意图");
    }

    protected void onDraw(Canvas c) {
      super.onDraw(c);
      float w = getWidth(), h = getHeight();
      c.save();
      c.translate((w - Math.min(w, h * 1.65f)) / 2, 0);
      float width = Math.min(w, h * 1.65f);
      c.scale(width / 320f, h / 194f);
      p.setColor(INK);
      c.drawRoundRect(0, 0, 320, 194, 24, 24, p);
      p.setColor(TINT);
      c.drawRoundRect(7, 7, 313, 187, 19, 19, p);
      p.setColor(0xFFB9D4C6);
      Path hill = new Path();
      hill.moveTo(8, 160);
      hill.cubicTo(105, 210, 140, 65, 313, 64);
      hill.lineTo(313, 165);
      hill.quadTo(313, 187, 290, 187);
      hill.lineTo(28, 187);
      hill.quadTo(7, 187, 8, 160);
      c.drawPath(hill, p);
      p.setColor(ACCENT);
      Path ridge = new Path();
      ridge.moveTo(105, 187);
      ridge.cubicTo(175, 170, 226, 110, 313, 120);
      ridge.lineTo(313, 165);
      ridge.quadTo(313, 187, 290, 187);
      ridge.close();
      c.drawPath(ridge, p);
      p.setColor(Color.WHITE);
      c.drawCircle(248, 46, 14, p);
      for (int y : new int[] {51, 139}) {
        p.setColor(INK);
        c.drawCircle(45, y, 31, p);
        p.setColor(0xFF2E4641);
        c.drawCircle(45, y, 23, p);
        p.setColor(0xFF405B54);
        c.drawCircle(40, y - 6, 7, p);
      }
      c.restore();
    }
  }
}
