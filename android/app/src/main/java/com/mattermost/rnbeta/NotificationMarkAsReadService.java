package com.mattermost.rnbeta;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.Bundle;
import android.text.TextUtils;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableMap;
import com.mattermost.helpers.CustomPushNotificationHelper;
import com.mattermost.helpers.DatabaseHelper;
import com.mattermost.helpers.Network;
import com.mattermost.helpers.ResolvePromise;
import com.mattermost.turbolog.TurboLog;
import com.mattermost.rnutils.helpers.NotificationHelper;
import com.nozbe.watermelondb.WMDatabase;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.Response;

import static com.mattermost.helpers.database_extension.GeneralKt.getDatabaseForServer;
import static com.mattermost.helpers.database_extension.SystemKt.queryCurrentTeamId;

public class NotificationMarkAsReadService extends Service {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    public int onStartCommand(final Intent intent, int flags, final int startId) {
        if (intent == null) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    handleIntent(intent);
                } finally {
                    stopSelf(startId);
                }
            }
        });

        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        executor.shutdown();
        super.onDestroy();
    }

    private void handleIntent(Intent intent) {
        final Bundle bundle = intent.getBundleExtra(CustomPushNotificationHelper.NOTIFICATION);
        if (bundle == null) {
            return;
        }

        final String serverUrl = bundle.getString("server_url");
        final String channelId = bundle.getString("channel_id");
        final String threadId = bundle.getString("root_id");
        final boolean isCRTEnabled = bundle.containsKey("is_crt_enabled") && "true".equals(bundle.getString("is_crt_enabled"));

        if (TextUtils.isEmpty(serverUrl) || TextUtils.isEmpty(channelId)) {
            return;
        }

        Network.init(this);

        if (isCRTEnabled && !TextUtils.isEmpty(threadId)) {
            if (markThreadAsRead(serverUrl, bundle, threadId)) {
                NotificationHelper.INSTANCE.clearChannelOrThreadNotifications(this, bundle);
            }
            return;
        }

        if (markChannelAsRead(serverUrl, channelId)) {
            NotificationHelper.INSTANCE.clearChannelOrThreadNotifications(this, bundle);
        }
    }

    private boolean markChannelAsRead(String serverUrl, String channelId) {
        WritableMap headers = Arguments.createMap();
        headers.putString("Content-Type", "application/json");

        WritableMap body = Arguments.createMap();
        body.putString("channel_id", channelId);
        body.putBoolean("collapsed_threads_supported", true);

        WritableMap options = Arguments.createMap();
        options.putMap("headers", headers);
        options.putMap("body", body);

        try (Response response = Network.postSync(serverUrl, "/api/v4/channels/members/me/view", options)) {
            if (response == null || response.code() < 200 || response.code() >= 300) {
                TurboLog.Companion.i("ReactNative", "Mark channel as read failed");
                return false;
            }

            updateLocalChannelReadState(serverUrl, channelId);
            return true;
        } catch (Exception e) {
            TurboLog.Companion.i("ReactNative", String.format("Mark channel as read failed %s", e.getMessage()));
            return false;
        }
    }

    private boolean markThreadAsRead(String serverUrl, Bundle bundle, String threadId) {
        final long timestamp = System.currentTimeMillis();
        String teamId = bundle.getString("team_id");
        if (TextUtils.isEmpty(teamId)) {
            teamId = getCurrentTeamId(serverUrl);
        }

        if (TextUtils.isEmpty(teamId)) {
            return false;
        }

        WritableMap headers = Arguments.createMap();
        headers.putString("Content-Type", "application/json");

        WritableMap body = Arguments.createMap();

        WritableMap options = Arguments.createMap();
        options.putMap("headers", headers);
        options.putMap("body", body);

        final String endpoint = String.format("/api/v4/users/me/teams/%s/threads/%s/read/%d", teamId, threadId, timestamp);
        try {
            BlockingNetworkPromise promise = new BlockingNetworkPromise();
            Network.put(serverUrl, endpoint, options, promise);
            if (!promise.awaitSuccess()) {
                TurboLog.Companion.i("ReactNative", "Mark thread as read failed");
                return false;
            }

            updateLocalThreadReadState(serverUrl, threadId, timestamp);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            TurboLog.Companion.i("ReactNative", String.format("Mark thread as read failed %s", e.getMessage()));
            return false;
        } catch (Exception e) {
            TurboLog.Companion.i("ReactNative", String.format("Mark thread as read failed %s", e.getMessage()));
            return false;
        }
    }

    private String getCurrentTeamId(String serverUrl) {
        final WMDatabase db = getServerDatabase(serverUrl);
        if (db == null) {
            return null;
        }
        return queryCurrentTeamId(db);
    }

    private void updateLocalChannelReadState(String serverUrl, String channelId) {
        final WMDatabase db = getServerDatabase(serverUrl);
        if (db == null) {
            return;
        }

        db.execute(
                "UPDATE MyChannel SET is_unread = ?, mentions_count = ?, manually_unread = ?, viewed_at = last_viewed_at, last_viewed_at = ?, _status = 'updated' WHERE id = ?",
                new Object[]{false, 0, false, System.currentTimeMillis(), channelId}
        );
    }

    private void updateLocalThreadReadState(String serverUrl, String threadId, long timestamp) {
        final WMDatabase db = getServerDatabase(serverUrl);
        if (db == null) {
            return;
        }

        db.execute(
                "UPDATE Thread SET last_viewed_at = ?, viewed_at = ?, unread_replies = ?, unread_mentions = ?, _status = 'updated' WHERE id = ?",
                new Object[]{timestamp, timestamp, 0, 0, threadId}
        );
    }

    private WMDatabase getServerDatabase(String serverUrl) {
        final DatabaseHelper dbHelper = DatabaseHelper.Companion.getInstance();
        if (dbHelper == null) {
            return null;
        }

        dbHelper.init(this);
        return getDatabaseForServer(dbHelper, this, serverUrl);
    }

    private static class BlockingNetworkPromise extends ResolvePromise {
        private static final long TIMEOUT_SECONDS = 35;
        private final CountDownLatch latch = new CountDownLatch(1);
        private boolean success;

        @Override
        public void resolve(Object value) {
            try {
                success = isSuccessfulResponse(value);
            } finally {
                latch.countDown();
            }
        }

        @Override
        public void reject(String code) {
            latch.countDown();
        }

        @Override
        public void reject(String code, String message) {
            latch.countDown();
        }

        @Override
        public void reject(String code, Throwable throwable) {
            latch.countDown();
        }

        @Override
        public void reject(String code, String message, Throwable throwable) {
            latch.countDown();
        }

        @Override
        public void reject(Throwable throwable) {
            latch.countDown();
        }

        @Override
        public void reject(Throwable throwable, WritableMap userInfo) {
            latch.countDown();
        }

        @Override
        public void reject(String code, WritableMap userInfo) {
            latch.countDown();
        }

        @Override
        public void reject(String code, Throwable throwable, WritableMap userInfo) {
            latch.countDown();
        }

        @Override
        public void reject(String code, String message, WritableMap userInfo) {
            latch.countDown();
        }

        @Override
        public void reject(String code, String message, Throwable throwable, WritableMap userInfo) {
            latch.countDown();
        }

        public boolean awaitSuccess() throws InterruptedException {
            return latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS) && success;
        }

        private static boolean isSuccessfulResponse(Object value) {
            if (!(value instanceof ReadableMap)) {
                return false;
            }

            ReadableMap response = (ReadableMap)value;
            if (response.hasKey("code")) {
                int statusCode = response.getInt("code");
                return statusCode >= 200 && statusCode < 300;
            }

            return response.hasKey("ok") && response.getBoolean("ok");
        }
    }
}
