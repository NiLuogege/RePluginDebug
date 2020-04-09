package com.qihoo360.replugin.gradle.compat

import org.gradle.api.Task

/**
 * @author hyongbai
 * 对 Variant 做兼容的类
 */
class VariantCompat {
    static def getAssembleTask(def variant) {
        return compatGetTask(variant, "getAssembleProvider", "getAssemble")
    }

    //获取不同版本 合并assets 的Task
    static def getMergeAssetsTask(def variant) {
        return compatGetTask(variant, "getMergeAssetsProvider", "getMergeAssets")
    }

    //获取不同版本 生成BuildConfig.class 的Task
    static def getGenerateBuildConfigTask(def variant) {
        return compatGetTask(variant, "getGenerateBuildConfigProvider", "getGenerateBuildConfig")
    }

    static def getProcessManifestTask(def variant) {
        return compatGetTask(variant, "getProcessManifestProvider", "getProcessManifest")
    }

    static def compatGetTask(def variant, String... candidates) {
        candidates?.findResult {
            variant.metaClass.respondsTo(variant, it).with {
                if (!it.isEmpty()) return it
            }
        }?.find {
            it.getParameterTypes().length == 0
        }?.invoke(variant)?.with {
            //TODO: check if is provider!!!
            Task.class.isInstance(it) ? it : it?.get()
        }
    }

}