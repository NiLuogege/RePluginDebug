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

package com.qihoo360.replugin.gradle.host.creator.impl.json

import net.dongliu.apk.parser.ApkFile
import org.xml.sax.Attributes
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler

import javax.xml.parsers.SAXParser
import javax.xml.parsers.SAXParserFactory

/**
 * 从manifest的xml中抽取PluginInfo信息
 * @author RePlugin Team
 */
public class PluginInfoParser extends DefaultHandler {

    private final String ANDROID_NAME = "android:name"
    private final String ANDROID_VALUE = "android:value"

    private final String TAG_NAME = "com.qihoo360.plugin.name"
    private final String TAG_VERSION_LOW = "com.qihoo360.plugin.version.low"
    private final String TAG_VERSION_HIGH = "com.qihoo360.plugin.version.high"
    private final String TAG_VERSION_VER = "com.qihoo360.plugin.version.ver"
    private final String TAG_FRAMEWORK_VER = "com.qihoo360.framework.ver"

    private PluginInfo pluginInfo


    public PluginInfoParser(File pluginFile, def config) {

        pluginInfo = new PluginInfo()

        //通过apk-parser解析apk
        ApkFile apkFile = new ApkFile(pluginFile)
        //获取到Manifest
        String manifestXmlStr = apkFile.getManifestXml()
        ByteArrayInputStream inputStream = new ByteArrayInputStream(manifestXmlStr.getBytes("UTF-8"))

        //解析Manifest 获取 其中配置的插件信息
        SAXParserFactory factory = SAXParserFactory.newInstance()
        SAXParser parser = factory.newSAXParser()
        parser.parse(inputStream, this)

        //plugin 名称
        String fullName = pluginFile.name
        //记录plugin的路径
        pluginInfo.path = config.pluginDir + "/" + fullName
        //插件后缀（jar）
        String postfix = config.pluginFilePostfix
        //记录插件 名称
        pluginInfo.name = fullName.substring(0, fullName.length() - postfix.length())
    }


    public PluginInfo getPluginInfo() {
        return pluginInfo;
    }


    @Override
    public void startDocument() throws SAXException {
    }

    /**
     * 解析 manifast中 配置的 插件信息并记录
     */
    @Override
    public void startElement(String uri, String localName, String qName, Attributes attributes) throws SAXException {

        if ("meta-data" == qName) {
            //是 android:name 节点
            switch (attributes.getValue(ANDROID_NAME)) {
                case TAG_NAME://插件名称
                    pluginInfo.name = attributes.getValue(ANDROID_VALUE)
                    break;
                case TAG_VERSION_LOW://插件最低兼容版本
                    pluginInfo.low = new Long(attributes.getValue(ANDROID_VALUE))
                    break;
                case TAG_VERSION_HIGH:// 插件最高兼容版本
                    pluginInfo.high = new Long(attributes.getValue(ANDROID_VALUE))
                    break;
                case TAG_VERSION_VER://插件版本号
                    pluginInfo.ver = new Long(attributes.getValue(ANDROID_VALUE))
                    break
                case TAG_FRAMEWORK_VER://框架版本号
                    pluginInfo.frm = new Long(attributes.getValue(ANDROID_VALUE))
                    break
                default:
                    break
            }
        } else if ("manifest" == qName) {// 是 manifast 节点
            //记录包名
            pluginInfo.pkg = attributes.getValue("package")
            //记录 版本号
            pluginInfo.ver = new Long(attributes.getValue("android:versionCode"))
        }
    }

    @Override
    public void endElement(String uri, String localName, String qName)
            throws SAXException {
    }

    @Override
    public void characters(char[] ch, int start, int length) throws SAXException {
    }

}
