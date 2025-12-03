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

import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import android.view.InputEvent;
import android.view.MotionEvent;

import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuBinderWrapper;
import rikka.shizuku.SystemServiceHelper;

public class ShizukuInputHelper {

    private static final int INJECT_INPUT_EVENT_MODE_ASYNC = 0;
    private static long dragDownTime;
    private static boolean dragging;

    private ShizukuInputHelper() {}

    public static boolean click(int displayId, float x, float y) {
        if(Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false;
        if(!Shizuku.pingBinder()) return false;

        try {
            IBinder inputBinder = new ShizukuBinderWrapper(SystemServiceHelper.getSystemService("input"));

            Class<?> stubClass = Class.forName("android.hardware.input.IInputManager$Stub");
            Object iInputManager = stubClass
                    .getMethod("asInterface", IBinder.class)
                    .invoke(null, inputBinder);

            Class<?> iInputManagerClass = Class.forName("android.hardware.input.IInputManager");
            java.lang.reflect.Method injectMethod = iInputManagerClass.getMethod("injectInputEvent", InputEvent.class, int.class);

            long now = SystemClock.uptimeMillis();
            MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
            MotionEvent up = MotionEvent.obtain(now, now + 10, MotionEvent.ACTION_UP, x, y, 0);

            setDisplayIdIfPossible(down, displayId);
            setDisplayIdIfPossible(up, displayId);

            injectMethod.invoke(iInputManager, down, INJECT_INPUT_EVENT_MODE_ASYNC);
            injectMethod.invoke(iInputManager, up, INJECT_INPUT_EVENT_MODE_ASYNC);

            down.recycle();
            up.recycle();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean startDrag(int displayId, float x, float y) {
        if(Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false;
        if(!Shizuku.pingBinder()) return false;
        try {
            IBinder inputBinder = new ShizukuBinderWrapper(SystemServiceHelper.getSystemService("input"));
            Class<?> stubClass = Class.forName("android.hardware.input.IInputManager$Stub");
            Object iInputManager = stubClass
                    .getMethod("asInterface", IBinder.class)
                    .invoke(null, inputBinder);
            Class<?> iInputManagerClass = Class.forName("android.hardware.input.IInputManager");
            java.lang.reflect.Method injectMethod = iInputManagerClass.getMethod("injectInputEvent", InputEvent.class, int.class);

            long now = SystemClock.uptimeMillis();
            MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
            setDisplayIdIfPossible(down, displayId);
            injectMethod.invoke(iInputManager, down, INJECT_INPUT_EVENT_MODE_ASYNC);
            down.recycle();

            dragDownTime = now;
            dragging = true;
            return true;
        } catch (Throwable t) {
            dragging = false;
            return false;
        }
    }

    public static void moveDrag(int displayId, float x, float y) {
        if(!dragging) return;
        injectMotion(displayId, MotionEvent.ACTION_MOVE, x, y);
    }

    public static void endDrag(int displayId, float x, float y) {
        if(!dragging) return;
        injectMotion(displayId, MotionEvent.ACTION_UP, x, y);
        dragging = false;
    }

    private static void injectMotion(int displayId, int action, float x, float y) {
        if(Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        if(!Shizuku.pingBinder()) return;
        try {
            IBinder inputBinder = new ShizukuBinderWrapper(SystemServiceHelper.getSystemService("input"));
            Class<?> stubClass = Class.forName("android.hardware.input.IInputManager$Stub");
            Object iInputManager = stubClass
                    .getMethod("asInterface", IBinder.class)
                    .invoke(null, inputBinder);
            Class<?> iInputManagerClass = Class.forName("android.hardware.input.IInputManager");
            java.lang.reflect.Method injectMethod = iInputManagerClass.getMethod("injectInputEvent", InputEvent.class, int.class);

            long now = SystemClock.uptimeMillis();
            MotionEvent ev = MotionEvent.obtain(dragDownTime, now, action, x, y, 0);
            setDisplayIdIfPossible(ev, displayId);
            injectMethod.invoke(iInputManager, ev, INJECT_INPUT_EVENT_MODE_ASYNC);
            ev.recycle();
        } catch (Throwable ignored) {}
    }

    private static void setDisplayIdIfPossible(MotionEvent event, int displayId) {
        if(event == null) return;

        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                MotionEvent.class.getMethod("setDisplayId", int.class).invoke(event, displayId);
            } catch (Throwable ignored) {}
        }
    }
}
