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

package com.qihoo360.replugin.gradle.host

import com.android.build.gradle.AppExtension
import com.android.build.gradle.AppPlugin
import com.qihoo360.replugin.gradle.compat.VariantCompat
import com.qihoo360.replugin.gradle.host.creator.FileCreators
import com.qihoo360.replugin.gradle.host.creator.IFileCreator
import com.qihoo360.replugin.gradle.host.creator.impl.json.PluginBuiltinJsonCreator
import com.qihoo360.replugin.gradle.host.handlemanifest.ComponentsGenerator
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.logging.LogLevel

/**
 * @author RePlugin Team
 */
public class Replugin implements Plugin<Project> {

    def static TAG = AppConstant.TAG
    def project
    def config

    @Override
    public void apply(Project project) {
        println "${TAG} Welcome to replugin world ! "

        this.project = project

        //创建配置信息extension，名称为 repluginHostConfig 可配置的信息在 RepluginConfig 中，都有默认值
        project.extensions.create(AppConstant.USER_CONFIG, RepluginConfig)

        //如果是application工程
        if (project.plugins.hasPlugin(AppPlugin)) {

            def android = project.extensions.getByType(AppExtension)
            android.applicationVariants.all { variant ->

                //添加 rpShowPlugin... Task
                addShowPluginTask(variant)

                if (config == null) {
                    config = project.extensions.getByName(AppConstant.USER_CONFIG)
                    //创建配置 并对配置项进行检查
                    checkUserConfig(config)
                }

                //获取生成BuildConfig.class 的Task
                def generateBuildConfigTask = VariantCompat.getGenerateBuildConfigTask(variant)
                //获取host包名（为啥要这样获取包名）
                def appID = generateBuildConfigTask.appPackageName
                //构建占坑的内容
                def newManifest = ComponentsGenerator.generateComponent(appID, config)
                println "${TAG} countTask=${config.countTask}"

                def variantData = variant.variantData
                def scope = variantData.scope

                //添加rpGenerateHostConfig Task 用于创建 RePluginHostConfig.java
                def generateHostConfigTaskName = scope.getTaskName(AppConstant.TASK_GENERATE, "HostConfig")
                def generateHostConfigTask = project.task(generateHostConfigTaskName)

                generateHostConfigTask.doLast {
                    //创建 RePluginHostConfig.java
                    FileCreators.createHostConfig(project, variant, config)
                }
                //设置分组
                generateHostConfigTask.group = AppConstant.TASKS_GROUP

                //设置依赖关系 依赖系统的buildconfig生成task（为啥要搞个双层依赖 既有 dependsOn 又有 finalizedBy）
                if (generateBuildConfigTask) {
                    generateHostConfigTask.dependsOn generateBuildConfigTask
                    generateBuildConfigTask.finalizedBy generateHostConfigTask
                }

                //添加rpGenerateBuiltinJson Task 用于生成 plugins-builtin.json 文件（不过这个文件不是在addShowPluginTask（）已经生成过了吗？，不过task名字不一样）
                // addShowPluginTask 只有在 第一次运行的时候才会生效 而  rpGenerateBuiltinJson 每次都会生效
                def generateBuiltinJsonTaskName = scope.getTaskName(AppConstant.TASK_GENERATE, "BuiltinJson")
                def generateBuiltinJsonTask = project.task(generateBuiltinJsonTaskName)

                generateBuiltinJsonTask.doLast {
                    FileCreators.createBuiltinJson(project, variant, config)
                }
                generateBuiltinJsonTask.group = AppConstant.TASKS_GROUP

                //depends on mergeAssets Task
                def mergeAssetsTask = VariantCompat.getMergeAssetsTask(variant)
                if (mergeAssetsTask) {
                    generateBuiltinJsonTask.dependsOn mergeAssetsTask
                    mergeAssetsTask.finalizedBy generateBuiltinJsonTask
                }

                //在处理 manifest 任务之后将 上面生产的占坑信息，插入到manifast中
                variant.outputs.each { output ->
                    VariantCompat.getProcessManifestTask(output).doLast {
                        println "${AppConstant.TAG} processManifest: ${it.outputs.files}"
                        //遍历所有文件
                        it.outputs.files.each { File file ->
                            updateManifest(file, newManifest)
                        }
                    }
                }
            }
        }
    }

    /**
     *
     * @hyongbai
     * 
     * 在gradle plugin 3.0.0之前，file是文件，且文件名为AndroidManifest.xml
     * 在gradle plugin 3.0.0之后，file是目录，(特别是3.3.2)在这里改成递归的方式替换内部所有的 manifest 文件
     *
     * @param file manifest文件
     * @param newManifest 需要添加的 manifest 信息
     */
    def updateManifest(def file, def newManifest) {
        // 除了目录和AndroidManifest.xml之外，还可能会包含manifest-merger-debug-report.txt等不相干的文件，过滤它
        if (file == null || !file.exists() || newManifest == null) return
        if (file.isDirectory()) {
            println "${AppConstant.TAG} updateManifest: ${file}"
            file.listFiles().each {
                updateManifest(it, newManifest)
            }
        } else if (file.name.equalsIgnoreCase("AndroidManifest.xml")) {
            appendManifest(file, newManifest)
        }
    }

    def appendManifest(def file, def content) {
        if (file == null || !file.exists()) return
        println "${AppConstant.TAG} appendManifest: ${file}"
        //将上面生成的信息，插入到Manifest 中
        def updatedContent = file.getText("UTF-8").replaceAll("</application>", content + "</application>")
        file.write(updatedContent, 'UTF-8')
    }

    // 添加 【查看所有插件信息】 任务
    def addShowPluginTask(def variant) {
        def variantData = variant.variantData
        def scope = variantData.scope
        // 获取 rpShowPlugin... 在当前variant 下的总名称
        def showPluginsTaskName = scope.getTaskName(AppConstant.TASK_SHOW_PLUGIN, "")
        //创建一个task
        def showPluginsTask = project.task(showPluginsTaskName)

        //设置action
        showPluginsTask.doLast {
            //创建 json 文件构建器
            IFileCreator creator = new PluginBuiltinJsonCreator(project, variant, config)
            //assets 合并后的输出 文件夹
            def dir = creator.getFileDir()

            if (!dir.exists()) {
                println "${AppConstant.TAG} The ${dir.absolutePath} does not exist "
                println "${AppConstant.TAG} pluginsInfo=null"
                return
            }

            //创建json文件内容
            String fileContent = creator.getFileContent()
            if (null == fileContent) {
                return
            }

            //将文件内容写入到 plugins-builtin.json 中
            new File(dir, creator.getFileName()).write(fileContent, 'UTF-8')
        }
        //确定 task 分组
        showPluginsTask.group = AppConstant.TASKS_GROUP

        def mergeAssetsTask = VariantCompat.getMergeAssetsTask(variant)
        if (mergeAssetsTask) {
            // 将rpShowPlugin... task 添加到 mergeAssetsTask 之后
            showPluginsTask.dependsOn mergeAssetsTask
        }

    }

    /**
     * 检查用户配置项
     */
    def checkUserConfig(config) {
/*
        def persistentName = config.persistentName

        if (persistentName == null || persistentName.trim().equals("")) {
            project.logger.log(LogLevel.ERROR, "\n---------------------------------------------------------------------------------")
            project.logger.log(LogLevel.ERROR, " ERROR: persistentName can'te be empty, please set persistentName in replugin. ")
            project.logger.log(LogLevel.ERROR, "---------------------------------------------------------------------------------\n")
            System.exit(0)
            return
        }
*/
        doCheckConfig("countProcess", config.countProcess)
        doCheckConfig("countTranslucentStandard", config.countTranslucentStandard)
        doCheckConfig("countTranslucentSingleTop", config.countTranslucentSingleTop)
        doCheckConfig("countTranslucentSingleTask", config.countTranslucentSingleTask)
        doCheckConfig("countTranslucentSingleInstance", config.countTranslucentSingleInstance)
        doCheckConfig("countNotTranslucentStandard", config.countNotTranslucentStandard)
        doCheckConfig("countNotTranslucentSingleTop", config.countNotTranslucentSingleTop)
        doCheckConfig("countNotTranslucentSingleTask", config.countNotTranslucentSingleTask)
        doCheckConfig("countNotTranslucentSingleInstance", config.countNotTranslucentSingleInstance)
        doCheckConfig("countTask", config.countTask)

        println '--------------------------------------------------------------------------'
//        println "${TAG} appID=${appID}"
        println "${TAG} useAppCompat=${config.useAppCompat}"
        // println "${TAG} persistentName=${config.persistentName}"
        println "${TAG} countProcess=${config.countProcess}"

        println "${TAG} countTranslucentStandard=${config.countTranslucentStandard}"
        println "${TAG} countTranslucentSingleTop=${config.countTranslucentSingleTop}"
        println "${TAG} countTranslucentSingleTask=${config.countTranslucentSingleTask}"
        println "${TAG} countTranslucentSingleInstance=${config.countTranslucentSingleInstance}"
        println "${TAG} countNotTranslucentStandard=${config.countNotTranslucentStandard}"
        println "${TAG} countNotTranslucentSingleTop=${config.countNotTranslucentSingleTop}"
        println "${TAG} countNotTranslucentSingleTask=${config.countNotTranslucentSingleTask}"
        println "${TAG} countNotTranslucentSingleInstance=${config.countNotTranslucentSingleInstance}"

        println "${TAG} countTask=${config.countTask}"
        println '--------------------------------------------------------------------------'
    }

    /**
     * 检查配置项是否正确
     * @param name 配置项
     * @param count 配置值
     */
    def doCheckConfig(def name, def count) {
        if (!(count instanceof Integer) || count < 0) {
            this.project.logger.log(LogLevel.ERROR, "\n--------------------------------------------------------")
            this.project.logger.log(LogLevel.ERROR, " ${TAG} ERROR: ${name} must be an positive integer. ")
            this.project.logger.log(LogLevel.ERROR, "--------------------------------------------------------\n")
            System.exit(0)
        }
    }
}

class RepluginConfig {

    /** 自定义进程的数量(除 UI 和 Persistent 进程) 每个进程都会埋一波坑*/
    def countProcess = 3

    /** 是否使用常驻进程？ */
    def persistentEnable = true

    /** 常驻进程名称（也就是上面说的 Persistent 进程，开发者可自定义）*/
    def persistentName = ':GuardService'

    /** 背景不透明的坑的数量 */
    def countNotTranslucentStandard = 6
    def countNotTranslucentSingleTop = 2
    def countNotTranslucentSingleTask = 3
    def countNotTranslucentSingleInstance = 2

    /** 背景透明的坑的数量 */
    def countTranslucentStandard = 2
    def countTranslucentSingleTop = 2
    def countTranslucentSingleTask = 2
    def countTranslucentSingleInstance = 3

    /** 宿主中声明的 TaskAffinity 的组数(每组都有 配置的透明的不透明的坑的数量) */
    def countTask = 2

    /**
     * 是否使用 AppCompat 库
     * com.android.support:appcompat-v7:25.2.0
     */
    def useAppCompat = false

    /** HOST 向下兼容的插件版本 */
    def compatibleVersion = 10

    /** HOST 插件版本 */
    def currentVersion = 12

    /** plugins-builtin.json 文件名自定义,默认是 "plugins-builtin.json" */
    def builtInJsonFileName = "plugins-builtin.json"

    /** 是否自动管理 plugins-builtin.json 文件,默认自动管理 */
    def autoManageBuiltInJsonFile = true

    /** assert目录下放置插件文件的目录自定义,默认是 assert 的 "plugins" */
    def pluginDir = "plugins"

    /** 插件文件的后缀自定义,默认是".jar" 暂时支持 jar 格式*/
    def pluginFilePostfix = ".jar"

    /** 当发现插件目录下面有不合法的插件 jar (有可能是特殊定制 jar)时是否停止构建,默认是 true */
    def enablePluginFileIllegalStopBuild = true
}
