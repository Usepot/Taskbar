/* Copyright 2025 Braden Farmer
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.farmerbb.taskbar.helper;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.farmerbb.taskbar.R;
import com.farmerbb.taskbar.helper.ShizukuInputHelper;
import com.farmerbb.taskbar.util.DisplayInfo;
import com.farmerbb.taskbar.util.U;

import java.lang.ref.WeakReference;

public class DesktopModeInputController {

    private static final float CURSOR_SPEED_MULTIPLIER = 1.5f;
    private static final float CURSOR_PULSE_SCALE = 1.12f;
    private static final float GYRO_SPEED_MULTIPLIER = 1200f;

    private static DesktopModeInputController theInstance;

    private WeakReference<View> cursorView = new WeakReference<>(null);
    private WeakReference<WindowManager> cursorWindowManager = new WeakReference<>(null);
    private WindowManager.LayoutParams cursorLayoutParams;
    private int cursorBoundsWidth;
    private int cursorBoundsHeight;
    private int cursorDisplayId = Display.DEFAULT_DISPLAY;
    private boolean hasCursorLocation;
    private float cursorX;
    private float cursorY;

    private WeakReference<ViewGroup> touchpadContainer = new WeakReference<>(null);
    private View touchpadView;
    private float lastTouchX;
    private float lastTouchY;
    private boolean touchpadFullscreen;
    private TextView debugView;
    private boolean isDragging;
    private float downX;
    private float downY;
    private float swipeX;
    private float swipeY;
    private int touchSlop;
    private boolean gyroEnabled;
    private boolean suppressGyro;
    private SensorManager sensorManager;
    private SensorEventListener gyroListener;
    private long lastGyroTimestamp;

    private DesktopModeInputController() {}

    public static DesktopModeInputController getInstance() {
        if(theInstance == null) theInstance = new DesktopModeInputController();

        return theInstance;
    }

    public synchronized void attachCursor(Context context, WindowManager windowManager) {
        if(windowManager == null || !U.isDesktopModeActive(context) || !U.canDrawOverlays(context))
            return;

        View existing = cursorView.get();
        if(existing != null) {
            WindowManager existingWm = cursorWindowManager.get();
            if(existingWm == windowManager) {
                updateCursorBounds(context);
                return;
            } else
                detachCursor();
        }

        int size = context.getResources().getDimensionPixelSize(R.dimen.tb_desktop_cursor_size);

        cursorLayoutParams = new WindowManager.LayoutParams(
                size,
                size,
                U.getOverlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        cursorLayoutParams.gravity = Gravity.TOP | Gravity.START;

        View cursor = buildCursorView(context, size);

        updateCursorBounds(context, size);
        if(!hasCursorLocation) centerCursor();

        cursorLayoutParams.x = Math.round(cursorX);
        cursorLayoutParams.y = Math.round(cursorY);
        cursorDisplayId = windowManager.getDefaultDisplay().getDisplayId();

        try {
            windowManager.addView(cursor, cursorLayoutParams);
            cursorView = new WeakReference<>(cursor);
            cursorWindowManager = new WeakReference<>(windowManager);
        } catch (IllegalStateException | IllegalArgumentException ignored) {}
    }

    public synchronized void detachCursor() {
        View view = cursorView.get();
        WindowManager wm = cursorWindowManager.get();
        if(view != null && wm != null) {
            try {
                wm.removeView(view);
            } catch (IllegalArgumentException ignored) {}
        }

        cursorView = new WeakReference<>(null);
        cursorWindowManager = new WeakReference<>(null);
        cursorLayoutParams = null;
        cursorBoundsWidth = 0;
        cursorBoundsHeight = 0;
        cursorDisplayId = Display.DEFAULT_DISPLAY;
        hasCursorLocation = false;
    }

    public synchronized void attachTouchpad(Context context, FrameLayout container) {
        attachTouchpad(context, container, false);
    }

    public synchronized void attachTouchpadFullscreen(Context context, FrameLayout container) {
        attachTouchpad(context, container, true);
    }

    private void attachTouchpad(Context context, FrameLayout container, boolean fullscreen) {
        if(container == null || !U.isDesktopModeActive(context)) {
            detachTouchpad();
            return;
        }

        ViewGroup currentContainer = touchpadContainer.get();
        if(touchpadView != null && currentContainer == container && touchpadFullscreen == fullscreen)
            return;

        detachTouchpad();

        FrameLayout pad = new FrameLayout(context);
        pad.setClickable(true);
        pad.setFocusable(true);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();

        int padWidth = fullscreen
                ? FrameLayout.LayoutParams.MATCH_PARENT
                : context.getResources().getDimensionPixelSize(R.dimen.tb_touchpad_width);
        int padHeight = fullscreen
                ? FrameLayout.LayoutParams.MATCH_PARENT
                : context.getResources().getDimensionPixelSize(R.dimen.tb_touchpad_height);
        int padMargin = fullscreen ? 0 : context.getResources().getDimensionPixelSize(R.dimen.tb_touchpad_margin);
        int padPadding = context.getResources().getDimensionPixelSize(R.dimen.tb_touchpad_padding);

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(padWidth, padHeight);
        params.gravity = fullscreen ? Gravity.CENTER : Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        params.setMargins(padMargin, padMargin, padMargin, padMargin);
        pad.setLayoutParams(params);
        pad.setPadding(padPadding, padPadding, padPadding, padPadding);

        pad.setBackground(createTouchpadBackground(context));

        TextView label = new TextView(context);
        label.setText(fullscreen
                ? context.getString(R.string.tb_touchpad_label_fullscreen)
                : context.getString(R.string.tb_touchpad_label));
        label.setTextColor(Color.WHITE);
        label.setGravity(Gravity.CENTER_HORIZONTAL);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, fullscreen ? 18 : 14);
        label.setAlpha(0.85f);

        debugView = new TextView(context);
        debugView.setTextColor(Color.WHITE);
        debugView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        debugView.setAlpha(0.8f);

        FrameLayout.LayoutParams labelParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
        );
        labelParams.gravity = fullscreen ? Gravity.TOP | Gravity.CENTER_HORIZONTAL : Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        labelParams.bottomMargin = dpToPx(context, 4);
        labelParams.topMargin = fullscreen ? dpToPx(context, 16) : 0;
        pad.addView(label, labelParams);

        FrameLayout.LayoutParams debugParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
        );
        debugParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        debugParams.bottomMargin = dpToPx(context, 8);
        pad.addView(debugView, debugParams);

        pad.setOnTouchListener((v, event) -> {
            switch(event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    lastTouchX = event.getX();
                    lastTouchY = event.getY();
                    downX = cursorX;
                    downY = cursorY;
                    swipeX = cursorX;
                    swipeY = cursorY;
                    isDragging = false;
                    v.setPressed(true);
                    updateDebug("touch down");
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getX() - lastTouchX;
                    float dy = event.getY() - lastTouchY;
                    lastTouchX = event.getX();
                    lastTouchY = event.getY();
                    if(!gyroEnabled) {
                        moveCursorBy(context, dx, dy);
                        updateDebug("touch move dx=" + Math.round(dx) + " dy=" + Math.round(dy));
                    } else {
                        if(!isDragging && shouldStartDrag(dx, dy)) {
                            isDragging = ShizukuInputHelper.startDrag(cursorDisplayId, swipeX, swipeY);
                            suppressGyro = true;
                        }
                        if(isDragging) {
                            swipeX = swipeX + (dx * CURSOR_SPEED_MULTIPLIER);
                            swipeY = swipeY + (dy * CURSOR_SPEED_MULTIPLIER);
                            float sendX = clamp(swipeX, 0, cursorBoundsWidth);
                            float sendY = clamp(swipeY, 0, cursorBoundsHeight);
                            ShizukuInputHelper.moveDrag(cursorDisplayId, sendX, sendY);
                            // Keep on-screen cursor indicator in sync with swipe location for accuracy.
                            cursorX = sendX;
                            cursorY = sendY;
                            applyCursorPosition();
                            updateDebug("swipe drag x=" + Math.round(sendX) + " y=" + Math.round(sendY));
                        }
                    }

                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.setPressed(false);
                    if(isDragging) {
                        float endX = gyroEnabled ? clamp(swipeX, 0, cursorBoundsWidth) : cursorX;
                        float endY = gyroEnabled ? clamp(swipeY, 0, cursorBoundsHeight) : cursorY;
                        ShizukuInputHelper.endDrag(cursorDisplayId, endX, endY);
                        cursorX = endX;
                        cursorY = endY;
                        applyCursorPosition();
                    } else
                        performClick(context);
                    isDragging = false;
                    suppressGyro = false;
                    updateDebug("touch up");
                    pulseCursor();
                    return true;
                default:
                    return false;
            }
        });

        container.addView(pad);
        pad.bringToFront();

        touchpadContainer = new WeakReference<>(container);
        touchpadView = pad;
        touchpadFullscreen = fullscreen;
    }

    public synchronized void detachTouchpad() {
        ViewGroup container = touchpadContainer.get();
        if(container != null && touchpadView != null) {
            container.removeView(touchpadView);
        }

        touchpadContainer = new WeakReference<>(null);
        touchpadView = null;
        touchpadFullscreen = false;
    }

    public synchronized void updateCursorBounds(Context context) {
        int size = cursorLayoutParams != null
                ? cursorLayoutParams.width
                : context.getResources().getDimensionPixelSize(R.dimen.tb_desktop_cursor_size);
        updateCursorBounds(context, size);
    }

    private void updateCursorBounds(Context context, int cursorSize) {
        int width = 0;
        int height = 0;

        WindowManager wm = cursorWindowManager.get();
        if(wm != null) {
            Display display = wm.getDefaultDisplay();
            if(display != null) {
                android.graphics.Point real = new android.graphics.Point();
                display.getRealSize(real);
                width = real.x;
                height = real.y;
            }
        }

        if(width == 0 || height == 0) {
            DisplayInfo info = U.getDisplayInfo(context);
            width = info.width;
            height = info.height;
        }

        cursorBoundsWidth = Math.max(0, width - cursorSize);
        cursorBoundsHeight = Math.max(0, height - cursorSize);
        cursorX = clamp(cursorX, 0, cursorBoundsWidth);
        cursorY = clamp(cursorY, 0, cursorBoundsHeight);
        hasCursorLocation = hasCursorLocation || (cursorX != 0 || cursorY != 0);
    }

    private void centerCursor() {
        cursorX = cursorBoundsWidth / 2f;
        cursorY = cursorBoundsHeight / 2f;
        hasCursorLocation = true;
    }

    private void moveCursorBy(Context context, float dx, float dy) {
        if(cursorLayoutParams == null) {
            int size = context.getResources().getDimensionPixelSize(R.dimen.tb_desktop_cursor_size);
            updateCursorBounds(context, size);
        } else if(cursorBoundsWidth == 0 || cursorBoundsHeight == 0)
            updateCursorBounds(context, cursorLayoutParams.width);

        cursorX = clamp(cursorX + (dx * CURSOR_SPEED_MULTIPLIER), 0, cursorBoundsWidth);
        cursorY = clamp(cursorY + (dy * CURSOR_SPEED_MULTIPLIER), 0, cursorBoundsHeight);

        applyCursorPosition();
        updateDebug("cursor " + Math.round(cursorX) + "," + Math.round(cursorY));
    }

    private boolean shouldStartDrag() {
        float dx = cursorX - downX;
        float dy = cursorY - downY;
        return Math.hypot(dx, dy) > touchSlop;
    }

    private boolean shouldStartDrag(float dx, float dy) {
        return Math.hypot(dx, dy) > touchSlop;
    }

    private void performClick(Context context) {
        ShizukuInputHelper.click(cursorDisplayId, cursorX, cursorY);
    }

    private void applyCursorPosition() {
        WindowManager wm = cursorWindowManager.get();
        View view = cursorView.get();
        if(wm != null && view != null && cursorLayoutParams != null) {
            cursorLayoutParams.x = Math.round(cursorX);
            cursorLayoutParams.y = Math.round(cursorY);

            try {
                wm.updateViewLayout(view, cursorLayoutParams);
            } catch (IllegalArgumentException ignored) {}
        }
    }

    private void pulseCursor() {
        View view = cursorView.get();
        if(view != null) {
            view.animate()
                    .scaleX(CURSOR_PULSE_SCALE)
                    .scaleY(CURSOR_PULSE_SCALE)
                    .setDuration(80)
                    .withEndAction(() ->
                            view.animate()
                                    .scaleX(1f)
                                    .scaleY(1f)
                                    .setDuration(80)
                                    .start()
                    )
                    .start();
        }
    }

    private View buildCursorView(Context context, int size) {
        View view = new View(context);
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        drawable.setColor(Color.WHITE);
        drawable.setStroke(Math.max(2, (int) (size * 0.15f)), Color.parseColor("#44000000"));
        drawable.setSize(size, size);
        view.setBackground(drawable);
        view.setElevation(dpToPx(context, 4));
        return view;
    }

    private GradientDrawable createTouchpadBackground(Context context) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(Color.parseColor("#CC1C1C1E"));
        drawable.setCornerRadius(dpToPx(context, 12));
        drawable.setStroke(dpToPx(context, 1), Color.parseColor("#40FFFFFF"));
        return drawable;
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private int dpToPx(Context context, float dp) {
        float density = context.getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }

    private void updateDebug(String message) {
        if(debugView == null) return;
        debugView.post(() -> debugView.setText(message));
    }

    public synchronized void setGyroEnabled(Context context, boolean enabled) {
        if(enabled == gyroEnabled) return;
        gyroEnabled = enabled;
        if(enabled)
            enableGyro(context);
        else
            disableGyro();
    }

    private void enableGyro(Context context) {
        sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        if(sensorManager == null) return;

        Sensor sensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        if(sensor == null) return;

        lastGyroTimestamp = 0;
        gyroListener = new SensorEventListener() {
            @Override
            public void onSensorChanged(SensorEvent event) {
                if(suppressGyro) {
                    lastGyroTimestamp = event.timestamp;
                    return;
                }

                if(lastGyroTimestamp != 0) {
                    float dt = (event.timestamp - lastGyroTimestamp) / 1_000_000_000f;
                    // event.values: [rot x, rot y, rot z]
                    // Rotate phone left/right (yaw around Z) moves cursor left/right.
                    float dx = -event.values[2] * GYRO_SPEED_MULTIPLIER * dt;
                    // Tilt phone forward/back (pitch around X) moves cursor up/down (invert to match natural feel).
                    float dy = -event.values[0] * GYRO_SPEED_MULTIPLIER * dt;
                    moveCursorBy(context, dx, dy);
                    updateDebug("gyro dx=" + String.format("%.2f", dx) + " dy=" + String.format("%.2f", dy));
                }
                lastGyroTimestamp = event.timestamp;
            }

            @Override
            public void onAccuracyChanged(Sensor sensor, int accuracy) {}
        };

        sensorManager.registerListener(gyroListener, sensor, SensorManager.SENSOR_DELAY_GAME);
    }

    private void disableGyro() {
        if(sensorManager != null && gyroListener != null)
            sensorManager.unregisterListener(gyroListener);
        gyroListener = null;
        lastGyroTimestamp = 0;
    }
}
