package com.fran.merge;

import com.fran.util.Utils;
import com.fran.utils.FileUtils;

import org.dom4j.Attribute;
import org.dom4j.Document;
import org.dom4j.DocumentException;
import org.dom4j.Element;
import org.dom4j.io.OutputFormat;
import org.dom4j.io.SAXReader;
import org.dom4j.io.XMLWriter;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * @author 程良明
 * @date 2022/10/10
 * * 说明:合并2个apk
 * 1、将plugin的copy到work。
 * 2、当处于文件合并的时候保留work的（例如:AndroidManiFest.xml, value/下的xml文件）
 * 3、当处于文件替换的时候即由plugin替换work的对应文件
 **/
public class MergeBase {
    private String[] mCopyWhiteList = new String[]{"assets", "lib", "res", "smali"};
    protected String mWorkPackName;
    protected String mPluginPackName;
    private final String mWorkPath;
    private final String mPluginPath;
    protected Document mWorkDocument;
    protected Document mPluginDocument;

    public static void main(String[] args) {
        MergeBase mergeBase = new MergeBase("F:\\Work\\Test\\A", "F:\\Work\\Test\\B");
        mergeBase.merge();
    }

    /**
     * 构造方法
     *
     * @param workPath   apktool解包后的文件路径
     * @param pluginPath apktool解包后的文件路径
     */
    public MergeBase(String workPath, String pluginPath) {
        mWorkPath = workPath;
        mPluginPath = pluginPath;
        String workManifestPath = Utils.linkPath(workPath, "AndroidManifest.xml");
        String pluginManifestPath = Utils.linkPath(pluginPath, "AndroidManifest.xml");
        try {
            SAXReader saxReader = new SAXReader();
            mWorkDocument = saxReader.read(workManifestPath);
            mPluginDocument = saxReader.read(pluginManifestPath);
            mWorkPackName = mWorkDocument.getRootElement().attributeValue("package");
            mPluginPackName = mPluginDocument.getRootElement().attributeValue("package");
        } catch (DocumentException e) {
            e.printStackTrace();
        }
    }

    /**
     * 合并操作
     */
    public void merge() {
        mergeManiFestXml();
        writeXmlFile(mWorkPath, mWorkDocument);
        copyPluginSource();
    }

    /**
     * 拷贝插件资源
     */
    public void copyPluginSource() {
        File workDir = new File(mWorkPath);
        File pluginDir = new File(mPluginPath);
        File[] files = pluginDir.listFiles((file, s) -> {
            String fileName = s.toLowerCase();
            for (String whiteName : mCopyWhiteList) {
                if (fileName.startsWith(whiteName)) {
                    return true;
                }
            }
            return false;
        });

        for (File file : files) {
            String fileName = file.getName();
            switch (fileName) {
                case "lib":
                    copyLibs(workDir, file);
                    break;
                case "res":
//                    value的需要合并，其他直接覆盖
                    File[] mergeValueFiles = file.listFiles((file1, s) -> s.startsWith("value"));
                    File[] copyFiles = file.listFiles((file1, s) -> !s.startsWith("value"));
                    if (copyFiles != null) {
                        for (File tempFile : copyFiles) {
                            for (File xmlFile : Objects.requireNonNull(tempFile.listFiles())) {
                                FileUtils.copyOperation(xmlFile, new File(xmlFile.getPath().replace(mPluginPath, mWorkPath)));
                            }
                        }
                    }
                    if (mergeValueFiles != null) {
                        SAXReader saxReader = new SAXReader();
                        for (File tempFile : mergeValueFiles) {
                            //  合并操作
                            for (File xmlFile : Objects.requireNonNull(tempFile.listFiles())) {
                                String fileType = xmlFile.getName().replace(".xml", "");
                                File workXmlFile = new File(xmlFile.getPath().replace(mPluginPath, mWorkPath));
                                if (workXmlFile.exists()) {
                                    if ("public".equals(fileType)) {
                                        //  public合并
                                        mergePublicXml(saxReader, xmlFile, workXmlFile);
                                        continue;
                                    }

                                    try {
                                        Document pluginXml = saxReader.read(xmlFile);
                                        Document workXml = saxReader.read(workXmlFile);
                                        List<String> workNameList = new ArrayList<>(1024);
                                        Map<String, Element> workMapElement = new HashMap<>(1024);
                                        for (Element element : workXml.getRootElement().elements()) {
                                            String name = element.attributeValue("name");
                                            if (workNameList.contains(name)) {
                                                continue;
                                            }
                                            workNameList.add(name);
                                            workMapElement.put(name, element);
                                        }
                                        List<String> pluginNameList = new ArrayList<>(1024);
                                        for (Element element : pluginXml.getRootElement().elements()) {
                                            String name = element.attributeValue("name");
                                            if (pluginNameList.contains(name)) {
                                                continue;
                                            }
                                            pluginNameList.add(name);
                                            if (workNameList.contains(name)) {
//                                                以插件为准，移除work的
                                                Element oldElement = workMapElement.get(name);
                                                workXml.remove(oldElement);
                                            }
                                        }
//                                        合并
                                        workXml.appendContent(pluginXml);
                                        writeXmlFile(workXmlFile.getPath(), workXml);
                                    } catch (DocumentException e) {
                                        e.printStackTrace();
                                    }
                                } else {
                                    FileUtils.copyOperation(xmlFile, new File(xmlFile.getPath().replace(mPluginPath, mWorkPath)));
                                }

                            }
                        }
                    }

                    break;
                default:
                    if (fileName.startsWith("smali")) {

                    }
                    break;
            }
        }
    }

    private void mergePublicXml(SAXReader saxReader, File xmlFile, File workXmlFile) {
        try {
            Document pluginXml = saxReader.read(xmlFile);
            Document workXml = saxReader.read(workXmlFile);
            Map<String, Map<String, Integer>> workMapTypeName = new HashMap<>(1024);
            Map<String, Integer> typeMaxIdMap = new HashMap<>(16);
            for (Element element : workXml.getRootElement().elements()) {
                String type = element.attributeValue("type");
                String name = element.attributeValue("name");
                String idString = element.attributeValue("id");
                int id = Integer.parseInt(idString, 16);
                Map<String, Integer> nameIdMap = workMapTypeName.get(type);
                if (nameIdMap == null) {
                    nameIdMap = new HashMap<>(1024);
                }
                nameIdMap.put(name, id);
                workMapTypeName.put(type, nameIdMap);
                if (typeMaxIdMap.get(type) < id) {
//                                                    更新类型最大值
                    typeMaxIdMap.put(type, id);
                }
            }

//            获取最大Id，用来添加新类型
            int allTypeMaxID = 0;
            for (int id : typeMaxIdMap.values()) {
                if (id > allTypeMaxID) {
                    allTypeMaxID = id;
                }
            }

//            改变plugin值
            for (Element element : pluginXml.getRootElement().elements()) {
                String type = element.attributeValue("type");
                String name = element.attributeValue("name");
                if (workMapTypeName.containsKey(type)) {
                    if (workMapTypeName.get(type).containsKey(name)) {
                        pluginXml.getRootElement().remove(element);
                    } else {
                        int maxId = typeMaxIdMap.get(type);
                        int newId = maxId + 1;
                        element.attribute("id").setValue(Integer.toHexString(newId));
                        typeMaxIdMap.put(type, newId);
                    }
                } else {
//                    类型不存在，需要更改所有的id
                    int id = typeMaxIdMap.get(type);
                    if (id == 0) {
                        id = ((allTypeMaxID >> 16) + 1) << 16;
                    } else {
                        id++;
                    }
                    element.attribute("id").setValue(Integer.toHexString(id));
                    typeMaxIdMap.put(type, id);
                    allTypeMaxID = id;
                }
            }


        } catch (DocumentException e) {
            e.printStackTrace();
        }
    }

    private void copyLibs(File workDir, File file) {
        String[] workLibFileName = new File(workDir, "lib").list();

        if (workLibFileName == null || workLibFileName.length == 0) {
            for (File tempFile : Objects.requireNonNull(file.listFiles())) {
                FileUtils.copyOperation(tempFile, new File(tempFile.getPath().replace(mPluginPath, mWorkPath)));
            }
        } else {
            List<String> list = Arrays.asList(workLibFileName);
            File[] pluginLibApiFiles = file.listFiles((file1, s) -> list.contains(s));
            if (pluginLibApiFiles != null) {
                for (File tempFile : pluginLibApiFiles) {
                    Utils.log("拷贝plugin的lib");
                    FileUtils.copyOperation(tempFile, new File(tempFile.getPath().replace(mPluginPath, mWorkPath)));
                }
            }
        }
    }

    /**
     * 合并后的文件序列化
     */
    private void writeXmlFile(String outPutPath, Document document) {
        XMLWriter writer = null;
        try (FileWriter fileWriter = new FileWriter(outPutPath)) {
            writer = new XMLWriter(fileWriter, OutputFormat.createPrettyPrint());
            writer.write(document);
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try {
                if (writer != null) {
                    writer.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * 合并AndroidManiFestXml文件
     */
    private void mergeManiFestXml() {
        Element workManifestElement = mWorkDocument.getRootElement();
        Element pluginManifestElement = mPluginDocument.getRootElement();
//      处理插件包名
        processPluginPackageName(pluginManifestElement);
        //        合并2个xml文件
        workManifestElement.appendContent(pluginManifestElement);
//        去除Manifest重复
        processElementToSole(workManifestElement, true);

    }

    /**
     * 将插件的packageName替换成work的packageName
     *
     * @param pluginElement Element
     */
    private void processPluginPackageName(Element pluginElement) {
        Utils.log("处理插件标签: " + pluginElement.getName());
        for (Element element : pluginElement.elements()) {
            if (element.hasContent()) {
                processPluginPackageName(element);
            } else {
                for (Attribute attribute : element.attributes()) {
                    String value = attribute.getValue();
                    if (value.contains(mPluginPackName)) {
                        Utils.log(String.format("插件标签%s,value:%s,将%s,替换成%s", element.getName(), value, mPluginPackName, mWorkPackName));
                        attribute.setValue(value.replace(mPluginPackName, mWorkPackName));
                    }
                }
            }

        }
    }

    /**
     * 处理合并后的元素，确保唯一值
     *
     * @param workElement      标签元素
     * @param isProcessContent 是否处理标签子元素的内容
     */
    private void processElementToSole(Element workElement, boolean isProcessContent) {
        Utils.log("处理标签: " + workElement.getName());
        List<String> nameList = new ArrayList<>();
        List<String> noneRootElementList = new ArrayList<>();
        for (Element element : workElement.elements()) {
            String name = element.getName();
            String value = element.attributeValue("name");
            if (!element.hasContent() || !isProcessContent) {

                if (value == null) {
                    StringBuilder builder = new StringBuilder();
                    for (Attribute attribute : element.attributes()) {
                        builder.append(attribute.getName()).append(attribute.getValue());
                    }
                    value = builder.toString();
                    Utils.log("注意: 因为name为null，所以判断attribute ==>" + element.getName());
                }

                value = name + ":" + value;

                if (nameList.contains(value)) {
                    Utils.log("去除重复元素： " + value);
                    workElement.remove(element);
                } else {
                    nameList.add(value);
                }
            } else if (!noneRootElementList.contains(name)) {
                noneRootElementList.add(name);
            }
        }

        if (isProcessContent) {
            //        处理需要合并的标签
            for (String noneName : noneRootElementList) {
                Utils.log(String.format("合并%s,使其唯一", noneName));
                mergeElementFromName(workElement, noneName);
                processElementToSole(workElement.element(noneName), false);
            }
        }

    }


    /**
     * 将Element下的 name 合并成一个
     *
     * @param manifestElement Element
     */
    private void mergeElementFromName(Element manifestElement, String name) {
        Element tempElement = null;
        for (Element element : manifestElement.elements(name)) {
            if (tempElement != null && element != tempElement) {
                tempElement.appendContent(element);
                manifestElement.remove(element);
            }
            tempElement = element;
        }
    }
}
