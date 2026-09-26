# 淮南师范学院教务「空闲教室查询」适配说明（2026-09-26）

> 目标页面：正方 jwglxt V9 `cdjy/cdjy_cxKxcdlb.html?gnmkdm=N2155`
> 契约来源：**服务端直出的查询页 HTML** + `js/plugins/jqGrid4.6/jquery.jqgrid.settings.js`
> 与业务脚本 `js/comp/jwglxt/pkgl/cdjy/kxcdlb.js`。**不是猜测**。

## 1. 入口与只读边界

| 动作 | 请求 | 本应用是否实现 |
|---|---|---|
| 打开查询页 | `GET cdjy/cdjy_cxKxcdlb.html?gnmkdm=N2155` | ✅ |
| 查询 | `POST cdjy/cdjy_cxKxcdlb.html?doType=query` | ✅ |
| 导出 Excel | `cdjy/cdjy_cxDcKxcdlb.html` | ❌（实测返回错误页，见 §6） |
| 节次/楼栋下拉数据 | `cdjy/cdjy_cxXqjc.html` | ✅（切校区时按校区拉取，见 §5.1） |
| 二级场地类别 | `query/query_cxEjjcdlbList.html` | ❌（界面不提供该项） |
| **场地借用申请** | `cdjy_cxYycdlb.html`、`bcOrTjCd` | ❌ **刻意不做**（写操作） |

本功能全程只读。网页上的「场地借用」「提交申请」属于向学校提交写请求，本应用不代提交。

## 2. 分页参数名（最容易踩的坑）

`jquery.jqgrid.settings.js` 里 `prmNames` 被显式改过：

```js
prmNames : {
    rows  : "queryModel.showCount",
    page  : "queryModel.currentPage",
    order : "queryModel.sortOrder",
    sort  : "queryModel.sortName"
}
jsonReader : { root:"items", page:"currentPage", total:"totalPage", records:"totalResult" }
mtype      : 'POST'
```

⇒ **按 jqGrid 默认名 `page` / `rows` 发参数会被服务端忽略**，症状是"翻页没反应、
永远是同一批数据"，HTTP 200、无异常。必须用 `queryModel.*` 前缀。

`rowNum` 默认 15、`rowList` 上限 100；本应用取 100 以减少往返。

## 3. 查询表单字段

隐藏域（服务端直出，**含登录人身份，必须现取、不可写死/落盘**）：
`fwzt=cx`、`xnm`、`xqm`、`cdyysqkz`、`xqFlag`、`syr`(学号)、`syrxm`(姓名/学号)、
`yuyjsctbj`、`jylysfbt`、`cdjylx`。

业务字段（`kxcdlb.js` 的 `map`）：

| 字段 | 含义 | 取值 |
|---|---|---|
| `xnm` / `xqm` | 学年 / 学期码 | `2026` / `3`（第一学期）·`12`（第二学期）·`16`（第三学期） |
| `xqh_id` | 校区 | 必选（页面标红星） |
| `lh` | 楼号 | 空＝全部 |
| `cdlb_id` / `cdejlb_id` | 场地类别 / 二级类别 | 空＝全部 |
| `cdmc` / `cd_id` | 场地名称关键词 / 场地主键 | 空＝不限 |
| `qszws` / `jszws` | 座位数起 / 止 | 空＝不限 |
| `jyfs` | 借用时间方式 | **`0`** = 按周次+星期+节次 |
| `zcd` | 周次 | **位掩码** `Σ 2^(周次-1)` |
| `xqj` | 星期 | **逗号列表** `1,3,5` |
| `jcd` | 节次 | **位掩码** `Σ 2^(节次-1)` |
| `zd_fzdm` | 网格 remoteParams | `N211205-kxcdlb`（`kxcdlb.js` 固定值） |

⚠️ 三者形态**不同**：`zcd`/`jcd` 是位掩码，`xqj` 是列表。写混了服务端不会报错，
只会返回一个"看起来合理但完全不对"的结果集。

⚠️ 位掩码用 `Long` 而不是 `Int`：JS 的 `Math.pow` 走双精度，周次 > 31 时
`1 shl 31` 在 Kotlin `Int` 上会溢出成负数。

## 4. 响应

```json
{ "currentPage": 1, "totalResult": 54, "totalPage": 6, "showCount": 10,
  "items": [ { "cd_id": "…", "cdbh": "12102", "cdmc": "朝阳物理楼-102",
               "cdlbmc": "普通教室", "jxlmc": "物理楼", "lch": "1",
               "xqh_id": "1", "xqmc": "朝阳校区", "zws": "121", "kszws1": "55",
               "date": "…", "queryModel": {…}, "userModel": {…}, "row_id": "1" } ] }
```

**每行都混着 jqGrid 的会话字段**（`date`/`queryModel`/`userModel`/`pageTotal`/
`listnav`/`localeKey`/`rangeable`…），它们不是场地属性，解析层一个都不收。

`zws`（座位数）与 `kszws1`（考试座位数）服务端**数字与字符串两种都给过**，都要认。

## 5. 筛选选项从页面直出

`#dm_cx`(学年学期)、`#xqh_id`(校区)、`#lh`(楼号)、`#cdlb_id`(场地类别)、
`#cdejlb_id`(二级类别) 五个 `<select>` 与周次表头 `#selectTR_ZC` **都是服务端渲染的**，
一次 GET 就能拿到，不需要额外接口。

周次只收 `class="selectTH"` 的格子：`class="displaynone"` 的是服务端按学期进度
禁用的周次（`zczt == "0"`），收进来会变成一个点不动的格子。

### 5.1 楼号与节次必须按校区重取（`cdjy_cxXqjc.html`）

**页面直出的 `#lh` 只属于"页面默认校区"（朝阳校区）。** 换校区后继续用它，服务端
**不会报错**，只会按另一个楼号去查，返回空集或别的楼 —— 用户看到的是"这个校区没有
空教室"，属于静默错误。所以切校区必须重取。

契约来自服务端业务脚本 `js/comp/jwglxt/pkgl/cdjy/kxcdlb.js` 的 `hqjcList()`：

```js
$.getJSON(_path + "/cdjy/cdjy_cxXqjc.html",
          {"xqh_id": $("#xqh_id").val(), "xnm": $("#xnm").val(), "xqm": $("#xqm").val()},
          function (data) { /* jcList -> JCMC / RSDJCMC ; lhList -> JXLDM / JXLMC */ });
$("#xqh_id").change(function () { hqjcList(); });   // 换校区 = 重建 #lh 与 #selectTR_JC
```

| 项 | 说明 |
|---|---|
| 方法 | `GET`（jQuery `getJSON`） |
| 路径 | 与查询页**同目录**：`cdjy/cdjy_cxXqjc.html`（由 `emptyRoomPath` 推导，不写死） |
| 参数 | `xqh_id`（校区）、`xnm`、`xqm`、**`gnmkdm`（必填）** |
| `gnmkdm` 缺失 | `HTTP请求参数gnmkdm不能为空！` |
| 楼号 | `lhList[]`：值取 `JXLDM`、名取 `JXLMC` |
| 节次 | `jcList[]`：值取 `JCMC`、名取 `RSDJCMC`（缺则退回 `JCMC`） |

**归属判据**（纯函数，`EmptyRoomModels.kt`）：三份来源不是按"哪个更新"取舍，而是按
**哪份确定属于当前校区** —— ①已拉到且 `loadedCampusId == 当前校区`；②否则当前就是
页面默认校区时用页面直出的那份；③否则只给「全部」。第 ③ 条是刻意的：宁可少给选项，
也不给错的选项。

解析层对键名大小写、数字/字符串形态、`String[]` 形态都做容错，但**认不出就返回空**，
不编造（"读取失败"与"确实没有"在界面上是两种文案）。

## 6. 未实现 / 未验证的部分（如实记录）

1. ~~**`cdjy_cxXqjc.html`（节次表 / 楼栋表）未接入。**~~
   **已接入（2026-09-26 修复）**，契约见 §5.1。此前该条记录的判断有误：当时认为它
   「只影响节次的显示名」，实测读 `kxcdlb.js` 后发现它同时决定**楼号下拉的内容**，
   且页面直出的 `#lh` 只属默认校区 —— 不接入会导致换校区后楼号不刷新（用户实际
   报障的现象）。现已按校区拉取，并在校区变化时清空已选的楼号与节次。
2. **`cdjy_cxDcKxcdlb.html`（导出）不可用**：GET/POST 均返回 1677 字节的错误页
   （`<title>错误提示</title>`），未接入。
3. **「场地二级类别」未提供**：其选项由 `query/query_cxEjjcdlbList.html` 按一级
   类别动态拉取，未验证，界面不提供该项（一级类别已足够筛出可用的教室）。
4. **⚠️ 真实链路联调未完成。** 契约是从服务端 JS 源码读出来的，本地纯函数单测
   钉住了参数形态；但**用测试账号做端到端验证时登录失败**（连续两次 POST
   `login_slogin.html` 都只返回登录页本身，无错误提示），按既定的凭据红线
   （单账号最多 3 次、见风控信号立即停止）**已停止尝试**。因此以下两点仍待确认：
   - `queryModel.currentPage` 在本路由是否真的生效（源码如此，但未实测）；
   - `queryModel.showCount=100` 是否被接受（若被截断成 15，翻页逻辑仍正确，只是多几次往返）。
