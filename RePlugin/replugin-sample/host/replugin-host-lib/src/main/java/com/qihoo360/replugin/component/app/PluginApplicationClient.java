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

package com.qihoo360.replugin.component.app;

import android.app.Application;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.res.Configuration;
import android.os.Build;
import android.text.TextUtils;

import com.qihoo360.LogUtil;
import com.qihoo360.mobilesafe.core.BuildConfig;
import com.qihoo360.replugin.utils.basic.ArrayMap;
import com.qihoo360.replugin.RePluginInternal;
import com.qihoo360.replugin.component.ComponentList;
import com.qihoo360.replugin.helper.LogDebug;
import com.qihoo360.replugin.model.PluginInfo;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static com.qihoo360.replugin.helper.LogDebug.LOG;
import static com.qihoo360.replugin.helper.LogDebug.PLUGIN_TAG;

/**
 * 一种能处理【插件】的Application的类
 *
 * @author RePlugin Team
 */
public class PluginApplicationClient {

    private static volatile boolean sInited;
    private static final byte[] LOCKER = new byte[0];
    private static Method sAttachBaseContextMethod; //Application.attach() 方法

    //插件的ClassLoader
    private final ClassLoader mPlgClassLoader;
    //插件的 ApplicationInfo 对象 ，应该就是 manifest中 对Application的描述
    private final ApplicationInfo mApplicationInfo;
    //自定义的Application 构造方法
    private Constructor mApplicationConstructor;

    //插件的 Application 对象
    private Application mApplication;

    // 插件名称（例如：demo1） 和 PluginApplicationClient 的映射关系
    private static ArrayMap<String, WeakReference<PluginApplicationClient>> sRunningClients = new ArrayMap<>();

    /**
     * 根据插件里的框架版本、Application等情况来创建PluginApplicationClient对象
     * 若已经存在，则返回之前创建的ApplicationClient对象（此时Application不一定被加载进来）
     * 若不符合条件（如插件加载失败、版本不正确等），则会返回null
     *
     * @param pn    插件名 例如：demo1
     * @param plgCL 插件的ClassLoader
     * @param cl    插件的ComponentList
     * @param pi    插件的信息
     */
    public static PluginApplicationClient getOrCreate(String pn, ClassLoader plgCL, ComponentList cl, PluginInfo pi) {
        if (pi.getFrameworkVersion() <= 1) {//低版本插件
            // 仅框架版本为2及以上的，才支持Application的加载
            if (LOG) {
                LogDebug.d(PLUGIN_TAG, "PAC.create(): FrameworkVer less than 1. cl=" + plgCL);
            }
            return null;
        }
        //从缓存中获取 PluginApplicationClient
        PluginApplicationClient pac = getRunning(pn);
        if (pac != null) {
            // 已经初始化过Application，直接返回
            if (LOG) {
                LogDebug.d(PLUGIN_TAG, "PAC.create(): Already Loaded." + plgCL);
            }
            return pac;
        }

        if (LOG) {
            LogDebug.d(PLUGIN_TAG, "PAC.create(): Create and load Application. cl=" + plgCL);
        }

        // 初始化所有需要反射的方法
        try {
            //反射获取 插件 Application 的 attach() 方法
            initMethods();
        } catch (Throwable e) {
            if (BuildConfig.DEBUG) {
                e.printStackTrace();
            }
            return null;
        }

        //创建一个 PluginApplicationClient 对象
        final PluginApplicationClient pacNew = new PluginApplicationClient(plgCL, cl, pi);
        if (pacNew.isValid()) {
            sRunningClients.put(pn, new WeakReference<>(pacNew));
            if (Build.VERSION.SDK_INT >= 14) {
                //宿主 注册 ComponentCallbacks2 监听，并将回调 分发给 PluginApplicationClient
                RePluginInternal.getAppContext().registerComponentCallbacks(new ComponentCallbacks2() {
                    @Override
                    public void onTrimMemory(int level) {
                        pacNew.callOnTrimMemory(level);
                    }

                    @Override
                    public void onConfigurationChanged(Configuration newConfig) {
                        pacNew.callOnConfigurationChanged(newConfig);
                    }

                    @Override
                    public void onLowMemory() {
                        pacNew.callOnLowMemory();
                    }
                });
            }
            return pacNew;
        } else {
            // Application对象没有初始化出来，则直接按失败处理
            return null;
        }
    }

    public static void notifyOnLowMemory() {
        for (WeakReference<PluginApplicationClient> pacw : sRunningClients.values()) {
            PluginApplicationClient pac = pacw.get();
            if (pac == null) {
                continue;
            }
            pac.callOnLowMemory();
        }
    }

    public static void notifyOnTrimMemory(int level) {
        for (WeakReference<PluginApplicationClient> pacw : sRunningClients.values()) {
            PluginApplicationClient pac = pacw.get();
            if (pac == null) {
                continue;
            }
            pac.callOnTrimMemory(level);
        }
    }

    public static void notifyOnConfigurationChanged(Configuration newConfig) {
        for (WeakReference<PluginApplicationClient> pacw : sRunningClients.values()) {
            PluginApplicationClient pac = pacw.get();
            if (pac == null) {
                continue;
            }
            pac.callOnConfigurationChanged(newConfig);
        }
    }

    /**
     * 从缓存中获取 PluginApplicationClient
     *
     * @param pn
     * @return
     */
    public static PluginApplicationClient getRunning(String pn) {
        WeakReference<PluginApplicationClient> w = sRunningClients.get(pn);
        if (w == null) {
            return null;
        }
        return w.get();
    }

    /**
     * 反射获取 插件 Application 的 attach() 方法
     *
     * @throws NoSuchMethodException
     */
    private static void initMethods() throws NoSuchMethodException {
        if (sInited) {
            return;
        }
        synchronized (LOCKER) {
            if (sInited) {
                return;
            }
            // NOTE getDeclaredMethod只能获取当前类声明的方法，无法获取继承到的，而getMethod虽可以获取继承方法，但又不能获取非Public的方法
            // NOTE 权衡利弊，还是仅构造函数用反射类，其余用它明确声明的类来做
            // 反射获取 插件 Application 的 attach() 方法
            sAttachBaseContextMethod = Application.class.getDeclaredMethod("attach", Context.class);
            sAttachBaseContextMethod.setAccessible(true);   // Protected 修饰
            sInited = true;
        }
    }

    /**
     * @param plgCL 插件的ClassLoader
     * @param cl    插件的ComponentList
     * @param pi    插件的信息
     */
    private PluginApplicationClient(ClassLoader plgCL, ComponentList cl, PluginInfo pi) {
        mPlgClassLoader = plgCL;
        //获取插件的 ApplicationInfo 对象
        mApplicationInfo = cl.getApplication();

        //例如：创建的 ApplicationInfo=
        // sourceDir= /data/user/0/com.qihoo360.replugin.sample.host/app_plugins_v3/demo1-10-10-104.jar
        // publicSourceDir= /data/user/0/com.qihoo360.replugin.sample.host/app_plugins_v3/demo1-10-10-104.jar
        // splitSourceDirs= null
        LogUtil.e("创建的 ApplicationInfo= " + "sourceDir= " + mApplicationInfo.sourceDir +
                " publicSourceDir= " + mApplicationInfo.publicSourceDir +
                " splitSourceDirs= " + mApplicationInfo.splitSourceDirs);

        try {
            // 尝试使用自定义Application（如有）
            if (mApplicationInfo != null && !TextUtils.isEmpty(mApplicationInfo.className)) {//插件的 manifest中配置了 自定义的Application
                initCustom();
            }
            // 若自定义有误（或没有)，且框架版本为3及以上的，则可以创建空Application对象，方便插件getApplicationContext到自己
            if (!isValid() && pi.getFrameworkVersion() >= 3) {
                //直接new 一个 Application
                mApplication = new Application();
            }
        } catch (Throwable e) {
            // 出现异常，表示Application有问题
            if (BuildConfig.DEBUG) {
                e.printStackTrace();
            }
            mApplication = new Application();
        }
    }

    public void callAttachBaseContext(Context c) {
        if (LOG) {
            LogDebug.d(PLUGIN_TAG, "PAC.callAttachBaseContext(): Call attachBaseContext(), cl=" + mPlgClassLoader);
        }
        try {
            sAttachBaseContextMethod.setAccessible(true);   // Protected 修饰
            sAttachBaseContextMethod.invoke(mApplication, c);
        } catch (Throwable e) {
            if (BuildConfig.DEBUG) {
                e.printStackTrace();
            }
        }
    }

    public void callOnCreate() {
        if (LOG) {
            LogDebug.d(PLUGIN_TAG, "PAC.callOnCreate(): Call onCreate(), cl=" + mPlgClassLoader);
        }
        mApplication.onCreate();
    }

    /**
     * 调用插件的 mApplication.onLowMemory
     */
    public void callOnLowMemory() {
        if (LOG) {
            LogDebug.d(PLUGIN_TAG, "PAC.callOnLowMemory(): Call onLowMemory(), cl=" + mPlgClassLoader);
        }
        mApplication.onLowMemory();
    }

    /**
     * 调用插件的 mApplication.onTrimMemory
     *
     * @param level
     */
    public void callOnTrimMemory(int level) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
            return;
        }

        if (LOG) {
            LogDebug.d(PLUGIN_TAG, "PAC.callOnLowMemory(): Call onTrimMemory(), cl=" + mPlgClassLoader + "; lv=" + level);
        }
        mApplication.onTrimMemory(level);
    }

    /**
     * 调用插件的 mApplication.onConfigurationChanged
     *
     * @param newConfig
     */
    public void callOnConfigurationChanged(Configuration newConfig) {
        if (LOG) {
            LogDebug.d(PLUGIN_TAG, "PAC.callOnLowMemory(): Call onConfigurationChanged(), cl=" + mPlgClassLoader + "; nc=" + newConfig);
        }
        mApplication.onConfigurationChanged(newConfig);
    }

    public Application getObj() {
        return mApplication;
    }

    /**
     * 加载插件自定义的 application
     * 并生成 mApplication 对象
     *
     * @return
     */
    private boolean initCustom() {
        try {
            // 获取 自定义的Application 构造方法
            initCustomConstructor();
            //通过反射 创建 插件自定义Application的 对象
            initCustomObject();

            // 看mApplication是否被初始化成功
            return mApplication != null;
        } catch (Throwable e) {
            // 出现异常，表示自定义Application有问题
            if (BuildConfig.DEBUG) {
                e.printStackTrace();
            }
        }
        return false;
    }

    /**
     * 获取 自定义的Application 构造方法
     *
     * @throws ClassNotFoundException
     * @throws NoSuchMethodException
     */
    private void initCustomConstructor() throws ClassNotFoundException, NoSuchMethodException {
        //插件的 Application 类路径
        String aic = mApplicationInfo.className;
        //通过插件的 classLoader 加载 插件自定义的Application 对象
        Class<?> psc = mPlgClassLoader.loadClass(aic);
        //自定义的Application 构造方法
        mApplicationConstructor = psc.getConstructor();
    }

    /**
     * 通过反射 创建 插件自定义Application的 对象
     *
     * @throws IllegalAccessException
     * @throws InvocationTargetException
     * @throws InstantiationException
     */
    private void initCustomObject() throws IllegalAccessException, InvocationTargetException, InstantiationException {
        Object appObj = mApplicationConstructor.newInstance();
        if (appObj instanceof Application) {
            mApplication = (Application) appObj;
        }
    }

    private boolean isValid() {
        return mApplication != null;
    }
}
