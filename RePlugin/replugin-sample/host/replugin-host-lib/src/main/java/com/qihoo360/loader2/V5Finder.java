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

package com.qihoo360.loader2;

import android.content.Context;

import com.qihoo360.loader.utils.ProcessLocker;
import com.qihoo360.loader2.Builder.PxAll;
import com.qihoo360.replugin.RePlugin;
import com.qihoo360.replugin.RePluginInternal;
import com.qihoo360.replugin.helper.LogDebug;
import com.qihoo360.replugin.model.PluginInfo;

import java.io.File;
import java.util.ArrayList;

import static com.qihoo360.replugin.helper.LogDebug.LOG;
import static com.qihoo360.replugin.helper.LogDebug.PLUGIN_TAG;

/**
 * @author RePlugin Team
 */
public class V5Finder {

    /**
     * 扫描 data/data/包名/files 下插件 并缓存到 PxAll 对象中
     *
     * @param context
     * @param pluginDir app_plugins_v3
     * @param all
     */
    static final void search(Context context, File pluginDir, PxAll all) {
        // 扫描V5下载目录
        ArrayList<V5FileInfo> v5Plugins = new ArrayList<V5FileInfo>();
        {
            //dir=data/data/包名/files
            File dir = RePlugin.getConfig().getPnInstallDir();
            if (LOG) {
                LogDebug.d(PLUGIN_TAG, "search v5 files: dir=" + dir.getAbsolutePath());
            }
            // //扫描 data/data/包名/files 下插件 ，构建为V5FileInfo 并装入 plugins
            searchV5Plugins(dir, v5Plugins);
        }

        // 同步V5原始插件文件到插件目录
        for (V5FileInfo p : v5Plugins) {

            ProcessLocker lock = new ProcessLocker(RePluginInternal.getAppContext(), p.mFile.getParent(), p.mFile.getName() + ".lock");

            /**
             * 此处逻辑的详细介绍请参照
             *
             * @see com.qihoo360.loader2.MP.pluginDownloaded(String path)
             */
            if (lock.isLocked()) {
                // 插件文件不可用，直接跳过
                continue;
            }

            PluginInfo info = p.updateV5FileTo(context, pluginDir, false, true);
            // 已检查版本
            if (info == null) {
                if (LOG) {
                    LogDebug.d(PLUGIN_TAG, "search: fail to update v5 plugin");
                }
            } else {
                all.addV5(info);
            }
        }
    }

    //扫描 data/data/包名/files 下插件 ，构建为V5FileInfo 并装入 plugins
    private static final void searchV5Plugins(File dir, ArrayList<V5FileInfo> plugins) {
        File files[] = dir.listFiles();
        if (files == null) {
            if (LOG) {
                LogDebug.d(PLUGIN_TAG, "search v5 plugin: nothing");
            }
            return;
        }
        if (LOG) {
            LogDebug.d(PLUGIN_TAG, "search v5 plugin: size= " + files.length);
        }
        for (File f : files) {
            if (LOG) {
                //例如：search v5 plugin: plugin= /data/user/0/com.qihoo360.replugin.sample.host/files/plugin_v3_demo1-10-10-104.jar.lock
                //这是哪里来的文件？
                LogDebug.d(PLUGIN_TAG, "search v5 plugin: plugin= " + f.getAbsolutePath());
            }
            if (f.isDirectory()) {
                continue;
            }
            if (f.length() <= 0) {
                continue;
            }
            V5FileInfo p = null;
            //构建V5FileInfo对象 为普通插件
            p = V5FileInfo.build(f, V5FileInfo.NORMAL_PLUGIN);
            if (p != null) {
                //添加到 plugins中
                plugins.add(p);
                continue;
            }
            //构建V5FileInfo对象 为增量插件
            // 这里为啥 给一个插件对象要构建两个 V5FileInfo？
            p = V5FileInfo.build(f, V5FileInfo.INCREMENT_PLUGIN);
            if (p != null) {
                plugins.add(p);
                continue;
            }
        }
    }
}
