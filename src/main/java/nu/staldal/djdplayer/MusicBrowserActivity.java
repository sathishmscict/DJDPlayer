/*
 * Copyright (C) 2007 The Android Open Source Project
 * Copyright (C) 2012-2015 Mikael Ståldal
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

package nu.staldal.djdplayer;

import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.media.AudioManager;
import android.media.audiofx.AudioEffect;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.provider.MediaStore;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.Toast;
import nu.staldal.djdplayer.provider.MusicContract;
import nu.staldal.djdplayer.provider.MusicProvider;
import nu.staldal.ui.WithSectionMenu;

import java.lang.reflect.Method;
import java.util.ArrayList;

public class MusicBrowserActivity extends Activity implements MusicUtils.Defs, ServiceConnection,
        SharedPreferences.OnSharedPreferenceChangeListener {

    private static final String LOGTAG = "MusicBrowserActivity";

    private static final String TRACKS_FRAGMENT = "tracksFragment";

    private ImageView categoryMenuView;

    private MusicUtils.ServiceToken token = null;
    private MediaPlayback service = null;

    private boolean invalidateTabs = false;
    private long songToPlay = -1;

    private ArrayList<Intent> backStack;

    private Uri uri;
    private String title;
    private boolean searchResult;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Log.i(LOGTAG, "onCreate - " + getIntent());

        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        PreferenceManager.setDefaultValues(this, R.xml.preferences, false);

        if (MusicUtils.android44OrLater() || !MusicUtils.hasMenuKey(this)) {
            disableStackedActionBar(getActionBar());
        }

        setContentView(R.layout.music_browser_activity);

        Button playQueueButton = (Button)findViewById(R.id.playqueue_button);
        if (playQueueButton != null) {
            playQueueButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    startActivity(new Intent(MusicBrowserActivity.this, MediaPlaybackActivity.class));
                }
            });
        }

        if (savedInstanceState != null) {
            backStack = savedInstanceState.getParcelableArrayList("backStack");
        } else {
            backStack = new ArrayList<>(8);
        }

        parseIntent(getIntent(), true);

        PreferenceManager.getDefaultSharedPreferences(this).registerOnSharedPreferenceChangeListener(this);
        token = MusicUtils.bindToService(this, this);
    }

    /**
     * Workaround to avoid stacked Action Bar.
     *
     * http://developer.android.com/guide/topics/ui/actionbar.html#Tabs
     */
    private void disableStackedActionBar(ActionBar actionBar) {
        try {
            Method setHasEmbeddedTabsMethod = actionBar.getClass()
                .getDeclaredMethod("setHasEmbeddedTabs", boolean.class);
            setHasEmbeddedTabsMethod.setAccessible(true);
            setHasEmbeddedTabsMethod.invoke(actionBar, true);
        } catch (Exception e) {
            Log.w(LOGTAG, "Unable to configure ActionBar tabs", e);
        }
    }

    public void onServiceConnected(ComponentName name, IBinder binder) {
        service = ((MediaPlaybackService.LocalBinder)binder).getService();

        notifyFragmentConnected(R.id.player_header, service);
        notifyFragmentConnected(R.id.playqueue, service);
        notifyFragmentConnected(R.id.player_footer, service);
        notifyFragmentConnected(R.id.nowplaying, service);

        if (songToPlay > -1) {
            MusicUtils.playSong(this, songToPlay);
            songToPlay = -1;
        }

        invalidateOptionsMenu();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        Log.i(LOGTAG, "onNewIntent - " + intent);
        boolean addToBackStack = parseIntent(intent, false);
        if (songToPlay > -1) {
            if (service != null) {
                MusicUtils.playSong(this, songToPlay);
                songToPlay = -1;
            }
        }
        if (addToBackStack) {
            backStack.add(getIntent());
            setIntent(intent);
        }
    }

    private boolean parseIntent(Intent intent, boolean onCreate) {
        if (Intent.ACTION_VIEW.equals(intent.getAction())
                && intent.getData() != null
                && intent.getType() != null && intent.getType().startsWith(MusicUtils.AUDIO_X_MPEGURL)) {
            new ImportPlaylistTask(getApplicationContext()).execute(intent.getData());
            songToPlay = -1;
            uri = null;
            title = null;
            searchResult = false;
            MusicUtils.setStringPref(this, SettingsActivity.ACTIVE_TAB, SettingsActivity.PLAYLISTS_TAB);
        } else if (Intent.ACTION_VIEW.equals(intent.getAction())
                && intent.getData() != null
                && intent.getData().toString().startsWith(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI.toString())
                && MusicUtils.isLong(intent.getData().getLastPathSegment())) {
            songToPlay = ContentUris.parseId(intent.getData());
            if (!onCreate) return false;
            uri = null;
            title = null;
            searchResult = false;
        } else if (Intent.ACTION_VIEW.equals(intent.getAction())
                && intent.getData() != null
                && intent.getData().getScheme().equals("file")) {
            songToPlay = fetchSongIdFromPath(intent.getData().getPath());
            if (!onCreate) return false;
            uri = null;
            title = null;
            searchResult = false;
        } else if ((Intent.ACTION_VIEW.equals(intent.getAction()) || Intent.ACTION_PICK.equals(intent.getAction()))
                && intent.getData() != null) {
            songToPlay = -1;
            uri = fixUri(intent.getData());
            title = MusicProvider.calcTitle(this, uri);
            searchResult = false;
        } else if (Intent.ACTION_SEARCH.equals(intent.getAction())
                            || MediaStore.INTENT_ACTION_MEDIA_SEARCH.equals(intent.getAction())) {
            songToPlay = -1;
            uri = null;
            title = getString(R.string.search_results, intent.getStringExtra(SearchManager.QUERY));
            searchResult = true;
        } else {
            songToPlay = -1;
            uri = null;
            title = null;
            searchResult = false;
        }

        if (title == null) {
            enterCategoryMode();
        } else {
            enterSongsMode();
        }

        return true;
    }

    private long fetchSongIdFromPath(String path) {
        Cursor cursor = getContentResolver().query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                new String[] { MediaStore.Audio.Media._ID },
                MediaStore.Audio.Media.DATA + "=?",
                new String[] { path },
                null);
        if (cursor == null) {
            Log.w(LOGTAG, "Unable to fetch Song Id (cursor is null) for " + path);
            return -1;
        }
        try {
            if (cursor.moveToFirst()) {
                return cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID));
            } else {
                Log.w(LOGTAG, "Unable to fetch Song Id (cursor is empty) for " + path);
                return -1;
            }
        } finally {
            cursor.close();
        }
    }

    private Uri fixUri(Uri uri) {
        String lastPathSegment = uri.getLastPathSegment();
        try {
            long id = Long.parseLong(lastPathSegment);
            if (uri.toString().startsWith(MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI.toString())) {
                return MusicContract.Album.getMembersUri(id);
            } else if (uri.toString().startsWith(MediaStore.Audio.Artists.EXTERNAL_CONTENT_URI.toString())) {
                return MusicContract.Artist.getMembersUri(id);
            } else if (uri.toString().startsWith(MediaStore.Audio.Genres.EXTERNAL_CONTENT_URI.toString())) {
                return MusicContract.Genre.getMembersUri(id);
            } else if (uri.toString().startsWith(MediaStore.Audio.Playlists.EXTERNAL_CONTENT_URI.toString())) {
                return MusicContract.Playlist.getMembersUri(id);
            } else {
                return uri;
            }
        } catch (NumberFormatException e) {
            return uri;
        }
    }

    private void enterCategoryMode() {
        uri = null;
        title = null;

        ActionBar actionBar = getActionBar();
        actionBar.setDisplayShowTitleEnabled(false);
        actionBar.setDisplayShowHomeEnabled(false);
        actionBar.setHomeButtonEnabled(false);
        setTitle(null);
        actionBar.setCustomView(null);
        actionBar.setDisplayOptions(0, ActionBar.DISPLAY_SHOW_CUSTOM);

        Fragment oldFragment = getFragmentManager().findFragmentByTag(TRACKS_FRAGMENT);
        if (oldFragment != null) {
            FragmentTransaction ft = getFragmentManager().beginTransaction();
            ft.remove(oldFragment);
            ft.commit();
        }
        setupTabs(actionBar);

        restoreActiveTab(actionBar);
    }

    private void enterSongsMode() {
        ActionBar actionBar = getActionBar();
        saveActiveTab(actionBar);
        actionBar.setNavigationMode(ActionBar.NAVIGATION_MODE_STANDARD);
        actionBar.setDisplayShowTitleEnabled(true);
        actionBar.setDisplayShowHomeEnabled(true);
        actionBar.setHomeButtonEnabled(true);
        setTitle(title);

        Fragment fragment;
        if (searchResult) {
            fragment = Fragment.instantiate(this, QueryFragment.class.getName());
            actionBar.setCustomView(null);
            actionBar.setDisplayOptions(0, ActionBar.DISPLAY_SHOW_CUSTOM);
        } else {
            Bundle bundle = new Bundle();
            bundle.putString(TrackFragment.URI, uri.toString());
            fragment = Fragment.instantiate(this, TrackFragment.class.getName(), bundle);
            final WithSectionMenu trackFragment = (WithSectionMenu)fragment;

            categoryMenuView = new ImageView(this);
            categoryMenuView.setImageResource(R.drawable.ic_section_menu);
            categoryMenuView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    trackFragment.onCreateSectionMenu(categoryMenuView);
                }
            });
            actionBar.setCustomView(categoryMenuView);
            actionBar.setDisplayOptions(ActionBar.DISPLAY_SHOW_CUSTOM, ActionBar.DISPLAY_SHOW_CUSTOM);
        }
        FragmentTransaction ft = getFragmentManager().beginTransaction();
        ft.replace(R.id.main, fragment, TRACKS_FRAGMENT);
        ft.commit();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (key.equals(SettingsActivity.SHOW_ARTISTS_TAB)
                || key.equals(SettingsActivity.SHOW_ALBUMS_TAB)
                || key.equals(SettingsActivity.SHOW_GENRES_TAB)
                || key.equals(SettingsActivity.SHOW_FOLDERS_TAB)
                || key.equals(SettingsActivity.SHOW_PLAYLISTS_TAB)) {
            invalidateTabs = true;
        }
    }

    private void setupTabs(ActionBar actionBar) {
        actionBar.removeAllTabs();

        if (PreferenceManager.getDefaultSharedPreferences(this).getBoolean(SettingsActivity.SHOW_ARTISTS_TAB, true)) {
            actionBar.addTab(actionBar.newTab()
                    .setText(R.string.artists_menu)
                    .setTag(SettingsActivity.ARTISTS_TAB)
                    .setTabListener(new TabListener<>(this, SettingsActivity.ARTISTS_TAB, ArtistFragment.class)));
        }

        if (PreferenceManager.getDefaultSharedPreferences(this).getBoolean(SettingsActivity.SHOW_ALBUMS_TAB, true)) {
            actionBar.addTab(actionBar.newTab()
                    .setText(R.string.albums_menu)
                    .setTag(SettingsActivity.ALBUMS_TAB)
                    .setTabListener(new TabListener<>(this, SettingsActivity.ALBUMS_TAB, AlbumFragment.class)));
        }

        if (PreferenceManager.getDefaultSharedPreferences(this).getBoolean(SettingsActivity.SHOW_GENRES_TAB, true)) {
            actionBar.addTab(actionBar.newTab()
                    .setText(R.string.genres_menu)
                    .setTag(SettingsActivity.GENRES_TAB)
                    .setTabListener(new TabListener<>(this, SettingsActivity.GENRES_TAB, GenreFragment.class)));
        }

        if (PreferenceManager.getDefaultSharedPreferences(this).getBoolean(SettingsActivity.SHOW_FOLDERS_TAB, true)) {
            actionBar.addTab(actionBar.newTab()
                    .setText(R.string.folders_menu)
                    .setTag(SettingsActivity.FOLDERS_TAB)
                    .setTabListener(new TabListener<>(this, SettingsActivity.FOLDERS_TAB, FolderFragment.class)));
        }

        if (PreferenceManager.getDefaultSharedPreferences(this).getBoolean(SettingsActivity.SHOW_PLAYLISTS_TAB, true)) {
            actionBar.addTab(actionBar.newTab()
                    .setText(R.string.playlists_menu)
                    .setTag(SettingsActivity.PLAYLISTS_TAB)
                    .setTabListener(new TabListener<>(this, SettingsActivity.PLAYLISTS_TAB, PlaylistFragment.class)));
        }

        actionBar.setNavigationMode(ActionBar.NAVIGATION_MODE_TABS);
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (title == null) {
            ActionBar actionBar = getActionBar();

            if (invalidateTabs) {
                setupTabs(actionBar);
                invalidateTabs = false;
            }

            restoreActiveTab(actionBar);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();

        saveActiveTab(getActionBar());
    }

    private void restoreActiveTab(ActionBar actionBar) {
        String activeTab = getSharedPreferences(getPackageName(), MODE_PRIVATE).getString(SettingsActivity.ACTIVE_TAB, null);
        for (int i = 0; i < actionBar.getTabCount(); i++) {
            if (actionBar.getTabAt(i).getTag().equals(activeTab)) {
                actionBar.setSelectedNavigationItem(i);
                break;
            }
        }
    }

    private void saveActiveTab(ActionBar actionBar) {
        ActionBar.Tab selectedTab = actionBar.getSelectedTab();
        if (selectedTab != null) {
            MusicUtils.setStringPref(this, SettingsActivity.ACTIVE_TAB, (String) selectedTab.getTag());
        }
    }

    public void onServiceDisconnected(ComponentName name) {
        service = null;

        notifyFragmentDisconnected(R.id.player_header);
        notifyFragmentDisconnected(R.id.playqueue);
        notifyFragmentDisconnected(R.id.player_footer);
        notifyFragmentDisconnected(R.id.nowplaying);

        finish();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);

        outState.putParcelableArrayList("backStack", backStack);
    }

    @Override
    protected void onDestroy() {
        if (token != null) MusicUtils.unbindFromService(token);
        service = null;
        PreferenceManager.getDefaultSharedPreferences(this).unregisterOnSharedPreferenceChangeListener(this);

        super.onDestroy();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);

        getMenuInflater().inflate(R.menu.browser_menu, menu);

        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        updateSoundEffectItem(menu);

        updateRepeatItem(menu);

        updatePlayingItems(menu);

        return true;
    }

    private void updateSoundEffectItem(Menu menu) {
        MenuItem item = menu.findItem(R.id.effect_panel);
        if (item != null) {
            Intent intent = new Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL);
            item.setVisible(getPackageManager().resolveActivity(intent, 0) != null);
        }
    }

    private void updateRepeatItem(Menu menu) {
        MenuItem item = menu.findItem(R.id.repeat);
        if (item != null) {
            if (service != null) {
                switch (service.getRepeatMode()) {
                    case MediaPlayback.REPEAT_ALL:
                        item.setIcon(R.drawable.ic_mp_repeat_all_btn);
                        break;
                    case MediaPlayback.REPEAT_CURRENT:
                        item.setIcon(R.drawable.ic_mp_repeat_once_btn);
                        break;
                    case MediaPlayback.REPEAT_STOPAFTER:
                        item.setIcon(R.drawable.ic_mp_repeat_stopafter_btn);
                        break;
                    default:
                        item.setIcon(R.drawable.ic_mp_repeat_off_btn);
                        break;
                }
            } else {
                item.setIcon(R.drawable.ic_mp_repeat_off_btn);
            }
        }
    }

    private void updatePlayingItems(Menu menu) {
        menu.setGroupVisible(R.id.playing_items, service != null && !service.isPlaying());
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home: {
                backStack.clear();
                Intent intent = new Intent(Intent.ACTION_MAIN);
                parseIntent(intent, false);
                setIntent(intent);
                return true;
            }

            case R.id.repeat:
                cycleRepeat();
                return true;

            case R.id.shuffle:
                if (service != null) service.doShuffle();
                return true;

            case R.id.uniqueify:
                if (service != null) service.uniqueify();
                return true;

            case R.id.clear_queue:
                if (service != null) service.removeTracks(0, Integer.MAX_VALUE);
                return true;

            case R.id.settings:
                startActivity(new Intent(this, SettingsActivity.class));
                return true;

            case R.id.search:
                return onSearchRequested();

            case R.id.effect_panel:
                Intent intent = new Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL);
                intent.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, service.getAudioSessionId());
                startActivityForResult(intent, 0);
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void cycleRepeat() {
        if (service == null) {
            return;
        }
        int mode = service.getRepeatMode();
        if (mode == MediaPlayback.REPEAT_NONE) {
            service.setRepeatMode(MediaPlayback.REPEAT_ALL);
            Toast.makeText(this, R.string.repeat_all_notif, Toast.LENGTH_SHORT).show();
        } else if (mode == MediaPlayback.REPEAT_ALL) {
            service.setRepeatMode(MediaPlayback.REPEAT_CURRENT);
            Toast.makeText(this, R.string.repeat_current_notif, Toast.LENGTH_SHORT).show();
        } else if (mode == MediaPlayback.REPEAT_CURRENT) {
            service.setRepeatMode(MediaPlayback.REPEAT_STOPAFTER);
            Toast.makeText(this, R.string.repeat_stopafter_notif, Toast.LENGTH_SHORT).show();
        } else {
            service.setRepeatMode(MediaPlayback.REPEAT_NONE);
            Toast.makeText(this, R.string.repeat_off_notif, Toast.LENGTH_SHORT).show();
        }
        invalidateOptionsMenu();
    }

    @Override
    public void onBackPressed() {
        if (!backStack.isEmpty()) {
            Intent intent = backStack.remove(backStack.size() - 1);
            parseIntent(intent, false);
            setIntent(intent);
        } else {
            super.onBackPressed();
        }
    }

    private void notifyFragmentConnected(int id, MediaPlayback service) {
        Fragment fragment = getFragmentManager().findFragmentById(id);
        if (fragment != null && fragment.isInLayout()) {
            ((FragmentServiceConnection) fragment).onServiceConnected(service);
        }
    }

    private void notifyFragmentDisconnected(int id) {
        Fragment fragment = getFragmentManager().findFragmentById(id);
        if (fragment != null && fragment.isInLayout()) {
            ((FragmentServiceConnection) fragment).onServiceDisconnected();
        }
    }

    static class TabListener<T extends Fragment> implements ActionBar.TabListener {
        private Fragment mFragment;
        private final Activity mActivity;
        private final String mTag;
        private final Class<T> mClass;

        /**
         * @param activity  The host Activity, used to instantiate the fragment
         * @param tag  The identifier tag for the fragment
         * @param clz  The fragment's Class, used to instantiate the fragment
         */
        public TabListener(Activity activity, String tag, Class<T> clz) {
            mActivity = activity;
            mTag = tag;
            mClass = clz;

            mFragment = mActivity.getFragmentManager().findFragmentByTag(mTag);
            if (mFragment != null && !mFragment.isDetached()) {
                FragmentTransaction ft = mActivity.getFragmentManager().beginTransaction();
                ft.detach(mFragment);
                ft.commit();
            }
        }

        public void onTabSelected(ActionBar.Tab tab, FragmentTransaction ft) {
            // Check if the fragment is already initialized
            if (mFragment == null) {
                // If not, instantiate and add it to the activity
                mFragment = Fragment.instantiate(mActivity, mClass.getName());
                ft.add(R.id.main, mFragment, mTag);
            } else {
                // If it exists, simply attach it in order to show it
                ft.attach(mFragment);
            }
        }

        public void onTabUnselected(ActionBar.Tab tab, FragmentTransaction ft) {
            if (mFragment != null) {
                // Detach the fragment, because another one is being attached
                ft.detach(mFragment);
            }
        }

        public void onTabReselected(ActionBar.Tab tab, FragmentTransaction ft) {
            // User selected the already selected tab. Usually do nothing.
        }
    }
}
