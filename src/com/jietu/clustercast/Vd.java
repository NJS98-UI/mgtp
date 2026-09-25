package com.jietu.clustercast;

import android.content.Context;
import android.os.Bundle;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;

import dalvik.system.DexClassLoader;

/**
 * Desay 私有 VDBus 总线的反射封装（协议是实机 dexdump 抓出来的）。
 *   仪表主题：service=CAR_INFO, eventId=327681, Bundle{"CMD_ID":232,"VALUE":int[]}
 *   仪表导航投屏：service=CAR_LAN, eventId=721699, Bundle{"data":VDNaviDisplayCluster}
 *   仪表导航区域：service=CAR_LAN, eventId=721702, Bundle{"data":VDNaviDisplayArea}
 * vdbus.jar 不在应用 classpath 上，运行时用 DexClassLoader 载入，全程反射；
 * 任何一步失败都返回真实异常描述写进界面日志，绝不假装成功。
 * 由 v5.1.0 的 Vd.java 裁剪移植：只留仪表主题 + CAR_LAN 导航四事件
 * （删掉媒体卡片、总线抓包、watchHud、360 环视）。
 */
public class Vd {

    private ClassLoader cl = null;
    private Object bus = null;
    private Class<?> evCls = null;
    private Class<?> clusterCls = null;
    private Class<?> areaCls = null;
    private Class<?> roadCls = null;
    private Class<?> digitalCls = null;
    private Constructor<?> evCtor = null;
    private Constructor<?> evCtor1 = null;
    private Method mGetOnce = null;
    private Method mSet = null;
    private Method mGetPayload = null;
    private Method mCreateEventCluster = null;
    private Method mCreateEventArea = null;
    private Method mCreateEventRoad = null;
    private Method mCreateEventDigital = null;
    private Object carInfoType = null;
    private Object carLanType = null;
    private Object naviServiceType = null;

    /** 第二条写入路径：SystemUI 用的 CarInfoProxy。 */
    private Object proxy = null;
    private Method mProxySend = null;
    private Method mProxyGet = null;

    /** 上一次 setTheme 实际走了哪条路：1=VDBus 2=Proxy，用于日志定位。 */
    public int lastPath = 0;

    /** 上一次连接/推送失败的真实原因，界面日志要用，绝不吞异常。 */
    public volatile String lastError = null;

    /** 三个服务各自的绑定结果，界面日志原样打印，不美化。 */
    public volatile String bindReport = "未连接";

    public boolean ok() { return bus != null; }

    // ---------- 常量 ----------

    public static final String TAG = "ClusterCast.Vd";
    public static final String JAR = "/system/framework/vdbus.jar";
    public static final String EXTRA_JAR = "/system/framework/vdbus_extra.jar";
    public static final String PROXY_CLS =
            "com.desaysv.ivi.extra.project.carinfo.proxy.CarInfoProxy";

    public static final int EV_CAR_SETTING = 327681;
    public static final int CMD_CLUSTER_THEME = 232;

    /** 仪表模式四档，取自原车 SystemUI「仪表模式」弹窗，协议值 = 选项下标 + 1。 */
    public static final int THEME_DIGITAL = 1;
    public static final int THEME_CLASSIC = 2;
    public static final int THEME_NAVI = 3;
    public static final int THEME_SIMPLE = 4;

    // VDEventCarLan（实机 dexdump 里逐个常量核对过）
    public static final int EV_NAVI_DISPLAY_TO_CLUSTER = 721699;
    public static final int EV_NAVI_ROAD_INFO = 721698;
    public static final int EV_NAVI_DIGITAL_INFO = 721701;
    public static final int EV_NAVI_DISPLAY_AREA = 721702;

    // VDValueCarLan$NaviDisplaySwitch / $NaviDisplayArea
    public static final int NAVI_SWITCH_CLOSE = 1;
    public static final int NAVI_SWITCH_OPEN = 2;
    public static final int NAVI_AREA_CLOSE = 0;
    public static final int NAVI_AREA_SECOND = 3;

    /** VDThreadType.CHILD_THREAD=1：回调走子线程，绝不占住主线程。 */
    private static final int THREAD_CHILD = 1;

    private static final String KEY_CMD = "CMD_ID";
    private static final String KEY_VALUE = "VALUE";

    private static volatile Vd sInst = null;

    public static Vd inst() { return sInst; }

    public static String themeName(int t) {
        switch (t) {
            case THEME_DIGITAL: return "数字模式";
            case THEME_CLASSIC: return "经典模式";
            case THEME_NAVI:    return "导航模式";
            case THEME_SIMPLE:  return "极简模式";
            default: return t < 0 ? "未知" : "编号" + t;
        }
    }

    /** 幂等；在子线程调用（bindService 与 getOnce 都是同步 binder）。 */
    public static synchronized Vd connect(Context c) {
        Vd v = sInst;
        if (v == null) { v = new Vd(); sInst = v; }
        if (v.bus == null) v.open(c.getApplicationContext());
        return v;
    }

    private static Object enumByName(Class<?> enumCls, String name) {
        Object[] constants = enumCls.getEnumConstants();
        if (constants == null) return null;
        for (Object o : constants) {
            if (((Enum<?>) o).name().equals(name)) return o;
        }
        return null;
    }

    private static int first(Bundle b) {
        if (b == null) return -1;
        int[] arr = b.getIntArray(KEY_VALUE);
        if (arr == null || arr.length == 0) return -1;
        return arr[0];
    }

    private static boolean asBool(Object v) {
        return Boolean.TRUE.equals(v);
    }

    /** 只走 public setter：字段全是 private，getField 会静默失败。 */
    private static void call(Class<?> cls, Object target, String name,
                             Class<?> pt, Object arg) {
        if (arg == null) return;
        try {
            cls.getMethod(name, pt).invoke(target, arg);
        } catch (Throwable t) {
            Log.w(TAG, name + " rejected: " + t);
        }
    }

    // ---------- 构造 / 连接 ----------

    private Vd() {}

    private void open(Context c) {
        try {
            ClassLoader loader = resolveLoader(c);
            cl = loader;
            Class<?> busCls = loader.loadClass("com.desaysv.ivi.vdb.client.VDBus");
            evCls = loader.loadClass("com.desaysv.ivi.vdb.event.VDEvent");
            clusterCls = loader.loadClass(
                    "com.desaysv.ivi.vdb.event.id.carlan.bean.VDNaviDisplayCluster");
            areaCls = loader.loadClass(
                    "com.desaysv.ivi.vdb.event.id.carlan.bean.VDNaviDisplayArea");
            roadCls = loader.loadClass(
                    "com.desaysv.ivi.vdb.event.id.carlan.bean.VDNaviRoadInfo");
            digitalCls = loader.loadClass(
                    "com.desaysv.ivi.vdb.event.id.carlan.bean.VDNaviDigitalInfo");
            Class<?> stCls = loader.loadClass(
                    "com.desaysv.ivi.vdb.client.bind.VDServiceDef$ServiceType");

            carInfoType = enumByName(stCls, "CAR_INFO");
            carLanType = enumByName(stCls, "CAR_LAN");
            naviServiceType = enumByName(stCls, "NAVI");

            Object b = busCls.getMethod("getDefault").invoke(null);
            busCls.getMethod("init", Context.class).invoke(b, c);
            bus = b;

            mGetOnce = busCls.getMethod("getOnce", evCls);
            mSet = busCls.getMethod("set", evCls);
            mGetPayload = evCls.getMethod("getPayload");
            evCtor = evCls.getConstructor(int.class, Bundle.class);
            evCtor1 = evCls.getConstructor(int.class);
            mCreateEventCluster = clusterCls.getMethod("createEvent", int.class, clusterCls);
            mCreateEventArea = areaCls.getMethod("createEvent", int.class, areaCls);
            mCreateEventRoad = roadCls.getMethod("createEvent", int.class, roadCls);
            mCreateEventDigital = digitalCls.getMethod("createEvent", int.class, digitalCls);

            Method bind = busCls.getMethod("bindService", stCls);
            boolean a = asBool(bind.invoke(b, carInfoType));
            boolean l = asBool(bind.invoke(b, carLanType));
            boolean n = asBool(bind.invoke(b, naviServiceType));
            bindReport = "CAR_INFO=" + a + " CAR_LAN=" + l + " NAVI=" + n;
            Log.i(TAG, "connect " + bindReport);
            openProxy(c);
        } catch (Throwable t) {
            bus = null;
            lastError = t.getClass().getSimpleName() + ": " + t.getMessage();
            bindReport = "失败：" + lastError;
            Log.w(TAG, "connect failed: " + t);
        }
    }

    /**
     * 载入 SystemUI 那条路径上的 CarInfoProxy。它和 VDBus 必须在同一个 ClassLoader 里
     * （proxy 内部是静态 VDBus.getDefault().set()），所以两个 jar 一起丢给一个 DexClassLoader。
     * 失败不影响主流程，只是少一条写入通道。
     */
    private void openProxy(Context c) {
        try {
            Class<?> pCls = cl.loadClass(PROXY_CLS);
            Object p = pCls.getMethod("getInstance").invoke(null);
            pCls.getMethod("init", Context.class).invoke(p, c);
            mProxySend = pCls.getMethod("sendItemValues",
                    int.class, int.class, int[].class);
            mProxyGet = pCls.getMethod("getItemValues",
                    int.class, int.class);
            proxy = p;
            Log.i(TAG, "proxy ready connected=" +
                    pCls.getMethod("isServiceConnnected").invoke(p));
        } catch (Throwable t) {
            proxy = null;
            mProxySend = null;
            mProxyGet = null;
            Log.w(TAG, "proxy unavailable: " + t);
        }
    }

    private ClassLoader resolveLoader(Context c) {
        try {
            Class.forName("com.desaysv.ivi.vdb.client.VDBus");
            Class.forName(PROXY_CLS);
            return Vd.class.getClassLoader();
        } catch (ClassNotFoundException ignored) { }
        return new DexClassLoader(JAR + ":" + EXTRA_JAR,
                c.getCodeCacheDir().getAbsolutePath(), null, Vd.class.getClassLoader());
    }

    // ---------- 仪表主题 ----------

    /** 读当前仪表主题；拿不到返回 -1。 */
    public int getTheme() {
        Object b = bus;
        if (b == null) return -1;
        try {
            Bundle bundle = new Bundle();
            bundle.putInt(KEY_CMD, CMD_CLUSTER_THEME);
            Object r = mGetOnce.invoke(b, evCtor.newInstance(EV_CAR_SETTING, bundle));
            if (r == null) return -1;
            return first(readPayload(r));
        } catch (Throwable t) {
            Log.w(TAG, "getTheme failed: " + t);
            return -1;
        }
    }

    /** 写仪表模式（见 THEME_*）。总线这层是 fire-and-forget，是否真生效只能回读。
     * 两条路径都发一遍：A 自己拼 VDEvent，B 走原车注册好的 CarInfoProxy 连接。 */
    public void setTheme(int theme) {
        int[] v = new int[]{theme};
        Object b = bus;
        if (b != null) {
            try {
                Bundle bundle = new Bundle();
                bundle.putInt(KEY_CMD, CMD_CLUSTER_THEME);
                bundle.putIntArray(KEY_VALUE, v);
                mSet.invoke(b, evCtor.newInstance(EV_CAR_SETTING, bundle));
                lastPath = lastPath | 1;
            } catch (Throwable t) {
                Log.w(TAG, "setTheme via VDBus failed: " + t);
            }
        }
        Object p = proxy;
        if (p != null) {
            try {
                mProxySend.invoke(p, EV_CAR_SETTING, CMD_CLUSTER_THEME, v);
                lastPath = lastPath | 2;
            } catch (Throwable t) {
                Log.w(TAG, "setTheme via CarInfoProxy failed: " + t);
            }
        }
    }

    /** 读仪表模式，优先走 proxy（和 SystemUI 一致），退回 VDBus 由调用方做。 */
    public int getThemeViaProxy() {
        Object p = proxy;
        if (p == null) return -1;
        try {
            int[] v = (int[]) mProxyGet.invoke(p, EV_CAR_SETTING, CMD_CLUSTER_THEME);
            if (v == null || v.length == 0) return -1;
            return v[0];
        } catch (Throwable t) {
            Log.w(TAG, "getThemeViaProxy failed: " + t);
            return -1;
        }
    }

    private Bundle readPayload(Object event) {
        Bundle b;
        try {
            b = (Bundle) mGetPayload.invoke(event);
        } catch (Throwable t) {
            return null;
        }
        if (b != null) b.setClassLoader(cl);
        return b;
    }

    // ---------- 仪表导航投屏（原车通道，和原车一样不搬窗口） ----------

    /**
     * 通知原车仪表渲染器：导航要不要显示在仪表上。
     * enable=true 时请求「第二主题」整屏区域，false 时关通道，仪表回到原显示模式。
     * 返回 null=总线已发出（是否真落到仪表还得看实机），否则是真实异常描述。
     */
    public String publishNaviCluster(boolean enable) {
        Object b = bus;
        if (b == null) return "总线没连上";
        try {
            Object bean = clusterCls.newInstance();
            // 用原车自己那份字符串格式，能读到就照抄，读不到才用数字串兜底。
            Object cur = readBean(EV_NAVI_DISPLAY_TO_CLUSTER, clusterCls);
            String[] fmt = stringShape(cur);
            setStr(clusterCls, bean, "DisplayCluster",
                    enable ? NAVI_SWITCH_OPEN : NAVI_SWITCH_CLOSE, fmt);
            setStr(clusterCls, bean, "NaviFrontDeskStatus",
                    enable ? NAVI_SWITCH_OPEN : NAVI_SWITCH_CLOSE, fmt);
            setStr(clusterCls, bean, "RequestDisplayNaviArea",
                    enable ? NAVI_AREA_SECOND : NAVI_AREA_CLOSE, fmt);
            if (cur != null) copyUnset(bean, cur, "Perspective", "PerspectiveResult");
            mSet.invoke(b, mCreateEventCluster.invoke(null, EV_NAVI_DISPLAY_TO_CLUSTER, bean));
            return null;
        } catch (Throwable t) {
            Log.w(TAG, "publishNaviCluster failed: " + t);
            return t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    /** 仪表导航显示区域（721702）：原车用这个决定导航画在仪表的哪块、占多宽。 */
    public String publishNaviArea(boolean toCluster) {
        Object b = bus;
        if (b == null) return "总线没连上";
        try {
            Object bean = areaCls.newInstance();
            areaCls.getMethod("setNaviDisplayArea", int.class)
                    .invoke(bean, toCluster ? NAVI_AREA_SECOND : NAVI_AREA_CLOSE);
            mSet.invoke(b, mCreateEventArea.invoke(null, EV_NAVI_DISPLAY_AREA, bean));
            return null;
        } catch (Throwable t) {
            Log.w(TAG, "publishNaviArea failed: " + t);
            return t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    /**
     * 使用系统 VDS Adapter 的字符串协议打开/关闭仪表导航视图。
     * QNX 实际读取的是 {@code true/false}，不是旧代码里的数字 1/2。
     */
    public String publishNaviDisplayJson(boolean show) {
        Object b = bus;
        if (b == null) return "总线没连上";
        try {
            Object bean = clusterCls.newInstance();
            call(clusterCls, bean, "setDisplayCluster", String.class, show ? "true" : "false");
            call(clusterCls, bean, "setNaviFrontDeskStatus", String.class, show ? "true" : "false");
            call(clusterCls, bean, "setPerspective", int.class, 0);
            call(clusterCls, bean, "setPerspectiveResult", String.class, "false");
            call(clusterCls, bean, "setRequestDisplayNaviArea", String.class, "false");
            mSet.invoke(b, mCreateEventCluster.invoke(null, EV_NAVI_DISPLAY_TO_CLUSTER, bean));
            Log.i(TAG, "navi display json show=" + show);
            return null;
        } catch (Throwable t) {
            lastError = t.getClass().getSimpleName() + ": " + t.getMessage();
            Log.w(TAG, "publishNaviDisplayJson failed: " + t);
            return lastError;
        }
    }

    /** 发送普通导航显示区域，对应 {@code {"NaviDisplayArea":1,...}}。 */
    public String publishNaviAreaNormal() {
        Object b = bus;
        if (b == null) return "总线没连上";
        try {
            Object bean = areaCls.newInstance();
            call(areaCls, bean, "setNaviDisplayArea", int.class, 1);
            call(areaCls, bean, "setNaviDisplayAreaResult", String.class, "true");
            mSet.invoke(b, mCreateEventArea.invoke(null, EV_NAVI_DISPLAY_AREA, bean));
            Log.i(TAG, "navi area normal sent");
            return null;
        } catch (Throwable t) {
            lastError = t.getClass().getSimpleName() + ": " + t.getMessage();
            Log.w(TAG, "publishNaviAreaNormal failed: " + t);
            return lastError;
        }
    }

    /** 发送 721698 VDNaviRoadInfo；QNX 靠它绘制路线区域，而不是只认开关。 */
    public String publishNaviRoad(int segRemainDis, int roadType, int roadIcon,
                                  String roadName, String nextRoadName,
                                  int progressPercent, int intersectionZoom) {
        Object b = bus;
        if (b == null) return "总线没连上";
        try {
            Object bean = roadCls.newInstance();
            call(roadCls, bean, "setSegRemainDis", int.class, segRemainDis);
            call(roadCls, bean, "setRoadType", int.class, roadType);
            call(roadCls, bean, "setRoadIcon", int.class, roadIcon);
            call(roadCls, bean, "setRoadName", String.class, roadName);
            call(roadCls, bean, "setNextRoadName", String.class, nextRoadName);
            call(roadCls, bean, "setNextNaviActionProgbar", int.class, progressPercent);
            call(roadCls, bean, "setIntersectionZoomStatus", int.class, intersectionZoom);
            mSet.invoke(b, mCreateEventRoad.invoke(null, EV_NAVI_ROAD_INFO, bean));
            Log.i(TAG, "navi road 721698 sent name=" + roadName + " dis=" + segRemainDis);
            return null;
        } catch (Throwable t) {
            lastError = t.getClass().getSimpleName() + ": " + t.getMessage();
            Log.w(TAG, "publishNaviRoad failed: " + t);
            return lastError;
        }
    }

    /** 发送 721701 TBT/电子眼状态。 */
    public String publishNaviDigital(int cameraType, int naviStatus,
                                     int speedingInfo, int warningMessage) {
        Object b = bus;
        if (b == null) return "总线没连上";
        try {
            Object bean = digitalCls.newInstance();
            call(digitalCls, bean, "setCameraType", int.class, cameraType);
            call(digitalCls, bean, "setNaviStatus", int.class, naviStatus);
            call(digitalCls, bean, "setSpeedingInfo", int.class, speedingInfo);
            call(digitalCls, bean, "setWarningMessage", int.class, warningMessage);
            mSet.invoke(b, mCreateEventDigital.invoke(null, EV_NAVI_DIGITAL_INFO, bean));
            Log.i(TAG, "navi digital 721701 sent status=" + naviStatus);
            return null;
        } catch (Throwable t) {
            lastError = t.getClass().getSimpleName() + ": " + t.getMessage();
            Log.w(TAG, "publishNaviDigital failed: " + t);
            return lastError;
        }
    }

    /** 回读总线上当前那个 bean，用来照抄原车的字段格式，读不到返回 null。 */
    private Object readBean(int eventId, Class<?> cls) {
        Object b = bus;
        if (b == null) return null;
        try {
            Object ev = mGetOnce.invoke(b, evCtor1.newInstance(eventId));
            if (ev == null) return null;
            Bundle p = readPayload(ev);
            if (p == null) return null;
            Object hit = null;
            for (String k : p.keySet()) {
                Object v = p.get(k);
                if (cls.isInstance(v)) { hit = v; break; }
            }
            return hit;
        } catch (Throwable t) {
            Log.w(TAG, "readBean " + eventId + " failed: " + t);
            return null;
        }
    }

    /** 从原车 bean 里摸出字符串字段的写法（是 "2" 还是 "OPEN" 还是别的）。 */
    private String[] stringShape(Object bean) {
        ArrayList<String> out = new ArrayList<>();
        if (bean == null) return new String[0];
        try {
            for (java.lang.reflect.Field f : bean.getClass().getDeclaredFields()) {
                if (f.getType() != String.class) continue;
                f.setAccessible(true);
                Object val = f.get(bean);
                if (val instanceof String) {
                    String s = (String) val;
                    if (!out.contains(s)) out.add(s);
                }
            }
        } catch (Throwable ignored) { }
        return out.toArray(new String[0]);
    }

    /** 把没打算改的字段从原车那份抄过来，别把人家维护的状态写没。 */
    private void copyUnset(Object target, Object src, String... names) {
        if (src == null) return;
        for (String n : names) {
            try {
                java.lang.reflect.Field rf = src.getClass().getDeclaredField(n);
                rf.setAccessible(true);
                java.lang.reflect.Field wf = target.getClass().getDeclaredField(n);
                wf.setAccessible(true);
                wf.set(target, rf.get(src));
            } catch (Throwable ignored) { }
        }
    }

    /** 原车用什么写法就用什么写法：能匹配到已有格式（按数字/关键字）就照抄。 */
    private void setStr(Class<?> cls, Object bean, String field, int want, String[] fmt) {
        String v = matchFormat(want, fmt);
        if (v == null) v = String.valueOf(want);
        try {
            cls.getMethod("set" + field, String.class).invoke(bean, v);
        } catch (Throwable t) {
            Log.w(TAG, "set " + field + " rejected: " + t);
        }
    }

    private String matchFormat(int want, String[] fmt) {
        if (fmt.length == 0) return null;
        String ws = String.valueOf(want);
        for (String s : fmt) {
            if (s.equals(ws)) return s;
        }
        return null;
    }
}
