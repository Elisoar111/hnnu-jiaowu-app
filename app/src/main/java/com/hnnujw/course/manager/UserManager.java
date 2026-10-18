package com.hnnujw.course.manager;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import com.hnnujw.course.model.SchoolConfig;
import com.hnnujw.course.model.Course;
import com.hnnujw.course.network.CourseApiClient;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class UserManager {
    private static final String TAG = "UserManager";
    private static UserManager instance;
    private SchoolConfig currentSchool;
    private String studentName;
    private String studentId;
    private boolean isLoggedIn = false;
    private boolean isDemoMode = false;
    private String savedCookie = "";
    // 内存缓存，避免每次续期都过一次 Keystore 解密；真正的落盘在 CredentialStore
    private String sessionPassword = "";
    private String currentAccountKey = "";
    private final SessionStateStore sessionState = new SessionStateStore();
    private String runtimeAccountStorageKey = "";
    private String runtimeCookie = "";
    private String runtimeSchoolAddress = "";
    private SchoolConfig runtimeSchool;
    private final Map<String, String> sessionPasswords = new HashMap<>();
    private List<Course> selectedCourses = new ArrayList<>();

    private static final String PREFS_NAME = "course_selector_prefs";
    private static final String KEY_COOKIE = "saved_cookie";
    private static final String KEY_LOGGED_IN = "is_logged_in";
    private static final String KEY_STUDENT_NAME = "student_name";
    private static final String KEY_STUDENT_ID = "student_id";
    private static final String KEY_CURRENT_SCHOOL_ID = "current_school_id";
    private static final String KEY_COOKIE_SAVE_TIME = "cookie_save_time";
    private static final String KEY_USERNAME = "saved_username";
    private static final String KEY_LOGIN_MODE = "login_mode";
    private static final String KEY_ACCOUNTS = "saved_accounts";
    private static final String KEY_CURRENT_ACCOUNT_KEY = "current_account_key";

    private Context appContext;

    // 内置学校列表（本应用只包含淮南师范学院）
    private final List<SchoolConfig> defaultSchools = new ArrayList<>();

    public static class AccountRecord {
        public String key = "";
        public String schoolId = "";
        public String schoolName = "";
        public String studentName = "";
        public String studentId = "";
        public String username = "";
        public String loginMode = "cookie";
        public String cookie = "";
        public long cookieSaveTime = 0L;

        public boolean isPasswordMode() {
            return "password".equals(loginMode);
        }

        public String getDisplayName() {
            if (studentName != null && !studentName.isEmpty()) return studentName;
            if (username != null && !username.isEmpty()) return username;
            if (studentId != null && !studentId.isEmpty()) return studentId;
            return "同学";
        }

        public String getAccountIdText() {
            if (studentId != null && !studentId.isEmpty()) return studentId;
            if (username != null && !username.isEmpty()) return username;
            return "未记录学号";
        }
    }

    private UserManager() {
        // 本应用只支持淮南师范学院，不再内置其它学校。
        // 淮南师范学院：正方 jwglxt V9，直登表单 + RSA 密码加密，无验证码、无 CAS。
        // 课表入口是「个人课表查询」N2151（不是正方默认的 N253508）；学期下拉只在首页
        // /kbcx/xskbcx_cxXskbcxIndex.html 上，数据仍由 /kbcx/xskbcx_cxXsKb.html 返回。
        SchoolConfig hnnu = new SchoolConfig("hnnu", "淮南师范学院", "jwgl.hnnu.edu.cn", "https");
        hnnu.academicSystem = "zf";
        hnnu.basePath = "/jwglxt";
        hnnu.scheduleGnmkdm = "N2151";
        hnnu.scheduleIndexPath = "/kbcx/xskbcx_cxXskbcxIndex.html";
        hnnu.allowedAcademicHosts.add("jwgl.hnnu.edu.cn");
        // 备用入口：域名解析不到或校外访问时用 IP 直连。同一套正方 jwglxt，
        // 仅主机与协议不同（IP 站只有 http，明文已在 res/xml/network_security_config.xml
        // 里按域名白名单精确放开，Manifest 的全局 usesCleartextTraffic 已移除）。
        hnnu.alternateDomain = "211.70.176.172";
        hnnu.alternateProtocol = "http";
        hnnu.allowedAcademicHosts.add("211.70.176.172");
        // 第二课堂：独立站点，学校编号 10381（= 该校学校代码，与教务无关）
        hnnu.secondClassroomBaseUrl = "https://ekta.hnnu.edu.cn/api/app/client/v1";
        hnnu.secondClassroomSchoolCode = "10381";
        defaultSchools.add(hnnu);
        // 重要修复：这里不要直接赋值 currentSchool，等待 init() 时从 SharedPreferences 加载
    }

    public static synchronized UserManager getInstance() {
        if (instance == null) {
            instance = new UserManager();
        }
        return instance;
    }

    // 初始化 Context（在 Application 或 Activity 中调用）
    public void init(Context context) {
        this.appContext = context.getApplicationContext();
        if (!isDemoMode) {
            loadLoginState(); // 演示会话只存活在当前进程，不允许持久化状态覆盖它
        }
    }

    public void setCurrentSchool(SchoolConfig school) {
        this.currentSchool = school;
        saveLoginState(); // 保存学校选择
    }

    public SchoolConfig getCurrentSchool() {
        return currentSchool;
    }

    // 获取所有学校（本应用只支持淮南师范学院）
    public List<SchoolConfig> getSupportedSchools() {
        return new ArrayList<>(defaultSchools);
    }

    // 根据ID查找学校
    public SchoolConfig getSchoolById(String schoolId) {
        for (SchoolConfig school : getSupportedSchools()) {
            if (school.id.equals(schoolId)) {
                return school;
            }
        }
        return null;
    }

    // 更新学校配置（本应用只有淮南师范学院，直接改内置配置即可）
    public void updateSchoolConfig(SchoolConfig updatedSchool) {
        for (int i = 0; i < defaultSchools.size(); i++) {
            if (defaultSchools.get(i).id.equals(updatedSchool.id)) {
                defaultSchools.set(i, updatedSchool);
                // 同时更新 currentSchool
                if (currentSchool != null && currentSchool.id.equals(updatedSchool.id)) {
                    currentSchool = updatedSchool;
                }
                return;
            }
        }
    }

    // ========== Cookie、登录状态与账号记录持久化 ==========

    // 保存登录状态到 SharedPreferences
    public void saveLoginState() {
        if (appContext == null || isDemoMode)
            return;

        try {
            SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = prefs.edit();

            editor.putBoolean(KEY_LOGGED_IN, isLoggedIn);
            editor.putString(KEY_STUDENT_NAME, studentName != null ? studentName : "");
            editor.putString(KEY_STUDENT_ID, studentId != null ? studentId : "");
            // 🔴 Cookie 不再明文落盘：它是等同于登录态的凭据（拿着 JSESSIONID 就能
            // 读学号、成绩、课表、余额，不需要密码）。改存进 CredentialStore 的
            // AES/GCM 加密文件，这里只保留写入时机的语义。
            //
            // 明文键必须**主动删掉**：老版本已经写过，不删就等于加密形同虚设
            // （攻击者照样能从旧行里读到）。
            CredentialStore.INSTANCE.saveSessionCookie(
                    appContext,
                    cookieStorageKey(),
                    savedCookie != null ? savedCookie : ""
            );
            editor.remove(KEY_COOKIE);
            editor.putLong(KEY_COOKIE_SAVE_TIME, System.currentTimeMillis());
            if (currentAccountKey != null && !currentAccountKey.isEmpty()) {
                editor.putString(KEY_CURRENT_ACCOUNT_KEY, currentAccountKey);
            }

            if (currentSchool != null) {
                editor.putString(KEY_CURRENT_SCHOOL_ID, currentSchool.id);
            }

            editor.apply();

            if (isLoggedIn && currentSchool != null && savedCookie != null && !savedCookie.isEmpty()) {
                upsertCurrentAccountRecord();
            }

            Log.d(TAG, "登录状态已保存: isLoggedIn=" + isLoggedIn + ", student=" + studentName);
        } catch (Exception e) {
            Log.e(TAG, "保存登录状态失败: " + e.getMessage());
        }
    }

    // 从 SharedPreferences 加载登录状态
    public void loadLoginState() {
        if (appContext == null)
            return;

        try {
            SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

            isLoggedIn = prefs.getBoolean(KEY_LOGGED_IN, false);
            studentName = prefs.getString(KEY_STUDENT_NAME, "");
            studentId = prefs.getString(KEY_STUDENT_ID, "");

            String schoolId = prefs.getString(KEY_CURRENT_SCHOOL_ID, "");
            Log.d(TAG, "正在恢复学校, 保存的 schoolId=" + schoolId);

            if (!schoolId.isEmpty()) {
                SchoolConfig school = getSchoolById(schoolId);
                if (school != null) {
                    currentSchool = school;
                    Log.d(TAG, "学校恢复成功: " + currentSchool.name);
                } else {
                    Log.w(TAG, "找不到保存的学校 ID: " + schoolId + ", 可用学校: " + listSchoolIds());
                    // 保持默认学校不变
                }
            } else {
                Log.d(TAG, "没有保存的学校 ID，使用默认学校");
            }

            migrateLegacyAccountIfNeeded(prefs);

            currentAccountKey = prefs.getString(KEY_CURRENT_ACCOUNT_KEY, "");
            if (!currentAccountKey.isEmpty()) {
                AccountRecord record = findAccountRecord(currentAccountKey);
                if (record != null) {
                    // applyAccountRecord 内部会把 Cookie 从加密存储读回来（见那里的说明）
                    applyAccountRecord(record, false);
                }
            }

            // 🔴 Cookie 迁移必须在**账号 key 确定之后**做：加密存储按账号分键，
            // 早一步迁移会把 Cookie 写到错误的键下，等于本次启动读不到、下次也读不到。
            // 老版本的明文 `saved_cookie` 在这里搬一次家并删掉明文。
            String legacyCookie = prefs.getString(KEY_COOKIE, "");
            if (legacyCookie != null && !legacyCookie.isEmpty()) {
                CredentialStore.INSTANCE.saveSessionCookie(appContext, cookieStorageKey(), legacyCookie);
                if (savedCookie == null || savedCookie.isEmpty()) savedCookie = legacyCookie;
                prefs.edit().remove(KEY_COOKIE).apply();
                Log.d(TAG, "已把明文 Cookie 迁移到加密存储");
            } else if (savedCookie.isEmpty()) {
                // 没有明文残留：从加密存储读（切号/重启后的正常路径）
                String stored = CredentialStore.INSTANCE.loadSessionCookie(appContext, cookieStorageKey());
                if (stored != null && !stored.isEmpty()) savedCookie = stored;
            }

            Log.d(TAG, "登录状态已加载: isLoggedIn=" + isLoggedIn + ", student=" + studentName + ", school="
                    + (currentSchool != null ? currentSchool.name : "null"));
        } catch (Exception e) {
            Log.e(TAG, "加载登录状态失败: " + e.getMessage());
        }
    }

    // 辅助方法：列出所有可用学校 ID（用于调试）
    private String listSchoolIds() {
        StringBuilder sb = new StringBuilder();
        for (SchoolConfig s : getSupportedSchools()) {
            if (sb.length() > 0)
                sb.append(", ");
            sb.append(s.id);
        }
        return sb.toString();
    }

    // 保存 Cookie。默认只更新当前会话 Cookie，不改变登录模式。
    public com.hnnujw.course.manager.SessionToken saveCookieIfCurrent(
            com.hnnujw.course.manager.SessionToken expected, String cookie) {
        synchronized (sessionState) {
            if (!sessionState.isCurrent(expected) || cookie == null || cookie.isEmpty()) return null;
            saveCookie(cookie);
            com.hnnujw.course.manager.SessionToken installed = sessionState.getToken();
            return installed.equals(expected) ? null : installed;
        }
    }

    public void saveCookie(String cookie) {
        this.savedCookie = cookie != null ? cookie : "";
        this.isLoggedIn = !this.savedCookie.isEmpty();
        saveLoginState();
        installSession(true);
    }

    public void saveCookieLogin(String cookie) {
        this.savedCookie = cookie != null ? cookie : "";
        this.isLoggedIn = !this.savedCookie.isEmpty();
        this.sessionPassword = "";
        currentAccountKey = buildAccountKey(currentSchool, "", studentId, studentName);
        if (appContext != null) {
            SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit()
                    .putString(KEY_LOGIN_MODE, "cookie")
                    .remove(KEY_USERNAME)
                    .apply();
        }
        saveLoginState();
        installSession(true);
    }

    public void savePasswordLogin(String username, String cookie, String password) {
        this.savedCookie = cookie != null ? cookie : "";
        this.isLoggedIn = !this.savedCookie.isEmpty();
        this.sessionPassword = password != null ? password : "";
        String key = buildAccountKey(currentSchool, username, studentId, studentName);
        if (!key.isEmpty()) {
            currentAccountKey = key;
            if (!this.sessionPassword.isEmpty()) {
                sessionPasswords.put(key, this.sessionPassword);
                // 落盘（Keystore 加密）：冷启动后仍能静默续期，这是"账号永久保存"的落点
                if (appContext != null) {
                    CredentialStore.INSTANCE.save(appContext, key, this.sessionPassword);
                }
            }
        }
        if (appContext != null) {
            SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit()
                    .putString(KEY_USERNAME, username != null ? username : "")
                    .putString(KEY_LOGIN_MODE, "password")
                    .apply();
        }
        saveLoginState();
        installSession(true);
    }

    /**
     * 当前账号的密码。内存缓存 miss 就回 {@link CredentialStore} 解密读取，
     * 所以杀进程重开之后依然拿得到 —— 会话失效时的自动续期靠的就是这一条。
     */
    public String getAccountPassword() {
        String key = currentAccountKey;
        if (key != null && !key.isEmpty()) {
            String cached = sessionPasswords.get(key);
            if (cached != null && !cached.isEmpty()) return cached;
            if (appContext != null) {
                String stored = CredentialStore.INSTANCE.load(appContext, key);
                if (stored != null && !stored.isEmpty()) {
                    sessionPasswords.put(key, stored);
                    sessionPassword = stored;
                    return stored;
                }
            }
        }
        return sessionPassword != null ? sessionPassword : "";
    }

    /** 指定账号是否有已保存的密码（账号管理界面用来决定要不要显示"删除密码"）。 */
    public boolean hasSavedPassword(String accountKey) {
        if (accountKey == null || accountKey.isEmpty()) return false;
        String cached = sessionPasswords.get(accountKey);
        if (cached != null && !cached.isEmpty()) return true;
        return appContext != null && CredentialStore.INSTANCE.has(appContext, accountKey);
    }

    /** 只删密码，账号记录与 Cookie 保留：之后仍能用这个账号，但不再自动续期。 */
    public void deletePassword(String accountKey) {
        if (accountKey == null || accountKey.isEmpty()) return;
        sessionPasswords.remove(accountKey);
        if (accountKey.equals(currentAccountKey)) {
            sessionPassword = "";
        }
        if (appContext != null) {
            CredentialStore.INSTANCE.remove(appContext, accountKey);
        }
        Log.d(TAG, "已删除账号密码: " + accountKey);
    }

    /**
     * 彻底删除一个账号：账号记录、已存密码、运行期 Cookie。
     *
     * @return 该账号的 storage key，方便调用方接着清它的本地缓存
     *         （{@code CourseCacheManager.clearAccountCache}）；账号不存在时返回空串。
     */
    public String deleteAccount(String accountKey) {
        if (accountKey == null || accountKey.isEmpty()) return "";
        String storageKey = toStorageKey(accountKey);

        if (appContext != null) {
            com.hnnujw.course.schedule.ScheduleReminderScheduler.get(appContext).clearAccount(storageKey);
            // 考试提醒的计划表按 account 字段区分账号，删号时清掉属于它的一份
            com.hnnujw.course.schedule.ExamReminderScheduler.clearAll(appContext, storageKey);
        }

        List<AccountRecord> records = loadAccountRecords();
        boolean removed = records.removeIf(record -> accountKey.equals(record.key));
        if (removed) {
            saveAccountRecords(records);
        }

        deletePassword(accountKey);
        // 加密存储里的 Cookie 也要删：只清 prefs 与内存的话，这张票还留在
        // secure_credentials 文件里，账号被"删除"了却仍可被取出使用。
        if (appContext != null) CredentialStore.INSTANCE.removeSessionCookie(appContext, storageKey);

        try {
            CourseApiClient.getInstance().clearCookies(storageKey);
        } catch (Exception e) {
            Log.w(TAG, "清理账号 Cookie 失败: " + e.getMessage());
        }

        Log.d(TAG, "已删除账号: " + accountKey + " (removed=" + removed + ")");
        return storageKey;
    }

    /** 是否可以通过密码模式自动刷新 Cookie */
    public boolean canAutoRelogin() {
        return "password".equals(getLoginMode())
                && !getUsername().isEmpty()
                && !getAccountPassword().isEmpty()
                && currentSchool != null;
    }

    public String getUsername() {
        if (appContext == null) return "";
        SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_USERNAME, "");
    }

    public String getLoginMode() {
        if (appContext == null) return "cookie";
        SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_LOGIN_MODE, "cookie");
    }

    // 获取已保存的 Cookie
    public String getSavedCookie() {
        return savedCookie != null ? savedCookie : "";
    }

    // 检查是否有保存的 Cookie
    public boolean hasSavedCookie() {
        return savedCookie != null && !savedCookie.isEmpty();
    }

    private void migrateLegacyAccountIfNeeded(SharedPreferences prefs) {
        try {
            if (currentSchool == null || savedCookie == null || savedCookie.isEmpty()) return;
            List<AccountRecord> accounts = loadAccountRecords();
            if (!accounts.isEmpty()) return;

            AccountRecord record = new AccountRecord();
            record.schoolId = currentSchool.id;
            record.schoolName = currentSchool.name;
            record.studentName = studentName != null ? studentName : "";
            record.studentId = studentId != null ? studentId : "";
            record.username = prefs.getString(KEY_USERNAME, "");
            record.loginMode = prefs.getString(KEY_LOGIN_MODE, "cookie");
            // Cookie 不放进 record：它已经在 CredentialStore 里加密存着了。
            // 放进内存对象再序列化出去，等于把加密又绕开了（见 accountToJson）。
            record.cookie = "";
            record.cookieSaveTime = prefs.getLong(KEY_COOKIE_SAVE_TIME, System.currentTimeMillis());
            record.key = buildAccountKey(currentSchool, record.username, record.studentId, record.studentName);
            if (record.key.isEmpty()) return;

            accounts.add(record);
            saveAccountRecords(accounts);
            currentAccountKey = record.key;
            prefs.edit().putString(KEY_CURRENT_ACCOUNT_KEY, currentAccountKey).apply();
            Log.d(TAG, "已迁移旧版单账号状态: " + record.studentName);
        } catch (Exception e) {
            Log.w(TAG, "迁移旧版账号状态失败: " + e.getMessage());
        }
    }

    /**
     * 构造账号键。规则本身在 [AccountKeys] 里（纯函数、可单测）。
     *
     * 这里只做转发：`UserManager` 是个 900 行的单例，规则留在它内部就只能靠反射测，
     * 而这两条规则恰恰是"切号看到上一个账号数据"这类 bug 的根源。
     */
    private String buildAccountKey(SchoolConfig school, String username, String studentId, String studentName) {
        if (school == null) return "";
        return AccountKeys.INSTANCE.build(school.id, username, studentId, studentName);
    }

    /** 取第一个非空白项。转发到 [AccountKeys]（纯函数，可单测）。 */
    private String firstNotBlank(String... values) {
        return AccountKeys.INSTANCE.firstNotBlank(values);
    }

    private JSONObject accountToJson(AccountRecord record) throws Exception {
        JSONObject obj = new JSONObject();
        obj.put("key", record.key != null ? record.key : "");
        obj.put("schoolId", record.schoolId != null ? record.schoolId : "");
        obj.put("schoolName", record.schoolName != null ? record.schoolName : "");
        obj.put("studentName", record.studentName != null ? record.studentName : "");
        obj.put("studentId", record.studentId != null ? record.studentId : "");
        obj.put("username", record.username != null ? record.username : "");
        obj.put("loginMode", record.loginMode != null ? record.loginMode : "cookie");
        // 🔴 账号列表里**不再写明文 Cookie**：这张 JSON 就躺在 course_selector_prefs 里，
        // 每个账号一份 Cookie，等于把全部账号的登录票明文摆在磁盘上。
        // Cookie 改由 CredentialStore 按账号加密存；这里只留一个空字段占位，
        // 老版本读回来的明文由 accountFromJson 负责迁移到加密存储并丢弃。
        obj.put("cookie", "");
        obj.put("cookieSaveTime", record.cookieSaveTime);
        return obj;
    }

    private AccountRecord accountFromJson(JSONObject obj) {
        AccountRecord record = new AccountRecord();
        record.key = obj.optString("key", "");
        record.schoolId = obj.optString("schoolId", "");
        record.schoolName = obj.optString("schoolName", "");
        record.studentName = obj.optString("studentName", "");
        record.studentId = obj.optString("studentId", "");
        record.username = obj.optString("username", "");
        record.loginMode = obj.optString("loginMode", "cookie");
        record.cookieSaveTime = obj.optLong("cookieSaveTime", 0L);
        if (record.key.isEmpty()) {
            SchoolConfig school = getSchoolById(record.schoolId);
            record.key = buildAccountKey(school, record.username, record.studentId, record.studentName);
        }
        // 老版本把 Cookie 明文存在这张 JSON 里：读到就搬进加密存储，**内存里也不留明文**。
        // 迁移后 record.cookie 留空 —— 需要 Cookie 的地方一律走 CredentialStore。
        String legacyPlain = obj.optString("cookie", "");
        String storageKey = toStorageKey(record.key);
        if (legacyPlain != null && !legacyPlain.isEmpty() && appContext != null && !storageKey.isEmpty()) {
            CredentialStore.INSTANCE.saveSessionCookie(appContext, storageKey, legacyPlain);
            Log.d(TAG, "已把账号列表里的明文 Cookie 迁移到加密存储: " + storageKey);
        }
        record.cookie = "";
        return record;
    }

    /**
     * 账号列表的解析缓存。
     *
     * 为什么需要：[loadAccountRecords] 在切号路径上被反复调用（列表、查找、删除、
     * 迁移各一次），每次都要读 prefs + `new JSONArray` + 逐条 `accountFromJson`。
     * 账号只有几个，但这段解析本身对调用频率很敏感。
     *
     * 缓存键用**原始 JSON 字符串**而不是"脏标记"：JSON 就是这份数据的唯一真相，
     * 字符串没变就一定解析出同样的结果（`accountFromJson` 里那次 Cookie 迁移是幂等的 ——
     * 明文搬走之后就没东西可搬了）。这样连"写入后忘了清缓存"这类 bug 都不可能发生。
     *
     * 只在**同一进程内**有效：`appContext` 的 prefs 变化由写入方经过
     * [saveAccountRecords]，所以进程内不会出现"别人改了而我不知道"。
     */
    private volatile String cachedAccountJson = null;
    private volatile List<AccountRecord> cachedAccountRecords = null;

    private List<AccountRecord> loadAccountRecords() {
        List<AccountRecord> records = new ArrayList<>();
        if (appContext == null) return records;
        try {
            SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String json = prefs.getString(KEY_ACCOUNTS, "[]");
            // 命中：同一个 JSON 文本，直接返回上次解析的结果。
            List<AccountRecord> cached = cachedAccountRecords;
            if (cached != null && json != null && json.equals(cachedAccountJson)) {
                // 返回副本：调用方会 removeIf / add，共享同一个 List 会互相污染。
                return new ArrayList<>(cached);
            }
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                AccountRecord record = accountFromJson(arr.getJSONObject(i));
                if (record.key != null && !record.key.isEmpty()) {
                    records.add(record);
                }
            }
            cachedAccountJson = json;
            cachedAccountRecords = new ArrayList<>(records);
        } catch (Exception e) {
            Log.e(TAG, "加载账号列表失败: " + e.getMessage());
        }
        return records;
    }

    private void saveAccountRecords(List<AccountRecord> records) {
        if (appContext == null) return;
        try {
            JSONArray arr = new JSONArray();
            for (AccountRecord record : records) {
                arr.put(accountToJson(record));
            }
            String json = arr.toString();
            SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit().putString(KEY_ACCOUNTS, json).apply();
            // 让缓存与实际写下去的内容对齐（而不是简单清空）：
            // 下一次读会命中，且命中的一定是这份新数据。
            cachedAccountJson = json;
            cachedAccountRecords = new ArrayList<>(records);
        } catch (Exception e) {
            // 写失败：把缓存作废，避免继续用一份与磁盘不符的旧值。
            cachedAccountJson = null;
            cachedAccountRecords = null;
            Log.e(TAG, "保存账号列表失败: " + e.getMessage());
        }
    }

    private AccountRecord buildCurrentAccountRecord() {
        if (currentSchool == null) return null;
        AccountRecord record = new AccountRecord();
        record.schoolId = currentSchool.id;
        record.schoolName = currentSchool.name;
        record.studentName = studentName != null ? studentName : "";
        record.studentId = studentId != null ? studentId : "";
        record.username = getUsername();
        record.loginMode = getLoginMode();
        // Cookie 不进来：这条 record 会被序列化进 prefs，带上 Cookie 就是明文落盘。
        // 真正需要它时按 key 去 CredentialStore 取（见 applyAccountRecord）。
        record.cookie = "";
        record.cookieSaveTime = System.currentTimeMillis();
        record.key = buildAccountKey(currentSchool, record.username, record.studentId, record.studentName);
        return record.key.isEmpty() ? null : record;
    }

    private void upsertCurrentAccountRecord() {
        AccountRecord record = buildCurrentAccountRecord();
        if (record == null) return;
        List<AccountRecord> records = loadAccountRecords();
        boolean replaced = false;
        for (int i = 0; i < records.size(); i++) {
            if (record.key.equals(records.get(i).key)) {
                records.set(i, record);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            records.add(record);
        }
        currentAccountKey = record.key;
        saveAccountRecords(records);
        if (appContext != null) {
            SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit().putString(KEY_CURRENT_ACCOUNT_KEY, currentAccountKey).apply();
        }
    }

    private AccountRecord findAccountRecord(String accountKey) {
        if (accountKey == null || accountKey.isEmpty()) return null;
        for (AccountRecord record : loadAccountRecords()) {
            if (accountKey.equals(record.key)) return record;
        }
        return null;
    }

    public List<AccountRecord> getSavedAccounts() {
        return new ArrayList<>(loadAccountRecords());
    }

    public List<AccountRecord> getAccountsForCurrentSchool() {
        List<AccountRecord> result = new ArrayList<>();
        if (currentSchool == null) return result;
        for (AccountRecord record : loadAccountRecords()) {
            if (currentSchool.id.equals(record.schoolId)) {
                result.add(record);
            }
        }
        return result;
    }

    public String getCurrentAccountKey() {
        if (currentAccountKey == null || currentAccountKey.isEmpty()) {
            currentAccountKey = buildAccountKey(currentSchool, getUsername(), studentId, studentName);
        }
        return currentAccountKey != null ? currentAccountKey : "";
    }

    public String getCurrentAccountStorageKey() {
        String key = getCurrentAccountKey();
        if (key.isEmpty() && currentSchool != null) {
            key = currentSchool.id + "::" + firstNotBlank(getUsername(), studentId, studentName, "default");
        }
        return toStorageKey(key);
    }

    /**
     * 加密存储 Cookie 用的键。
     *
     * 与 [getCurrentAccountStorageKey] 同一口径 —— 两处不一致的话，切号后会读到
     * **上一个账号**的 Cookie，那是最严重的一类串号。
     */
    private String cookieStorageKey() {
        return getCurrentAccountStorageKey();
    }

    /**
     * 账号 key → 运行期/缓存用的 storage key。
     *
     * 规则在 [AccountKeys.storageKey]。两处必须走同一条规则，否则清不干净 ——
     * 所以这里**不留第二份实现**，只转发。
     */
    private String toStorageKey(String accountKey) {
        return AccountKeys.INSTANCE.storageKey(accountKey);
    }

    public boolean switchToAccount(String accountKey) {
        AccountRecord record = findAccountRecord(accountKey);
        if (record == null) return false;
        return applyAccountRecord(record, true);
    }

    private boolean applyAccountRecord(AccountRecord record, boolean persist) {
        if (record == null) return false;
        SchoolConfig school = getSchoolById(record.schoolId);
        if (school == null) {
            Log.w(TAG, "切换账号失败，找不到学校: " + record.schoolId);
            return false;
        }

        currentSchool = school;
        studentName = record.studentName != null ? record.studentName : "";
        studentId = record.studentId != null ? record.studentId : "";
        currentAccountKey = record.key != null ? record.key : "";
        // Cookie 从加密存储读；record.cookie 只是内存里的载体，不再落盘明文。
        // 读不到（老库/密钥失效）就留空，由上层走「请重新登录」。
        String storedCookie = "";
        if (appContext != null && !currentAccountKey.isEmpty()) {
            String loaded = CredentialStore.INSTANCE.loadSessionCookie(appContext, cookieStorageKey());
            if (loaded != null && !loaded.isEmpty()) storedCookie = loaded;
        }
        if (storedCookie.isEmpty() && record.cookie != null) storedCookie = record.cookie;
        savedCookie = storedCookie;
        isLoggedIn = true;
        isDemoMode = false;
        // 密码从内存缓存回填，miss 就读 Keystore —— loadLoginState() 也走这里，
        // 所以这一行就是"杀进程重开后仍能自动续期"的关键。
        String cachedPassword = sessionPasswords.get(currentAccountKey);
        if (cachedPassword == null || cachedPassword.isEmpty()) {
            if (appContext != null && !currentAccountKey.isEmpty()) {
                cachedPassword = CredentialStore.INSTANCE.load(appContext, currentAccountKey);
                if (cachedPassword != null && !cachedPassword.isEmpty()) {
                    sessionPasswords.put(currentAccountKey, cachedPassword);
                }
            }
        }
        sessionPassword = cachedPassword != null ? cachedPassword : "";

        if (persist && appContext != null) {
            SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            // Cookie 只进加密存储，prefs 里不再有明文（见 saveLoginState 的说明）。
            CredentialStore.INSTANCE.saveSessionCookie(appContext, cookieStorageKey(), savedCookie);
            prefs.edit()
                    .putBoolean(KEY_LOGGED_IN, true)
                    .putString(KEY_STUDENT_NAME, studentName)
                    .putString(KEY_STUDENT_ID, studentId)
                    .remove(KEY_COOKIE)
                    .putString(KEY_CURRENT_SCHOOL_ID, school.id)
                    .putString(KEY_CURRENT_ACCOUNT_KEY, currentAccountKey)
                    .putString(KEY_USERNAME, record.username != null ? record.username : "")
                    .putString(KEY_LOGIN_MODE, record.loginMode != null ? record.loginMode : "cookie")
                    .putLong(KEY_COOKIE_SAVE_TIME, System.currentTimeMillis())
                    .apply();
        }

        installSession(persist);
        if (sessionState.getState().getValue().getExpired()) isLoggedIn = false;
        Log.d(TAG, "已切换账号: " + studentName + " @ " + school.name);
        return true;
    }

    public void refreshRuntimeForCurrentAccount() {
        installSession(false);
    }

    public SessionStateStore getSessionState() {
        return sessionState;
    }

    private void installSession(boolean renewed) {
        synchronized (sessionState) {
        String account = getCurrentAccountStorageKey();
        String address = currentSchool != null ? currentSchool.getFullBasePath() : "";
        boolean accountChanged = !account.equals(runtimeAccountStorageKey);
        boolean changed = accountChanged || !address.equals(runtimeSchoolAddress)
                || !savedCookie.equals(runtimeCookie);
        if (!renewed && !changed) return;
        try {
            CourseApiClient apiClient = CourseApiClient.getInstance();
            apiClient.clearDisplayParamsCache();
            if (runtimeSchool != null && (accountChanged || !address.equals(runtimeSchoolAddress))) {
                com.hnnujw.course.academic.AcademicGatewayFactory.INSTANCE.invalidate(runtimeSchool, runtimeAccountStorageKey);
                apiClient.clearCookies(runtimeAccountStorageKey);
            }
            if (currentSchool != null && savedCookie != null && !savedCookie.isEmpty()) {
                apiClient.setCookie(currentSchool.getBaseUrl(), savedCookie, account);
                if (com.hnnujw.course.academic.AcademicGatewayFactory.INSTANCE.supports(currentSchool)) {
                    com.hnnujw.course.academic.AcademicGatewayFactory.INSTANCE.importCookie(
                            currentSchool, account, savedCookie, true, firstNotBlank(getUsername(), studentId));
                }
            } else {
                apiClient.clearCookies(account);
            }
            runtimeAccountStorageKey = account;
            runtimeCookie = savedCookie;
            runtimeSchoolAddress = address;
            runtimeSchool = currentSchool;
            sessionState.replace(account);
        } catch (Exception e) {
            Log.w(TAG, "刷新账号运行态失败: " + e.getMessage());
        }
        }
    }

    /**
     * 清除登录状态（退出登录时调用）。
     *
     * **故意不删 {@link CredentialStore} 里的密码**：退出登录只是结束这次会话，
     * 账号本身是用户资产，重新登录时不该再让他手打一遍密码。真要清掉，
     * 走「设置 → 账号管理」里的删除动作（{@link #deletePassword} / {@link #deleteAccount}）。
     */
    public void clearLoginState() {
        boolean wasDemoMode = isDemoMode;
        String accountStorageKeyToClear = getCurrentAccountStorageKey();
        if (currentSchool != null) {
            com.hnnujw.course.academic.AcademicGatewayFactory.INSTANCE.invalidate(currentSchool, accountStorageKeyToClear);
        }
        runtimeAccountStorageKey = "";
        runtimeCookie = "";
        runtimeSchoolAddress = "";
        runtimeSchool = null;
        sessionState.replace("default");
        isLoggedIn = false;
        isDemoMode = false;
        studentName = "";
        studentId = "";
        savedCookie = "";
        sessionPassword = "";
        currentAccountKey = "";
        if (wasDemoMode) {
            currentSchool = null;
            selectedCourses = new ArrayList<>();
            Log.d(TAG, "演示登录状态已清除");
            return;
        }

        if (appContext != null) {
            SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit()
                    .remove(KEY_LOGGED_IN)
                    .remove(KEY_STUDENT_NAME)
                    .remove(KEY_STUDENT_ID)
                    .remove(KEY_COOKIE)
                    .remove(KEY_COOKIE_SAVE_TIME)
                    .remove(KEY_USERNAME)
                    .remove(KEY_LOGIN_MODE)
                    .remove(KEY_CURRENT_ACCOUNT_KEY)
                    .apply(); // 注意：这里不再 remove KEY_CURRENT_SCHOOL_ID，实现学校记忆
        }

        try {
            CourseApiClient.getInstance().clearCookies(accountStorageKeyToClear);
        } catch (Exception e) {
            Log.w(TAG, "清理账号运行期 Cookie 失败: " + e.getMessage());
        }

        // 退出登录后那张票必须一起删：否则它留在 secure_credentials 里，
        // 界面上已经是"未登录"，但磁盘上仍有一张可用的会话凭据。
        if (appContext != null && !accountStorageKeyToClear.isEmpty()) {
            CredentialStore.INSTANCE.removeSessionCookie(appContext, accountStorageKeyToClear);
        }

        Log.d(TAG, "登录状态已清除");
    }

    // 别名方法，方便 Kotlin 调用
    public void logout() {
        clearLoginState();
    }

    // ========== 原有方法 ==========

    public void setLoggedIn(boolean loggedIn) {
        this.isLoggedIn = loggedIn;
        // 登出方向也要落盘：会话过期（notifyCookieExpired）只改内存字段的话，
        // 用户杀进程重开后 prefs 里仍是「已登录」，启动会拿死 Cookie 直登、再撞一轮
        // 失效探测才稳定。demo 模式下 saveLoginState 自身是空操作，不会误写。
        saveLoginState();
    }

    public boolean isLoggedIn() {
        return isLoggedIn;
    }

    public void startDemoSession(SchoolConfig school) {
        currentSchool = school;
        studentName = "演示用户";
        studentId = "2024000001";
        savedCookie = "";
        sessionPassword = "";
        currentAccountKey = "demo::preview";
        selectedCourses = new ArrayList<>();
        isDemoMode = true;
        isLoggedIn = true;
        if (!sessionState.getToken().getAccountStorageKey().equals(getCurrentAccountStorageKey())) {
            sessionState.replace(getCurrentAccountStorageKey());
        }
    }

    public void setDemoMode(boolean demoMode) {
        this.isDemoMode = demoMode;
    }

    public boolean isDemoMode() {
        return isDemoMode;
    }

    public String getStudentName() {
        return studentName;
    }

    public void setStudentName(String studentName) {
        this.studentName = studentName;
        saveLoginState();
    }

    public String getStudentId() {
        return studentId;
    }

    public void setStudentId(String studentId) {
        this.studentId = studentId;
        saveLoginState();
    }
}
