package com.jietu.clustercast;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 主屏设置页 —— 深色主题。
 * 布局：左侧应用网格（4 列，上图标下名称），右侧侧边栏（标题、投屏按钮、设置、日志）。
 */
public class MainActivity extends Activity implements CastService.LogSink {

    private Cfg cfg;
    private TextView tvStatus;
    private TextView tvAppCount;
    private TextView tvLog;
    private ScrollView svLog;
    private GridView grid;
    private AppAdapter adapter;
    private FrameLayout rightPanel;
    private List<ResolveInfo> allApps = new ArrayList<>();

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
        // 全屏：透明状态栏/导航栏 + 隐藏系统栏，避免底部白条
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        cfg = new Cfg(this);
        allApps = loadApps();
        setContentView(buildUi());
        refresh();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
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

    @Override public void onBackPressed() {
        if (settingsOverlay != null) {
            closeSettingsOverlay();
            return;
        }
        super.onBackPressed();
    }

    // ---------- 构建 ----------

    private View buildUi() {
        // 根：横向分栏，左 = 侧边栏，右 = 应用网格
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackground(Ui.darkWallpaper(this));
        int pad = Ui.dp(this, 12);
        root.setPadding(pad, pad, pad, pad);

        // ===== 左侧：侧边栏 =====
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setBackground(Ui.darkBg(this, Ui.D_CARD, 12));
        int rpad = Ui.dp(this, 12);
        left.setPadding(rpad, rpad, rpad, rpad);
        root.addView(left, Ui.weighted(1f, ViewGroup.LayoutParams.MATCH_PARENT));

        // 标题
        TextView title = Ui.text(this, 20, Ui.D_TEXT, Typeface.BOLD, 1);
        title.setText("冥城投屏助手");
        left.addView(title, Ui.lw());
        left.addView(vsp(4));

        // 应用数量
        tvAppCount = Ui.text(this, 12, Ui.D_TEXT_SUB, Typeface.NORMAL, 1);
        tvAppCount.setText("应用列表  共 " + allApps.size() + " 个应用");
        left.addView(tvAppCount, Ui.lw());
        left.addView(vsp(10));

        // 状态文字
        tvStatus = Ui.text(this, 12, Ui.D_TEXT_SUB, Typeface.NORMAL, 2);
        left.addView(tvStatus, Ui.lw());
        left.addView(vsp(10));

        // 开始投屏按钮（绿色）
        TextView btnCast = Ui.darkButton(this, "开始投屏", 15, Ui.D_GREEN, 0xFFFFFFFF);
        Ui.click(btnCast, new Runnable() {
            @Override public void run() {
                CastService s = CastService.inst();
                if (s == null) { toast("服务还在启动，两秒后再试"); return; }
                s.castNow();
            }
        });
        left.addView(btnCast, Ui.lw());
        left.addView(vsp(8));

        // 结束投屏按钮（红色）
        TextView btnExit = Ui.darkButton(this, "结束投屏", 15, Ui.D_DANGER, 0xFFFFFFFF);
        Ui.click(btnExit, new Runnable() {
            @Override public void run() {
                CastService s = CastService.inst();
                if (s == null) { toast("服务还没起来"); return; }
                s.exitNow();
            }
        });
        left.addView(btnExit, Ui.lw());
        left.addView(vsp(8));

        // 设置按钮
        TextView btnSettings = Ui.darkButton(this, "⚙ 设置", 14, Ui.D_BTN, Ui.D_TEXT);
        Ui.click(btnSettings, new Runnable() {
            @Override public void run() { showSettingsOverlay(); }
        });
        left.addView(btnSettings, Ui.lw());
        left.addView(vsp(12));

        // 日志区标题行
        LinearLayout logHead = new LinearLayout(this);
        logHead.setOrientation(LinearLayout.HORIZONTAL);
        logHead.setGravity(Gravity.CENTER_VERTICAL);
        TextView lt = Ui.text(this, 13, Ui.D_TEXT, Typeface.BOLD, 1);
        lt.setText("全部日志");
        logHead.addView(lt, Ui.weighted(1, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView btnClear = Ui.darkButton(this, "清空", 11, Ui.D_BTN, Ui.D_TEXT_SUB);
        Ui.click(btnClear, new Runnable() {
            @Override public void run() {
                CastService s = CastService.inst();
                if (s != null) s.clearLog();
                if (tvLog != null) tvLog.setText("");
            }
        });
        logHead.addView(btnClear, Ui.ww());
        left.addView(logHead, Ui.lw());
        left.addView(vsp(6));

        // 日志区
        svLog = new ScrollView(this);
        svLog.setFillViewport(true);
        svLog.setBackground(Ui.darkBg(this, Ui.D_FIELD, 8));
        tvLog = Ui.text(this, 10.5f, Ui.D_TEXT_SUB, Typeface.NORMAL, -1);
        tvLog.setTypeface(Typeface.MONOSPACE);
        tvLog.setPadding(Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8));
        tvLog.setMovementMethod(new ScrollingMovementMethod());
        svLog.addView(tvLog, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        left.addView(svLog, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // ===== 右侧：应用网格（FrameLayout 用于覆盖设置页） =====
        rightPanel = new FrameLayout(this);
        LinearLayout.LayoutParams rlp = Ui.weighted(2.5f, ViewGroup.LayoutParams.MATCH_PARENT);
        rlp.leftMargin = Ui.dp(this, 10);
        root.addView(rightPanel, rlp);

        grid = new GridView(this);
        grid.setNumColumns(4);
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setHorizontalSpacing(Ui.dp(this, 14));
        grid.setVerticalSpacing(Ui.dp(this, 14));
        grid.setPadding(Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8));
        grid.setBackground(Ui.darkBg(this, Ui.D_CARD, 12));
        adapter = new AppAdapter(allApps);
        grid.setAdapter(adapter);
        grid.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override public void onItemClick(android.widget.AdapterView<?> p, View v,
                    int pos, long id) {
                ResolveInfo r = adapter.getItem(pos);
                chooseApp(r);
            }
        });
        rightPanel.addView(grid, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        return root;
    }

    // ---------- 应用列表 ----------

    private List<ResolveInfo> loadApps() {
        Intent probe = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> rs = getPackageManager().queryIntentActivities(probe, 0);
        final ArrayList<ResolveInfo> out = new ArrayList<>();
        for (ResolveInfo r : rs)
            if (!r.activityInfo.packageName.equals(getPackageName())) out.add(r);
        final Collator coll = Collator.getInstance(Locale.CHINA);
        Collections.sort(out, new Comparator<ResolveInfo>() {
            @Override public int compare(ResolveInfo x, ResolveInfo y) {
                return coll.compare(x.loadLabel(getPackageManager()).toString(),
                        y.loadLabel(getPackageManager()).toString());
            }
        });
        return out;
    }

    private void chooseApp(ResolveInfo r) {
        android.content.pm.ApplicationInfo ai = r.activityInfo.applicationInfo;
        String name = getPackageManager().getApplicationLabel(ai).toString();
        cfg.setTarget(r.activityInfo.packageName, r.activityInfo.name, name);
        if (cfg.followTop()) cfg.setFollowTop(false);
        adapter.notifyDataSetChanged();
        // 选定后直接开始投屏，无需再点"开始投屏"
        CastService s = CastService.inst();
        if (s != null) {
            s.castNow();
            toast("已选定并投屏：" + name);
        } else {
            toast("已选定：" + name + "（服务启动中，稍后自动投屏）");
        }
    }

    /** 一格：图标在上、名字在下。选中的蓝色高亮。 */
    private class AppAdapter extends BaseAdapter {
        private final List<ResolveInfo> items;
        AppAdapter(List<ResolveInfo> l) { items = l; }
        @Override public int getCount() { return items.size(); }
        @Override public ResolveInfo getItem(int pos) { return items.get(pos); }
        @Override public long getItemId(int pos) { return pos; }

        @Override public View getView(int pos, View cv, ViewGroup parent) {
            LinearLayout cell;
            if (cv instanceof LinearLayout) cell = (LinearLayout) cv;
            else {
                cell = new LinearLayout(MainActivity.this);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setGravity(Gravity.CENTER_HORIZONTAL);
                cell.setPadding(Ui.dp(MainActivity.this, 4), Ui.dp(MainActivity.this, 6),
                        Ui.dp(MainActivity.this, 4), Ui.dp(MainActivity.this, 6));
                ImageView iv = new ImageView(MainActivity.this);
                cell.addView(iv, new LinearLayout.LayoutParams(
                        Ui.dp(MainActivity.this, 44), Ui.dp(MainActivity.this, 44)));
                TextView t = Ui.text(MainActivity.this, 11, Ui.D_TEXT, Typeface.NORMAL, 1);
                t.setGravity(Gravity.CENTER);
                LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                tlp.topMargin = Ui.dp(MainActivity.this, 4);
                cell.addView(t, tlp);
            }
            ResolveInfo r = items.get(pos);
            Drawable icon = r.loadIcon(getPackageManager());
            ((ImageView) cell.getChildAt(0)).setImageDrawable(icon);
            ((TextView) cell.getChildAt(1)).setText(r.loadLabel(getPackageManager()).toString());
            String sel = cfg.pkg();
            boolean selected = sel != null && sel.equals(r.activityInfo.packageName);
            cell.setBackground(Ui.darkBg(MainActivity.this,
                    selected ? Ui.D_BTN_ON : Ui.D_BTN, 10));
            // 卡片刚好放完图标和名称：宽度填满列，高度自适应
            cell.setLayoutParams(new AbsListView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            return cell;
        }
    }

    // ---------- 设置（覆盖到应用区，不用弹窗） ----------

    private View settingsOverlay = null;

    private void showSettingsOverlay() {
        if (settingsOverlay != null) return;

        // 覆盖层：占满整个右侧应用区，直接纵向排列
        LinearLayout overlay = new LinearLayout(this);
        overlay.setOrientation(LinearLayout.VERTICAL);
        overlay.setBackground(Ui.darkBg(this, Ui.D_BG, 12));
        int pad = Ui.dp(this, 16);
        overlay.setPadding(pad, Ui.dp(this, 12), pad, pad);

        // 顶部：返回按钮 + 标题
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView btnBack = Ui.darkButton(this, "← 返回", 14, Ui.D_BTN, Ui.D_TEXT);
        Ui.click(btnBack, new Runnable() {
            @Override public void run() { closeSettingsOverlay(); }
        });
        head.addView(btnBack, Ui.ww());
        head.addView(hsp(12));
        TextView headTitle = Ui.text(this, 18, Ui.D_TEXT, Typeface.BOLD, 1);
        headTitle.setText("投屏设置");
        head.addView(headTitle, Ui.weighted(1, ViewGroup.LayoutParams.WRAP_CONTENT));
        overlay.addView(head, Ui.lw());

        // 分割线
        View divider = new View(this);
        divider.setBackgroundColor(0xFF2A3040);
        overlay.addView(divider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 1)));
        overlay.addView(vsp(16));

        // 仪表档位
        TextView lblTheme = Ui.text(this, 14, Ui.D_TEXT, Typeface.BOLD, 1);
        lblTheme.setText("仪表档位");
        overlay.addView(lblTheme, Ui.lw());
        overlay.addView(vsp(6));
        LinearLayout themeRow = new LinearLayout(this);
        themeRow.setOrientation(LinearLayout.HORIZONTAL);
        final TextView btnNavi = Ui.darkButton(this, "导航模式", 14,
                cfg.castTheme() == Vd.THEME_NAVI ? Ui.D_BTN_ON : Ui.D_BTN,
                cfg.castTheme() == Vd.THEME_NAVI ? 0xFFFFFFFF : Ui.D_TEXT);
        final TextView btnSimple = Ui.darkButton(this, "极简模式", 14,
                cfg.castTheme() == Vd.THEME_SIMPLE ? Ui.D_BTN_ON : Ui.D_BTN,
                cfg.castTheme() == Vd.THEME_SIMPLE ? 0xFFFFFFFF : Ui.D_TEXT);
        Ui.click(btnNavi, new Runnable() {
            @Override public void run() {
                cfg.setCastTheme(Vd.THEME_NAVI);
                btnNavi.setBackground(Ui.darkBg(MainActivity.this, Ui.D_BTN_ON, 10));
                btnNavi.setTextColor(0xFFFFFFFF);
                btnSimple.setBackground(Ui.darkBg(MainActivity.this, Ui.D_BTN, 10));
                btnSimple.setTextColor(Ui.D_TEXT);
            }
        });
        Ui.click(btnSimple, new Runnable() {
            @Override public void run() {
                cfg.setCastTheme(Vd.THEME_SIMPLE);
                btnSimple.setBackground(Ui.darkBg(MainActivity.this, Ui.D_BTN_ON, 10));
                btnSimple.setTextColor(0xFFFFFFFF);
                btnNavi.setBackground(Ui.darkBg(MainActivity.this, Ui.D_BTN, 10));
                btnNavi.setTextColor(Ui.D_TEXT);
            }
        });
        themeRow.addView(btnNavi, Ui.ww());
        themeRow.addView(hsp(8));
        themeRow.addView(btnSimple, Ui.ww());
        overlay.addView(themeRow, Ui.lw());
        overlay.addView(vsp(16));

        // 跟随前台
        TextView lblFollow = Ui.text(this, 14, Ui.D_TEXT, Typeface.BOLD, 1);
        lblFollow.setText("跟随前台");
        overlay.addView(lblFollow, Ui.lw());
        overlay.addView(vsp(6));
        final boolean[] follow = {cfg.followTop()};
        final TextView btnFollow = Ui.darkButton(this,
                follow[0] ? "跟随前台：开" : "跟随前台：关", 14,
                follow[0] ? Ui.D_BTN_ON : Ui.D_BTN,
                follow[0] ? 0xFFFFFFFF : Ui.D_TEXT);
        Ui.click(btnFollow, new Runnable() {
            @Override public void run() {
                follow[0] = !follow[0];
                cfg.setFollowTop(follow[0]);
                btnFollow.setText(follow[0] ? "跟随前台：开" : "跟随前台：关");
                btnFollow.setBackground(Ui.darkBg(MainActivity.this,
                        follow[0] ? Ui.D_BTN_ON : Ui.D_BTN, 10));
                btnFollow.setTextColor(follow[0] ? 0xFFFFFFFF : Ui.D_TEXT);
            }
        });
        overlay.addView(btnFollow, Ui.lw());
        overlay.addView(vsp(16));

        // 权限提示
        boolean topGranted = TopApp.granted(this);
        boolean overlayOk;
        try { overlayOk = Settings.canDrawOverlays(this); } catch (Throwable t) { overlayOk = true; }
        if (!topGranted || !overlayOk) {
            TextView lblPerm = Ui.text(this, 14, Ui.D_TEXT, Typeface.BOLD, 1);
            lblPerm.setText("权限");
            overlay.addView(lblPerm, Ui.lw());
            overlay.addView(vsp(6));
            if (!topGranted) {
                TextView tp = Ui.text(this, 12, 0xFFFF8080, Typeface.NORMAL, 4);
                tp.setText("「跟随前台」需要使用情况访问权限：\n"
                        + "adb shell pm grant com.jietu.clustercast"
                        + " android.permission.PACKAGE_USAGE_STATS");
                overlay.addView(tp, Ui.lw());
                overlay.addView(vsp(6));
            }
            if (!overlayOk) {
                TextView op = Ui.text(this, 12, 0xFFFF8080, Typeface.NORMAL, 4);
                op.setText("悬浮模式需要「显示在其他应用上层」权限：\n"
                        + "adb shell appops set com.jietu.clustercast SYSTEM_ALERT_WINDOW allow");
                overlay.addView(op, Ui.lw());
            }
        }

        settingsOverlay = overlay;
        rightPanel.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void closeSettingsOverlay() {
        if (settingsOverlay != null) {
            rightPanel.removeView(settingsOverlay);
            settingsOverlay = null;
        }
    }

    // ---------- 辅助 ----------

    private View vsp(int dpH) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, dpH)));
        return v;
    }

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
        if (s != null) {
            String cp = s.castingPkg();
            if (cp != null) {
                tvStatus.setText("正在投屏：" + s.label(cp)
                        + "\n目标：" + (target != null ? target : "未选"));
                tvStatus.setTextColor(Ui.GREEN);
            } else {
                tvStatus.setText("目标：" + (target != null ? target : "未选择（三指左滑投前台）"));
                tvStatus.setTextColor(Ui.D_TEXT_SUB);
            }
        } else {
            tvStatus.setText("服务未启动");
            tvStatus.setTextColor(Ui.D_TEXT_SUB);
        }
        if (adapter != null) adapter.notifyDataSetChanged();
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
