package com.liskovsoft.smartyoutubetv2.tv.ui.mod.leanback.misc;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * YouTube Me's video buffering indicator, drawn from the user's Lottie keyframes.
 * No web requests or external animation dependencies. Used only by playback.
 */
public final class YoutubeMeLoadingView extends View {
    private static final int DOTS = 3;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final double[][][] position = new double[DOTS][][];
    private final double[][][] radius = new double[DOTS][][];
    private final double[][][] colors = new double[DOTS][][];
    private double endFrame = 348.48;
    private double fps = 60;
    private long startedNanos;

    public YoutubeMeLoadingView(Context context) {
        super(context);
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        loadAnimation();
    }

    private void loadAnimation() {
        try (InputStream in = getContext().getAssets().open("ytme_loading.json")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[4096]; int n;
            while ((n = in.read(b)) != -1) out.write(b, 0, n);
            JSONObject doc = new JSONObject(out.toString("UTF-8"));
            fps = doc.optDouble("fr", 60);
            endFrame = doc.optDouble("op", 348.48);
            JSONArray layers = doc.getJSONArray("assets").getJSONObject(0).getJSONArray("layers");
            for (int i = 0; i < DOTS; i++) {
                JSONObject layer = layers.getJSONObject(i);
                JSONObject ks = layer.getJSONObject("ks");
                position[i] = keys(ks.getJSONObject("p").getJSONArray("k"), 2);
                radius[i] = keys(ks.getJSONObject("a").getJSONArray("k"), 2);
                colors[i] = keys(layer.getJSONArray("shapes").getJSONObject(1)
                        .getJSONObject("c").getJSONArray("k"), 3);
            }
        } catch (Exception err) {
            // Fallback draws the same three dots if the JSON cannot be read.
        }
    }

    private static double[][] keys(JSONArray source, int dimensions) throws Exception {
        double[][] parsed = new double[source.length()][dimensions + 1];
        for (int i = 0; i < source.length(); i++) {
            JSONObject key = source.getJSONObject(i);
            parsed[i][0] = key.optDouble("t", i * 36);
            JSONArray v = key.optJSONArray("s");
            for (int d = 0; d < dimensions; d++) {
                parsed[i][d + 1] = v != null ? v.optDouble(d, i == 0 ? 0 : parsed[i - 1][d + 1])
                        : i == 0 ? 0 : parsed[i - 1][d + 1];
            }
        }
        return parsed;
    }

    private static double at(double[][] keys, double frame, int column, double fallback) {
        if (keys == null || keys.length == 0) return fallback;
        for (int i = 0; i < keys.length - 1; i++) {
            if (frame < keys[i + 1][0]) {
                double delta = keys[i + 1][0] - keys[i][0];
                double t = delta > 0 ? (frame - keys[i][0]) / delta : 0;
                // Smooth transition matches the easing of the supplied animation.
                t = Math.max(0, Math.min(1, t));
                t = t * t * (3 - 2 * t);
                return keys[i][column] + (keys[i + 1][column] - keys[i][column]) * t;
            }
        }
        return keys[keys.length - 1][column];
    }

    @Override public void setVisibility(int visibility) {
        super.setVisibility(visibility);
        if (visibility == VISIBLE) {
            startedNanos = System.nanoTime();
            postInvalidateOnAnimation();
        }
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float sx = getWidth() / 68f;
        float sy = getHeight() / 41f;
        canvas.save();
        canvas.scale(sx, sy);
        paint.setColor(Color.argb(26, 255, 255, 255));
        canvas.drawRoundRect(0, 0, 68, 41, 20.5f, 20.5f, paint);
        double frame = ((System.nanoTime() - startedNanos) / 1000000000.0 * fps) % endFrame;
        for (int i = DOTS - 1; i >= 0; i--) {
            double x = at(position[i], frame, 1, 24 + (2 - i) * 10.5);
            double y = at(position[i], frame, 2, 20.5);
            double r = at(radius[i], frame, 1, 3.5);
            double brightness = at(colors[i], frame, 1, i == 2 ? 1 : .5753);
            int v = (int) Math.max(0, Math.min(255, brightness * 255));
            paint.setColor(Color.rgb(v, v, v));
            canvas.drawCircle((float)x, (float)y, (float)r, paint);
        }
        canvas.restore();
        if (getVisibility() == VISIBLE && isAttachedToWindow()) postInvalidateDelayed(33);
    }

    public static void setup(androidx.leanback.app.ProgressBarManager manager, ViewGroup root) {
        if (root == null) return;
        YoutubeMeLoadingView indicator = new YoutubeMeLoadingView(root.getContext());
        float density = root.getResources().getDisplayMetrics().density;
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                (int)(102 * density), (int)(62 * density), Gravity.CENTER);
        root.addView(indicator, params);
        manager.setProgressBarView(indicator);
    }
}
