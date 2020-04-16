/*
 * Copyright (C) 2005-2017 Qihoo 360 Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed To in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.qihoo360.replugin.component.process;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;

import com.qihoo360.loader2.PluginProviderStub;
import com.qihoo360.replugin.base.IPC;

/**
 * @author RePlugin Team
 *
 * 用于对外共享binder的的provider
 *
 * 会在  replugin-host-gradle 中自动配置在 manifast中
 *
 *
 *
 *  <provider
 *             android:name='com.qihoo360.replugin.component.process.ProcessPitProviderPersist'
 *             android:authorities='com.qihoo360.replugin.sample.host.loader.p.main'
 *             android:exported='false'
 *             android:process=':CangzhuService' />
 *
 * 该内容提供者是运行在 常驻进程中的， ui进行第一次 调用该 内容提供者时，
 * 常驻进程肯定还没有 启动，所以 这个内容提供者还是 常驻进程启动的 触发点
 */
public class ProcessPitProviderPersist extends ContentProvider {

    private static final String TAG = "ProcessPitProviderPersist";

    private static final String AUTHORITY_PREFIX = IPC.getPackageName() + ".loader.p.main";

    public static final Uri URI = Uri.parse("content://" + AUTHORITY_PREFIX + "/main");

    public static boolean sInvoked;

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        sInvoked = true;
        return PluginProviderStub.stubMain(uri, projection, selection, selectionArgs, sortOrder);
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }
}
