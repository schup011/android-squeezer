/*
 * Copyright (c) 2009 Google Inc.  All Rights Reserved.
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

package uk.org.ngo.squeezer.service;

import android.annotation.TargetApi;
import android.app.DownloadManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.media.MediaMetadata;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.os.PowerManager;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.util.Base64;
import android.util.Log;
import android.widget.RemoteViews;

import com.google.common.io.Files;

import org.eclipse.jetty.util.ajax.JSON;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ScheduledThreadPoolExecutor;

import uk.org.ngo.squeezer.NowPlayingActivity;
import uk.org.ngo.squeezer.Preferences;
import uk.org.ngo.squeezer.R;
import uk.org.ngo.squeezer.Squeezer;
import uk.org.ngo.squeezer.Util;
import uk.org.ngo.squeezer.download.DownloadDatabase;
import uk.org.ngo.squeezer.framework.Action;
import uk.org.ngo.squeezer.framework.Item;
import uk.org.ngo.squeezer.itemlist.IServiceItemListCallback;
import uk.org.ngo.squeezer.model.Alarm;
import uk.org.ngo.squeezer.model.AlarmPlaylist;
import uk.org.ngo.squeezer.model.CurrentPlaylistItem;
import uk.org.ngo.squeezer.model.Player;
import uk.org.ngo.squeezer.model.PlayerState;
import uk.org.ngo.squeezer.model.Plugin;
import uk.org.ngo.squeezer.model.Song;
import uk.org.ngo.squeezer.service.event.ConnectionChanged;
import uk.org.ngo.squeezer.service.event.HandshakeComplete;
import uk.org.ngo.squeezer.service.event.MusicChanged;
import uk.org.ngo.squeezer.service.event.PlayStatusChanged;
import uk.org.ngo.squeezer.service.event.PlayerStateChanged;
import uk.org.ngo.squeezer.service.event.PlayersChanged;
import uk.org.ngo.squeezer.service.event.SongTimeChanged;
import uk.org.ngo.squeezer.util.ImageFetcher;
import uk.org.ngo.squeezer.util.ImageWorker;
import uk.org.ngo.squeezer.util.NotificationUtil;
import uk.org.ngo.squeezer.util.Scrobble;

/**
 * Persistent service which acts as an interface to for activities to communicate with LMS.
 * <p>
 * The interface is documented here {@link ISqueezeService}
 * <p>
 * The service lifecycle is managed as both a bound and a started servic. as follows.
 * <ul>
 *     <li>On connect to LMS call Context.start[Foreground]Service and Service.startForeground</li>
 *     <li>On disconnect from LMS call Service.stopForeground and Service.stopSelf</li>
 *     <li>bind to the SqueezeService in activities in onCreate</li>
 *     <li>unbind the SqueezeService in activities  onDestroy</li>
 * </ul>
 * This means the service will as long as there is a Squeezer or we are connected to LMS activity.
 * When we are connected to LMS it runs as a foreground service and a notification is displayed.
 */
public class SqueezeService extends Service {

    private static final String TAG = "SqueezeService";

    public static final String NOTIFICATION_CHANNEL_ID = "channel_squeezer_1";
    private static final int PLAYBACKSERVICE_STATUS = 1;
    public static final int DOWNLOAD_ERROR = 2;

    /**
     * Information that will be requested about songs.
     * <p>
     * a:artist artist name<br/>
     * C:compilation (1 if true, missing otherwise)<br/>
     * j:coverart (1 if available, missing otherwise)<br/>
     * J:artwork_track_id (if available, missing otherwise)<br/>
     * K:artwork_url URL to remote artwork<br/>
     * l:album album name<br/>
     * t:tracknum, if known<br/>
     * u:url Song file url<br/>
     * x:remote 1, if this is a remote track<br/>
     */
    // This should probably be a field in Song.
    public static final String SONGTAGS = "aCjJKltux";

    /** Service-specific eventbus. All events generated by the service will be sent here. */
    private final EventBus mEventBus = new EventBus();

    /** Executor for off-main-thread work. */
    @NonNull
    private final ScheduledThreadPoolExecutor mExecutor = new ScheduledThreadPoolExecutor(1);

    /** True if the handshake with the server has completed, otherwise false. */
    private volatile boolean mHandshakeComplete = false;

    /** Media session to associate with ongoing notifications. */
    private MediaSessionCompat mMediaSession;

    /** Are the service currently in the foregrund */
    private volatile boolean foreGround;

    /** The most recent notifcation. */
    private NotificationState ongoingNotification;

    private final SlimDelegate mDelegate = new SlimDelegate(mEventBus);

    /**
     * Is scrobbling enabled?
     */
    private boolean scrobblingEnabled;

    /**
     * Was scrobbling enabled?
     */
    private boolean scrobblingPreviouslyEnabled;

    int mFadeInSecs;

    private static final String ACTION_NEXT_TRACK = "uk.org.ngo.squeezer.service.ACTION_NEXT_TRACK";
    private static final String ACTION_PREV_TRACK = "uk.org.ngo.squeezer.service.ACTION_PREV_TRACK";
    private static final String ACTION_PLAY = "uk.org.ngo.squeezer.service.ACTION_PLAY";
    private static final String ACTION_PAUSE = "uk.org.ngo.squeezer.service.ACTION_PAUSE";
    private static final String ACTION_CLOSE = "uk.org.ngo.squeezer.service.ACTION_CLOSE";

    private final BroadcastReceiver deviceIdleModeReceiver = new BroadcastReceiver() {
        @Override
        @RequiresApi(api = Build.VERSION_CODES.M)
        public void onReceive(Context context, Intent intent) {
            // On M and above going in to Doze mode suspends the network but does not shut down
            // existing network connections or cause them to generate exceptions. Explicitly
            // disconnect here, so that resuming from Doze mode forces a reconnect. See
            // https://github.com/nikclayton/android-squeezer/issues/177.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);

                if (pm.isDeviceIdleMode()) {
                    Log.d(TAG, "Entering doze mode, disconnecting");
                    disconnect();
                }
            }
        }
    };


    /**
     * Thrown when the service is asked to send a command to the server before the server
     * handshake completes.
     */
    public static class HandshakeNotCompleteException extends IllegalStateException {
        public HandshakeNotCompleteException() { super(); }
        public HandshakeNotCompleteException(String message) { super(message); }
        public HandshakeNotCompleteException(String message, Throwable cause) { super(message, cause); }
        public HandshakeNotCompleteException(Throwable cause) { super(cause); }
    }

    @Override
    public void onCreate() {
        super.onCreate();

        // Clear leftover notification in case this service previously got killed while playing
        NotificationManagerCompat nm = NotificationManagerCompat.from(this);
        nm.cancel(PLAYBACKSERVICE_STATUS);

        cachePreferences();

        setWifiLock(((WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE)).createWifiLock(
                WifiManager.WIFI_MODE_FULL, "Squeezer_WifiLock"));

        mEventBus.register(this, 1);  // Get events before other subscribers

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            registerReceiver(deviceIdleModeReceiver, new IntentFilter(
                    PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED));
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try{
            if(intent != null && intent.getAction()!= null ) {
                if (intent.getAction().equals(ACTION_NEXT_TRACK)) {
                    squeezeService.nextTrack();
                } else if (intent.getAction().equals(ACTION_PREV_TRACK)) {
                    squeezeService.previousTrack();
                } else if (intent.getAction().equals(ACTION_PLAY)) {
                    squeezeService.play();
                } else if (intent.getAction().equals(ACTION_PAUSE)) {
                    squeezeService.pause();
                } else if (intent.getAction().equals(ACTION_CLOSE)) {
                    squeezeService.disconnect();
                }
            }
        } catch(Exception e) {

        }
        return START_STICKY;
    }

    /**
     * Cache the value of various preferences.
     */
    private void cachePreferences() {
        final SharedPreferences preferences = getSharedPreferences(Preferences.NAME, MODE_PRIVATE);
        scrobblingEnabled = preferences.getBoolean(Preferences.KEY_SCROBBLE_ENABLED, false);
        mFadeInSecs = preferences.getInt(Preferences.KEY_FADE_IN_SECS, 0);
    }

    @Override
    public IBinder onBind(Intent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            mMediaSession = new MediaSessionCompat(getApplicationContext(), "squeezer");
        }
        return (IBinder) squeezeService;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            if (mMediaSession != null) {
                mMediaSession.release();
            }
        }
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        disconnect();
        mEventBus.unregister(this);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                unregisterReceiver(deviceIdleModeReceiver);
            } catch (IllegalArgumentException e) {
                // Do nothing. This can occur in testing when we destroy the service before the
                // receiver is registered.
            }
        }
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        disconnect();
        super.onTaskRemoved(rootIntent);
    }

    void disconnect() {
        mDelegate.disconnect();
    }

    @Nullable public PlayerState getActivePlayerState() {
        Player activePlayer = mDelegate.getActivePlayer();
        return activePlayer == null ? null : activePlayer.getPlayerState();

    }

    /**
     * The player state change might warrant a new subscription type (e.g., if the
     * player didn't have a sleep duration set, and now does).
     */
    public void onEvent(PlayerStateChanged event) {
        updatePlayerSubscription(event.player, calculateSubscriptionTypeFor(event.player));
    }

    /**
     * Updates the playing status of the current player.
     * <p>
     * Updates the Wi-Fi lock and ongoing status notification as necessary.
     */
    public void onEvent(PlayStatusChanged event) {
        if (event.player.equals(mDelegate.getActivePlayer())) {
            updateWifiLock(event.player.getPlayerState().isPlaying());
            updateOngoingNotification();
        }

        updatePlayerSubscription(event.player, calculateSubscriptionTypeFor(event.player));
    }

    /**
     * Change the player that is controlled by Squeezer (the "active" player).
     *
     * @param newActivePlayer The new active player. May be null, in which case no players
     *     are controlled.
     */
    void changeActivePlayer(@Nullable final Player newActivePlayer) {
        Player prevActivePlayer = mDelegate.getActivePlayer();

        // Do nothing if the player hasn't actually changed.
        if (prevActivePlayer == newActivePlayer) {
            return;
        }

        mDelegate.setActivePlayer(newActivePlayer);
        if (prevActivePlayer != null) {
            mDelegate.subscribeDisplayStatus(prevActivePlayer, false);
            mDelegate.subscribeMenuStatus(prevActivePlayer, false);
        }
        if (newActivePlayer != null) {
            mDelegate.subscribeDisplayStatus(newActivePlayer, true);
            mDelegate.subscribeMenuStatus(newActivePlayer, true);
        }
        updateAllPlayerSubscriptionStates();

        Log.i(TAG, "Active player now: " + newActivePlayer);

        // If this is a new player then start an async fetch of its status.
        if (newActivePlayer != null) {
            mDelegate.requestPlayerStatus(newActivePlayer);

            // Start an asynchronous fetch of the squeezeservers "home menu" items
            // See http://wiki.slimdevices.com/index.php/SqueezePlayAndSqueezeCenterPlugins
            mDelegate.clearHomeMenu();
            mDelegate.requestItems(newActivePlayer, 0, new IServiceItemListCallback<Plugin>() {
                @Override
                public void onItemsReceived(int count, int start, Map<String, Object> parameters, List<Plugin> items, Class<Plugin> dataType) {
                    mDelegate.addToHomeMenu(count, items);
                }

                @Override
                public Object getClient() {
                    return SqueezeService.this;
                }
            }).cmd("menu").param("direct", "1").exec();
        }

        // NOTE: this involves a write and can block (sqlite lookup via binder call), so
        // should be done off-thread, so we can process service requests & send our callback
        // as quickly as possible.
        mExecutor.execute(new Runnable() {
            @Override
            public void run() {
                final SharedPreferences preferences = Squeezer.getContext().getSharedPreferences(Preferences.NAME,
                        Squeezer.MODE_PRIVATE);
                SharedPreferences.Editor editor = preferences.edit();

                if (newActivePlayer == null) {
                    Log.v(TAG, "Clearing " + Preferences.KEY_LAST_PLAYER);
                    editor.remove(Preferences.KEY_LAST_PLAYER);
                } else {
                    Log.v(TAG, "Saving " + Preferences.KEY_LAST_PLAYER + "=" + newActivePlayer.getId());
                    editor.putString(Preferences.KEY_LAST_PLAYER, newActivePlayer.getId());
                }

                editor.apply();
            }
        });
    }

    /**
     * Adjusts the subscription to players' status updates.
     */
    private void updateAllPlayerSubscriptionStates() {
        for (Player player : mDelegate.getPlayers().values()) {
            updatePlayerSubscription(player, calculateSubscriptionTypeFor(player));
        }
    }

    /**
     * Determine the correct status subscription type for the given player, based on
     * how frequently we need to know its status.
     */
    private PlayerState.PlayerSubscriptionType calculateSubscriptionTypeFor(Player player) {
        Player activePlayer = mDelegate.getActivePlayer();

        if (mEventBus.hasSubscriberForEvent(PlayerStateChanged.class) ||
                (mEventBus.hasSubscriberForEvent(SongTimeChanged.class) && player.equals(activePlayer))) {
            return PlayerState.PlayerSubscriptionType.NOTIFY_ON_CHANGE;
        } else {
            return PlayerState.PlayerSubscriptionType.NOTIFY_NONE;
        }
    }

    /**
     * Manage subscription to a player's status updates.
     *
     * @param player player to manage.
     * @param playerSubscriptionType the new subscription type
     */
    private void updatePlayerSubscription(
            Player player,
            @NonNull PlayerState.PlayerSubscriptionType playerSubscriptionType) {
        PlayerState playerState = player.getPlayerState();

        // Do nothing if the player subscription type hasn't changed. This prevents sending a
        // subscription update "status" message which will be echoed back by the server and
        // trigger processing of the status message by the service.
        if (playerState.getSubscriptionType().equals(playerSubscriptionType)) {
            return;
        }

        mDelegate.subscribePlayerStatus(player, playerSubscriptionType);
    }

    /**
     * Manages the state of any ongoing notification based on the player and connection state.
     */
    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private void updateOngoingNotification() {
        PlayerState activePlayerState = getActivePlayerState();

        // Update scrobble state, if either we're currently scrobbling, or we
        // were (to catch the case where we started scrobbling a song, and the
        // user went in to settings to disable scrobbling).
        if (scrobblingEnabled || scrobblingPreviouslyEnabled) {
            scrobblingPreviouslyEnabled = scrobblingEnabled;
            Scrobble.scrobbleFromPlayerState(this, activePlayerState);
        }

        NotificationState notificationState = notificationState();

        // Compare the current state with the state when the notification was last updated.
        // If there are no changes (same song, same playing state) then there's nothing to do.
        if (notificationState.equals(ongoingNotification)) {
            return;
        }
        ongoingNotification = notificationState;

        final NotificationManagerCompat nm = NotificationManagerCompat.from(this);
        final NotificationData notificationData = new NotificationData(ongoingNotification);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            final MediaMetadataCompat.Builder metaBuilder = new MediaMetadataCompat.Builder();
            metaBuilder.putString(MediaMetadata.METADATA_KEY_ARTIST, ongoingNotification.artistName);
            metaBuilder.putString(MediaMetadata.METADATA_KEY_ALBUM, ongoingNotification.albumName);
            metaBuilder.putString(MediaMetadata.METADATA_KEY_TITLE, ongoingNotification.songName);
            mMediaSession.setMetadata(metaBuilder.build());

            ImageFetcher.getInstance(this).loadImage(ongoingNotification.artworkUrl,
                    getResources().getDimensionPixelSize(android.R.dimen.notification_large_icon_width),
                    getResources().getDimensionPixelSize(android.R.dimen.notification_large_icon_height),
                    new ImageWorker.ImageWorkerCallback() {
                        @Override
                        @TargetApi(Build.VERSION_CODES.LOLLIPOP)
                        public void process(Object data, @Nullable Bitmap bitmap) {
                            if (bitmap == null) {
                                bitmap = BitmapFactory.decodeResource(getResources(), R.drawable.icon_pending_artwork);
                            }

                            metaBuilder.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, bitmap);
                            metaBuilder.putBitmap(MediaMetadata.METADATA_KEY_ART, bitmap);
                            mMediaSession.setMetadata(metaBuilder.build());
                            notificationData.builder.setLargeIcon(bitmap);
                            nm.notify(PLAYBACKSERVICE_STATUS, notificationData.builder.build());
                        }
                    });
        } else {
            Notification notification = notificationData.builder.build();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                notification.bigContentView = notificationData.expandedView;
            }

            nm.notify(PLAYBACKSERVICE_STATUS, notification);

            ImageFetcher.getInstance(this).loadImage(this, ongoingNotification.artworkUrl, notificationData.normalView, R.id.album,
                    getResources().getDimensionPixelSize(R.dimen.album_art_icon_normal_notification_width),
                    getResources().getDimensionPixelSize(R.dimen.album_art_icon_normal_notification_height),
                    nm, PLAYBACKSERVICE_STATUS, notification);
            ImageFetcher.getInstance(this).loadImage(this, ongoingNotification.artworkUrl, notificationData.expandedView, R.id.album,
                    getResources().getDimensionPixelSize(R.dimen.album_art_icon_expanded_notification_width),
                    getResources().getDimensionPixelSize(R.dimen.album_art_icon_expanded_notification_height),
                    nm, PLAYBACKSERVICE_STATUS, notification);
        }
    }

    private class NotificationData {
        private final NotificationCompat.Builder builder;
        private RemoteViews normalView;
        private RemoteViews expandedView;

        /**
         * Prepare a notification builder from the supplied notification state.
         */
        @TargetApi(21)
        private NotificationData(NotificationState notificationState) {
            PendingIntent nextPendingIntent = getPendingIntent(ACTION_NEXT_TRACK);
            PendingIntent prevPendingIntent = getPendingIntent(ACTION_PREV_TRACK);
            PendingIntent playPendingIntent = getPendingIntent(ACTION_PLAY);
            PendingIntent pausePendingIntent = getPendingIntent(ACTION_PAUSE);
            PendingIntent closePendingIntent = getPendingIntent(ACTION_CLOSE);

            Intent showNowPlaying = new Intent(SqueezeService.this, NowPlayingActivity.class)
                    .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            PendingIntent pIntent = PendingIntent.getActivity(SqueezeService.this, 0, showNowPlaying, 0);


            NotificationUtil.createNotificationChannel(SqueezeService.this, NOTIFICATION_CHANNEL_ID,
                    "Squeezer ongoing notification",
                    "Notifications of player and connection state",
                    NotificationManagerCompat.IMPORTANCE_LOW, false, NotificationCompat.VISIBILITY_PUBLIC);
            builder = new NotificationCompat.Builder(SqueezeService.this, NOTIFICATION_CHANNEL_ID);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                builder.setContentIntent(pIntent);
                builder.setSmallIcon(R.drawable.squeezer_notification);
                builder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC);
                builder.setShowWhen(false);
                builder.setContentTitle(notificationState.songName);
                builder.setContentText(notificationState.artistAlbum());
                builder.setSubText(notificationState.playerName);
                builder.setStyle(new androidx.media.app.NotificationCompat.MediaStyle()
                        .setShowActionsInCompactView(2, 3)
                        .setMediaSession(mMediaSession.getSessionToken()));

                // Don't set an ongoing notification, otherwise wearable's won't show it.
                builder.setOngoing(false);

                builder.setDeleteIntent(closePendingIntent);
                builder.addAction(new NotificationCompat.Action(R.drawable.ic_action_disconnect, "Disconnect", closePendingIntent));
                builder.addAction(new NotificationCompat.Action(R.drawable.ic_action_previous, "Previous", prevPendingIntent));
                if (notificationState.playing) {
                    builder.addAction(new NotificationCompat.Action(R.drawable.ic_action_pause, "Pause", pausePendingIntent));
                } else {
                    builder.addAction(new NotificationCompat.Action(R.drawable.ic_action_play, "Play", playPendingIntent));
                }
                builder.addAction(new NotificationCompat.Action(R.drawable.ic_action_next, "Next", nextPendingIntent));
            } else {
                normalView = new RemoteViews(SqueezeService.this.getPackageName(), R.layout.notification_player_normal);
                expandedView = new RemoteViews(SqueezeService.this.getPackageName(), R.layout.notification_player_expanded);

                builder.setOngoing(true);
                builder.setCategory(NotificationCompat.CATEGORY_SERVICE);
                builder.setSmallIcon(R.drawable.squeezer_notification);

                normalView.setImageViewBitmap(R.id.next, vectorToBitmap(R.drawable.ic_action_next));
                normalView.setOnClickPendingIntent(R.id.next, nextPendingIntent);

                expandedView.setImageViewBitmap(R.id.disconnect, vectorToBitmap(R.drawable.ic_action_disconnect));
                expandedView.setOnClickPendingIntent(R.id.disconnect, closePendingIntent);
                expandedView.setImageViewBitmap(R.id.previous, vectorToBitmap(R.drawable.ic_action_previous));
                expandedView.setOnClickPendingIntent(R.id.previous, prevPendingIntent);
                expandedView.setImageViewBitmap(R.id.next, vectorToBitmap(R.drawable.ic_action_next));
                expandedView.setOnClickPendingIntent(R.id.next, nextPendingIntent);

                builder.setContent(normalView);
                builder.setCustomBigContentView(expandedView);

                normalView.setTextViewText(R.id.trackname, notificationState.songName);
                normalView.setTextViewText(R.id.artist_album, notificationState.artistAlbum());

                expandedView.setTextViewText(R.id.trackname, notificationState.songName);
                expandedView.setTextViewText(R.id.artist_album, notificationState.artistAlbum());
                expandedView.setTextViewText(R.id.player_name, notificationState.playerName);

                if (notificationState.playing) {
                    normalView.setImageViewBitmap(R.id.pause, vectorToBitmap(R.drawable.ic_action_pause));
                    normalView.setOnClickPendingIntent(R.id.pause, pausePendingIntent);

                    expandedView.setImageViewBitmap(R.id.pause, vectorToBitmap(R.drawable.ic_action_pause));
                    expandedView.setOnClickPendingIntent(R.id.pause, pausePendingIntent);
                } else {
                    normalView.setImageViewBitmap(R.id.pause, vectorToBitmap(R.drawable.ic_action_play));
                    normalView.setOnClickPendingIntent(R.id.pause, playPendingIntent);

                    expandedView.setImageViewBitmap(R.id.pause, vectorToBitmap(R.drawable.ic_action_play));
                    expandedView.setOnClickPendingIntent(R.id.pause, playPendingIntent);
                }

                builder.setContentTitle(notificationState.songName);
                builder.setContentText(getString(R.string.notification_playing_text, notificationState.playerName));
                builder.setContentIntent(pIntent);
            }
        }
    }

    private Bitmap vectorToBitmap(@DrawableRes int vectorResource) {
        Drawable drawable = AppCompatResources.getDrawable(this, vectorResource);
        Bitmap b = Bitmap.createBitmap(drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight(), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        drawable.setBounds(0, 0, c.getWidth(), c.getHeight());
        drawable.draw(c);
        return b;
    }

    /**
     * Build current notification state based on the player and connection state.
     */
    private NotificationState notificationState() {
        NotificationState notificationState = new NotificationState();

        final Player activePlayer = mDelegate.getActivePlayer();
        notificationState.hasPlayer = (activePlayer != null);
        if (notificationState.hasPlayer) {
            final PlayerState activePlayerState = activePlayer.getPlayerState();

            notificationState.playing = activePlayerState.isPlaying();

            final CurrentPlaylistItem currentSong = activePlayerState.getCurrentSong();
            notificationState.hasSong = (currentSong != null);
            if (currentSong != null) {
                notificationState.songName = currentSong.getName();
                notificationState.albumName = currentSong.getAlbum();
                notificationState.artistName = currentSong.getArtist();
                notificationState.artworkUrl = currentSong.getIcon();
                notificationState.playerName = activePlayer.getName();
            }
        }

        return notificationState;
    }

    /**
     * @param action The action to be performed.
     * @return A new {@link PendingIntent} for {@literal action} that will update any existing
     *     intents that use the same action.
     */
    @NonNull
    private PendingIntent getPendingIntent(@NonNull String action){
        Intent intent = new Intent(this, SqueezeService.class);
        intent.setAction(action);
        return PendingIntent.getService(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);
    }

    public void onEvent(ConnectionChanged event) {
        if (ConnectionState.isConnected(event.connectionState) ||
                ConnectionState.isConnectInProgress(event.connectionState)) {
            startForeground();
        } else {
            mHandshakeComplete = false;
            stopForeground();
        }
    }

    private void startForeground() {
        if (!foreGround) {
            Log.i(TAG, "startForeground");
            foreGround = true;

            ongoingNotification = notificationState();
            NotificationData notificationData = new NotificationData(ongoingNotification);
            Notification notification;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                final MediaMetadataCompat.Builder metaBuilder = new MediaMetadataCompat.Builder();
                metaBuilder.putString(MediaMetadata.METADATA_KEY_ARTIST, ongoingNotification.artistName);
                metaBuilder.putString(MediaMetadata.METADATA_KEY_ALBUM, ongoingNotification.albumName);
                metaBuilder.putString(MediaMetadata.METADATA_KEY_TITLE, ongoingNotification.songName);
                mMediaSession.setMetadata(metaBuilder.build());
                notification = notificationData.builder.build();
            } else {
                notification = notificationData.builder.build();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                    notification.bigContentView = notificationData.expandedView;
                }
            }


            // Start it and have it run forever (until it shuts itself down).
            // This is required so swapping out the activity (and unbinding the
            // service connection in onDestroy) doesn't cause the service to be
            // killed due to zero refcount.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(new Intent(this, SqueezeService.class));
            } else {
                startService(new Intent(this, SqueezeService.class));
            }

            // Call startForeground immediately after startForegroundService
            startForeground(PLAYBACKSERVICE_STATUS, notification);
        }
    }

    private void stopForeground() {
        Log.i(TAG, "stopForeground");
        foreGround = false;
        ongoingNotification = null;
        stopForeground(true);
        stopSelf();
    }

    public void onEvent(HandshakeComplete event) {
        mHandshakeComplete = true;
        //fetchPlugins("menu");
    }

    private void fetchPlugins(String cmd) {
        HashMap<String, Object> params = new HashMap<>();
        if ("menu".equals(cmd)) {
            params.put("direct", "1");
        } else {
            params.put("menu", "menu");
        }
        fetchPlugins(new File(getFilesDir(), cmd), new String[]{cmd}, params);
    }

    private void fetchPlugins(final File path, String[] cmd, Map<String, Object> params) {
        Log.i(TAG, "fetchPlugins(path:" + path + ", cmd:" + Arrays.toString(cmd) + ", params:" + params + ")");
        mDelegate.requestItems(mDelegate.getActivePlayer(), -1, new IServiceItemListCallback<Plugin>() {
            @Override
            public void onItemsReceived(int count, int start, Map<String, Object> parameters, List<Plugin> items, Class<Plugin> dataType) {
                path.getParentFile().mkdirs();
                File file = new File(path.getParentFile(), path.getName() + "." + start + ".json");
                try {
                    FileOutputStream output = new FileOutputStream(file);
                    output.write(JSON.toString(parameters).getBytes());
                    output.close();
                } catch (FileNotFoundException e) {
                    Log.e(TAG, "Can't create output file: " + file);
                } catch (IOException e) {
                    Log.e(TAG, "Can't write output file: " + file);
                }
                for (Plugin plugin : items) {
                    if (plugin.goAction != null) fetchPlugins(path, plugin.goAction.action);
                    if (plugin.moreAction != null) fetchPlugins(path, plugin.moreAction.action);
                }
            }

            @Override
            public Object getClient() {
                return SqueezeService.this;
            }
        }).cmd(cmd).params(params).exec();
    }

    private void fetchPlugins(final File path, Action.JsonAction action) {
        if (action.cmd[0].equals("playlistcontrol")) {
            Log.w(TAG, "Skip to avoid calling playlistcontrol command: " + action);
            return;
        }
        if (action.cmd.length > 1 && action.cmd[1].equals("playlist")) {
            Log.w(TAG, "Skip to avoid calling playlist command: " + action);
            return;
        }
        fetchPlugins(new File(path, action.cmd()), action.cmd, action.params);
    }

    public void onEvent(MusicChanged event) {
        if (event.player.equals(mDelegate.getActivePlayer())) {
            updateOngoingNotification();
        }
    }

    public void onEvent(PlayersChanged event) {
        // Figure out the new active player, let everyone know.
        changeActivePlayer(getPreferredPlayer(event.players.values()));
    }

    /**
     * @return The player that should be chosen as the (new) active player. This is either the
     *     last active player (if known), the first player the server knows about if there are
     *     connected players, or null if there are no connected players.
     */
    private @Nullable Player getPreferredPlayer(Collection<Player> players) {
        final SharedPreferences preferences = Squeezer.getContext().getSharedPreferences(Preferences.NAME,
                Context.MODE_PRIVATE);
        final String lastConnectedPlayer = preferences.getString(Preferences.KEY_LAST_PLAYER,
                null);
        Log.i(TAG, "lastConnectedPlayer was: " + lastConnectedPlayer);

        Log.i(TAG, "players empty?: " + players.isEmpty());
        for (Player player : players) {
            if (player.getId().equals(lastConnectedPlayer)) {
                return player;
            }
        }
        return !players.isEmpty() ? players.iterator().next() : null;
    }

    /* Start an async fetch of the SqueezeboxServer's songs */
    private void songs(IServiceItemListCallback<Song> callback, Map<String, Object> filters) throws HandshakeNotCompleteException {
        mDelegate.requestItems(-1, callback).param("tags", SONGTAGS).params(filters).cmd("titles").exec();
    }

    /** A download request will be passed to the download manager for each song called back to this */
    private final IServiceItemListCallback<Song> songDownloadCallback = new IServiceItemListCallback<Song>() {
        @Override
        public void onItemsReceived(int count, int start, Map<String, Object> parameters, List<Song> items, Class<Song> dataType) {
            for (Song song : items) {
                downloadSong(song);
            }
        }

        @Override
        public Object getClient() {
            return this;
        }
    };

    /**
     * For each item called to this:
     * If it is a folder: recursive lookup items in the folder
     * If is is a track: Enqueue a download request to the download manager
     */
//    private final IServiceItemListCallback<MusicFolderItem> musicFolderDownloadCallback = new IServiceItemListCallback<MusicFolderItem>() {
//        @Override
//        public void onItemsReceived(int count, int start, Map<String, String> parameters, List<MusicFolderItem> items, Class<MusicFolderItem> dataType) {
//            for (MusicFolderItem item : items) {
//                squeezeService.downloadItem(item);
//            }
//        }
//
//        @Override
//        public Object getClient() {
//            return this;
//        }
//    };

    private void downloadSong(Song song) {
        Log.i(TAG, "downloadSong(" + song + ")");
        Uri downloadUrl = Util.getDownloadUrl(mDelegate.getUrlPrefix(), song.id);
        Uri url = Uri.parse(song.url);
        Uri imageUrl = Util.getImageUrl(mDelegate.getUrlPrefix(), song.url);
        final Preferences preferences = new Preferences(this);
        if (preferences.isDownloadUseServerPath()) {
            downloadSong(downloadUrl, song.title, url, imageUrl);
        } else {
            final String lastPathSegment = url.getLastPathSegment();
            final String fileExtension = Files.getFileExtension(lastPathSegment);
            final String localPath = song.getLocalPath(preferences.getDownloadPathStructure(), preferences.getDownloadFilenameStructure());
            downloadSong(downloadUrl, song.title, localPath + "." + fileExtension, imageUrl);
        }
    }

    private void downloadSong(@NonNull Uri url, String title, @NonNull Uri serverUrl, @NonNull Uri albumArtUrl) {
        downloadSong(url, title, getLocalFile(serverUrl), albumArtUrl);
    }

    private void downloadSong(@NonNull Uri url, String title, String localPath, @NonNull Uri albumArtUrl) {
        Log.i(TAG, "downloadSong(" + title + "): " + url);
        if (url.equals(Uri.EMPTY)) {
            return;
        }

        if (localPath == null) {
            return;
        }

        // Convert VFAT-unfriendly characters to "_".
        localPath =  localPath.replaceAll("[?<>\\\\:*|\"]", "_");

        // If running on Gingerbread or greater use the Download Manager
        DownloadManager downloadManager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        DownloadDatabase downloadDatabase = new DownloadDatabase(this);
        String tempFile = UUID.randomUUID().toString();
        String credentials = mDelegate.getUsername() + ":" + mDelegate.getPassword();
        String base64EncodedCredentials = Base64.encodeToString(credentials.getBytes(), Base64.NO_WRAP);
        DownloadManager.Request request = new DownloadManager.Request(url)
                .setTitle(title)
                .setDestinationInExternalFilesDir(this, Environment.DIRECTORY_MUSIC, tempFile)
                .setVisibleInDownloadsUi(false)
                .addRequestHeader("Authorization", "Basic " + base64EncodedCredentials);
        long downloadId = downloadManager.enqueue(request);

        Log.i(TAG, "download enqueued: " + downloadId);
        if (!downloadDatabase.registerDownload(downloadId, tempFile, localPath, albumArtUrl)) {
            // TODO external logging
            Log.w(TAG, "Could not register download entry for: " + downloadId);
            downloadManager.remove(downloadId);
        }
    }

    /**
     * Tries to get the path relative to the server music library.
     * <p>
     * If this is not possible resort to the last path segment of the server path.
     */
    @Nullable
    private String getLocalFile(@NonNull Uri serverUrl) {
        String serverPath = serverUrl.getPath();
        String mediaDir = null;
        String path;
        for (String dir : mDelegate.getMediaDirs()) {
            if (serverPath.startsWith(dir)) {
                mediaDir = dir;
                break;
            }
        }
        if (mediaDir != null) {
            path = serverPath.substring(mediaDir.length(), serverPath.length());
        } else {
            // Note: if serverUrl is the empty string this can return null.
            path = serverUrl.getLastPathSegment();
        }

        return path;
    }


    private WifiManager.WifiLock wifiLock;

    void setWifiLock(WifiManager.WifiLock wifiLock) {
        this.wifiLock = wifiLock;
    }

    void updateWifiLock(boolean state) {
        // TODO: this might be running in the wrong thread.  Is wifiLock thread-safe?
        if (state && !wifiLock.isHeld()) {
            Log.v(TAG, "Locking wifi while playing.");
            wifiLock.acquire();
        }
        if (!state && wifiLock.isHeld()) {
            Log.v(TAG, "Unlocking wifi.");
            try {
                wifiLock.release();
                // Seen a crash here with:
                //
                // Permission Denial: broadcastIntent() requesting a sticky
                // broadcast
                // from pid=29506, uid=10061 requires
                // android.permission.BROADCAST_STICKY
                //
                // Catching the exception (which seems harmless) seems better
                // than requesting an additional permission.

                // Seen a crash here with
                //
                // java.lang.RuntimeException: WifiLock under-locked
                // Squeezer_WifiLock
                //
                // Both crashes occurred when the wifi was disabled, on HTC Hero
                // devices running 2.1-update1.
            } catch (SecurityException e) {
                Log.v(TAG, "Caught odd SecurityException releasing wifilock");
            }
        }
    }

    private final ISqueezeService squeezeService = new SqueezeServiceBinder();
    private class SqueezeServiceBinder extends Binder implements ISqueezeService {

        @Override
        @NonNull
        public EventBus getEventBus() {
            return mEventBus;
        }

        @Override
        public void adjustVolumeTo(Player player, int newVolume) {
            mDelegate.command(player).cmd("mixer", "volume", String.valueOf(Math.min(100, Math.max(0, newVolume)))).exec();
        }

        @Override
        public void adjustVolumeTo(int newVolume) {
            mDelegate.activePlayerCommand().cmd("mixer", "volume", String.valueOf(Math.min(100, Math.max(0, newVolume)))).exec();
        }

        @Override
        public void adjustVolumeBy(int delta) {
            if (delta > 0) {
                mDelegate.activePlayerCommand().cmd("mixer", "volume", "+" + delta).exec();
            } else if (delta < 0) {
                mDelegate.activePlayerCommand().cmd("mixer", "volume", String.valueOf(delta)).exec();
            }
        }

        @Override
        public boolean isConnected() {
            return mDelegate.isConnected();
        }

        @Override
        public boolean isConnectInProgress() {
            return mDelegate.isConnectInProgress();
        }

        @Override
        public void startConnect() {
            mDelegate.startConnect(SqueezeService.this);
        }

        @Override
        public void disconnect() {
            if (!isConnected()) {
                return;
            }
            SqueezeService.this.disconnect();
        }

        @Override
        public void register(IServiceItemListCallback<Plugin> callback) throws SqueezeService.HandshakeNotCompleteException {
            if (!mHandshakeComplete) {
                throw new HandshakeNotCompleteException("Handshake with server has not completed.");
            }
            // We register ourselves as a player. This will come back in serverstatus, so we get an
            // active player, which is required for the register_sn command:
            // [ "playerid", [ "register_sn", 0, 100, "login_password", "email:...", "password:..." ] ]
            // We then start register flow with the command:
            // [ "", [ "register", 0, 100, "login_password", "service:SN" ] ]
            // This is same command squeezeplay uses, and allows connect to an existing account or
            // create a new.
            // This way we can use server side logic and we don't have to store account credentials
            // locally.
            String macId = new Preferences(SqueezeService.this).getMacId();
            mDelegate.command().cmd("playerRegister", null, macId, "Squeezer-" + Build.MODEL).exec();
            mDelegate.requestItems(callback).cmd("register").param("service", "SN").exec();
        }

        @Override
        public void powerOn() {
            mDelegate.activePlayerCommand().cmd("power", "1").exec();
        }

        @Override
        public void powerOff() {
            mDelegate.activePlayerCommand().cmd("power", "0").exec();
        }

        @Override
        public void togglePower(Player player) {
            mDelegate.command(player).cmd("power").exec();
        }

        @Override
        public void playerRename(Player player, String newName) {
            mDelegate.command(player).cmd("name", newName).exec();
        }

        @Override
        public void sleep(Player player, int duration) {
            mDelegate.command(player).cmd("sleep", String.valueOf(duration)).exec();
        }

        @Override
        public void syncPlayerToPlayer(@NonNull Player slave, @NonNull String masterId) {
            Player master = mDelegate.getPlayer(masterId);
            mDelegate.command(master).cmd("sync", slave.getId()).exec();
        }

        @Override
        public void unsyncPlayer(@NonNull Player player) {
            mDelegate.command(player).cmd("sync", "-").exec();
        }


        @Override
        @Nullable
        public PlayerState getActivePlayerState() {
            Player activePlayer = getActivePlayer();
            if (activePlayer == null) {
                return null;
            }

            return activePlayer.getPlayerState();
        }

        /**
         * Issues a query for given player preference.
         */
        @Override
        public void playerPref(@Player.Pref.Name String playerPref) {
            playerPref(playerPref, "?");
        }

        @Override
        public void playerPref(@Player.Pref.Name String playerPref, String value) {
            mDelegate.activePlayerCommand().cmd("playerpref", playerPref, value).exec();
        }

        @Override
        public void playerPref(Player player, @Player.Pref.Name String playerPref, String value) {
            mDelegate.command(player).cmd("playerpref", playerPref, value).exec();
        }

        @Override
        public boolean canPowerOn() {
            Player activePlayer = getActivePlayer();
            if (activePlayer == null) {
                return false;
            } else {
                PlayerState playerState = activePlayer.getPlayerState();
                return canPower() && activePlayer.getConnected() && !playerState.isPoweredOn();
            }
        }

        @Override
        public boolean canPowerOff() {
            Player activePlayer = getActivePlayer();
            if (activePlayer == null) {
                return false;
            } else {
                PlayerState playerState = activePlayer.getPlayerState();
                return canPower() && activePlayer.getConnected() && playerState.isPoweredOn();
            }
        }

        private boolean canPower() {
            Player player = getActivePlayer();
            return mDelegate.isConnected() && player != null && player.isCanpoweroff();
        }

        @Override
        public String getServerVersion() throws HandshakeNotCompleteException {
            if (!mHandshakeComplete) {
                throw new HandshakeNotCompleteException("Handshake with server has not completed.");
            }
            return mDelegate.getServerVersion();
        }

        private String fadeInSecs() {
            return mFadeInSecs > 0 ? " " + mFadeInSecs : "";
        }

        @Override
        public boolean togglePausePlay() {
            if (!isConnected()) {
                return false;
            }

            Player activePlayer = getActivePlayer();

            // May be null (e.g., connected to a server with no connected
            // players. TODO: Handle this better, since it's not obvious in the
            // UI.
            if (activePlayer == null)
                return false;

            PlayerState activePlayerState = activePlayer.getPlayerState();
            @PlayerState.PlayState String playStatus = activePlayerState.getPlayStatus();

            // May be null -- race condition when connecting to a server that
            // has a player. Squeezer knows the player exists, but has not yet
            // determined its state.
            if (playStatus == null)
                return false;

            if (playStatus.equals(PlayerState.PLAY_STATE_PLAY)) {
                // NOTE: we never send ambiguous "pause" toggle commands (without the '1')
                // because then we'd get confused when they came back in to us, not being
                // able to differentiate ours coming back on the listen channel vs. those
                // of those idiots at the dinner party messing around.
                mDelegate.command(activePlayer).cmd("pause", "1").exec();
                return true;
            }

            if (playStatus.equals(PlayerState.PLAY_STATE_STOP)) {
                mDelegate.command(activePlayer).cmd("play", fadeInSecs()).exec();
                return true;
            }

            if (playStatus.equals(PlayerState.PLAY_STATE_PAUSE)) {
                mDelegate.command(activePlayer).cmd("pause", "0", fadeInSecs()).exec();
                return true;
            }

            return true;
        }

        @Override
        public boolean play() {
            if (!isConnected()) {
                return false;
            }
            mDelegate.activePlayerCommand().cmd("play", fadeInSecs()).exec();
            return true;
        }

        @Override
        public boolean pause() {
            if(!isConnected()) {
                return false;
            }
            mDelegate.activePlayerCommand().cmd("pause", "1", fadeInSecs()).exec();
            return true;
        }

        @Override
        public boolean stop() {
            if (!isConnected()) {
                return false;
            }
            mDelegate.activePlayerCommand().cmd("stop").exec();
            return true;
        }

        @Override
        public boolean nextTrack() {
            if (!isConnected() || !isPlaying()) {
                return false;
            }
            mDelegate.activePlayerCommand().cmd("button", "jump_fwd").exec();
            return true;
        }

        @Override
        public boolean previousTrack() {
            if (!isConnected() || !isPlaying()) {
                return false;
            }
            mDelegate.activePlayerCommand().cmd("button", "jump_rew").exec();
            return true;
        }

        @Override
        public boolean toggleShuffle() {
            if (!isConnected()) {
                return false;
            }
            mDelegate.activePlayerCommand().cmd("button", "shuffle").exec();
            return true;
        }

        @Override
        public boolean toggleRepeat() {
            if (!isConnected()) {
                return false;
            }
            mDelegate.activePlayerCommand().cmd("button", "repeat").exec();
            return true;
        }

        /**
         * Start playing the song in the current playlist at the given index.
         *
         * @param index the index to jump to
         */
        @Override
        public boolean playlistIndex(int index) {
            if (!isConnected()) {
                return false;
            }
            mDelegate.activePlayerCommand().cmd("playlist", "index", String.valueOf(index), fadeInSecs()).exec();
            return true;
        }

        @Override
        public boolean playlistClear() {
            if (!isConnected()) {
                return false;
            }
            mDelegate.activePlayerCommand().cmd("playlist", "clear").exec();
            return true;
        }

        @Override
        public boolean playlistSave(String name) {
            if (!isConnected()) {
                return false;
            }
            mDelegate.activePlayerCommand().cmd("playlist", "save", name).exec();
            return true;
        }

        private boolean isPlaying() {
            PlayerState playerState = getActivePlayerState();
            return playerState != null && playerState.isPlaying();
        }

        /**
         * Change the player that is controlled by Squeezer (the "active" player).
         *
         * @param newActivePlayer May be null, in which case no players are controlled.
         */
        @Override
        public void setActivePlayer(@Nullable final Player newActivePlayer) {
            changeActivePlayer(newActivePlayer);
        }

        @Override
        @Nullable
        public Player getActivePlayer() {
            return mDelegate.getActivePlayer();
        }

        @Override
        public Collection<Player> getPlayers() {
            return mDelegate.getPlayers().values();
        }

        @Override
        public PlayerState getPlayerState() {
            return getActivePlayerState();
        }

        /**
         * @return null if there is no active player, otherwise the name of the current playlist,
         *     which may be the empty string.
         */
        @Override
        @Nullable
        public String getCurrentPlaylist() {
            PlayerState playerState = getActivePlayerState();

            if (playerState == null)
                return null;

            return playerState.getCurrentPlaylist();
        }

        @Override
        public boolean setSecondsElapsed(int seconds) {
            if (!isConnected()) {
                return false;
            }
            if (seconds < 0) {
                return false;
            }

            mDelegate.activePlayerCommand().cmd("time", String.valueOf(seconds)).exec();

            return true;
        }

        @Override
        public void preferenceChanged(String key) {
            Log.i(TAG, "Preference changed: " + key);
            cachePreferences();
        }


        @Override
        public void cancelItemListRequests(Object client) {
            mDelegate.cancelClientRequests(client);
        }

        @Override
        public void alarms(int start, IServiceItemListCallback<Alarm> callback) {
            if (!isConnected()) {
                return;
            }
            mDelegate.requestItems(getActivePlayer(), start, callback).cmd("alarms").param("filter", "all").exec();
        }

        @Override
        public void alarmPlaylists(IServiceItemListCallback<AlarmPlaylist> callback) {
            if (!isConnected()) {
                return;
            }
            // The LMS documentation states that
            // The "alarm playlists" returns all the playlists, sounds, favorites etc. available to alarms.
            // This will however return only one playlist: the current playlist.
            // Inspection of the LMS code reveals that the "alarm playlists" command takes the
            // customary <start> and <itemsPerResponse> parameters, but these are interpreted as
            // categories (eg. Favorites, Natural Sounds etc.), but the returned list is flattened,
            // i.e. contains all items of the requested categories.
            // So we order all playlists without paging.
            mDelegate.requestItems(callback).cmd("alarm", "playlists").exec();
        }

        @Override
        public void alarmAdd(int time) {
            if (!isConnected()) {
                return;
            }
            mDelegate.activePlayerCommand().cmd("alarm", "add").param("time", time).exec();
        }

        @Override
        public void alarmDelete(String id) {
            if (!isConnected()) {
                return;
            }
            mDelegate.activePlayerCommand().cmd("alarm", "delete").param("id", id).exec();
        }

        @Override
        public void alarmSetTime(String id, int time) {
            if (!isConnected()) {
                return;
            }
            mDelegate.activePlayerCommand().cmd("alarm", "update").param("id", id).param("time", time).exec();
        }

        @Override
        public void alarmAddDay(String id, int day) {
            mDelegate.activePlayerCommand().cmd("alarm", "update").param("id", id).param("dowAdd", day).exec();
        }

        @Override
        public void alarmRemoveDay(String id, int day) {
            mDelegate.activePlayerCommand().cmd("alarm", "update").param("id", id).param("dowDel", day).exec();
        }

        @Override
        public void alarmEnable(String id, boolean enabled) {
            mDelegate.activePlayerCommand().cmd("alarm", "update").param("id", id).param("enabled", enabled ? "1" : "0").exec();
        }

        @Override
        public void alarmRepeat(String id, boolean repeat) {
            mDelegate.activePlayerCommand().cmd("alarm", "update").param("id", id).param("repeat", repeat ? "1" : "0").exec();
        }

        @Override
        public void alarmSetPlaylist(String id, AlarmPlaylist playlist) {
            mDelegate.activePlayerCommand().cmd("alarm", "update").param("id", id)
                    .param("url", "".equals(playlist.getId()) ? "0" : playlist.getId()).exec();
        }

        /* Start an asynchronous fetch of the squeezeservers generic menu items */
        @Override
        public void pluginItems(int start, String cmd, IServiceItemListCallback<Plugin>  callback) throws SqueezeService.HandshakeNotCompleteException {
            if (!mHandshakeComplete) {
                throw new HandshakeNotCompleteException("Handshake with server has not completed.");
            }
            mDelegate.requestItems(getActivePlayer(), start, callback).cmd(cmd).param("menu", "menu").exec();
        }

        /* Start an asynchronous fetch of the squeezeservers generic menu items */
        @Override
        public void pluginItems(int start, Item item, Action action, IServiceItemListCallback<Plugin>  callback) throws SqueezeService.HandshakeNotCompleteException {
            if (!mHandshakeComplete) {
                throw new HandshakeNotCompleteException("Handshake with server has not completed.");
            }
            mDelegate.requestItems(getActivePlayer(), start, callback).cmd(action.action.cmd).params(action.action.params(item.inputValue)).exec();
        }

        @Override
        public void pluginItems(Action action, IServiceItemListCallback<Plugin> callback) throws HandshakeNotCompleteException {
            // We cant use paging for context menu items as LMS does some "magic"
            // See XMLBrowser.pm ("xmlBrowseInterimCM" and  "# Cannot do this if we might screw up paging")
            mDelegate.requestItems(getActivePlayer(), callback).cmd(action.action.cmd).params(action.action.params).exec();
        }

        @Override
        public void action(Item item, Action action) {
            if (!isConnected()) {
                return;
            }
            mDelegate.command(getActivePlayer()).cmd(action.action.cmd).params(action.action.params(item.inputValue)).exec();
        }

        @Override
        public void action(Action.JsonAction action) {
            if (!isConnected()) {
                return;
            }
            mDelegate.command(getActivePlayer()).cmd(action.cmd).params(action.params).exec();
        }

        @Override
        public void downloadItem(Item item) throws HandshakeNotCompleteException {
            Log.i(TAG, "downloadItem(" + item + ")");
            songs(songDownloadCallback, Collections.singletonMap("track_id", (Object)item.getId()));

//            if (item instanceof Song) {
//                Song song = (Song) item;
//                if (!song.isRemote()) {
//                    downloadSong(song);
//                }
//            } else if (item instanceof Playlist) {
//                playlistSongs(-1, (Playlist) item, songDownloadCallback);
//            } else if (item instanceof MusicFolderItem) {
//                MusicFolderItem musicFolderItem = (MusicFolderItem) item;
//                if ("track".equals(musicFolderItem.getType())) {
//                    Uri url = musicFolderItem.getUrl();
//                    if (! url.equals(Uri.EMPTY)) {
//                        downloadSong(musicFolderItem.getDownloadUrl(), musicFolderItem.getName(), url, Uri.EMPTY);
//                    }
//                } else if ("folder".equals(musicFolderItem.getType())) {
//                    musicFolders(-1, musicFolderItem, musicFolderDownloadCallback);
//                }
//            } else if (item != null) {
//                songs(songDownloadCallback, -1, SongViewDialog.SongsSortOrder.title.name(), null, item);
//            }
        }
    }

    /**
     * Calculate and set player subscription states every time a client of the bus
     * un/registers.
     * <p>
     * For example, this ensures that if a new client subscribes and needs real
     * time updates, the player subscription states will be updated accordingly.
     */
    class EventBus extends de.greenrobot.event.EventBus {

        @Override
        public void register(Object subscriber) {
            super.register(subscriber);
            updateAllPlayerSubscriptionStates();
        }

        @Override
        public void register(Object subscriber, int priority) {
            super.register(subscriber, priority);
            updateAllPlayerSubscriptionStates();
        }

        @Override
        public void post(Object event) {
            Log.v("EventBus", "post() " + event.getClass().getSimpleName() + ": " + event);
            super.post(event);
        }

        @Override
        public void postSticky(Object event) {
            Log.v("EventBus", "postSticky() " + event.getClass().getSimpleName() + ": " + event);
            super.postSticky(event);
        }

        @Override
        public void registerSticky(Object subscriber) {
            super.registerSticky(subscriber);
            updateAllPlayerSubscriptionStates();
        }

        @Override
        public void registerSticky(Object subscriber, int priority) {
            super.registerSticky(subscriber, priority);
            updateAllPlayerSubscriptionStates();
        }

        @Override
        public synchronized void unregister(Object subscriber) {
            super.unregister(subscriber);
            updateAllPlayerSubscriptionStates();
        }
    }
}
