/*
 * Copyright (C) 2012 The Android Open Source Project
 * Copyright (C) 2013  haus.xda@gmail.com
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
 * limitations under the License
 */

package com.android.onetimeinitializer;

import android.app.IntentService;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * A class that performs one-time initialization after installation.
 *
 * <p>Android doesn't offer any mechanism to trigger an app right after installation, so we use the
 * BOOT_COMPLETED broadcast intent instead.  This means, when the app is upgraded, the
 * initialization code here won't run until the device reboots.
 */
public class OneTimeInitializerService extends IntentService {

    // package and class names for dialer and contacts
    final CharSequence CONTACTS_PKG = "com.android.contacts";
    final CharSequence DIALT_CONTACTS_CLASS = CONTACTS_PKG + ".activities.DialtactsActivity";
    final CharSequence DIALER_PKG = "com.android.dialer";
    final CharSequence DIALT_DIALER_CLASS = DIALER_PKG + ".DialtactsActivity";

    // class name is too long
    private static final String TAG = OneTimeInitializerReceiver.TAG;

    // Name of the shared preferences file.
    private static final String SHARED_PREFS_FILE = "oti";

    // Name of the preference containing the mapping version.
    private static final String MAPPING_VERSION_PREF = "mapping_version";

    // This is the content uri for Launcher content provider. See
    // LauncherSettings and LauncherProvider in the Launcher app for details.
    private static final CharSequence LAUNCHER_2_TXT = "content://com.android.launcher2.settings/favorites?notify=true";
    private static final CharSequence LAUNCHER_3_TXT = "content://com.android.launcher3.settings/favorites?notify=true";
    private static Uri LAUNCHER_CONTENT_URI;

    private static final String LAUNCHER_ID_COLUMN = "_id";
    private static final String LAUNCHER_INTENT_COLUMN = "intent";

    private SharedPreferences mPreferences;

    public OneTimeInitializerService() {
        super("OneTimeInitializer Service");
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mPreferences = getSharedPreferences(SHARED_PREFS_FILE, Context.MODE_PRIVATE);
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "OneTimeInitializerService.onHandleIntent");
        }

        final int currentVersion = getMappingVersion();
        int newVersion = currentVersion;
        if (currentVersion < 1) {
            if (Log.isLoggable(TAG, Log.INFO)) {
                Log.i(TAG, "Updating to version 1.");
            }
            updateDialtactsLauncher(LAUNCHER_2_TXT);

            newVersion = 1;
        }

        updateMappingVersion(newVersion);
    }

    private int getMappingVersion() {
        return mPreferences.getInt(MAPPING_VERSION_PREF, 0);
    }

    private void updateMappingVersion(int version) {
        SharedPreferences.Editor ed = mPreferences.edit();
        ed.putInt(MAPPING_VERSION_PREF, version);
        ed.commit();
    }

    private void updateDialtactsLauncher(CharSequence LAUNCHER_URI_TXT) {
        ContentResolver cr = getContentResolver();
        
        LAUNCHER_CONTENT_URI = Uri.parse((String)LAUNCHER_URI_TXT);
        String[] projections = {LAUNCHER_ID_COLUMN, LAUNCHER_INTENT_COLUMN};
        Cursor c = cr.query(LAUNCHER_CONTENT_URI, projections, null, null, null);

        if (c != null) {
            try {
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "Total launcher icons: " + c.getCount());
                }

                Intent intent;
                ComponentName componentName;
                List<String> categories;
                long favoriteId;
                String intentUri;

                while (c.moveToNext()) {
                    favoriteId = c.getLong(0);
                    intentUri = String.valueOf(getString(1));

                    // Odds are this one isn't it, skip it if possible
                    if (!intentUri.contains(DIALT_CONTACTS_CLASS) || !intentUri.contains(Intent.CATEGORY_LAUNCHER)) {
                        continue;
                    }

                    try {
                        intent = new Intent(intentUri).addFlags(0);
                        componentName = intent.getComponent();
                        categories = new ArrayList<String>(intent.getCategories());

                        if (Intent.ACTION_MAIN.equals(intent.getAction())
                                && componentName != null
                                && CONTACTS_PKG.equals(componentName.getPackageName())
                                && DIALT_CONTACTS_CLASS.equals(componentName.getClassName())
                                && categories.contains(Intent.CATEGORY_LAUNCHER)) {

                            final ComponentName newName = new ComponentName((String)DIALER_PKG, (String)DIALT_DIALER_CLASS);
                            intent.setComponent(newName);

                            final ContentValues values = new ContentValues(1);
                            values.put(LAUNCHER_INTENT_COLUMN, intent.toUri(0));

                            final String updateWhere = LAUNCHER_ID_COLUMN + "=" + favoriteId;
                            cr.update(LAUNCHER_CONTENT_URI, values, updateWhere, null);

                            if (Log.isLoggable(TAG, Log.INFO)) {
                                Log.i(TAG, "Updated " + componentName + " to " + newName);
                            }
                        }
                    } catch (Exception ex) {
                        Log.e(TAG, "Problem moving Dialtacts activity", ex);
                    }
                }
            } finally {
                c.close();
            }
        }
        
        if (LAUNCHER_URI_TXT.equals(LAUNCHER_2_TXT)) {
	    updateDialtactsLauncher(LAUNCHER_3_TXT);
        }
    }
}
