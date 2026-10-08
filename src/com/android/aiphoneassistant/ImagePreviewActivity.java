/*
 * Copyright (C) 2024 The LineageOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.aiphoneassistant;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;

/**
 * Full-screen viewer for a stored key-node screenshot: pinch to zoom,
 * drag to pan, double-tap toggles zoom, single tap closes.
 */
public class ImagePreviewActivity extends Activity {
    private static final String TAG = "AIPhoneAssistant.Preview";
    public static final String EXTRA_IMAGE_NAME = "image_name";

    private final Object mLock = new Object();

    /** Open the full-screen viewer for a stored screenshot. */
    public static Intent intent(Context context, String imageName) {
        Intent intent = new Intent(context, ImagePreviewActivity.class);
        intent.putExtra(EXTRA_IMAGE_NAME, imageName);
        return intent;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_image_preview);

        final String name = getIntent().getStringExtra(EXTRA_IMAGE_NAME);
        final ImageView image = findViewById(R.id.preview_image);
        final ProgressBar progress = findViewById(R.id.preview_progress);

        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);

        if (name == null || name.isEmpty()) {
            finish();
            return;
        }
        View.OnClickListener close = v -> {
            finish();
            overridePendingTransition(0, android.R.anim.fade_out);
        };
        image.setOnClickListener(close);
        findViewById(R.id.preview_close).setOnClickListener(close);

        // Decode off the UI thread; the blob is a small JPEG (~100 KB).
        new Thread(() -> {
            byte[] bytes = new ChatStore(this).getImageBytes(name);
            Bitmap bitmap = null;
            if (bytes != null) {
                try {
                    bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                } catch (Exception e) {
                    Log.e(TAG, "Failed to decode screenshot " + name, e);
                }
            }
            final Bitmap result = bitmap;
            runOnUiThread(() -> {
                progress.setVisibility(View.GONE);
                if (result != null) {
                    image.setImageBitmap(result);
                } else {
                    finish();
                }
            });
        }, "preview-decode").start();
    }
}
