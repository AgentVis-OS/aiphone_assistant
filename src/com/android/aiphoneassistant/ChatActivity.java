/*
 * Copyright (C) 2026 The AgentVisOS Project
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
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Read-only transcript of ONE conversation (one task): the user's task,
 * every key action node with its decision-time screenshot, status lines and
 * the model's completion summary. Live-updates while that conversation's
 * task is executing. New tasks are composed on the conversation list screen.
 */
public class ChatActivity extends Activity {
    private static final String TAG = "AIPhoneAssistant.Chat";
    public static final String EXTRA_CONV_ID = "conv_id";

    private static final int THUMB_TARGET_DIM = 640;

    private ChatStore mStore;
    private long mConvId;
    private ChatStore.Conversation mConv;
    private ChatAdapter mAdapter;

    private ListView mList;
    private TextView mEmptyView;
    private TextView mTitle;
    private TextView mSubtitle;

    private final ExecutorService mImageExecutor = Executors.newSingleThreadExecutor();
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private LruCache<String, Bitmap> mImageCache;

    private BroadcastReceiver mStateReceiver;

    /** Open the transcript of one conversation. */
    public static Intent intent(Context context, long convId) {
        Intent intent = new Intent(context, ChatActivity.class);
        intent.putExtra(EXTRA_CONV_ID, convId);
        return intent;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        mConvId = getIntent().getLongExtra(EXTRA_CONV_ID, 0);
        if (mConvId <= 0) {
            finish();
            return;
        }
        mStore = new ChatStore(this);
        setContentView(R.layout.activity_chat);

        int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);
        mImageCache = new LruCache<String, Bitmap>(maxMemory / 8) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount() / 1024;
            }
        };

        mList = findViewById(R.id.chat_list);
        mEmptyView = findViewById(R.id.chat_empty);
        mTitle = findViewById(R.id.chat_title);
        mSubtitle = findViewById(R.id.chat_subtitle);

        mAdapter = new ChatAdapter();
        mList.setAdapter(mAdapter);
        mList.setEmptyView(mEmptyView);

        findViewById(R.id.chat_back).setOnClickListener(v -> finish());
        findViewById(R.id.chat_settings).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));

        // Edge-to-edge on targetSdk 35: keep the header clear of system bars
        // and the display cutout.
        View root = findViewById(android.R.id.content);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(
                    android.view.WindowInsets.Type.systemBars()
                            | android.view.WindowInsets.Type.displayCutout());
            v.setPadding(0, bars.top, 0, bars.bottom);
            return insets;
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerStateReceiver();
        refresh();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mStateReceiver != null) {
            unregisterReceiver(mStateReceiver);
            mStateReceiver = null;
        }
    }

    @Override
    protected void onDestroy() {
        mImageExecutor.shutdown();
        super.onDestroy();
    }

    private void registerStateReceiver() {
        mStateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                refresh();
            }
        };
        IntentFilter filter = new IntentFilter(AIPhoneAssistantService.BROADCAST_STATE_CHANGED);
        registerReceiver(mStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
    }

    private void refresh() {
        mConv = mStore.getConversation(mConvId);
        if (mConv == null) {
            finish();
            return;
        }
        mTitle.setText(mConv.task);
        mSubtitle.setText(ChatStore.stateLabel(this, mConv.state));
        mAdapter.setMessages(mStore.getMessages(mConvId));
    }

    // ------------------------------------------------------------------
    // Adapter
    // ------------------------------------------------------------------

    private class ChatAdapter extends BaseAdapter {
        private List<ChatStore.Message> mMessages;

        void setMessages(List<ChatStore.Message> messages) {
            mMessages = messages;
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return mMessages != null ? mMessages.size() : 0;
        }

        @Override
        public ChatStore.Message getItem(int position) {
            return mMessages.get(position);
        }

        @Override
        public long getItemId(int position) {
            return mMessages.get(position).id;
        }

        @Override
        public int getViewTypeCount() {
            return 4;
        }

        @Override
        public int getItemViewType(int position) {
            return mMessages.get(position).kind;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ChatStore.Message msg = getItem(position);
            View view = convertView;
            switch (msg.kind) {
                case ChatStore.KIND_USER: {
                    TextView text = convertView != null
                            ? convertView.findViewById(R.id.chat_text)
                            : null;
                    if (text == null) {
                        view = LayoutInflater.from(ChatActivity.this).inflate(
                                R.layout.item_chat_user, parent, false);
                        text = view.findViewById(R.id.chat_text);
                    }
                    text.setText(msg.text);
                    break;
                }
                case ChatStore.KIND_ACTION: {
                    ImageView image;
                    TextView text;
                    if (convertView != null
                            && convertView.findViewById(R.id.chat_image) != null) {
                        view = convertView;
                        image = view.findViewById(R.id.chat_image);
                        text = view.findViewById(R.id.chat_text);
                    } else {
                        view = LayoutInflater.from(ChatActivity.this).inflate(
                                R.layout.item_chat_action, parent, false);
                        image = view.findViewById(R.id.chat_image);
                        text = view.findViewById(R.id.chat_text);
                    }
                    text.setText(msg.text);
                    bindScreenshot(image, msg.imagePath);
                    break;
                }
                case ChatStore.KIND_RESULT:
                case ChatStore.KIND_STATUS:
                default: {
                    boolean result = msg.kind == ChatStore.KIND_RESULT;
                    // ListView only recycles views of the same view type, so a
                    // recycled convertView here is always the right layout.
                    TextView text;
                    if (convertView != null) {
                        view = convertView;
                        text = view.findViewById(R.id.chat_text);
                    } else {
                        view = LayoutInflater.from(ChatActivity.this).inflate(
                                result ? R.layout.item_chat_result
                                        : R.layout.item_chat_status,
                                parent, false);
                        text = view.findViewById(R.id.chat_text);
                    }
                    text.setText(msg.text);
                    break;
                }
            }
            return view;
        }
    }

    // ------------------------------------------------------------------
    // Screenshot thumbnails
    // ------------------------------------------------------------------

    private void bindScreenshot(ImageView view, String fileName) {
        view.setTag(fileName);
        if (fileName == null || fileName.isEmpty()) {
            view.setVisibility(View.GONE);
            view.setImageDrawable(null);
            view.setOnClickListener(null);
            return;
        }
        view.setVisibility(View.VISIBLE);
        view.setOnClickListener(v ->
                startActivity(ImagePreviewActivity.intent(this, fileName)));

        Bitmap cached = mImageCache.get(fileName);
        if (cached != null) {
            view.setImageBitmap(cached);
            return;
        }
        view.setImageDrawable(null);
        mImageExecutor.execute(() -> {
            byte[] bytes = mStore.getImageBytes(fileName);
            Bitmap decoded = bytes != null ? decodeThumbnail(bytes) : null;
            if (decoded != null) {
                mImageCache.put(fileName, decoded);
            }
            mMainHandler.post(() -> {
                if (fileName.equals(view.getTag())) {
                    view.setImageBitmap(decoded);
                }
            });
        });
    }

    private Bitmap decodeThumbnail(byte[] jpeg) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, bounds);
            int sample = 1;
            int maxDim = Math.max(bounds.outWidth, bounds.outHeight);
            while (maxDim / (sample * 2) >= THUMB_TARGET_DIM) {
                sample *= 2;
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, options);
        } catch (Exception e) {
            return null;
        }
    }
}
