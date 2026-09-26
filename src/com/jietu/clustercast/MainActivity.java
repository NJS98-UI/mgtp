package com.jietu.clustercast;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.provider.Settings;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
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
 * 主界面 —— 深色主题。
 * 布局：上方左右等宽（侧边栏 + 内容区），下方导航栏（投屏/空调/盲区/记录仪）。
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

    // 导航栏
    private LinearLayout navBar;
    private final List<TextView> navTabs = new ArrayList<>();
    private int currentTab = 0; // 0=投屏, 1=空调, 2=盲区, 3=记录仪

    // 记录仪
    private TextureView dashcamPreview;
    private CameraDevice dashcamCamera;
    private CameraCaptureSession dashcamSession;
    private HandlerThread camThread;
    private Handler camHandler;
    private boolean camInited = false;

    // 空调页回读显示
    private TextView tvHvacDriverTemp;
    private TextView tvHvacCopilotTemp;
    private TextView tvHvacFan;
    private int hvacDriverTemp = 22;
    private int hvacCopilotTemp = 22;
    private int hvacFan = 3;

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

    @Override protected void onDestroy() {
        releaseDashcamCamera();
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if (inSettings) {
            closeSettingsOverlay();
            return;
        }
        super.onBackPressed();
    }

    // ---------- 构建 ----------

    private View buildUi() {
        // 根：纵向（内容区 + 底部导航栏）
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(Ui.darkWallpaper(this));
        int pad = Ui.dp(this, 12);
        root.setPadding(pad, pad, pad, pad);

        // ===== 内容行：左 + 右等宽 =====
        LinearLayout contentRow = new LinearLayout(this);
        contentRow.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(contentRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // ===== 左侧：侧边栏 =====
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setBackground(Ui.darkBg(this, Ui.D_CARD, 12));
        int rpad = Ui.dp(this, 12);
        left.setPadding(rpad, rpad, rpad, rpad);
        contentRow.addView(left, Ui.weighted(1f, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView title = Ui.text(this, 20, Ui.D_TEXT, Typeface.BOLD, 1);
        title.setText("冥城投屏助手");
        left.addView(title, Ui.lw());
        left.addView(vsp(4));

        tvAppCount = Ui.text(this, 12, Ui.D_TEXT_SUB, Typeface.NORMAL, 1);
        tvAppCount.setText("应用列表  共 " + allApps.size() + " 个应用");
        left.addView(tvAppCount, Ui.lw());
        left.addView(vsp(10));

        tvStatus = Ui.text(this, 12, Ui.D_TEXT_SUB, Typeface.NORMAL, 2);
        left.addView(tvStatus, Ui.lw());
        left.addView(vsp(10));

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

        TextView btnSettings = Ui.darkButton(this, "⚙ 设置", 14, Ui.D_BTN, Ui.D_TEXT);
        Ui.click(btnSettings, new Runnable() {
            @Override public void run() { showSettingsOverlay(); }
        });
        left.addView(btnSettings, Ui.lw());
        left.addView(vsp(12));

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

        // ===== 右侧：内容区（FrameLayout 切换） =====
        rightPanel = new FrameLayout(this);
        LinearLayout.LayoutParams rlp = Ui.weighted(1f, ViewGroup.LayoutParams.MATCH_PARENT);
        rlp.leftMargin = Ui.dp(this, 10);
        contentRow.addView(rightPanel, rlp);

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

        // ===== 底部导航栏 =====
        navBar = new LinearLayout(this);
        navBar.setOrientation(LinearLayout.HORIZONTAL);
        navBar.setGravity(Gravity.CENTER);
        navBar.setBackground(Ui.darkBg(this, Ui.D_CARD, 12));
        int npad = Ui.dp(this, 8);
        navBar.setPadding(npad, npad, npad, npad);
        root.addView(navBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        String[] tabNames = {"投屏", "空调", "盲区", "记录仪"};
        for (int i = 0; i < tabNames.length; i++) {
            final int idx = i;
            TextView tab = Ui.darkButton(this, tabNames[i], 14,
                    i == currentTab ? Ui.D_BTN_ON : Ui.D_BTN,
                    i == currentTab ? 0xFFFFFFFF : Ui.D_TEXT);
            Ui.click(tab, new Runnable() {
                @Override public void run() { switchTab(idx); }
            });
            navBar.addView(tab, Ui.weighted(1f, ViewGroup.LayoutParams.WRAP_CONTENT));
            if (i < tabNames.length - 1) navBar.addView(hsp(8));
            navTabs.add(tab);
        }

        return root;
    }

    // ---------- 导航切换 ----------

    private void switchTab(int index) {
        currentTab = index;
        for (int i = 0; i < navTabs.size(); i++) {
            boolean active = (i == index);
            navTabs.get(i).setBackground(Ui.darkBg(this, active ? Ui.D_BTN_ON : Ui.D_BTN, 10));
            navTabs.get(i).setTextColor(active ? 0xFFFFFFFF : Ui.D_TEXT);
        }
        if (inSettings) { closeSettingsOverlay(); return; }
        releaseDashcamCamera();
        rightPanel.removeAllViews();
        switch (index) {
            case 0:
                rightPanel.addView(grid, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                break;
            case 1:
                rightPanel.addView(buildAcPage(), new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                break;
            case 2:
                rightPanel.addView(buildBlindSpotPage(), new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                break;
            case 3:
                rightPanel.addView(buildDashcamPage(), new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                break;
        }
    }

    private void switchTabBack(int index) {
        currentTab = index;
        for (int i = 0; i < navTabs.size(); i++) {
            boolean active = (i == index);
            navTabs.get(i).setBackground(Ui.darkBg(this, active ? Ui.D_BTN_ON : Ui.D_BTN, 10));
            navTabs.get(i).setTextColor(active ? 0xFFFFFFFF : Ui.D_TEXT);
        }
        rightPanel.removeAllViews();
        rightPanel.addView(grid, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    // ---------- 空调页 ----------

    private View buildAcPage() {
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.setBackground(Ui.darkBg(this, Ui.D_CARD, 12));

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 12);
        page.setPadding(pad, pad, pad, pad);

        TextView title = Ui.text(this, 16, Ui.D_TEXT, Typeface.BOLD, 1);
        title.setText("空调");
        page.addView(title, Ui.lw());
        page.addView(vsp(8));

        LinearLayout hvacRow = new LinearLayout(this);
        hvacRow.setOrientation(LinearLayout.HORIZONTAL);
        hvacRow.addView(hvacBtn("电源", new Runnable() {
            @Override public void run() { vdHvac(Vd.HVAC_STATE, 2); }
        }));
        hvacRow.addView(hsp(8));
        hvacRow.addView(hvacBtn("AUTO", new Runnable() {
            @Override public void run() { vdHvac(Vd.HVAC_AUTO, 2); }
        }));
        hvacRow.addView(hsp(8));
        hvacRow.addView(hvacBtn("双区", new Runnable() {
            @Override public void run() { vdHvac(Vd.HVAC_DUAL, 2); }
        }));
        hvacRow.addView(hsp(8));
        hvacRow.addView(hvacBtn("内循环", new Runnable() {
            @Override public void run() { vdHvac(Vd.HVAC_CIRC, 2); }
        }));
        page.addView(hvacRow, Ui.lw());
        page.addView(vsp(8));

        LinearLayout defrostRow = new LinearLayout(this);
        defrostRow.setOrientation(LinearLayout.HORIZONTAL);
        defrostRow.addView(hvacBtn("前除霜", new Runnable() {
            @Override public void run() { vdHvac(Vd.HVAC_FRONT_DEFROST, 2); }
        }));
        defrostRow.addView(hsp(8));
        defrostRow.addView(hvacBtn("后除霜", new Runnable() {
            @Override public void run() { vdHvac(Vd.HVAC_REAR_DEFROST, 2); }
        }));
        defrostRow.addView(hsp(8));
        defrostRow.addView(hvacBtn("关空调", new Runnable() {
            @Override public void run() { vdHvac(Vd.HVAC_STATE, 1); }
        }));
        page.addView(defrostRow, Ui.lw());
        page.addView(vsp(12));

        tvHvacDriverTemp = Ui.text(this, 14, Ui.D_TEXT, Typeface.BOLD, 1);
        tvHvacDriverTemp.setGravity(Gravity.CENTER);
        tvHvacCopilotTemp = Ui.text(this, 14, Ui.D_TEXT, Typeface.BOLD, 1);
        tvHvacCopilotTemp.setGravity(Gravity.CENTER);
        tvHvacFan = Ui.text(this, 14, Ui.D_TEXT, Typeface.BOLD, 1);
        tvHvacFan.setGravity(Gravity.CENTER);
        refreshHvacLabels();

        page.addView(stepperRow("主驾温度", tvHvacDriverTemp, new Runnable() {
            @Override public void run() {
                hvacDriverTemp = clamp(hvacDriverTemp - 1, 16, 32);
                vdHvac(Vd.HVAC_TEMP_DRIVER, hvacDriverTemp);
                refreshHvacLabels();
            }
        }, new Runnable() {
            @Override public void run() {
                hvacDriverTemp = clamp(hvacDriverTemp + 1, 16, 32);
                vdHvac(Vd.HVAC_TEMP_DRIVER, hvacDriverTemp);
                refreshHvacLabels();
            }
        }), Ui.lw());
        page.addView(vsp(8));
        page.addView(stepperRow("副驾温度", tvHvacCopilotTemp, new Runnable() {
            @Override public void run() {
                hvacCopilotTemp = clamp(hvacCopilotTemp - 1, 16, 32);
                vdHvac(Vd.HVAC_TEMP_COPILOT, hvacCopilotTemp);
                refreshHvacLabels();
            }
        }, new Runnable() {
            @Override public void run() {
                hvacCopilotTemp = clamp(hvacCopilotTemp + 1, 16, 32);
                vdHvac(Vd.HVAC_TEMP_COPILOT, hvacCopilotTemp);
                refreshHvacLabels();
            }
        }), Ui.lw());
        page.addView(vsp(8));
        page.addView(stepperRow("风速", tvHvacFan, new Runnable() {
            @Override public void run() {
                hvacFan = clamp(hvacFan - 1, 1, 8);
                vdHvac(Vd.HVAC_FAN, hvacFan);
                refreshHvacLabels();
            }
        }, new Runnable() {
            @Override public void run() {
                hvacFan = clamp(hvacFan + 1, 1, 8);
                vdHvac(Vd.HVAC_FAN, hvacFan);
                refreshHvacLabels();
            }
        }), Ui.lw());

        page.addView(vsp(16));
        TextView winTitle = Ui.text(this, 16, Ui.D_TEXT, Typeface.BOLD, 1);
        winTitle.setText("车窗");
        page.addView(winTitle, Ui.lw());
        page.addView(vsp(8));

        LinearLayout winAll = new LinearLayout(this);
        winAll.setOrientation(LinearLayout.HORIZONTAL);
        winAll.addView(hvacBtn("全车升起", new Runnable() {
            @Override public void run() { vdWindow(Vd.WIN_ALL, Vd.WIN_UP); }
        }));
        winAll.addView(hsp(8));
        winAll.addView(hvacBtn("全车降下", new Runnable() {
            @Override public void run() { vdWindow(Vd.WIN_ALL, Vd.WIN_DOWN); }
        }));
        page.addView(winAll, Ui.lw());
        page.addView(vsp(8));

        page.addView(windowRow("主驾", Vd.WIN_FL), Ui.lw());
        page.addView(vsp(6));
        page.addView(windowRow("副驾", Vd.WIN_FR), Ui.lw());
        page.addView(vsp(6));
        page.addView(windowRow("左后", Vd.WIN_RL), Ui.lw());
        page.addView(vsp(6));
        page.addView(windowRow("右后", Vd.WIN_RR), Ui.lw());

        page.addView(vsp(16));
        TextView tailTitle = Ui.text(this, 16, Ui.D_TEXT, Typeface.BOLD, 1);
        tailTitle.setText("尾门");
        page.addView(tailTitle, Ui.lw());
        page.addView(vsp(8));
        LinearLayout tailRow = new LinearLayout(this);
        tailRow.setOrientation(LinearLayout.HORIZONTAL);
        tailRow.addView(hvacBtn("打开尾门", new Runnable() {
            @Override public void run() { vdTail(Vd.TAIL_OPEN); }
        }));
        tailRow.addView(hsp(8));
        tailRow.addView(hvacBtn("关闭尾门", new Runnable() {
            @Override public void run() { vdTail(Vd.TAIL_CLOSE); }
        }));
        page.addView(tailRow, Ui.lw());

        sv.addView(page, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        pullHvacState();
        return sv;
    }

    private View hvacBtn(String name, Runnable action) {
        TextView b = Ui.darkButton(this, name, 13, Ui.D_BTN, Ui.D_TEXT);
        Ui.click(b, action);
        LinearLayout.LayoutParams lp = Ui.weighted(1f, ViewGroup.LayoutParams.WRAP_CONTENT);
        b.setLayoutParams(lp);
        return b;
    }

    private View stepperRow(String label, TextView valueTv, Runnable minus, Runnable plus) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = Ui.text(this, 13, Ui.D_TEXT, Typeface.NORMAL, 1);
        t.setText(label);
        row.addView(t, Ui.weighted(1f, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView btnMinus = Ui.darkButton(this, "－", 16, Ui.D_BTN, Ui.D_TEXT);
        Ui.click(btnMinus, minus);
        row.addView(btnMinus, Ui.ww());
        row.addView(hsp(8));
        row.addView(valueTv, Ui.ww());
        row.addView(hsp(8));
        TextView btnPlus = Ui.darkButton(this, "＋", 16, Ui.D_BTN, Ui.D_TEXT);
        Ui.click(btnPlus, plus);
        row.addView(btnPlus, Ui.ww());
        return row;
    }

    private View windowRow(String name, final int win) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = Ui.text(this, 13, Ui.D_TEXT, Typeface.NORMAL, 1);
        t.setText(name);
        row.addView(t, Ui.weighted(1f, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView up = Ui.darkButton(this, "升起", 13, Ui.D_BTN, Ui.D_TEXT);
        Ui.click(up, new Runnable() {
            @Override public void run() { vdWindow(win, Vd.WIN_UP); }
        });
        row.addView(up, Ui.ww());
        row.addView(hsp(8));
        TextView down = Ui.darkButton(this, "降下", 13, Ui.D_BTN, Ui.D_TEXT);
        Ui.click(down, new Runnable() {
            @Override public void run() { vdWindow(win, Vd.WIN_DOWN); }
        });
        row.addView(down, Ui.ww());
        return row;
    }

    private void refreshHvacLabels() {
        if (tvHvacDriverTemp != null) tvHvacDriverTemp.setText(hvacDriverTemp + "℃");
        if (tvHvacCopilotTemp != null) tvHvacCopilotTemp.setText(hvacCopilotTemp + "℃");
        if (tvHvacFan != null) tvHvacFan.setText(String.valueOf(hvacFan));
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private void vdHvac(final int cmd, final int value) {
        new Thread(new Runnable() {
            @Override public void run() {
                Vd v = Vd.connect(MainActivity.this);
                if (v == null || !v.ok()) {
                    ui.post(new Runnable() {
                        @Override public void run() { toast("空调总线未连接：" + (v == null ? "null" : v.lastError)); }
                    });
                    return;
                }
                v.setHvac(cmd, value);
            }
        }, "vd-hvac").start();
    }

    private void vdWindow(final int window, final int action) {
        new Thread(new Runnable() {
            @Override public void run() {
                Vd v = Vd.connect(MainActivity.this);
                if (v == null || !v.ok()) {
                    ui.post(new Runnable() {
                        @Override public void run() { toast("车窗总线未连接：" + (v == null ? "null" : v.lastError)); }
                    });
                    return;
                }
                v.setWindow(window, action);
            }
        }, "vd-win").start();
    }

    private void vdTail(final int action) {
        new Thread(new Runnable() {
            @Override public void run() {
                Vd v = Vd.connect(MainActivity.this);
                if (v == null || !v.ok()) {
                    ui.post(new Runnable() {
                        @Override public void run() { toast("尾门总线未连接：" + (v == null ? "null" : v.lastError)); }
                    });
                    return;
                }
                v.setTailgate(action);
            }
        }, "vd-tail").start();
    }

    private void pullHvacState() {
        new Thread(new Runnable() {
            @Override public void run() {
                Vd v = Vd.connect(MainActivity.this);
                if (v == null || !v.ok()) return;
                final int d = v.getHvac(Vd.HVAC_TEMP_DRIVER);
                final int c = v.getHvac(Vd.HVAC_TEMP_COPILOT);
                final int f = v.getHvac(Vd.HVAC_FAN);
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (d >= 16 && d <= 32) hvacDriverTemp = d;
                        if (c >= 16 && c <= 32) hvacCopilotTemp = c;
                        if (f >= 1 && f <= 8) hvacFan = f;
                        refreshHvacLabels();
                    }
                });
            }
        }, "vd-hvac-get").start();
    }

    // ---------- 盲区页 ----------

    private View buildBlindSpotPage() {
        LinearLayout page = Ui.darkCard(this, 12);
        page.setGravity(Gravity.CENTER);
        TextView t = Ui.text(this, 18, Ui.D_TEXT_SUB, Typeface.NORMAL, 2);
        t.setGravity(Gravity.CENTER);
        t.setText("盲区监控\n功能开发中…");
        page.addView(t, Ui.lw());
        return page;
    }

    // ---------- 记录仪页 ----------

    private View buildDashcamPage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackground(Ui.darkBg(this, Ui.D_CARD, 12));
        int pad = Ui.dp(this, 12);
        page.setPadding(pad, pad, pad, pad);

        TextView title = Ui.text(this, 16, Ui.D_TEXT, Typeface.BOLD, 1);
        title.setText("行车记录仪");
        page.addView(title, Ui.lw());
        page.addView(vsp(10));

        dashcamPreview = new TextureView(this);
        dashcamPreview.setBackground(Ui.darkBg(this, Ui.D_FIELD, 8));
        page.addView(dashcamPreview, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        page.addView(vsp(10));

        // 控制行：胶囊开关
        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER_VERTICAL);

        controls.addView(makeSwitchRow("录制", false, new Runnable() {
            @Override public void run() { toast("录制功能开发中"); }
        }));
        controls.addView(hsp(20));
        controls.addView(makeSwitchRow("水印", true, new Runnable() {
            @Override public void run() { toast("水印功能开发中"); }
        }));
        controls.addView(hsp(20));
        controls.addView(makeSwitchRow("循环录制", true, new Runnable() {
            @Override public void run() { toast("循环录制功能开发中"); }
        }));
        page.addView(controls, Ui.lw());

        initDashcamCamera();
        return page;
    }

    /** 一行：标签 + 胶囊开关 */
    private View makeSwitchRow(String label, boolean on, Runnable onToggle) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = Ui.text(this, 13, Ui.D_TEXT, Typeface.NORMAL, 1);
        t.setText(label);
        row.addView(t, Ui.ww());
        row.addView(hsp(6));
        Ui.CapsuleSwitch sw = new Ui.CapsuleSwitch(this);
        sw.setChecked(on);
        sw.setOnToggle(onToggle);
        row.addView(sw, Ui.ww());
        return row;
    }

    // ---------- 记录仪相机 ----------

    private void initDashcamCamera() {
        if (camInited) return;
        camThread = new HandlerThread("dashcam-cam");
        camThread.start();
        camHandler = new Handler(camThread.getLooper());
        camInited = true;
        dashcamPreview.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                openDashcamCamera(st);
            }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) { }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) { return true; }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture st) { }
        });
    }

    private void openDashcamCamera(final SurfaceTexture st) {
        try {
            if (checkSelfPermission(android.Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        toast("需要摄像头权限：\nadb shell pm grant com.jietu.clustercast android.permission.CAMERA");
                    }
                });
                return;
            }
            CameraManager cm = (CameraManager) getSystemService(CAMERA_SERVICE);
            String[] ids = cm.getCameraIdList();
            if (ids.length == 0) return;
            String camId = ids[0];
            for (String id : ids) {
                try {
                    CameraCharacteristics cc = cm.getCameraCharacteristics(id);
                    Integer facing = cc.get(CameraCharacteristics.LENS_FACING);
                    if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                        camId = id;
                        break;
                    }
                } catch (Throwable ignored) { }
            }
            cm.openCamera(camId, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice cam) {
                    dashcamCamera = cam;
                    try {
                        st.setDefaultBufferSize(1280, 720);
                        Surface surface = new Surface(st);
                        CaptureRequest.Builder req =
                                cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                        req.addTarget(surface);
                        cam.createCaptureSession(java.util.Collections.singletonList(surface),
                                new CameraCaptureSession.StateCallback() {
                                    @Override public void onConfigured(CameraCaptureSession session) {
                                        dashcamSession = session;
                                        try {
                                            session.setRepeatingRequest(req.build(), null, camHandler);
                                        } catch (Throwable ignored) { }
                                    }
                                    @Override public void onConfigureFailed(CameraCaptureSession s) { }
                                }, camHandler);
                    } catch (Throwable ignored) { }
                }
                @Override public void onDisconnected(CameraDevice cam) { cam.close(); }
                @Override public void onError(CameraDevice cam, int error) { cam.close(); }
            }, camHandler);
        } catch (Throwable ignored) { }
    }

    private void releaseDashcamCamera() {
        try {
            if (dashcamSession != null) { dashcamSession.close(); dashcamSession = null; }
            if (dashcamCamera != null) { dashcamCamera.close(); dashcamCamera = null; }
        } catch (Throwable ignored) { }
        if (camThread != null) {
            if (dashcamPreview != null) dashcamPreview.setSurfaceTextureListener(null);
            camThread.quitSafely();
            camThread = null;
            camHandler = null;
            camInited = false;
        }
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
        CastService s = CastService.inst();
        if (s != null) {
            s.castNow();
            toast("已选定并投屏：" + name);
        } else {
            toast("已选定：" + name + "（服务启动中，稍后自动投屏）");
        }
    }

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
            cell.setLayoutParams(new AbsListView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            return cell;
        }
    }

    // ---------- 设置（覆盖到右侧内容区） ----------

    private View settingsView = null;
    private boolean inSettings = false;

    private void showSettingsOverlay() {
        if (inSettings) return;

        ScrollView sv = new ScrollView(this);
        sv.setBackground(Ui.darkBg(this, Ui.D_BG, 12));
        sv.setFillViewport(true);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 16));

        // 顶部：返回 + 标题
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
        body.addView(head, Ui.lw());
        body.addView(vsp(14));

        View divider = new View(this);
        divider.setBackgroundColor(0xFF2A3040);
        body.addView(divider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 1)));
        body.addView(vsp(16));

        // 仪表档位（两选一按钮，非开关）
        TextView lblTheme = Ui.text(this, 14, Ui.D_TEXT, Typeface.BOLD, 1);
        lblTheme.setText("仪表档位");
        body.addView(lblTheme, Ui.lw());
        body.addView(vsp(8));
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
        body.addView(themeRow, Ui.lw());
        body.addView(vsp(20));

        // 跟随前台（胶囊开关）
        LinearLayout followRow = new LinearLayout(this);
        followRow.setOrientation(LinearLayout.HORIZONTAL);
        followRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView lblFollow = Ui.text(this, 14, Ui.D_TEXT, Typeface.BOLD, 1);
        lblFollow.setText("跟随前台");
        followRow.addView(lblFollow, Ui.weighted(1, ViewGroup.LayoutParams.WRAP_CONTENT));
        final Ui.CapsuleSwitch swFollow = new Ui.CapsuleSwitch(this);
        swFollow.setChecked(cfg.followTop());
        swFollow.setOnToggle(new Runnable() {
            @Override public void run() {
                cfg.setFollowTop(swFollow.isChecked());
            }
        });
        followRow.addView(swFollow, Ui.ww());
        body.addView(followRow, Ui.lw());
        body.addView(vsp(20));

        // 权限提示
        boolean topGranted = TopApp.granted(this);
        boolean overlayOk;
        try { overlayOk = Settings.canDrawOverlays(this); } catch (Throwable t) { overlayOk = true; }
        boolean camGranted = checkSelfPermission(android.Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
        if (!topGranted || !overlayOk || !camGranted) {
            TextView lblPerm = Ui.text(this, 14, Ui.D_TEXT, Typeface.BOLD, 1);
            lblPerm.setText("权限");
            body.addView(lblPerm, Ui.lw());
            body.addView(vsp(8));
            if (!topGranted) {
                TextView tp = Ui.text(this, 12, 0xFFFF8080, Typeface.NORMAL, 4);
                tp.setText("「跟随前台」需要使用情况访问权限：\n"
                        + "adb shell pm grant com.jietu.clustercast"
                        + " android.permission.PACKAGE_USAGE_STATS");
                body.addView(tp, Ui.lw());
                body.addView(vsp(8));
            }
            if (!overlayOk) {
                TextView op = Ui.text(this, 12, 0xFFFF8080, Typeface.NORMAL, 4);
                op.setText("悬浮模式需要「显示在其他应用上层」权限：\n"
                        + "adb shell appops set com.jietu.clustercast SYSTEM_ALERT_WINDOW allow");
                body.addView(op, Ui.lw());
                body.addView(vsp(8));
            }
            if (!camGranted) {
                TextView cp = Ui.text(this, 12, 0xFFFF8080, Typeface.NORMAL, 4);
                cp.setText("「记录仪」需要摄像头权限：\n"
                        + "adb shell pm grant com.jietu.clustercast android.permission.CAMERA");
                body.addView(cp, Ui.lw());
            }
        }

        sv.addView(body, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        settingsView = sv;
        rightPanel.removeAllViews();
        rightPanel.addView(sv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        inSettings = true;
    }

    private void closeSettingsOverlay() {
        if (!inSettings) return;
        releaseDashcamCamera();
        rightPanel.removeAllViews();
        switch (currentTab) {
            case 0:
                rightPanel.addView(grid, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                break;
            case 1:
                rightPanel.addView(buildAcPage(), new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                break;
            case 2:
                rightPanel.addView(buildBlindSpotPage(), new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                break;
            case 3:
                rightPanel.addView(buildDashcamPage(), new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                break;
        }
        settingsView = null;
        inSettings = false;
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
