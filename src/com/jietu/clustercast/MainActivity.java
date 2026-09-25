package com.jietu.clustercast;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 主屏设置页。壁纸（assets/wallpaper.png）只是这个页面的背景；
 * 投到仪表的是「自选软件」：三指左滑投屏、右滑退出。
 *
 * 页面结构（纯代码构建，无布局 XML）：
 *   · 标题行 + 「选择应用」（进 AppPicker 选投屏目标）
 *   · 状态卡：投屏目标 / 投屏状态与仪表模式 / 仪表档位（导航·默认 / 极简）/
 *     跟随前台开关 / 立即投屏 / 退出投屏 / 壁纸加载诊断
 *   · 用法卡：三指手势与 A/B 切换语义
 *   · 日志卡：服务日志实时滚动
 */
public class MainActivity extends Activity implements CastService.LogSink {

    private Cfg cfg;
    private TextView tvTarget;
    private TextView tvCast;
    private TextView btnFollow;
    private TextView btnNavi;
    private TextView btnSimple;
    private View permRow;
    private View overlayRow;
    private TextView tvDiag;
    private TextView tvLog;
    private ScrollView svLog;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            refresh();
            ui.postDelayed(this, 1000);
        }
    };
    private boolean sinkBound = false;

    @Override protected void onCreate(Bundle b) {
        Ui.fit1050(this);
        super.onCreate(b);
        cfg = new Cfg(this);
        setContentView(buildUi());
        refresh();
    }

    @Override protected void onStart() {
        super.onStart();
        CastService.start(this);
    }

    @Override protected void onResume() {
        super.onResume();
        ui.removeCallbacks(tick);
        ui.post(tick);
    }

    @Override protected void onPause() {
        super.onPause();
        ui.removeCallbacks(tick);
        CastService s = CastService.inst();
        if (s != null) s.setSink(null);
        sinkBound = false;
    }

    // ---------- 构建 ----------

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(Ui.wallpaper(this));
        int pad = Ui.dp(this, 22);
        root.setPadding(pad, pad, pad, pad);

        // 标题行
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = Ui.text(this, 26, Ui.INK, Typeface.BOLD, 1);
        title.setText("仪表投屏");
        head.addView(title, Ui.weighted(1, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView btnPick = Ui.button(this, "选择应用", 15, false, 16, 9, Ui.R_BTN);
        Ui.click(btnPick, new Runnable() {
            @Override public void run() {
                startActivity(new Intent(MainActivity.this, AppPicker.class));
            }
        });
        head.addView(btnPick, Ui.ww());
        root.addView(head, Ui.lw());
        root.addView(vsp(12));

        // 状态卡
        LinearLayout card = Ui.card(this, 16);
        tvTarget = Ui.text(this, 15, Ui.INK, Typeface.NORMAL, 2);
        card.addView(tvTarget, Ui.lw());
        card.addView(vsp(4));
        tvCast = Ui.text(this, 14, Ui.INK_SUB, Typeface.NORMAL, 2);
        card.addView(tvCast, Ui.lw());
        tvDiag = Ui.text(this, 11, Ui.INK_FAINT, Typeface.NORMAL, 2);
        card.addView(tvDiag, Ui.lw());
        card.addView(vsp(10));
        card.addView(Ui.divider(this));
        card.addView(vsp(10));

        // 仪表档位行（v13）：默认导航模式 —— 投屏时仪表切导航主题，投的窗口盖住原车地图；
        // 极简模式可选（极简档下原车仪表层依旧在下面，没有导航主题配合容易被压住）。
        LinearLayout themeRow = new LinearLayout(this);
        themeRow.setOrientation(LinearLayout.HORIZONTAL);
        themeRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView tvTheme = Ui.text(this, 14, Ui.INK, Typeface.NORMAL, 1);
        tvTheme.setText("仪表档位");
        themeRow.addView(tvTheme, Ui.ww());
        themeRow.addView(hsp(8));
        btnNavi = Ui.button(this, "", 14, true, 14, 8, Ui.R_ONOFF);
        Ui.click(btnNavi, new Runnable() {
            @Override public void run() {
                cfg.setCastTheme(Vd.THEME_NAVI);
                restyleTheme();
                toast("导航模式：投屏时仪表切导航主题，投的软件盖住原车地图");
            }
        });
        themeRow.addView(btnNavi, Ui.ww());
        themeRow.addView(hsp(8));
        btnSimple = Ui.button(this, "", 14, true, 14, 8, Ui.R_ONOFF);
        Ui.click(btnSimple, new Runnable() {
            @Override public void run() {
                cfg.setCastTheme(Vd.THEME_SIMPLE);
                restyleTheme();
                toast("极简模式：投屏时仪表切极简主题（老行为）");
            }
        });
        themeRow.addView(btnSimple, Ui.ww());
        restyleTheme();
        card.addView(themeRow, Ui.lw());
        card.addView(vsp(10));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        btnFollow = Ui.button(this, "", 14, true, 14, 8, Ui.R_ONOFF);
        restyleFollow(cfg.followTop());
        Ui.click(btnFollow, new Runnable() {
            @Override public void run() {
                boolean on = !cfg.followTop();
                cfg.setFollowTop(on);
                restyleFollow(on);
                toast(on ? "已开启跟随前台：三指左滑投当前前台应用"
                        : "已关闭跟随前台：三指左滑投选定的应用");
            }
        });
        row.addView(btnFollow, Ui.ww());
        row.addView(hsp(8));
        TextView btnCast = Ui.button(this, "立即投屏", 14, false, 14, 8, Ui.R_GREEN);
        Ui.click(btnCast, new Runnable() {
            @Override public void run() {
                CastService s = CastService.inst();
                if (s == null) { toast("服务还在启动，两秒后再试"); return; }
                s.castNow();
            }
        });
        row.addView(btnCast, Ui.ww());
        row.addView(hsp(8));
        TextView btnExit = Ui.button(this, "退出投屏", 14, false, 14, 8, Ui.R_DANGER);
        Ui.click(btnExit, new Runnable() {
            @Override public void run() {
                CastService s = CastService.inst();
                if (s == null) { toast("服务还没起来"); return; }
                s.exitNow();
            }
        });
        row.addView(btnExit, Ui.ww());
        card.addView(row, Ui.lw());

        // 使用情况访问权限：这台 ROM 没有授权页，未授权就提示 adb 授权一次
        permRow = new LinearLayout(this);
        ((LinearLayout) permRow).setOrientation(LinearLayout.HORIZONTAL);
        ((LinearLayout) permRow).setGravity(Gravity.CENTER_VERTICAL);
        TextView tvPerm = Ui.text(this, 12, Ui.DANGER, Typeface.NORMAL, 4);
        tvPerm.setText("「跟随前台」需要使用情况访问权限。这台车机没有授权页，电脑连一次 adb 执行：\n"
                + "adb shell pm grant com.jietu.clustercast"
                + " android.permission.PACKAGE_USAGE_STATS");
        ((LinearLayout) permRow).addView(tvPerm, Ui.weighted(1, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView btnPerm = Ui.button(this, "去授权", 13, false, 12, 7, Ui.R_BTN);
        Ui.click(btnPerm, new Runnable() {
            @Override public void run() {
                if (!TopApp.request(MainActivity.this))
                    toast("这台车机打不开授权页，请用左边那条 adb 命令授权");
            }
        });
        ((LinearLayout) permRow).addView(btnPerm, Ui.ww());
        card.addView(vsp(10));
        card.addView(permRow, Ui.lw());

        // 悬浮窗权限（v14）：悬浮模式需要「显示在其他应用上层」；没给也能投，自动退回搬屏模式
        overlayRow = new LinearLayout(this);
        ((LinearLayout) overlayRow).setOrientation(LinearLayout.HORIZONTAL);
        ((LinearLayout) overlayRow).setGravity(Gravity.CENTER_VERTICAL);
        TextView tvOverlay = Ui.text(this, 12, Ui.DANGER, Typeface.NORMAL, 4);
        tvOverlay.setText("悬浮模式需要「显示在其他应用上层」权限。没给也能投（自动退回搬屏、临时禁用高德）。授权页打不开就用 adb：\n"
                + "adb shell appops set com.jietu.clustercast SYSTEM_ALERT_WINDOW allow");
        ((LinearLayout) overlayRow).addView(tvOverlay, Ui.weighted(1, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView btnOverlay = Ui.button(this, "去授权", 13, false, 12, 7, Ui.R_BTN);
        Ui.click(btnOverlay, new Runnable() {
            @Override public void run() {
                if (!ClusterOverlay.requestPermission(MainActivity.this))
                    toast("这台车机打不开授权页，请用左边那条 adb 命令授权");
            }
        });
        ((LinearLayout) overlayRow).addView(btnOverlay, Ui.ww());
        card.addView(vsp(10));
        card.addView(overlayRow, Ui.lw());
        root.addView(card, Ui.lw());
        root.addView(vsp(12));

        // 用法卡
        LinearLayout tips = Ui.card(this, 16);
        TextView tt = Ui.text(this, 14, Ui.INK, Typeface.BOLD, 1);
        tt.setText("用法");
        tips.addView(tt, Ui.lw());
        tips.addView(vsp(6));
        String[] lines = {
                "· 三指左滑＝投屏，三指右滑＝退出",
                "· 悬浮模式：投的软件浮在仪表原车内容上层，原车高德照常跑，主屏随便用",
                "· 投着 A 时在主屏打开 B 再左滑：B 上仪表，A 留在仪表后台，可在主屏再打开",
                "· 「选择应用」选定后自动关闭跟随前台，左滑投的就是选定的软件",
                "· 悬浮失败自动退回搬屏模式（投屏期间临时禁用原车高德，退出自动恢复）",
        };
        for (String ln : lines) {
            TextView t = Ui.text(this, 12.5f, Ui.INK_SUB, Typeface.NORMAL, 2);
            t.setText(ln);
            tips.addView(t, Ui.lw());
            tips.addView(vsp(3));
        }
        root.addView(tips, Ui.lw());
        root.addView(vsp(12));

        // 日志卡
        LinearLayout logCard = Ui.card(this, 16);
        TextView lt = Ui.text(this, 13, Ui.INK_SUB, Typeface.BOLD, 1);
        lt.setText("运行日志");
        logCard.addView(lt, Ui.lw());
        logCard.addView(vsp(6));
        svLog = new ScrollView(this);
        svLog.setFillViewport(true);
        tvLog = Ui.text(this, 10.5f, Ui.INK_SUB, Typeface.NORMAL, -1);
        tvLog.setTypeface(Typeface.MONOSPACE);
        tvLog.setBackground(Ui.paint(this, Ui.R_FIELD, 8));
        tvLog.setPadding(Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8));
        tvLog.setMovementMethod(new ScrollingMovementMethod());
        svLog.addView(tvLog, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        logCard.addView(svLog, Ui.weighted(1, 0));
        root.addView(logCard, Ui.weighted(1, 0));
        return root;
    }

    private void restyleFollow(boolean on) {
        Ui.restyle(btnFollow, this, on ? "跟随前台：开" : "跟随前台：关", on, Ui.R_ONOFF);
    }

    /** v13：档位按钮高亮 —— 选中的一档亮蓝，另一档白底。 */
    private void restyleTheme() {
        boolean navi = cfg.castTheme() == Vd.THEME_NAVI;
        Ui.restyle(btnNavi, this, "导航模式", navi, Ui.R_ONOFF);
        Ui.restyle(btnSimple, this, "极简模式", !navi, Ui.R_ONOFF);
    }

    /** 竖向间隔（自身带 LayoutParams，直接 addView 即可）。 */
    private View vsp(int dpH) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, dpH)));
        return v;
    }

    /** 横向间隔。 */
    private View hsp(int dpW) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                Ui.dp(this, dpW), ViewGroup.LayoutParams.WRAP_CONTENT));
        return v;
    }

    // ---------- 刷新 ----------

    private void refresh() {
        CastService s = CastService.inst();
        if (s != null && !sinkBound) {
            s.setSink(this);
            sinkBound = true;
        }
        String lbl = cfg.label();
        String pkg = cfg.pkg();
        String target = lbl != null ? lbl : (pkg != null ? pkg : null);
        tvTarget.setText("投屏目标：" + (target != null ? target
                : "未选择（三指左滑时投当前前台应用）"));

        if (s != null) {
            String cp = s.castingPkg();
            if (cp != null) {
                tvCast.setText("正在投屏：" + s.label(cp)
                        + "   仪表模式：" + themeName(s.themeSeen()));
                tvCast.setTextColor(Ui.GREEN);
            } else {
                tvCast.setText("未在投屏   仪表模式：" + themeName(s.themeSeen()));
                tvCast.setTextColor(Ui.INK_SUB);
            }
        } else {
            tvCast.setText("服务未启动");
            tvCast.setTextColor(Ui.INK_SUB);
        }
        permRow.setVisibility(TopApp.granted(this) ? View.GONE : View.VISIBLE);
        boolean overlayOk;
        try { overlayOk = Settings.canDrawOverlays(this); } catch (Throwable t) { overlayOk = true; }
        overlayRow.setVisibility(overlayOk ? View.GONE : View.VISIBLE);
        tvDiag.setText("背景图：" + Ui.wallpaperDiag);
    }

    private static String themeName(int t) {
        return t >= 0 ? Vd.themeName(t) : "未知";
    }

    @Override public void onLog(String s) {
        if (tvLog == null) return;
        tvLog.setText(s);
        svLog.post(new Runnable() {
            @Override public void run() { svLog.fullScroll(View.FOCUS_DOWN); }
        });
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
}
