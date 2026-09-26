package com.jietu.clustercast;

import android.app.Activity;
import android.app.AlertDialog;
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
import android.widget.AbsListView;
import android.widget.BaseAdapter;
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
 * 主屏设置页。壁纸（assets/wallpaper.png）只是这个页面的背景；
 * 投到仪表的是「自选软件」：三指左滑投屏、右滑退出。
 *
 * 页面结构（纯代码构建，无布局 XML）：
 *   · 左侧：右上角标题「冥城投屏助手」+ 设置图标 + 开始/结束投屏按钮
 *   · 左侧下方：应用网格（4 列，上图标下名称），点击即选为投屏目标
 *   · 右侧：运行日志实时滚动
 */
public class MainActivity extends Activity implements CastService.LogSink {

    private Cfg cfg;
    private TextView tvStatus;
    private TextView tvLog;
    private ScrollView svLog;
    private GridView grid;
    private AppAdapter adapter;
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
        cfg = new Cfg(this);
        allApps = loadApps();
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
        // 根：横向分栏，左 = 应用区，右 = 日志区
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackground(Ui.wallpaper(this));
        int pad = Ui.dp(this, 16);
        root.setPadding(pad, pad, pad, pad);

        // ===== 左侧：标题 + 控制按钮 + 应用网格 =====
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        root.addView(left, Ui.weighted(2.2f, ViewGroup.LayoutParams.MATCH_PARENT));

        // 标题行：右上角「冥城投屏助手」
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        TextView title = Ui.text(this, 24, Ui.INK, Typeface.BOLD, 1);
        title.setText("冥城投屏助手");
        head.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        left.addView(head, Ui.lw());
        left.addView(vsp(6));

        // 控制行：设置图标 + 开始投屏 + 结束投屏
        LinearLayout ctrl = new LinearLayout(this);
        ctrl.setOrientation(LinearLayout.HORIZONTAL);
        ctrl.setGravity(Gravity.CENTER_VERTICAL);

        TextView btnSettings = Ui.button(this, "⚙ 设置", 14, false, 12, 8, Ui.R_BTN);
        Ui.click(btnSettings, new Runnable() {
            @Override public void run() { showSettingsDialog(); }
        });
        ctrl.addView(btnSettings, Ui.ww());
        ctrl.addView(hsp(8));

        TextView btnCast = Ui.button(this, "开始投屏", 14, false, 16, 8, Ui.R_GREEN);
        Ui.click(btnCast, new Runnable() {
            @Override public void run() {
                CastService s = CastService.inst();
                if (s == null) { toast("服务还在启动，两秒后再试"); return; }
                s.castNow();
            }
        });
        ctrl.addView(btnCast, Ui.ww());
        ctrl.addView(hsp(8));

        TextView btnExit = Ui.button(this, "结束投屏", 14, false, 16, 8, Ui.R_DANGER);
        Ui.click(btnExit, new Runnable() {
            @Override public void run() {
                CastService s = CastService.inst();
                if (s == null) { toast("服务还没起来"); return; }
                s.exitNow();
            }
        });
        ctrl.addView(btnExit, Ui.ww());
        ctrl.addView(hsp(8));

        // 状态文字
        tvStatus = Ui.text(this, 12, Ui.INK_SUB, Typeface.NORMAL, 2);
        ctrl.addView(tvStatus, Ui.weighted(1, ViewGroup.LayoutParams.WRAP_CONTENT));

        left.addView(ctrl, Ui.lw());
        left.addView(vsp(10));

        // 应用网格：4 列，上图标下名称
        grid = new GridView(this);
        grid.setNumColumns(4);
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setHorizontalSpacing(Ui.dp(this, 8));
        grid.setVerticalSpacing(Ui.dp(this, 8));
        grid.setPadding(Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6));
        grid.setBackground(Ui.paint(this, Ui.R_CARD, 12));
        adapter = new AppAdapter(allApps);
        grid.setAdapter(adapter);
        grid.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override public void onItemClick(android.widget.AdapterView<?> p, View v,
                    int pos, long id) {
                ResolveInfo r = adapter.getItem(pos);
                chooseApp(r);
            }
        });
        left.addView(grid, Ui.weighted(1, 0));

        // ===== 右侧：日志区 =====
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        root.addView(right, Ui.weighted(1f, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout logCard = Ui.card(this, 12);
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
        right.addView(logCard, Ui.weighted(1, 0));

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
        // 选定应用后自动关闭跟随前台，让选择生效
        if (cfg.followTop()) cfg.setFollowTop(false);
        adapter.notifyDataSetChanged();
        toast("已选定：" + name);
    }

    /** 一格：图标在上、名字在下。选中的高亮。 */
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
                cell.setPadding(Ui.dp(MainActivity.this, 4), Ui.dp(MainActivity.this, 8),
                        Ui.dp(MainActivity.this, 4), Ui.dp(MainActivity.this, 8));
                ImageView iv = new ImageView(MainActivity.this);
                cell.addView(iv, new LinearLayout.LayoutParams(
                        Ui.dp(MainActivity.this, 42), Ui.dp(MainActivity.this, 42)));
                TextView t = Ui.text(MainActivity.this, 11, Ui.INK, Typeface.NORMAL, 1);
                t.setGravity(Gravity.CENTER);
                LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                tlp.topMargin = Ui.dp(MainActivity.this, 5);
                cell.addView(t, tlp);
            }
            ResolveInfo r = items.get(pos);
            Drawable icon = r.loadIcon(getPackageManager());
            ((ImageView) cell.getChildAt(0)).setImageDrawable(icon);
            ((TextView) cell.getChildAt(1)).setText(r.loadLabel(getPackageManager()).toString());
            // 选中高亮
            String sel = cfg.pkg();
            boolean selected = sel != null && sel.equals(r.activityInfo.packageName);
            cell.setBackground(Ui.paint(MainActivity.this,
                    selected ? Ui.R_BTN_ON : Ui.R_BTN, 10));
            int size = Ui.dp(MainActivity.this, 96);
            cell.setLayoutParams(new AbsListView.LayoutParams(size, size));
            return cell;
        }
    }

    // ---------- 设置弹窗 ----------

    private void showSettingsDialog() {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 16), Ui.dp(this, 12));

        // 仪表档位
        TextView lblTheme = Ui.text(this, 14, Ui.INK, Typeface.BOLD, 1);
        lblTheme.setText("仪表档位");
        body.addView(lblTheme, Ui.lw());
        body.addView(vsp(6));
        LinearLayout themeRow = new LinearLayout(this);
        themeRow.setOrientation(LinearLayout.HORIZONTAL);
        final TextView btnNavi = Ui.button(this, "导航模式", 14,
                cfg.castTheme() == Vd.THEME_NAVI, 14, 8, Ui.R_ONOFF);
        final TextView btnSimple = Ui.button(this, "极简模式", 14,
                cfg.castTheme() == Vd.THEME_SIMPLE, 14, 8, Ui.R_ONOFF);
        Ui.click(btnNavi, new Runnable() {
            @Override public void run() {
                cfg.setCastTheme(Vd.THEME_NAVI);
                Ui.restyle(btnNavi, MainActivity.this, "导航模式", true, Ui.R_ONOFF);
                Ui.restyle(btnSimple, MainActivity.this, "极简模式", false, Ui.R_ONOFF);
            }
        });
        Ui.click(btnSimple, new Runnable() {
            @Override public void run() {
                cfg.setCastTheme(Vd.THEME_SIMPLE);
                Ui.restyle(btnNavi, MainActivity.this, "导航模式", false, Ui.R_ONOFF);
                Ui.restyle(btnSimple, MainActivity.this, "极简模式", true, Ui.R_ONOFF);
            }
        });
        themeRow.addView(btnNavi, Ui.ww());
        themeRow.addView(hsp(8));
        themeRow.addView(btnSimple, Ui.ww());
        body.addView(themeRow, Ui.lw());
        body.addView(vsp(12));

        // 跟随前台
        TextView lblFollow = Ui.text(this, 14, Ui.INK, Typeface.BOLD, 1);
        lblFollow.setText("跟随前台");
        body.addView(lblFollow, Ui.lw());
        body.addView(vsp(6));
        final TextView btnFollow = Ui.button(this,
                cfg.followTop() ? "跟随前台：开" : "跟随前台：关", 14,
                cfg.followTop(), 14, 8, Ui.R_ONOFF);
        Ui.click(btnFollow, new Runnable() {
            @Override public void run() {
                boolean on = !cfg.followTop();
                cfg.setFollowTop(on);
                Ui.restyle(btnFollow, MainActivity.this,
                        on ? "跟随前台：开" : "跟随前台：关", on, Ui.R_ONOFF);
            }
        });
        body.addView(btnFollow, Ui.lw());
        body.addView(vsp(12));

        // 权限提示
        boolean topGranted = TopApp.granted(this);
        boolean overlayOk;
        try { overlayOk = Settings.canDrawOverlays(this); } catch (Throwable t) { overlayOk = true; }
        if (!topGranted || !overlayOk) {
            TextView lblPerm = Ui.text(this, 14, Ui.INK, Typeface.BOLD, 1);
            lblPerm.setText("权限");
            body.addView(lblPerm, Ui.lw());
            body.addView(vsp(6));
            if (!topGranted) {
                TextView tp = Ui.text(this, 12, Ui.DANGER, Typeface.NORMAL, 4);
                tp.setText("「跟随前台」需要使用情况访问权限：\n"
                        + "adb shell pm grant com.jietu.clustercast"
                        + " android.permission.PACKAGE_USAGE_STATS");
                body.addView(tp, Ui.lw());
                body.addView(vsp(6));
            }
            if (!overlayOk) {
                TextView op = Ui.text(this, 12, Ui.DANGER, Typeface.NORMAL, 4);
                op.setText("悬浮模式需要「显示在其他应用上层」权限：\n"
                        + "adb shell appops set com.jietu.clustercast SYSTEM_ALERT_WINDOW allow");
                body.addView(op, Ui.lw());
            }
        }

        new AlertDialog.Builder(this)
                .setTitle("设置")
                .setView(body)
                .setPositiveButton("完成", null)
                .show();
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
                        + "   目标：" + (target != null ? target : "未选"));
                tvStatus.setTextColor(Ui.GREEN);
            } else {
                tvStatus.setText("目标：" + (target != null ? target : "未选择（三指左滑投前台）"));
                tvStatus.setTextColor(Ui.INK_SUB);
            }
        } else {
            tvStatus.setText("服务未启动");
            tvStatus.setTextColor(Ui.INK_SUB);
        }
        // 选中态可能在设置弹窗里改了跟随前台，刷新网格高亮
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
