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
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ComponentInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Resources;
import android.os.Bundle;
import android.os.IBinder;
import android.text.TextUtils;
import android.util.Log;

import com.qihoo360.LogUtil;
import com.qihoo360.i.Factory;
import com.qihoo360.i.IModule;
import com.qihoo360.i.IPlugin;
import com.qihoo360.mobilesafe.core.BuildConfig;
import com.qihoo360.mobilesafe.parser.manifest.ManifestParser;
import com.qihoo360.replugin.RePlugin;
import com.qihoo360.replugin.base.IPC;
import com.qihoo360.replugin.component.ComponentList;
import com.qihoo360.replugin.component.process.PluginProcessHost;
import com.qihoo360.replugin.component.receiver.PluginReceiverProxy;
import com.qihoo360.replugin.helper.LogDebug;
import com.qihoo360.replugin.helper.LogRelease;
import com.qihoo360.replugin.model.PluginInfo;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.qihoo360.replugin.helper.LogDebug.LOADER_TAG;
import static com.qihoo360.replugin.helper.LogDebug.LOG;
import static com.qihoo360.replugin.helper.LogDebug.PLUGIN_TAG;
import static com.qihoo360.replugin.helper.LogRelease.LOGR;

/**
 * @author RePlugin Team
 */
class Loader {

    //宿主的 Application
    private final Context mContext;

    private final String mPluginName;//插件名称 例如：demo1

    final String mPath;//插件存储路径 如：/data/user/0/com.qihoo360.replugin.sample.host/app_plugins_v3/demo1-10-10-104.jar

    final Plugin mPluginObj;//传过来的插件对象

    PackageInfo mPackageInfo;

    Resources mPkgResources;//插件的 Resources

    //插件 使用的 context（PluginContext）
    Context mPkgContext;

    //插件使用的 classLoader(PluginDexClassLoader)
    ClassLoader mClassLoader;

    /**
     * 记录所有缓存的Component列表
     */
    ComponentList mComponents;

    Method mCreateMethod;

    Method mCreateMethod2;

    IPlugin mPlugin;

    IPluginHost mPluginHost;

    ProxyPlugin mBinderPlugin;

    /**
     * layout缓存：忽略表
     */
    HashSet<String> mIgnores = new HashSet<String>();

    /**
     * layout缓存：构造器表
     */
    HashMap<String, Constructor<?>> mConstructors = new HashMap<String, Constructor<?>>();

    static class ProxyPlugin implements IPlugin {

        com.qihoo360.loader2.IPlugin mPlugin;

        ProxyPlugin(IBinder plugin) {
            mPlugin = com.qihoo360.loader2.IPlugin.Stub.asInterface(plugin);
        }

        @Override
        public IModule query(Class<? extends IModule> c) {
            IBinder b = null;
            try {
                b = mPlugin.query(c.getName());
            } catch (Throwable e) {
                if (LOGR) {
                    LogRelease.e(PLUGIN_TAG, "query(" + c + ") exception: " + e.getMessage(), e);
                }
            }
            // TODO: return IModule
            return null;
        }
    }

    /**
     * 初始化Loader对象
     *
     * @param p Plugin类的对象
     *          为何会反向依赖plugin对象？因为plugin.mInfo对象会发生变化，
     *          缓存plugin可以实时拿到最新的mInfo对象，防止出现问题
     *          FIXME 有优化空间，但改动量会很大，暂缓
     */
    Loader(Context context, String name, String path, Plugin p) {
        mContext = context;
        mPluginName = name;
        mPath = path;
        mPluginObj = p;
    }

    final boolean isPackageInfoLoaded() {
        return mPackageInfo != null;
    }

    final boolean isResourcesLoaded() {
        return isPackageInfoLoaded() && mPkgResources != null;
    }

    final boolean isDexLoaded() {
        return isResourcesLoaded() && mClassLoader != null;
    }

    final boolean isAppLoaded() {
        return mPlugin != null;
    }

    final Context createBaseContext(Context newBase) {
        return new PluginContext(newBase, android.R.style.Theme, mClassLoader, mPkgResources, mPluginName, this);
    }

    /**
     * @param parent 这个原始的classLoader（没有被hook的） ,因为 PmBase 是在 hook classLoader 之前加载的
     * @param load   例：Plugin.LOAD_APP ： {@link com.qihoo360.loader2.Plugin 中有解释}
     * @return
     */
    final boolean loadDex(ClassLoader parent, int load) {
        try {
            PackageManager pm = mContext.getPackageManager();
            //获取packageInfo
            mPackageInfo = Plugin.queryCachedPackageInfo(mPath);
            if (mPackageInfo == null) {
                // 获取 插件的 PackageInfo
                mPackageInfo = pm.getPackageArchiveInfo(mPath,
                        PackageManager.GET_ACTIVITIES | PackageManager.GET_SERVICES | PackageManager.GET_PROVIDERS | PackageManager.GET_RECEIVERS | PackageManager.GET_META_DATA);
                if (mPackageInfo == null || mPackageInfo.applicationInfo == null) {
                    if (LOG) {
                        LogDebug.d(PLUGIN_TAG, "get package archive info null");
                    }
                    mPackageInfo = null;
                    return false;
                }
                if (LOG) {
                    LogDebug.d(PLUGIN_TAG, "get package archive info, pi=" + mPackageInfo
                            +" 插件原始 applicationInfo.sourceDir= "+mPackageInfo.applicationInfo.sourceDir
                            +" 插件原始 applicationInfo.publicSourceDir= "+mPackageInfo.applicationInfo.publicSourceDir);
                }
                //设置sourceDir 这俩路径在 获取插件的Resources对象是 要用到 (mPath 是 插件的存储路径 例如)
                //这俩路径在设置之前都是空的
                mPackageInfo.applicationInfo.sourceDir = mPath;
                mPackageInfo.applicationInfo.publicSourceDir = mPath;

                //设置进程名称
                if (TextUtils.isEmpty(mPackageInfo.applicationInfo.processName)) {
                    mPackageInfo.applicationInfo.processName = mPackageInfo.applicationInfo.packageName;
                }

                // 添加针对SO库的加载
                // 此属性最终用于ApplicationLoaders.getClassLoader，在创建PathClassLoader时成为其参数
                // 这样findLibrary可不用覆写，即可直接实现SO的加载
                // Added by Jiongxuan Zhang
                PluginInfo pi = mPluginObj.mInfo;
                File ld = pi.getNativeLibsDir();
                //设置 so文件存放 路径
                mPackageInfo.applicationInfo.nativeLibraryDir = ld.getAbsolutePath();

//                // 若PluginInfo.getFrameworkVersion为FRAMEWORK_VERSION_UNKNOWN（p-n才会有），则这里需要读取并修改
//                if (pi.getFrameworkVersion() == PluginInfo.FRAMEWORK_VERSION_UNKNOWN) {
//                    pi.setFrameworkVersionByMeta(mPackageInfo.applicationInfo.metaData);
//                }

                // 缓存 包名 -> 插件名的映射
                // 缓存表: pkgName -> pluginName
                synchronized (Plugin.PKG_NAME_2_PLUGIN_NAME) {
                    Plugin.PKG_NAME_2_PLUGIN_NAME.put(mPackageInfo.packageName, mPluginName);
                }

                // 缓存 插件名 -> 插件路径（data/data 下） 的映射
                // 缓存表: pluginName -> fileName
                synchronized (Plugin.PLUGIN_NAME_2_FILENAME) {
                    Plugin.PLUGIN_NAME_2_FILENAME.put(mPluginName, mPath);
                }

                // 缓存 插件路径（data/data 下） -> PackageInfo 的映射
                // 缓存表: fileName -> PackageInfo
                synchronized (Plugin.FILENAME_2_PACKAGE_INFO) {
                    Plugin.FILENAME_2_PACKAGE_INFO.put(mPath, new WeakReference<PackageInfo>(mPackageInfo));
                }
            }

            // TODO preload预加载虽然通知到常驻了(但pluginInfo是通过MP.getPlugin(name, true)完全clone出来的)，本进程的PluginInfo并没有得到更新
            // TODO 因此preload会造成某些插件真正生效时由于cache，造成插件版本号2.0或者以上无法生效。
            // TODO 这里是临时做法，避免发版前出现重大问题，后面可以修过修改preload的流程来优化
            // 若PluginInfo.getFrameworkVersion为FRAMEWORK_VERSION_UNKNOWN（p-n才会有），则这里需要读取并修改
            if (mPluginObj.mInfo.getFrameworkVersion() == PluginInfo.FRAMEWORK_VERSION_UNKNOWN) {
                mPluginObj.mInfo.setFrameworkVersionByMeta(mPackageInfo.applicationInfo.metaData);
                // 只有“P-n”插件才会到这里，故无需调用“纯APK”的保存功能
                // PluginInfoList.save();
            }

            // 创建或获取ComponentList表
            // Added by Jiongxuan Zhang
            mComponents = Plugin.queryCachedComponentList(mPath);
            if (mComponents == null) {
                // 创建 ComponentList
                mComponents = new ComponentList(mPackageInfo, mPath, mPluginObj.mInfo);

                // 动态注册插件中声明的 receiver 到常驻进程
                regReceivers();

                // 缓存 插件路径 -> mComponents 的关系
                // 缓存表：ComponentList
                synchronized (Plugin.FILENAME_2_COMPONENT_LIST) {
                    Plugin.FILENAME_2_COMPONENT_LIST.put(mPath, new WeakReference<>(mComponents));
                }

                /* 只调整一次 */
                // 调整插件中组件的进程名称(添加 组件 进程名)
                adjustPluginProcess(mPackageInfo.applicationInfo);

                // 调整插件中 Activity 的 TaskAffinity
                adjustPluginTaskAffinity(mPluginName, mPackageInfo.applicationInfo);
            }

            if (load == Plugin.LOAD_INFO) {
                return isPackageInfoLoaded();
            }

            // 解析资源
            //获取缓存的  Resources
            mPkgResources = Plugin.queryCachedResources(mPath);
            // LOAD_RESOURCES和LOAD_ALL都会获取资源，但LOAD_INFO不可以（只允许获取PackageInfo）
            if (mPkgResources == null) {
                // Resources
                try {
                    if (BuildConfig.DEBUG) {
                        // 如果是Debug模式的话，防止与Instant Run冲突，资源重新New一个
                        Resources r = pm.getResourcesForApplication(mPackageInfo.applicationInfo);
                        mPkgResources = new Resources(r.getAssets(), r.getDisplayMetrics(), r.getConfiguration());
                    } else {
                        //直接通过api获取 Resources
                        mPkgResources = pm.getResourcesForApplication(mPackageInfo.applicationInfo);
                    }
                } catch (NameNotFoundException e) {
                    if (LOG) {
                        LogDebug.d(PLUGIN_TAG, e.getMessage(), e);
                    }
                    return false;
                }
                if (mPkgResources == null) {
                    if (LOG) {
                        LogDebug.d(PLUGIN_TAG, "get resources null");
                    }
                    return false;
                }
                if (LOG) {
                    LogDebug.d(PLUGIN_TAG, "get resources for app, r=" + mPkgResources);
                }

                // 插件路径 和 Resources的 映射关系
                // 缓存表: Resources
                synchronized (Plugin.FILENAME_2_RESOURCES) {
                    Plugin.FILENAME_2_RESOURCES.put(mPath, new WeakReference<>(mPkgResources));
                }
            }
            if (load == Plugin.LOAD_RESOURCES) {
                return isResourcesLoaded();
            }

            //获取缓存的 ClassLoader
            mClassLoader = Plugin.queryCachedClassLoader(mPath);
            if (mClassLoader == null) {
                // 获取优化有的 dex 文件路径
                // 已内置插件为例：data/data/packagename/plugins_v3/oat/arm64/
                String out = mPluginObj.mInfo.getDexParentDir().getPath();
                //changeDexMode(out);

                //
                Log.i("dex", "load " + mPath + " ...");
                if (BuildConfig.DEBUG) {
                    // 因为Instant Run会替换parent为IncrementalClassLoader，所以在DEBUG环境里
                    // 需要替换为BootClassLoader才行
                    // Added by yangchao-xy & Jiongxuan Zhang
                    parent = ClassLoader.getSystemClassLoader();
                } else {
                    // 线上环境保持不变
                    parent = getClass().getClassLoader().getParent(); // TODO: 这里直接用父类加载器
                }

                // 获取 存储 库的 文件夹，这里是已经被替换过的
                // 例如 /data/user/0/com.qihoo360.replugin.sample.host/app_plugins_v3_libs/demo1-10-10-104
                String soDir = mPackageInfo.applicationInfo.nativeLibraryDir;
                LogUtil.e("soDir= " + soDir);

                long begin = 0;
                boolean isDexExist = false;

                if (LOG) {
                    begin = System.currentTimeMillis();
                    File dexFile = mPluginObj.mInfo.getDexFile();
                    if (dexFile.exists() && dexFile.length() > 0) {
                        isDexExist = true;
                    }
                }

                //创建插件使用的 classLoader对象
                // 这里会传入 dex 优化以后的路径也就是 out ,so文件路径 也就是 soDir
                mClassLoader = RePlugin.getConfig().getCallbacks().createPluginClassLoader(mPluginObj.mInfo, mPath, out, soDir, parent);

                //例如：load /data/user/0/com.qihoo360.replugin.sample.host/app_plugins_v3/demo1-10-10-104.jar
                // = com.qihoo360.replugin.PluginDexClassLoader[DexPathList[[zip file "/data/user/0/com.qihoo360.replugin.sample.host/app_plugins_v3/demo1-10-10-104.jar"],
                // nativeLibraryDirectories=[/data/user/0/com.qihoo360.replugin.sample.host/app_plugins_v3_libs/demo1-10-10-104, /system/lib64]]]
                Log.i("dex", "load " + mPath + " = " + mClassLoader);

                if (mClassLoader == null) {
                    if (LOG) {
                        LogDebug.d(PLUGIN_TAG, "get dex null");
                    }
                    return false;
                }

                if (LOG) {
                    if (!isDexExist) {
                        Log.d(LOADER_TAG, " --释放DEX, " + "(plugin=" + mPluginName + ", version=" + mPluginObj.mInfo.getVersion() + ")"
                                + ", use:" + (System.currentTimeMillis() - begin)
                                + ", process:" + IPC.getCurrentProcessName());
                    } else {
                        Log.d(LOADER_TAG, " --无需释放DEX, " + "(plugin=" + mPluginName + ", version=" + mPluginObj.mInfo.getVersion() + ")"
                                + ", use:" + (System.currentTimeMillis() - begin)
                                + ", process:" + IPC.getCurrentProcessName());
                    }
                }

                // 缓存插件使用的classLoader  插件路径 -> ClassLoader
                // 缓存表：ClassLoader
                synchronized (Plugin.FILENAME_2_DEX) {
                    Plugin.FILENAME_2_DEX.put(mPath, new WeakReference<>(mClassLoader));
                }
            }
            if (load == Plugin.LOAD_DEX) {
                return isDexLoaded();
            }

            // 创建插件 使用的 context（PluginContext），这个Context具有 在插件中打开 宿主 activity等能力
            mPkgContext = new PluginContext(mContext, android.R.style.Theme, mClassLoader, mPkgResources, mPluginName, this);
            if (LOG) {
                LogDebug.d(PLUGIN_TAG, "pkg context=" + mPkgContext);
            }

        } catch (Throwable e) {
            if (LOGR) {
                LogRelease.e(PLUGIN_TAG, "p=" + mPath + " m=" + e.getMessage(), e);
            }
            return false;
        }

        return true;
    }

    /**
     * 动态注册插件中静态声明的 receiver 到常驻进程
     *
     * @throws android.os.RemoteException
     */
    private void regReceivers() throws android.os.RemoteException {
        String plugin = mPluginObj.mInfo.getName();

        //获取到插件中注册的 receiver 信息
        Map<String, List<IntentFilter>> map = ManifestParser.INS.getReceiverFilterMap(plugin);

        if (map == null || map.size() == 0) {
            return;
        }

        //应该是获取 常驻进程的 代理对象
        if (mPluginHost == null) {
            mPluginHost = getPluginHost();
        }

        if (mPluginHost != null) {
            mPluginHost.regReceiver(plugin, map);
        }
    }

    /**
     * 获取 IPluginHost Binder 接口
     */
    private IPluginHost getPluginHost() {
        IBinder binder = PluginProviderStub.proxyFetchHostBinder(mContext);
        if (binder == null) {
            if (LOG) {
                LogDebug.e(PluginReceiverProxy.TAG, "p.p fhb fail");
            }
            return null;
        } else {
            return IPluginHost.Stub.asInterface(binder);
        }
    }

    final boolean loadEntryMethod(boolean log) {
        //
        try {
            String className = Factory.PLUGIN_ENTRY_PACKAGE_PREFIX + "." + mPluginName + "." + Factory.PLUGIN_ENTRY_CLASS_NAME;
            Class<?> c = mClassLoader.loadClass(className);
            if (LOG) {
                LogDebug.d(PLUGIN_TAG, "found entry: className=" + className + ", loader=" + c.getClassLoader());
            }
            mCreateMethod = c.getDeclaredMethod(Factory.PLUGIN_ENTRY_EXPORT_METHOD_NAME, Factory.PLUGIN_ENTRY_EXPORT_METHOD_PARAMS);
        } catch (Throwable e) {
            if (log) {
                if (LOGR) {
                    LogRelease.e(PLUGIN_TAG, e.getMessage(), e);
                }
            } else {
                if (LOG) {
                    LogDebug.d(PLUGIN_TAG, "loadEntryMethod exception");
                }
            }
        }
        return mCreateMethod != null;
    }

    final boolean invoke(PluginCommImpl manager) {
        try {
            mPlugin = (IPlugin) mCreateMethod.invoke(null, mPkgContext, manager);
            if (LOG) {
                LogDebug.d(PLUGIN_TAG, "Loader.invoke(): plugin=" + mPath + ", cl=" + (mPlugin != null ? mPlugin.getClass().getClassLoader() : "null"));
            }
        } catch (Throwable e) {
            if (LOGR) {
                LogRelease.e(PLUGIN_TAG, e.getMessage(), e);
            }
            return false;
        }
        return true;
    }

    final boolean loadEntryMethod2() {
        //
        try {
            String className = Factory.PLUGIN_ENTRY_PACKAGE_PREFIX + "." + mPluginName + "." + Factory.PLUGIN_ENTRY_CLASS_NAME;
            Class<?> c = mClassLoader.loadClass(className);
            if (LOG) {
                LogDebug.d(PLUGIN_TAG, "found entry: className=" + className + ", loader=" + c.getClassLoader());
            }
            mCreateMethod2 = c.getDeclaredMethod(Factory.PLUGIN_ENTRY_EXPORT_METHOD_NAME, Factory.PLUGIN_ENTRY_EXPORT_METHOD2_PARAMS);
        } catch (Throwable e) {
            // 老版本的插件才会用到这个方法，因后面还有新版本的load方式，这里不打log
//            if (LOGR) {
//                LogRelease.e(PLUGIN_TAG, e.getMessage(), e);
//            }
        }
        return mCreateMethod2 != null;
    }

    final boolean loadEntryMethod3() {
        //
        try {
            //className = com.qihoo360.replugin.Entry
            String className = Factory.REPLUGIN_LIBRARY_ENTRY_PACKAGE_PREFIX + "." + Factory.PLUGIN_ENTRY_CLASS_NAME;
            Class<?> c = mClassLoader.loadClass(className);
            if (LOG) {
                LogDebug.d(PLUGIN_TAG, "found entry: className=" + className + ", loader=" + c.getClassLoader());
            }
            //方法名为 create ，参数类型为  Context.class, ClassLoader.class, IBinder.class
            mCreateMethod2 = c.getDeclaredMethod(Factory.PLUGIN_ENTRY_EXPORT_METHOD_NAME, Factory.PLUGIN_ENTRY_EXPORT_METHOD2_PARAMS);
        } catch (Throwable e) {
            if (LOGR) {
                LogRelease.e(PLUGIN_TAG, e.getMessage(), e);
            }
        }
        return mCreateMethod2 != null;
    }

    final boolean invoke2(PluginCommImpl x) {
        try {
            IBinder manager = null; // TODO
            IBinder b = (IBinder) mCreateMethod2.invoke(null, mPkgContext, getClass().getClassLoader(), manager);
            if (b == null) {
                if (LOGR) {
                    LogRelease.e(PLUGIN_TAG, "p.e.r.b n");
                }
                return false;
            }
            //创建 mBinderPlugin
            mBinderPlugin = new ProxyPlugin(b);
            //赋值给 mBinderPlugin
            mPlugin = mBinderPlugin;
            if (LOG) {
                LogDebug.d(PLUGIN_TAG, "Loader.invoke2(): plugin=" + mPath + ", plugin.binder.cl=" + b.getClass().getClassLoader());
            }
        } catch (Throwable e) {
            if (LOGR) {
                LogRelease.e(PLUGIN_TAG, e.getMessage(), e);
            }
            return false;
        }
        return true;
    }

    /**
     * 获取宿主中可分配的自定义进程列表
     *
     * @return
     */
    private List<String> getHostProcessList() {
        List<String> pluginProcessList = new ArrayList<>();
        for (int i = 0; i < PluginProcessHost.PROCESS_COUNT; i++) {
            pluginProcessList.add(IPC.getPackageName() + PluginProcessHost.PROCESS_PLUGIN_SUFFIX2 + i);
        }
        return pluginProcessList;
    }

    /**
     * 读取插件中自定义进程列表
     *
     * @return
     */
    private List<String> getPluginProcessList() {
        Set<String> processSet = new HashSet<>();

        String pluginUIProcess = mComponents.getApplication().packageName;

        getPluginProcess(processSet, mComponents.getProviders());
        getPluginProcess(processSet, mComponents.getActivities());
        getPluginProcess(processSet, mComponents.getServices());
        getPluginProcess(processSet, mComponents.getReceivers());

        processSet.remove(pluginUIProcess);

        return Arrays.asList(processSet.toArray(new String[0]));
    }

    /**
     * 把来自插件的进程去重
     *
     * @param processSet
     * @param componentInfos
     */
    private void getPluginProcess(Set<String> processSet, ComponentInfo[] componentInfos) {
        if (componentInfos != null) {
            for (ComponentInfo componentInfo : componentInfos) {
                processSet.add(componentInfo.processName);
            }
        }
    }

    /**
     * 生成进程映射表，把插件中的自定义进程映射到宿主
     *
     * @return
     */
    private HashMap<String, String> genDynamicProcessMap() {
        HashMap<String, String> processMap = new HashMap<>();

        List<String> hostProcessList = getHostProcessList();
        List<String> pluginProcessList = getPluginProcessList();

        int hostProcessCount = hostProcessList != null ? hostProcessList.size() : 0;

        if (hostProcessCount <= 0) {
            return processMap;
        }

        int pluginProcessCount = pluginProcessList != null ? pluginProcessList.size() : 0;

        for (int i = 0; i < pluginProcessCount; i++) {
            int hostProcessIndex = i % hostProcessCount;
            processMap.put(pluginProcessList.get(i), hostProcessList.get(hostProcessIndex));
        }

        return processMap;
    }

    /**
     * 获取插件AndroidMainfest中配置的静态进程映射表，meta-data："process_map"
     * <p>
     * 在这里会将 插件中的进程 映射到 宿主项目的 坑中 并返回 插件中配置进程名和 实际使用坑名 对应关系
     *
     * @param appInfo
     * @return
     */
    private HashMap<String, String> getConfigProcessMap(ApplicationInfo appInfo) {
        HashMap<String, String> processMap = new HashMap<>();
        Bundle bdl = appInfo.metaData;
        if (bdl == null || TextUtils.isEmpty(bdl.getString("process_map"))) {
            return processMap;
        }
        try {
            String processMapStr = bdl.getString("process_map");
            JSONArray ja = new JSONArray(processMapStr);
            for (int i = 0; i < ja.length(); i++) {
                //jo 的格式为 {"from":"com.qihoo360.replugin.sample.demo1:bg","to":"$p0"}
                // 这个是 插件项目中我们自己在 manifest 中配置的
                JSONObject jo = (JSONObject) ja.get(i);
                LogUtil.e("process_map item= " + jo.toString());
                if (jo != null) {
                    String to = jo.getString("to").toLowerCase();
                    if (to.equals("$ui")) {//如果是UI进程 则修改对应进程名为 宿主包名
                        to = IPC.getPackageName();
                    } else {//非 UI 进程，且是用户自定义的进程
                        if (to.contains("$" + PluginProcessHost.PROCESS_PLUGIN_SUFFIX)) {
                            //设置为 宿主中的预埋进程
                            to = PluginProcessHost.PROCESS_ADJUST_MAP.get(to);
                        }
                    }
                    //替换信息
                    processMap.put(jo.getString("from"), to);
                }
            }
        } catch (JSONException e) {
            if (BuildConfig.DEBUG) {
                e.printStackTrace();
            }
        }
        return processMap;
    }

    /**
     * 调整插件中组件的进程名称，用宿主中的进程坑位来接收插件中的自定义进程
     * <p>
     * 注：
     * 如果插件中没有配置静态的 “meta-data：process_map” 进行静态的进程映射，则自动为插件中组件分配进程
     *
     * @param appInfo
     */
    private void adjustPluginProcess(ApplicationInfo appInfo) {
        //将 插件中的进程 映射到 宿主项目的 坑中 并返回 插件中配置进程名和 实际使用坑名 对应关系
        HashMap<String, String> processMap = getConfigProcessMap(appInfo);
        if (processMap == null || processMap.isEmpty()) {

            PluginInfo pi = MP.getPlugin(mPluginName, false);
            //如果插件的版本大于等于四 就替换 processMap？ 应该是兼容 p_n插件的
            if (pi != null && pi.getFrameworkVersion() >= 4) {
                processMap = genDynamicProcessMap();
            }
        }

        if (LOG) {
            Log.d(PLUGIN_TAG, "--- 调整插件中组件的进程 BEGIN ---");
            for (Map.Entry<String, String> entry : processMap.entrySet()) {
                Log.d(PLUGIN_TAG, entry.getKey() + " -> " + entry.getValue());
            }
        }

        doAdjust(processMap, mComponents.getActivityMap());
        doAdjust(processMap, mComponents.getServiceMap());
        doAdjust(processMap, mComponents.getReceiverMap());
        doAdjust(processMap, mComponents.getProviderMap());

        if (LOG) {
            Log.d(PLUGIN_TAG, "--- 调整插件中组件的进程 END --- " + IPC.getCurrentProcessName());
        }
    }

    /**
     * @param processMap 插件中配置进程名和 实际使用坑名 对应关系
     * @param infos      Class类名 -> 四大组件的映射表
     */
    private void doAdjust(HashMap<String, String> processMap, HashMap<String, ? extends ComponentInfo> infos) {

        if (processMap == null || processMap.isEmpty()) {
            return;
        }

        for (HashMap.Entry<String, ? extends ComponentInfo> entry : infos.entrySet()) {
            ComponentInfo info = entry.getValue();
            if (info != null) {
                //获取 宿主中坑位进程名
                String targetProcess = processMap.get(info.processName);

                if (!TextUtils.isEmpty(targetProcess)) {
                    if (LOG) {
                        //例如：com.qihoo360.replugin.sample.demo1.service.PluginDemoService1, com.qihoo360.replugin.sample.demo1:bg -> com.qihoo360.replugin.sample.host:p0
                        Log.d(TaskAffinityStates.TAG, String.format("--- 调整组件 %s, %s -> %s", info.name, info.processName, targetProcess));
                    }

                    //重新设置进程
                    info.processName = targetProcess;
                }
            }
        }
    }

    /**
     * 调整插件中 Activity 的默认 TaskAffinity
     *
     * @param plugin  插件名称 例如：demo1
     * @param appInfo 插件的 ApplicationInfo
     */
    private void adjustPluginTaskAffinity(String plugin, ApplicationInfo appInfo) {
        if (appInfo == null) {
            return;
        }

        Bundle bdl = appInfo.metaData;
        if (bdl != null) {
            // 如果没有配置 use_default_task_affinity 默认使用 默认的TaskAffinity 也就是 包名
            boolean useDefault = bdl.getBoolean("use_default_task_affinity", true);
            if (LOG) {
                LogDebug.d(TaskAffinityStates.TAG, "useDefault = " + useDefault);
            }

            if (!useDefault) {//如果不使用默认的
                if (LOG) {
                    LogDebug.d(TaskAffinityStates.TAG, String.format("替换插件 %s 中默认的 TaskAffinity", plugin));
                }

                //获取包名 也就是 默认的 TaskAffinity 名称
                String defaultPluginTaskAffinity = appInfo.packageName;
                for (HashMap.Entry<String, ActivityInfo> entry : mComponents.getActivityMap().entrySet()) {
                    ActivityInfo info = entry.getValue();
                    if (LOG) {
                        if (info != null) {
                            LogDebug.d(TaskAffinityStates.TAG, String.format("%s.taskAffinity = %s ", info.name, info.taskAffinity));
                        }
                    }

                    // 如果是默认 TaskAffinity
                    if (info != null && info.taskAffinity.equals(defaultPluginTaskAffinity)) {
                        // 修改 TaskAffinity 为 包名+plugin路径
                        info.taskAffinity = info.taskAffinity + "." + plugin;
                        if (LOG) {
                            LogDebug.d(TaskAffinityStates.TAG, String.format("修改 %s 的 TaskAffinity 为 %s", info.name, info.taskAffinity));
                        }
                    }
                }
            }
        }
    }
}
