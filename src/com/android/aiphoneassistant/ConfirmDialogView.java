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

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Full-screen dim + centered card drawn INSIDE the overlay layer (not an
 * Activity) so the underlying screen — and the paused task's context — stays
 * untouched underneath. Shown after the interrupt gesture: "continue or stop
 * the current task?".
 *
 * The window hosting this view is non-focusable; buttons work purely via
 * touch.
 */
public class ConfirmDialogView extends FrameLayout {
    private static final String TAG = "AIPhoneAssistant.Dialog";

    /** Callbacks invoked on the UI thread. */
    public interface Listener {
        /** User chose to keep going: resume the task. */
        void onContinue();

        /** User confirmed: interrupt and stop the task. */
        void onStopConfirmed();
    }

    public ConfirmDialogView(Context context, String taskText, Listener listener) {
        super(context);
        float density = context.getResources().getDisplayMetrics().density;
        int pad = (int) (24 * density);

        // Full-screen dim; clickable so touches are consumed, never forwarded.
        setBackgroundColor(0x99000000);
        setClickable(true);

        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(Color.WHITE);
        cardBg.setCornerRadius(24 * density);
        card.setBackground(cardBg);
        int cardPad = (int) (24 * density);
        card.setPadding(cardPad, cardPad, cardPad, cardPad);

        LayoutParams cardParams = new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        cardParams.leftMargin = pad * 2;
        cardParams.rightMargin = pad * 2;
        cardParams.gravity = Gravity.CENTER;

        TextView title = new TextView(context);
        title.setText(R.string.dialog_stop_title);
        title.setTextSize(19);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.parseColor("#DE000000"));
        card.addView(title);

        if (taskText != null && !taskText.isEmpty()) {
            TextView message = new TextView(context);
            message.setText(context.getString(R.string.dialog_stop_message_format, taskText));
            message.setTextSize(14);
            message.setTextColor(Color.parseColor("#8A000000"));
            message.setPadding(0, (int) (10 * density), 0, 0);
            card.addView(message);
        }

        LinearLayout buttons = new LinearLayout(context);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowParams.topMargin = (int) (20 * density);
        buttons.setLayoutParams(rowParams);

        Button cont = new Button(context);
        cont.setText(R.string.dialog_btn_continue);
        cont.setOnClickListener(v -> {
            if (listener != null) {
                listener.onContinue();
            }
        });
        LinearLayout.LayoutParams contParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        cont.setLayoutParams(contParams);
        buttons.addView(cont);

        Button stop = new Button(context);
        stop.setText(R.string.dialog_btn_stop_task);
        stop.setTextColor(Color.parseColor("#D93025"));
        stop.setOnClickListener(v -> {
            if (listener != null) {
                listener.onStopConfirmed();
            }
        });
        LinearLayout.LayoutParams stopParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        stopParams.leftMargin = (int) (12 * density);
        stop.setLayoutParams(stopParams);
        buttons.addView(stop);

        card.addView(buttons);
        addView(card, cardParams);
    }
}
