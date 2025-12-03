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

package com.farmerbb.taskbar.activity;

import android.os.Bundle;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.content.SharedPreferences;

import androidx.appcompat.app.AppCompatActivity;

import com.farmerbb.taskbar.R;
import com.farmerbb.taskbar.helper.DesktopModeInputController;
import com.farmerbb.taskbar.util.U;

import static com.farmerbb.taskbar.util.Constants.PREF_DESKTOP_INPUT;
import static com.farmerbb.taskbar.util.Constants.PREF_DESKTOP_GYRO;

public class TrackpadActivity extends AppCompatActivity {

    private final DesktopModeInputController controller = DesktopModeInputController.getInstance();
    private FrameLayout root;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setTitle(R.string.tb_touchpad_label);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        root = new FrameLayout(this);
        root.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        root.setBackgroundColor(getResources().getColor(android.R.color.black));

        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();

        SharedPreferences pref = U.getSharedPreferences(this);
        if(!pref.getBoolean(PREF_DESKTOP_INPUT, true) || !U.isDesktopModeActive(this)) {
            finish();
            return;
        }

        controller.attachTouchpadFullscreen(this, root);
        controller.setGyroEnabled(this, pref.getBoolean(PREF_DESKTOP_GYRO, false));
    }

    @Override
    protected void onPause() {
        super.onPause();
        controller.setGyroEnabled(this, false);
        controller.detachTouchpad();
    }
}
