package com.hnnujw.course.utils;

import android.util.Log;
import com.hnnujw.course.model.Course;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CourseParser {
    private static final String TAG = "CourseParser";

    // 从学生信息页面解析学生姓名
    public static String parseStudentName(String html) {
        try {
            Document doc = Jsoup.parse(html);

            // 方法1: 查找input[name="xm"]
            Element nameInput = doc.selectFirst("input[name=\"xm\"]");
            if (nameInput != null) {
                String value = nameInput.attr("value");
                if (value != null && !value.trim().isEmpty()) {
                    Log.d(TAG, "Found name from input[name=xm]: " + value);
                    return value.trim();
                }
            }

            // 方法2: 查找h4.media-heading (Python版本的方法)
            Element nameElement = doc.selectFirst("h4.media-heading");
            if (nameElement != null) {
                String text = nameElement.text().trim();
                if (!text.isEmpty()) {
                    // 移除"学生"后缀
                    String name = text.replaceAll("\\s*学生\\s*$", "").trim();
                    Log.d(TAG, "Found name from h4.media-heading: " + name);
                    return name;
                }
            }

            // 方法3: 查找其他可能的姓名元素
            String[] selectors = {
                    "span[name=\"xm\"]",
                    "div[name=\"xm\"]",
                    ".user-name",
                    ".student-name",
                    "#xhxm"
            };

            for (String selector : selectors) {
                Element el = doc.selectFirst(selector);
                if (el != null) {
                    String text = el.text().trim();
                    if (!text.isEmpty()) {
                        // 清理可能的后缀
                        String name = text.replaceAll("\\s*同学\\s*$", "")
                                .replaceAll("\\s*学生\\s*$", "")
                                .trim();
                        Log.d(TAG, "Found name from " + selector + ": " + name);
                        return name;
                    }
                }
            }

            Log.w(TAG, "Could not parse student name from HTML");
            return null;

        } catch (Exception e) {
            Log.e(TAG, "Error parsing student name: " + e.getMessage());
            return null;
        }
    }

    // 从学生信息页面解析学号
    public static String parseStudentId(String html) {
        try {
            Document doc = Jsoup.parse(html);

            // 方法1: 查找input[name="xh"]
            Element idInput = doc.selectFirst("input[name=\"xh\"]");
            if (idInput != null) {
                String value = idInput.attr("value");
                if (value != null && !value.trim().isEmpty()) {
                    Log.d(TAG, "Found ID from input[name=xh]: " + value);
                    return value.trim();
                }
            }

            // 方法2: 查找特定的学号容器
            String[] selectors = {
                    "span[name=\"xh\"]",
                    "div[name=\"xh\"]",
                    ".student-id",
                    "#xh",
                    ".user-id"
            };

            for (String selector : selectors) {
                Element el = doc.selectFirst(selector);
                if (el != null) {
                    String text = el.text().trim();
                    if (!text.isEmpty()) {
                        Log.d(TAG, "Found ID from " + selector + ": " + text);
                        return text;
                    }
                }
            }

            return null;
        } catch (Exception e) {
            Log.e(TAG, "Error parsing student ID: " + e.getMessage());
            return null;
        }
    }


















}
