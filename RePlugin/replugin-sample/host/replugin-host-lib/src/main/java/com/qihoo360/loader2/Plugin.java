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
import android.content.pm.PackageInfo;
import android.content.res.Resources;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.text.TextUtils;

import com.qihoo360.i.IModule;
import com.qihoo360.i.IPlugin;
import com.qihoo360.loader.utils.ProcessLocker;
import com.qihoo360.mobilesafe.api.Tasks;
import com.qihoo360.replugin.RePlugin;
import com.qihoo360.replugin.component.ComponentList;
import com.qihoo360.replugin.component.app.PluginApplicationClient;
import com.qihoo360.replugin.helper.LogDebug;
import com.qihoo360.replugin.helper.LogRelease;
import com.qihoo360.replugin.model.PluginInfo;
import com.qihoo360.replugin.packages.PluginManagerProxy;
import com.qihoo360.replugin.utils.AssetsUtils;
import com.qihoo360.replugin.utils.FileUtils;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;

import static com.qihoo360.replugin.helper.LogDebug.LOG;
import static com.qihoo360.replugin.helper.LogDebug.MAIN_TAG;
import static com.qihoo360.replugin.helper.LogDebug.PLUGIN_TAG;
import static com.qihoo360.replugin.helper.LogRelease.LOGR;

/**
 * @author RePlugin Team
 */
class Plugin {

    private static final String TAG = "Plugin";

    // 只加载Service/Activity/ProviderInfo信息（包含ComponentList）
    static final int LOAD_INFO = 0;

    // 加载插件信息和资源
    static final int LOAD_RESOURCES = 1;

    // 加载插件信息、资源和Dex
    static final int LOAD_DEX = 2;

    // 加载插件信息、资源、Dex，并运行Entry类
    static final int LOAD_APP = 3;

    /**
     * 专门针对LoadEntry（见方法）的锁
     */
    private static final byte[] LOCK_LOAD_ENTRY = new byte[0];

    /**
     * 保存插件 pkgName 至 pluginName 的映射
     */
    static final HashMap<String, String> PKG_NAME_2_PLUGIN_NAME = new HashMap<>();

    /**
     * 保存 插件名(例如 demo1) -> 插件路径（data/data 下） 的映射
     */
    static final HashMap<String, String> PLUGIN_NAME_2_FILENAME = new HashMap<>();

    /**
     * 插件路径（data/data 下） -> 插件使用的ClassLoader 的映射关系
     */
    static final HashMap<String, WeakReference<ClassLoader>> FILENAME_2_DEX = new HashMap<>();

    /**
     * 插件路径和 插件 插件 Resources 的对应关系
     */
    static final HashMap<String, WeakReference<Resources>> FILENAME_2_RESOURCES = new HashMap<>();

    /**
     * 插件路径和 插件 PackageInfo 的对应关系
     */
    static final HashMap<String, WeakReference<PackageInfo>> FILENAME_2_PACKAGE_INFO = new HashMap<>();

    /**
     * 插件路径和 ComponentList 的对应关系
     */
    static final HashMap<String, WeakReference<ComponentList>> FILENAME_2_COMPONENT_LIST = new HashMap<>();

    /**
     * 调试用
     */
    static volatile ArrayList<String> sLoadedReasons;

    /**
     *
     */
    PluginInfo mInfo;

    /**
     * 没有IPlugin对象
     *
     * 一致是 false
     */
    boolean mDummyPlugin;

    /**
     *
     */
    Context mContext;

    /**
     *这个原始的classLoader（没有被hook的） ,因为 PmBase 是在 hook classLoader 之前加载的
     */
    ClassLoader mParent;

    /**
     *
     */
    PluginCommImpl mPluginManager;

    /**
     *是否已经初始化过
     */
    boolean mInitialized;

    /**
     *
     */
    Loader mLoader;

    /**
     * 跑在UI线程里的Handler对象
     */
    final Handler mMainH = new Handler(Looper.getMainLooper());

    /**
     * 用来控制插件里的Application对象
     */
    PluginApplicationClient mApplicationClient;

    private static class UpdateInfoTask implements Runnable {

        PluginInfo mInfo;

        UpdateInfoTask(PluginInfo info) {
            mInfo = info;
        }

        @Override
        public void run() {
            try {
                PluginProcessMain.getPluginHost().updatePluginInfo(mInfo);
            } catch (Throwable e) {
                if (LOGR) {
                    LogRelease.e(PLUGIN_TAG, "ph u p i: " + e.getMessage(), e);
                }
            }
        }
    }

    /**
     * 通过 PluginInfo 创建一个 Plugin
     * @param info
     * @return
     */
    static final Plugin build(PluginInfo info) {
        return new Plugin(info);
    }

    static final Plugin cloneAndReattach(Context c, Plugin p, ClassLoader parent, PluginCommImpl pm) {
        if (p == null) {
            return null;
        }
        //通过 PluginInfo 创建一个 Plugin
        p = build(p.mInfo);
        //缓存 Context ClassLoader 和PluginCommImpl
        p.attach(c, parent, pm);
        return p;
    }

    /**
     * 根据插件 pkgName 取 pluginName
     */
    static final String queryPluginNameByPkgName(String pkgName) {
        String pluginName;
        synchronized (PKG_NAME_2_PLUGIN_NAME) {
            pluginName = PKG_NAME_2_PLUGIN_NAME.get(pkgName);
            if (LOG) {
                LogDebug.d(PLUGIN_TAG, "cached pluginName: " + pkgName + " -> " + pluginName);
            }
        }
        return pluginName;
    }

    /**
     * 通过插件名 查询 插件路径
     * @param name
     * @return
     */
    static final String queryCachedFilename(String name) {
        String filename = null;
        synchronized (PLUGIN_NAME_2_FILENAME) {
            filename = PLUGIN_NAME_2_FILENAME.get(name);
            if (LOG) {
                LogDebug.d(PLUGIN_TAG, "cached filename: " + name + " -> " + filename);
            }
        }
        return filename;
    }

    /**
     *
     * 通过插件路径 获取 ClassLoader
     *
     * @param filename 插件路径 data/data/packagename/plugins_v3/demo1-10-10-104.jar 下
     * @return
     */
    static final ClassLoader queryCachedClassLoader(String filename) {
        ClassLoader dex = null;
        if (!TextUtils.isEmpty(filename)) {
            synchronized (FILENAME_2_DEX) {
                WeakReference<ClassLoader> ref = FILENAME_2_DEX.get(filename);
                if (ref != null) {
                    dex = ref.get();
                    if (dex == null) {
                        FILENAME_2_DEX.remove(filename);
                    }
                    if (LOG) {
                        LogDebug.d(PLUGIN_TAG, "cached Dex " + filename + " -> " + dex);
                    }
                }
            }
        }
        return dex;
    }

    /**
     *
     * @param filename 插件路径 data/data/packagename/plugins_v3/demo1-10-10-104.jar 下
     * @return
     */
    static final Resources queryCachedResources(String filename) {
        Resources resources = null;
        if (!TextUtils.isEmpty(filename)) {
            synchronized (FILENAME_2_RESOURCES) {
                //通过插件路径获取 缓存的 Resources
                WeakReference<Resources> ref = FILENAME_2_RESOURCES.get(filename);
                if (ref != null) {
                    resources = ref.get();
                    if (resources == null) {
                        FILENAME_2_RESOURCES.remove(filename);
                    }
                    if (LOG) {
                        LogDebug.d(PLUGIN_TAG, "cached Resources " + filename + " -> " + resources);
                    }
                }
            }
        }
        return resources;
    }

    /**
     * 通过插件路径获取 插件的 PackageInfo
     * @param filename
     * @return
     */
    static final PackageInfo queryCachedPackageInfo(String filename) {
        PackageInfo packageInfo = null;
        if (!TextUtils.isEmpty(filename)) {
            synchronized (FILENAME_2_PACKAGE_INFO) {
                WeakReference<PackageInfo> ref = FILENAME_2_PACKAGE_INFO.get(filename);
                if (ref != null) {
                    packageInfo = ref.get();
                    if (packageInfo == null) {
                        FILENAME_2_PACKAGE_INFO.remove(filename);
                    }
                    if (LOG) {
                        LogDebug.d(PLUGIN_TAG, "cached packageInfo " + filename + " -> " + packageInfo);
                    }
                }
            }
        }
        return packageInfo;
    }

    static final ComponentList queryCachedComponentList(String filename) {
        ComponentList cl = null;
        if (!TextUtils.isEmpty(filename)) {
            synchronized (FILENAME_2_COMPONENT_LIST) {
                WeakReference<ComponentList> ref = FILENAME_2_COMPONENT_LIST.get(filename);
                if (ref != null) {
                    cl = ref.get();
                    if (cl == null) {
                        FILENAME_2_COMPONENT_LIST.remove(filename);
                    }
                    if (LOG) {
                        LogDebug.d(PLUGIN_TAG, "cached componentList " + filename + " -> " + cl);
                    }
                }
            }
        }
        return cl;
    }

    /**
     * 移除内存中插件的PackageInfo、Resources、ComponentList和DexClassLoader缓存对象
     * @param filename 插件路径
     */
    static final void clearCachedPlugin(String filename) {
        if (TextUtils.isEmpty(filename)) {
            return;
        }

        //移除 DexClassLoader
        ClassLoader dex = null;
        synchronized (FILENAME_2_DEX) {
            WeakReference<ClassLoader> ref = FILENAME_2_DEX.get(filename);
            if (ref != null) {
                dex = ref.get();
                FILENAME_2_DEX.remove(filename);
                if (LOG) {
                    LogDebug.d(PLUGIN_TAG, "clear Cached Dex " + filename + " -> " + dex);
                }
            }
        }

        //移除 Resources
        Resources resources = null;
        synchronized (FILENAME_2_RESOURCES) {
            WeakReference<Resources> ref = FILENAME_2_RESOURCES.get(filename);
            if (ref != null) {
                resources = ref.get();
                FILENAME_2_RESOURCES.remove(filename);
                if (LOG) {
                    LogDebug.d(PLUGIN_TAG, "clear Cached Resources " + filename + " -> " + resources);
                }
            }
        }

        //移除 PackageInfo
        PackageInfo packageInfo = null;
        synchronized (FILENAME_2_PACKAGE_INFO) {
            WeakReference<PackageInfo> ref = FILENAME_2_PACKAGE_INFO.get(filename);
            if (ref != null) {
                packageInfo = ref.get();
                FILENAME_2_PACKAGE_INFO.remove(filename);
                if (LOG) {
                    LogDebug.d(PLUGIN_TAG, "clear Cached packageInfo " + filename + " -> " + packageInfo);
                }
            }
        }

        //移除 ComponentList
        ComponentList cl = null;
        synchronized (FILENAME_2_COMPONENT_LIST) {
            WeakReference<ComponentList> ref = FILENAME_2_COMPONENT_LIST.get(filename);
            if (ref != null) {
                cl = ref.get();
                FILENAME_2_COMPONENT_LIST.remove(filename);
                if (LOG) {
                    LogDebug.d(PLUGIN_TAG, "clear Cached componentList " + filename + " -> " + cl);
                }
            }
        }

    }

    static final void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        if (LogDebug.DUMP_ENABLED) {
            writer.println("--- cached plugin filename ---");
            // 懒得锁了
            for (String name : PLUGIN_NAME_2_FILENAME.keySet()) {
                writer.println(name + ": " + PLUGIN_NAME_2_FILENAME.get(name));
            }
            writer.println("--- cached plugin Resources ---");
            // 懒得锁了
            for (String name : FILENAME_2_RESOURCES.keySet()) {
                writer.println(name + ": " + FILENAME_2_RESOURCES.get(name));
            }
            writer.println("--- cached plugin PackageInfo ---");
            // 懒得锁了
            for (String name : FILENAME_2_PACKAGE_INFO.keySet()) {
                writer.println(name + ": " + FILENAME_2_PACKAGE_INFO.get(name));
            }
            writer.println("--- cached plugin ComponentList ---");
            // 懒得锁了
            for (String name : FILENAME_2_COMPONENT_LIST.keySet()) {
                writer.println(name + ": " + FILENAME_2_COMPONENT_LIST.get(name));
            }
        }
    }

    private Plugin(PluginInfo info) {
        mInfo = info;
    }

    @Override
    public String toString() {
        if (LOG) {
            return super.toString() + " {info=" + mInfo + "}";
        }
        return super.toString();
    }

    /**
     * 插件进行挂载
     * @param context
     * @param parent 这个原始的classLoader（没有被hook的） ,因为 PmBase 是在 hook classLoader 之前加载的
     * @param manager
     */
    final void attach(Context context, ClassLoader parent, PluginCommImpl manager) {
        mContext = context;
        mParent = parent;
        mPluginManager = manager;
    }

    /**
     * @return
     */
    final ClassLoader getClassLoader() {
        if (mLoader == null) {
            return null;
        }
        return mLoader.mClassLoader;
    }

    /**
     * @return
     */
    final boolean isInitialized() {
        return mInitialized;
    }

    /**
     * @return
     */
    final boolean isLoaded() {
        if (mLoader == null) {
            return false;
        }
        return mLoader.isAppLoaded();
    }

    /**
     * @return
     */
    final boolean isPackageInfoLoaded() {
        if (mLoader == null) {
            return false;
        }
        return mLoader.isPackageInfoLoaded();
    }

    /**
     *加载插件
     * return: 插件是非加载成功
     */
    final boolean load(int load, boolean useCache) {
        PluginInfo info = mInfo;
        //加载插件 rc标识插件是非加载成功
        boolean rc = loadLocked(load, useCache);
        // 尝试在此处调用Application.onCreate方法
        // Added by Jiongxuan Zhang
        if (load == LOAD_APP && rc) {
            callApp();
        }
        // 如果info改了，通知一下常驻
        // 只针对P-n的Type转化来处理，一定要通知，这样Framework_Version也会得到更新
        if (rc && mInfo != info) {
            UpdateInfoTask task = new UpdateInfoTask((PluginInfo) mInfo.clone());
            Tasks.post2Thread(task);
        }
        return rc;
    }

    final void replaceInfo(PluginInfo info) {
        boolean rc = false;
        if (mInfo.canReplaceForPn(info)) {
            mInfo = info;
            rc = true;
        }
        if (LOG) {
            LogDebug.d(PLUGIN_TAG, "replace plugin info: info=" + info + " rc=" + rc);
        }
    }

    /**
     * 从缓存中读取Loader信息
     * @param load 加载类型
     * @return true: 缓存命中 false: 没有缓存
     */
    private boolean loadByCache(int load) {
        if (load == LOAD_INFO) {
            // 提取PackageInfo对象
            String filename = queryCachedFilename(mInfo.getName());
            PackageInfo pi = queryCachedPackageInfo(filename);
            ComponentList cl = queryCachedComponentList(filename);
            if (pi != null && cl != null) {
                mLoader = new Loader(mContext, mInfo.getName(), null, this);
                mLoader.mPackageInfo = pi;
                mLoader.mComponents = cl;
                if (LOG) {
                    LogDebug.i(MAIN_TAG, "loadLocked(): Cached, pkgInfo loaded");
                }
                return true;
            }
        }
        if (load == LOAD_RESOURCES) {
            // 提取PackageInfo和Resources对象
            String filename = queryCachedFilename(mInfo.getName());
            Resources r = queryCachedResources(filename);
            PackageInfo pi = queryCachedPackageInfo(filename);
            ComponentList cl = queryCachedComponentList(filename);
            if (r != null && pi != null && cl != null) {
                mLoader = new Loader(mContext, mInfo.getName(), null, this);
                mLoader.mPkgResources = r;
                mLoader.mPackageInfo = pi;
                mLoader.mComponents = cl;
                if (LOG) {
                    LogDebug.i(MAIN_TAG, "loadLocked(): Cached, resource loaded");
                }
                return true;
            }
        }
        if (load == LOAD_DEX) {
            // 提取PackageInfo、Resources和DexClassLoader对象
            String filename = queryCachedFilename(mInfo.getName());
            Resources r = queryCachedResources(filename);
            PackageInfo pi = queryCachedPackageInfo(filename);
            ComponentList cl = queryCachedComponentList(filename);
            ClassLoader clzl = queryCachedClassLoader(filename);
            if (r != null && pi != null && cl != null && clzl != null) {
                mLoader = new Loader(mContext, mInfo.getName(), null, this);
                mLoader.mPkgResources = r;
                mLoader.mPackageInfo = pi;
                mLoader.mComponents = cl;
                mLoader.mClassLoader = clzl;
                if (LOG) {
                    LogDebug.i(MAIN_TAG, "loadLocked(): Cached, dex loaded");
                }
                return true;
            }
        }
        return false;
    }

    /**
     *
     * 改方法最多会进行两次， 如果两次后还没有加载成功就放弃
     *
     * @param load
     * @return
     */
    private boolean loadLocked(int load, boolean useCache) {
        // 若插件被“禁用”，则即便上次加载过（且进程一直活着），这次也不能再次使用了
        // Added by Jiongxuan Zhang
        int status = PluginStatusController.getStatus(mInfo.getName(), mInfo.getVersion());
        if (status < PluginStatusController.STATUS_OK) {//插件被禁用
            if (LOG) {
                LogDebug.d(PLUGIN_TAG, "loadLocked(): Disable in=" + mInfo.getName() + ":" + mInfo.getVersion() + "; st=" + status);
            }
            return false;
        }
        if (mInitialized) {//初始化过
            if (mLoader == null) {
                if (LOG) {
                    LogDebug.i(MAIN_TAG, "loadLocked(): Initialized but mLoader is Null");
                }
                return false;
            }
            if (load == LOAD_INFO) {
                boolean rl = mLoader.isPackageInfoLoaded();
                if (LOG) {
                    LogDebug.i(MAIN_TAG, "loadLocked(): Initialized, pkginfo loaded = " + rl);
                }
                return rl;
            }
            if (load == LOAD_RESOURCES) {
                boolean rl = mLoader.isResourcesLoaded();
                if (LOG) {
                    LogDebug.i(MAIN_TAG, "loadLocked(): Initialized, resource loaded = " + rl);
                }
                return rl;
            }
            if (load == LOAD_DEX) {
                boolean rl = mLoader.isDexLoaded();
                if (LOG) {
                    LogDebug.i(MAIN_TAG, "loadLocked(): Initialized, dex loaded = " + rl);
                }
                return rl;
            }
            boolean il = mLoader.isAppLoaded();
            if (LOG) {
                LogDebug.i(MAIN_TAG, "loadLocked(): Initialized, is loaded = " + il);
            }
            return il;
        }
        mInitialized = true;

        // 若开启了“打印详情”则打印调用栈，便于观察
        if (RePlugin.getConfig().isPrintDetailLog()) {
            String reason = "";
            reason += "--- plugin: " + mInfo.getName() + " ---\n";
            reason += "load=" + load + "\n";
            StackTraceElement elements[] = Thread.currentThread().getStackTrace();
            for (StackTraceElement item : elements) {
                if (item.isNativeMethod()) {
                    continue;
                }
                String cn = item.getClassName();
                String mn = item.getMethodName();
                String filename = item.getFileName();
                int line = item.getLineNumber();
                if (LOG) {
                    LogDebug.i(PLUGIN_TAG, cn + "." + mn + "(" + filename + ":" + line + ")");
                }
                reason += cn + "." + mn + "(" + filename + ":" + line + ")" + "\n";
            }
            if (sLoadedReasons == null) {
                sLoadedReasons = new ArrayList<String>();
            }
            sLoadedReasons.add(reason);
        }

        // 这里先处理一下，如果cache命中，省了后面插件提取（如释放Jar包等）操作
        if (useCache) {
            boolean result = loadByCache(load);
            // 如果缓存命中，则直接返回
            if (result) {
                return true;
            }
        }

        Context context = mContext;
        //这个原始的classLoader（没有被hook的） ,因为 PmBase 是在 hook classLoader 之前加载的
        ClassLoader parent = mParent;
        PluginCommImpl manager = mPluginManager;

        //
        String logTag = "try1";
        String lockFileName = String.format(Constant.LOAD_PLUGIN_LOCK, mInfo.getApkFile().getName());
        ProcessLocker lock = new ProcessLocker(context, lockFileName);
        if (LOG) {
            LogDebug.i(PLUGIN_TAG, "loadLocked(): Ready to lock! logtag = " + logTag + "; pn = " + mInfo.getName());
        }
        if (!lock.tryLockTimeWait(5000, 10)) {
            // 此处仅仅打印错误
            if (LOGR) {
                LogRelease.w(PLUGIN_TAG, logTag + ": failed to lock: can't wait plugin ready");
            }
        }
        //
        long t1 = System.currentTimeMillis();

        //加载
        boolean rc = doLoad(logTag, context, parent, manager, load);

        if (LOG) {
            //例如：load /data/user/0/com.qihoo360.replugin.sample.host/app_plugins_v3/demo1-10-10-104.jar 11862898 c=3 rc=true delta=133
            LogDebug.i(PLUGIN_TAG, "load " + mInfo.getPath() + " " + hashCode() + " c=" + load + " rc=" + rc + " delta=" + (System.currentTimeMillis() - t1));
        }
        //
        lock.unlock();
        if (LOG) {
            LogDebug.i(PLUGIN_TAG, "loadLocked(): Unlock! logtag = " + logTag + "; pn = " + mInfo.getName());
        }
        if (!rc) {
            if (LOGR) {
                LogRelease.e(PLUGIN_TAG, logTag + ": loading fail1");
            }
        }

        if (rc) {//dex 加载成功
            // 打印当前内存占用情况，只针对Dex和App加载做输出
            // 只有开启“详细日志”才会输出，防止“消耗性能”
            if (LOG && RePlugin.getConfig().isPrintDetailLog()) {
                if (load == LOAD_DEX || load == LOAD_APP) {
                    //例如：desc=, memory_v_0_0_1, process=, com.qihoo360.replugin.sample.host, totalPss=, 16365, dalvikPss=, 1004,
                    // nativeSize=, 4113, otherPss=, 11148, act=, loadLocked, flag=, Start, pn=, demo1, type=, 3, apk=, 61812, odex=,
                    // 21408, sys_api=, 28
                    LogDebug.printPluginInfo(mInfo, load);
                    //例如：desc=, memory_v_0_0_1, process=, com.qihoo360.replugin.sample.host, totalPss=, 16365, dalvikPss=, 1004, nativeSize=,
                    // 4113, otherPss=, 11148, act=, loadLocked, flag=, End-1, pn=, demo1, type=, 3
                    LogDebug.printMemoryStatus(LogDebug.TAG, "act=, loadLocked, flag=, End-1, pn=, " + mInfo.getName() + ", type=, " + load);
                }
            }
            try {
                // 至此，该插件已开始运行
                // 添加插件到"当前进程的正在运行插件列表"，并同步到Server端
                PluginManagerProxy.addToRunningPluginsNoThrows(mInfo.getName());
            } catch (Throwable e) {
                if (LOGR) {
                    LogRelease.e(PLUGIN_TAG, "p.u.1: " + e.getMessage(), e);
                }
            }

            return true;
        }

        // 下面是 dex 加载失败的处理 也就是 rc = false的情况下才会走到 下面的代码

        logTag = "try2";
        lock = new ProcessLocker(context, lockFileName);

        //尝试阻塞5s
        if (!lock.tryLockTimeWait(5000, 10)) {
            // 此处仅仅打印错误
            if (LOGR) {
                LogRelease.w(PLUGIN_TAG, logTag + ": failed to lock: can't wait plugin ready");
            }
        }

        // 删除优化dex文件
        File odex = mInfo.getDexFile();
        if (odex.exists()) {
            if (LOG) {
                LogDebug.d(PLUGIN_TAG, logTag + ": delete exist odex=" + odex.getAbsolutePath());
            }
            odex.delete();
        }


        //小于5.0 删除 额外的 dex
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            // support for multidex below LOLLIPOP:delete Extra odex,if need
            try {
                FileUtils.forceDelete(mInfo.getExtraOdexDir());
            } catch (IOException e) {
                e.printStackTrace();
            } catch (IllegalArgumentException e2) {
                e2.printStackTrace();
            }
        }

        t1 = System.currentTimeMillis();
        // 尝试再次加载该插件
        rc = tryLoadAgain(logTag, context, parent, manager, load);
        if (LOG) {
            LogDebug.i(PLUGIN_TAG, "load2 " + mInfo.getPath() + " " + hashCode() + " c=" + load + " rc=" + rc + " delta=" + (System.currentTimeMillis() - t1));
        }
        //
        lock.unlock();
        if (!rc) {//第二次失败 就放弃
            if (LOGR) {
                LogRelease.e(PLUGIN_TAG, logTag + ": loading fail2");
            }
            return false;
        }

        // 打印当前内存占用情况，只针对Dex和App加载做输出
        // 只有开启“详细日志”才会输出，防止“消耗性能”
        if (LOG && RePlugin.getConfig().isPrintDetailLog()) {
            if (load == LOAD_DEX || load == LOAD_APP) {
                LogDebug.printPluginInfo(mInfo, load);
                LogDebug.printMemoryStatus(LogDebug.TAG, "act=, loadLocked, flag=, End-2, pn=, " + mInfo.getName() + ", type=, " + load);
            }
        }

        try {
            // 至此，该插件已开始运行
            PluginManagerProxy.addToRunningPluginsNoThrows(mInfo.getName());
        } catch (Throwable e) {
            if (LOGR) {
                LogRelease.e(PLUGIN_TAG, "p.u.2: " + e.getMessage(), e);
            }
        }

        return true;
    }

    final IModule query(Class<? extends IModule> c) {
        return mLoader.mPlugin.query(c);
    }

    final IBinder query(String binder) {
        try {
            return mLoader.mBinderPlugin.mPlugin.query(binder);
        } catch (Throwable e) {
            if (LOGR) {
                LogRelease.e(PLUGIN_TAG, "q.b.e.m" + e.getMessage(), e);
            }
        }
        return null;
    }

    /**
     * 抽出方法，将mLoader设置为null与doload中mLoader的使用添加同步锁，解决在多线程下导致mLoader为空指针的问题。
     */
    private synchronized boolean tryLoadAgain(String tag, Context context, ClassLoader parent, PluginCommImpl manager, int load) {
        mLoader = null;
        return doLoad(tag, context, parent, manager, load);
    }

    /**
     *
     * @param tag
     * @param context
     * @param parent 这个原始的classLoader（没有被hook的） ,因为 PmBase 是在 hook classLoader 之前加载的
     * @param manager
     * @param load
     * @return 是否加载成功
     */
    private final boolean doLoad(String tag, Context context, ClassLoader parent, PluginCommImpl manager, int load) {
        if (mLoader == null) {
            // 试图释放文件
            PluginInfo info = null;
            if (mInfo.getType() == PluginInfo.TYPE_BUILTIN) {//内置插件
                //获取  data/data/packagename/plugins_v3 文件夹对象
                File dir = context.getDir(Constant.LOCAL_PLUGIN_SUB_DIR, 0);
                //获取优化后的 dex 文件存储目录  已内置插件为例：data/data/packagename/plugins_v3/oat/arm64/
                File dexdir = mInfo.getDexParentDir();
                //apk 所要存放的 文件路径 是个jar包  已内置插件为例：data/data/packagename/plugins_v3/webview-10-10-100.jar
                String dstName = mInfo.getApkFile().getName();
                //将assets下的插件copy到 data/data/packagename/plugins_v3 目录下
                boolean rc = AssetsUtils.quickExtractTo(context, mInfo, dir.getAbsolutePath(), dstName, dexdir.getAbsolutePath());
                if (!rc) {
                    // extract built-in plugin failed: plugin=
                    if (LOGR) {
                        LogRelease.e(PLUGIN_TAG, "p e b i p f " + mInfo);
                    }
                    return false;
                }
                //已内置插件为例 file的路径为 data/data/packagename/plugins_v3/demo1-10-10-104.jar
                File file = new File(dir, dstName);
                info = (PluginInfo) mInfo.clone();
                //更新 路径
                info.setPath(file.getPath());

                // 设置状态时已安装
                // FIXME 不应该是P-N，即便目录相同，未来会优化这里
                info.setType(PluginInfo.TYPE_PN_INSTALLED);

            } else if (mInfo.getType() == PluginInfo.TYPE_PN_JAR) {//老的插件先不看
                //
                V5FileInfo v5i = V5FileInfo.build(new File(mInfo.getPath()), mInfo.getV5Type());
                if (v5i == null) {
                    // build v5 plugin info failed: plugin=
                    if (LOGR) {
                        LogRelease.e(PLUGIN_TAG, "p e b v i f " + mInfo);
                    }
                    return false;
                }
                File dir = context.getDir(Constant.LOCAL_PLUGIN_SUB_DIR, 0);
                info = v5i.updateV5FileTo(context, dir, true, true);
                if (info == null) {
                    // update v5 file to failed: plugin=
                    if (LOGR) {
                        LogRelease.e(PLUGIN_TAG, "p u v f t f " + mInfo);
                    }
                    return false;
                }
                // 检查是否改变了？
                if (info.getLowInterfaceApi() != mInfo.getLowInterfaceApi() || info.getHighInterfaceApi() != mInfo.getHighInterfaceApi()) {
                    if (LOG) {
                        LogDebug.d(PLUGIN_TAG, "v5 plugin has changed: plugin=" + info + ", original=" + mInfo);
                    }
                    // 看看目标文件是否存在
                    String dstName = mInfo.getApkFile().getName();
                    File file = new File(dir, dstName);
                    if (!file.exists()) {
                        if (LOGR) {
                            LogRelease.e(PLUGIN_TAG, "can't load: v5 plugin has changed to "
                                    + info.getLowInterfaceApi() + "-" + info.getHighInterfaceApi()
                                    + ", orig " + mInfo.getLowInterfaceApi() + "-" + mInfo.getHighInterfaceApi()
                                    + " bare not exist");
                        }
                        return false;
                    }
                    // 重新构造
                    info = PluginInfo.build(file);
                    if (info == null) {
                        return false;
                    }
                }

            } else {
                //
            }

            //
            if (info != null) {
                // 更新mInfo
                mInfo = info;
            }

            //这里的  mInfo.getPath() 已经是 data/data 下的目录了
            mLoader = new Loader(context, mInfo.getName(), mInfo.getPath(), this);
            // 加载插件信息、资源、Dex，并运行Entry类(可能只包含其中的一部分 要看 load 的类型)
            if (!mLoader.loadDex(parent, load)) {
                return false;
            }

            // 设置插件为“使用过的”
            // 注意，需要重新获取当前的PluginInfo对象，而非使用“可能是新插件”的mInfo
            try {
                PluginManagerProxy.updateUsedIfNeeded(mInfo.getName(), true);
            } catch (RemoteException e) {
                // 同步出现问题，但仍继续进行
                if (LOGR) {
                    e.printStackTrace();
                }
            }

            // 若需要加载Dex，则还同时需要初始化插件里的Entry对象
            if (load == LOAD_APP) {
                // NOTE Entry对象是可以在任何线程中被调用到
                if (!loadEntryLocked(manager)) {
                    return false;
                }
                // NOTE 在此处调用则必须Post到UI，但此时有可能Activity已被加载
                //      会出现Activity.onCreate比Application更早的情况，故应放在load外面立即调用
                // callApp();
            }
        }

        if (load == LOAD_INFO) {
            return mLoader.isPackageInfoLoaded();
        } else if (load == LOAD_RESOURCES) {
            return mLoader.isResourcesLoaded();
        } else if (load == LOAD_DEX) {
            return mLoader.isDexLoaded();
        } else {
            return mLoader.isAppLoaded();
        }
    }

    private boolean loadEntryLocked(PluginCommImpl manager) {
        if (mDummyPlugin) {
            if (LOGR) {
                LogRelease.w(PLUGIN_TAG, "p.lel dm " + mInfo.getName());
            }
            mLoader.mPlugin = new IPlugin() {
                @Override
                public IModule query(Class<? extends IModule> c) {
                    return null;
                }
            };
        } else {
            if (LOG) {
                LogDebug.d(PLUGIN_TAG, "Plugin.loadEntryLocked(): Load entry, info=" + mInfo);
            }
            if (mLoader.loadEntryMethod2()) {
                if (!mLoader.invoke2(manager)) {
                    return false;
                }
            } else if (mLoader.loadEntryMethod(false)) {
                if (!mLoader.invoke(manager)) {
                    return false;
                }
            } else if (mLoader.loadEntryMethod3()) {//对于demo1 插件 会走这里
                if (!mLoader.invoke2(manager)) {
                    return false;
                }
            } else {
                if (LOGR) {
                    LogRelease.e(PLUGIN_TAG, "p.lel f " + mInfo.getName());
                }
                return false;
            }
        }
        return true;
    }

    // 确保在UI线程中调用
    // ATTENTION 必须在LOCK锁之外调用此方法
    //           否则一旦LOCK锁内任一位置再次调用Plugin.doLoad（如打开另一插件）时会造成循环锁
    // Added by Jiongxuan Zhang
    private void callApp() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            callAppLocked();
        } else {
            // 确保一定在UI的最早消息处调用
            mMainH.postAtFrontOfQueue(new Runnable() {
                @Override
                public void run() {
                    callAppLocked();
                }
            });
        }
    }

    /**
     * 启动插件Application
     */
    private void callAppLocked() {
        // 获取并调用Application的几个核心方法
        if (!mDummyPlugin) {
            // NOTE 不排除A的Application中调到了B，B又调回到A，或在同一插件内的onCreate开启Service/Activity，而内部逻辑又调用fetchContext并再次走到这里
            // NOTE 因此需要对mApplicationClient做判断，确保永远只执行一次，无论是否成功
            if (mApplicationClient != null) {
                // 已经初始化过，无需再次处理
                return;
            }

            //启动并初始化 插件Application
            mApplicationClient = PluginApplicationClient.getOrCreate(
                    mInfo.getName(), mLoader.mClassLoader, mLoader.mComponents, mLoader.mPluginObj.mInfo);

            if (mApplicationClient != null) {
                //调用插件Application的 attach 方法 ,传入的 context是 Loader 中创建的 插件使用的Context
                mApplicationClient.callAttachBaseContext(mLoader.mPkgContext);
                //调用插件的 onCreate()
                mApplicationClient.callOnCreate();
            }
        } else {
            if (LOGR) {
                LogRelease.e(PLUGIN_TAG, "p.cal dm " + mInfo.getName());
            }
        }
    }
}
