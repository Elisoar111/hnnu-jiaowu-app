package com.hnnujw.course.model;

import org.json.JSONObject;

public class SchoolConfig {
    public String id;
    public String name;
    public String domain;
    public String protocol;
    public String description;

    // ── 备用入口 ────────────────────────────────────────────────
    // 同一所学校的另一个访问地址（域名解析不到 / 校外访问时切到它）。
    // 刻意做成「一个 SchoolConfig 里的备用 host」而不是第二个 SchoolConfig：
    // 设备绑定额度是按 (schoolId, 学号) 计的，拆成两个学校会让同一个学生白占两个名额。
    public String alternateDomain = "";
    public String alternateProtocol = "https";
    /** 是否切到备用入口。随学校配置一起持久化，下次启动保持选择。 */
    public boolean useAlternate = false;

    // 教务适配类型。本应用只支持正方 jwglxt（淮南师范学院）。
    public String academicSystem = "zf";
    public String detectionSource = "legacy";
    public String pageCharset = "UTF-8";
    public java.util.ArrayList<String> allowedAcademicHosts = new java.util.ArrayList<>();
    public int academicConfigVersion = 1;

    // gnmkdm 参数配置 (可自定义)
    public String gradeGnmkdm = "N305005";
    public String courseGnmkdm = "N253512";
    public String scheduleGnmkdm = "N253508";

    // 空闲场地查询（cdjy/cdjy_cxKxcdlb.html）。只读查询，不涉及场地借用申请。
    public String emptyRoomGnmkdm = "N2155";
    // 查询网格的 remoteParams.zd_fzdm —— 由页面里的 kxcdlb.js 固定下发，
    // 网页端每次查询都会带上它。缺失时服务端可能按"无字典"处理，故一并配置化。
    public String emptyRoomRemoteParam = "N211205-kxcdlb";

    // URL 路径模板 (可自定义) - 默认为正方教务系统标准路径
    public String basePath = "/jwglxt"; // 基础路径，如 /jwglxt 或 /jwxt
    public String studentInfoPath = "/xtgl/index_cxYhxxIndex.html";
    public String courseIndexPath = "/xsxk/zzxkyzb_cxZzxkYzbIndex.html";
    public String schedulePath = "/kbcx/xskbcx_cxXsKb.html";
    // 课表首页（用于读取可选学期）；部分学校的学期下拉只在这个入口页上
    public String scheduleIndexPath = "/kbcx/xskbcx_cxXsKb.html";
    public String gradesPath = "/cjcx/cjcx_cxDgXscj.html";
    public String emptyRoomPath = "/cdjy/cdjy_cxKxcdlb.html";
    public String overallGradesIndexPath = "/xsxy/xsxyqk_cxXsxyqkIndex.html";
    public String overallGradesDataPath = "/xsxy/xsxyqk_cxJxzxjhxfyqKcxx.html";

    // 登录相关路径
    public String captchaPath = "/kaptcha";
    public String publicKeyPath = "/xtgl/login_getPublicKey.html";
    public String loginPagePath = "/xtgl/login_slogin.html";

    // ── 第二课堂（共青团"成绩单"系统）──────────────────────────────────
    // 独立站点、独立会话，与教务不是同一套凭据。两项都为空表示该校未接入，
    // App 里不会出现"二课"入口。
    /** API 根地址，如 https://ekta.hnnu.edu.cn/api/app/client/v1 */
    public String secondClassroomBaseUrl = "";
    /** 该站在其平台内的学校编号（不是教务的学校代码），登录 /token 时提交。 */
    public String secondClassroomSchoolCode = "";

    public SchoolConfig(String id, String name, String domain, String protocol) {
        this.id = id;
        this.name = name;
        this.domain = domain;
        this.protocol = protocol;
    }

    public String getBaseUrl() {
        if (useAlternate && alternateDomain != null && !alternateDomain.trim().isEmpty()) {
            return alternateProtocol + "://" + alternateDomain.trim();
        }
        return protocol + "://" + domain;
    }

    /** 当前实际使用的主机（主站或备用入口），用于界面展示与白名单校验。 */
    public String getActiveHost() {
        return useAlternate && alternateDomain != null && !alternateDomain.trim().isEmpty()
            ? alternateDomain.trim()
            : domain;
    }

    /**
     * 当前实际使用的协议（主站或备用入口各自的协议），用于 URL 白名单校验。
     * 否则备用站是 HTTP 时会被「HTTPS 不能降级」检查误判为 untrusted。
     */
    public String getActiveProtocol() {
        return useAlternate && alternateDomain != null && !alternateDomain.trim().isEmpty()
            ? (alternateProtocol == null || alternateProtocol.isEmpty() ? "https" : alternateProtocol)
            : protocol;
    }

    public boolean hasAlternate() {
        return alternateDomain != null && !alternateDomain.trim().isEmpty();
    }

    public String getFullBasePath() {
        return getBaseUrl() + basePath;
    }

    // 生成学生信息验证URL
    public String getStudentInfoUrl() {
        return getFullBasePath() + studentInfoPath + "?xt=jw&localeKey=zh_CN&_="
                + System.currentTimeMillis() + "&gnmkdm=index";
    }

    // 生成选课参数页面URL
    public String getCourseSelectionParamsUrl() {
        return getFullBasePath() + courseIndexPath + "?gnmkdm=" + courseGnmkdm + "&layout=default&su=" + domain;
    }





    // 生成Referer头
    public String getCourseReferer() {
        return getFullBasePath() + courseIndexPath + "?gnmkdm=" + courseGnmkdm + "&layout=default&su=" + domain;
    }

    // 生成课表URL
    public String getScheduleUrl() {
        return getFullBasePath() + schedulePath + "?gnmkdm=" + scheduleGnmkdm + "&su=" + domain;
    }

    // 生成成绩查询URL
    public String getGradesUrl(String semester) {
        String xnm = "2024";
        String xqm = "1";

        if (semester != null && semester.contains("-")) {
            String[] parts = semester.split("-");
            if (parts.length >= 3) {
                xnm = parts[0];
                xqm = parts[2].equals("1") ? "3" : "12";
            }
        }

        return getFullBasePath() + gradesPath + "?gnmkdm=" + gradeGnmkdm
                + "&doType=query&xnm=" + xnm + "&xqm=" + xqm
                + "&queryModel.showCount=1500&queryModel.currentPage=1";
    }

    // 生成分项成绩详情URL (接口A - 兜底用)
    public String getGradeDetailUrl() {
        return getFullBasePath() + "/cjcx/cjjdcx_cxXsjdxmcjIndex.html?doType=query&gnmkdm=N305099";
    }

    // 从 semester 字符串提取 xnm/xqm
    public String[] parseSemester(String semester) {
        String xnm = "2024";
        String xqm = "3";
        if (semester != null && semester.contains("-")) {
            String[] parts = semester.split("-");
            if (parts.length >= 3) {
                xnm = parts[0];
                xqm = parts[2].equals("1") ? "3" : "12";
            }
        }
        return new String[]{xnm, xqm};
    }

    // 生成总体成绩查询URL
    public String getOverallGradesUrl() {
        return getFullBasePath() + overallGradesIndexPath + "?gnmkdm=N105515&layout=default";
    }

    // 生成总体成绩数据URL
    public String getOverallGradesDataUrl() {
        return getFullBasePath() + overallGradesDataPath + "?gnmkdm=N105515";
    }

    // 序列化为 JSON
    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("id", id);
            json.put("name", name);
            json.put("domain", domain);
            json.put("protocol", protocol);
            // 短标签（登录页站点切换用）。原先漏了持久化，恢复出来的配置会丢标签。
            json.put("description", description);
            json.put("alternateDomain", alternateDomain);
            json.put("alternateProtocol", alternateProtocol);
            json.put("useAlternate", useAlternate);
            json.put("academicSystem", academicSystem);
            json.put("detectionSource", detectionSource);
            json.put("pageCharset", pageCharset);
            org.json.JSONArray academicHosts = new org.json.JSONArray();
            for (String host : allowedAcademicHosts) academicHosts.put(host);
            json.put("allowedAcademicHosts", academicHosts);
            json.put("academicConfigVersion", academicConfigVersion);
            json.put("basePath", basePath);
            json.put("gradeGnmkdm", gradeGnmkdm);
            json.put("courseGnmkdm", courseGnmkdm);
            json.put("scheduleGnmkdm", scheduleGnmkdm);
            json.put("emptyRoomGnmkdm", emptyRoomGnmkdm);
            json.put("emptyRoomRemoteParam", emptyRoomRemoteParam);
            json.put("studentInfoPath", studentInfoPath);
            json.put("courseIndexPath", courseIndexPath);
            json.put("schedulePath", schedulePath);
            json.put("scheduleIndexPath", scheduleIndexPath);
            json.put("gradesPath", gradesPath);
            json.put("emptyRoomPath", emptyRoomPath);
            json.put("overallGradesIndexPath", overallGradesIndexPath);
            json.put("overallGradesDataPath", overallGradesDataPath);
            json.put("captchaPath", captchaPath);
            json.put("publicKeyPath", publicKeyPath);
            json.put("loginPagePath", loginPagePath);
            json.put("secondClassroomBaseUrl", secondClassroomBaseUrl);
            json.put("secondClassroomSchoolCode", secondClassroomSchoolCode);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return json;
    }

    // 从 JSON 反序列化
    public static SchoolConfig fromJson(JSONObject json) {
        try {
            SchoolConfig config = new SchoolConfig(
                    json.optString("id", ""),
                    json.optString("name", ""),
                    json.optString("domain", ""),
                    json.optString("protocol", "https"));
            config.description = json.optString("description", "");
            config.alternateDomain = json.optString("alternateDomain", "");
            config.alternateProtocol = json.optString("alternateProtocol", "https");
            config.useAlternate = json.optBoolean("useAlternate", false);
            config.academicSystem = json.optString("academicSystem", "zf");
            config.detectionSource = json.optString("detectionSource", "legacy");
            config.pageCharset = json.optString("pageCharset", "UTF-8");
            config.academicConfigVersion = json.optInt("academicConfigVersion", 1);
            org.json.JSONArray academicHosts = json.optJSONArray("allowedAcademicHosts");
            if (academicHosts != null) {
                for (int i = 0; i < academicHosts.length(); i++) {
                    String host = academicHosts.optString(i, "").trim();
                    if (!host.isEmpty() && !config.allowedAcademicHosts.contains(host)) {
                        config.allowedAcademicHosts.add(host);
                    }
                }
            }
            config.basePath = json.optString("basePath", "/jwglxt");
            config.gradeGnmkdm = json.optString("gradeGnmkdm", "N305005");
            config.courseGnmkdm = json.optString("courseGnmkdm", "N253512");
            config.scheduleGnmkdm = json.optString("scheduleGnmkdm", "N253508");
            config.emptyRoomGnmkdm = json.optString("emptyRoomGnmkdm", "N2155");
            config.emptyRoomRemoteParam = json.optString("emptyRoomRemoteParam", "N211205-kxcdlb");
            config.studentInfoPath = json.optString("studentInfoPath", "/xtgl/index_cxYhxxIndex.html");
            config.courseIndexPath = json.optString("courseIndexPath", "/xsxk/zzxkyzb_cxZzxkYzbIndex.html");
            config.schedulePath = json.optString("schedulePath", "/kbcx/xskbcx_cxXsKb.html");
            config.scheduleIndexPath = json.optString("scheduleIndexPath",
                    json.optString("schedulePath", "/kbcx/xskbcx_cxXsKb.html"));
            config.gradesPath = json.optString("gradesPath", "/cjcx/cjcx_cxDgXscj.html");
            config.emptyRoomPath = json.optString("emptyRoomPath", "/cdjy/cdjy_cxKxcdlb.html");
            config.overallGradesIndexPath = json.optString("overallGradesIndexPath", "/xsxy/xsxyqk_cxXsxyqkIndex.html");
            config.overallGradesDataPath = json.optString("overallGradesDataPath",
                    "/xsxy/xsxyqk_cxJxzxjhxfyqKcxx.html");
            config.captchaPath = json.optString("captchaPath", "/kaptcha");
            config.publicKeyPath = json.optString("publicKeyPath", "/xtgl/login_getPublicKey.html");
            config.loginPagePath = json.optString("loginPagePath", "/xtgl/login_slogin.html");
            // 旧配置里没有这两个键：默认空串＝不显示第二课堂入口，向后兼容。
            config.secondClassroomBaseUrl = json.optString("secondClassroomBaseUrl", "");
            config.secondClassroomSchoolCode = json.optString("secondClassroomSchoolCode", "");
            return config;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}
