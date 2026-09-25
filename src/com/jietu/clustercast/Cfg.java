package com.jietu.clustercast;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 记住投屏目标、跟随前台、投屏仪表档位、投屏整屏填充、
 * 导航区压制和「高德是我们禁的」状态（读取→保存）。
 * 由 v5.1.0 的 Cfg.java 裁剪移植：只留投屏相关的六项。
 */
public class Cfg {

    private final SharedPreferences sp;

    public Cfg(Context c) {
        sp = c.getSharedPreferences("cast", Context.MODE_PRIVATE);
    }

    public String pkg() { return sp.getString("pkg", null); }
    public String cls() { return sp.getString("cls", null); }
    public String label() { return sp.getString("label", null); }

    public void setTarget(String pkg, String cls, String label) {
        sp.edit().putString("pkg", pkg).putString("cls", cls)
                .putString("label", label).apply();
    }

    public boolean followTop() { return sp.getBoolean("follow_top", true); }
    public void setFollowTop(boolean on) { sp.edit().putBoolean("follow_top", on).apply(); }

    /** 投屏时把仪表切到哪一档：Vd.THEME_NAVI(3) 或 Vd.THEME_SIMPLE(4)。默认导航档。 */
    public int castTheme() { return sp.getInt("cast_theme", Vd.THEME_NAVI); }
    public void setCastTheme(int t) { sp.edit().putInt("cast_theme", t).apply(); }

    /** 投屏整屏填充：launchBounds 由我们写成仪表整屏，不让原车布局留边。默认开。 */
    public boolean castFill() { return sp.getBoolean("cast_fill", true); }
    public void setCastFill(boolean on) { sp.edit().putBoolean("cast_fill", on).apply(); }

    /**
     * 总线压制原车导航（CAR_LAN 721699/721702）：防止高德后台吐状态导致仪表渲染
     * 「地图信息准备中」盖住投屏。默认开。投屏原车高德时自动放行。
     */
    public boolean suppressNaviCluster() { return sp.getBoolean("suppress_navi", true); }
    public void setSuppressNaviCluster(boolean on) { sp.edit().putBoolean("suppress_navi", on).apply(); }

    /**
     * 「高德是我们禁的」必须落盘。
     * 否则投屏中途进程被杀，高德就永久停在禁用状态，没人负责恢复。
     */
    public boolean amapOffByUs() { return sp.getBoolean("amap_off_by_us", false); }
    public void setAmapOffByUs(boolean on) { sp.edit().putBoolean("amap_off_by_us", on).apply(); }
}
