package com.jietu.clustercast;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * 常驻前台服务：接收车机系统自己发出的三指手势广播。
 *
 * 该广播由 system_server 里的 CarSystemGesturesManager 用
 * sendBroadcastAsUser(intent, UserHandle.ALL) 发出，不带任何接收权限：
 *   action  = android.intent.action.car_system_gesture_mode
 *   extras  = gesture(int), display(int)
 *   gesture 编码 = 手指数*100 + 方向（0左 1右）
 * 所以 300 = 三指左滑（投屏），301 = 三指右滑（退出）。普通应用注册个接收器
 * 就能收到，不需要 root、不需要 Shizuku、也不需要开无障碍。
 *
 * v12 形态（用户纠正后的还原）：投到仪表的是「自选软件」——
 *   · 三指左滑：把当前前台应用投到仪表屏（跟随前台，拿不到就用选定的目标）
 *   · 三指右滑：退出投屏，投着的退回主屏（不杀进程），仪表还原原显示模式，
 *     同时解除对原车高德（psmap）的禁用
 *   · 投着 A 时主屏打开 B、再三指左滑：B 上仪表，A 退回主屏，可在主屏再打开
 *   · 投第三方软件期间禁用原车高德防抢仪表；退出时恢复
 *
 * v13（实车反馈四项）：
 *   1. 档位可选：默认导航模式，极简可选（MainActivity 档位行 → Cfg.castTheme）
 *   2. 修「被原车压住」：psmap 活着时在 display 2 有全屏 overlay，谁也盖不过它。
 *      投屏开始先解禁高德让它重新启动、把 DisplayCluster=true 锁存进 QNX，
 *      等一拍再禁用 —— overlay 随进程消失，锁存还在，投屏才透得出来
 *   3. 守卫只在投屏期跑：v12 的守卫常驻，退出后跟恢复的 psmap 抢总线，
 *      原车地图还原不回去。v13 投第三方时启动，退出/投原车高德时停止
 *   4. 自选应用自动关闭跟随前台（不然左滑永远投前台，选了白选）
 *
 * v14（悬浮模式，窗口机制来自 amap-companion 的 OverlayService.ensureClusterMirror）：
 *   投第三方软件优先走「仪表悬浮层 + 虚拟屏」（ClusterOverlay）：在仪表屏自己的
 *   WindowManager 上 addView 一个 TYPE_APPLICATION_OVERLAY 全屏层 —— 同层窗口
 *   后加者在上面，必然盖过原车高德的全屏 overlay；层里的 SurfaceView 绑一块
 *   仪表同分辨率的公共虚拟屏，自选 app 用 setLaunchDisplayId 启进去。app 在
 *   虚拟屏（后台）渲染，主屏随便用；原车高德不禁用、仪表模式不动、总线不碰。
 *   缺悬浮窗权限或系统拒绝往虚拟屏启应用时，自动退回 v13 搬屏路径（那时才
 *   临时禁用高德），并把真实原因写进日志。
 */
public class CastService extends Service {

    Handler mHandler;
    /** VDBus 的 bindService/getOnce 是同步 binder，绝不能在主线程跑。 */
    Handler mWork;
    Cfg mCfg;
    long mLastGestureAt = 0L;
    private LogSink mSink = null;
    private final StringBuilder mLogBuf = new StringBuilder();
    private final SimpleDateFormat mTime = new SimpleDateFormat("HH:mm:ss", java.util.Locale.US);

    public interface LogSink { void onLog(String s); }

    /** 静态嵌套类风格的接收器：只持弱引用，服务没了就静默丢弃。 */
    public static class GestureRx extends BroadcastReceiver {
        private final WeakReference<CastService> ref;
        GestureRx(CastService s) { ref = new WeakReference<>(s); }
        @Override public void onReceive(Context context, Intent intent) {
            final CastService s = ref.get();
            if (s == null) return;
            int g = intent.getIntExtra("gesture", -1);
            int disp = intent.getIntExtra("display", -1);
            if (g != G_3_LEFT && g != G_3_RIGHT) return;
            s.log("收到三指手势 " + g + "（来源屏 " + disp + "）");
            long now = System.currentTimeMillis();
            synchronized (s) {
                if (now - s.mLastGestureAt < DEBOUNCE_MS) return;
                s.mLastGestureAt = now;
            }
            if (disp != 0 && disp != -1) return;   // 只认主屏上的手势
            s.mWork.post(g == G_3_LEFT ? s.mCast : s.mExit);
        }
    }

    private final Runnable mCast = new Runnable() {
        @Override public void run() { castFromGesture(); }
    };
    private final Runnable mExit = new Runnable() {
        @Override public void run() { exitFromGesture(); }
    };

    /** 给界面上的按钮用：动作一律丢到工作线程，总线调用不能卡主线程。 */
    public void castNow() { mWork.post(mCast); }
    public void exitNow() { mWork.post(mExit); }

    @Override public void onCreate() {
        super.onCreate();
        sInst = this;
        mHandler = new Handler(Looper.getMainLooper());
        HandlerThread ht = new HandlerThread("cast-work");
        ht.start();
        mWork = new Handler(ht.getLooper());
        mCfg = new Cfg(this);
        mOverlay = new ClusterOverlay(this);
        startForegroundNotice();
        registerReceiver(new GestureRx(this), new IntentFilter(GESTURE_ACTION));
        mWork.post(new Runnable() {
            @Override public void run() {
                Vd v = Vd.connect(CastService.this);
                if (!v.ok()) {
                    log("车身总线连不上，投屏仍可用（" + v.bindReport + "）");
                    return;
                }
                log("已连上车身总线：" + v.bindReport);
                mWork.postDelayed(mThemeRetry, 1500);
                // v13：导航区接管守卫不再常驻 —— 只在投第三方软件期间运行，
                // 退出投屏即停。常驻会和恢复后的原车高德抢总线，地图还原不回去。
            }
        });
        log("服务已启动，正在监听三指手势广播");
        // 投屏槽位是内存态，进程重启就没了。若上次是我们禁了高德但进程中途被杀，
        // 这里自愈恢复，别让高德一直躺尸。
        if (mCfg.amapOffByUs()) {
            mWork.post(new Runnable() {
                @Override public void run() { mAmapDisabledByUs = true; restoreAmap(); }
            });
        }
    }

    private int mThemeTries = 0;
    private final Runnable mThemeRetry = new Runnable() {
        @Override public void run() {
            Vd v = Vd.inst();
            if (v == null) return;
            int t = v.getThemeViaProxy();
            if (t < 0) t = v.getTheme();
            if (t >= 0) { mThemeSeen = t; log("当前仪表模式：" + Vd.themeName(t)); return; }
            if (++mThemeTries < 5) mWork.postDelayed(this, 1500);
        }
    };

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        startForegroundNotice();
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDestroy() {
        sInst = null;
        if (mOverlay != null && mCastViaOverlay) mOverlay.teardown();
        super.onDestroy();
    }

    // ---------- 动作 ----------

    /** 进投屏前的仪表主题，退出时要还回去；-1 表示当前不在投屏中。 */
    int mThemeBefore = -1;

    /** 仪表屏上唯一那个投屏槽位：当前投的是哪个包，null = 没投。 */
    volatile String mCastPkg = null;

    public String castingPkg() { return mCastPkg; }

    /** 是我们自己禁的高德，退出时才允许恢复，别把用户手动禁的状态改回去。 */
    boolean mAmapDisabledByUs = false;

    /** 这次投屏走的是「原车导航通道」：没开新实例，退出时也别去动主屏那份。 */
    boolean mNavFollow = false;

    /** v14 主路径组件：仪表悬浮层 + 虚拟屏投屏（借鉴 amap-companion 窗口机制）。 */
    ClusterOverlay mOverlay;

    /** 这次投屏走的是悬浮路径（成了就不用还原任何原车状态，退出只撤悬浮层）。 */
    boolean mCastViaOverlay = false;

    /** 仪表盘导航区占领守卫：周期性地往 CAR_LAN 总线发状态，防止高德后台吐状态盖住投屏内容。 */
    private volatile Runnable mNavGuardTick = null;
    private final Object mNavGuardLock = new Object();

    /**
     * 三指左滑：把「当前前台应用」投到仪表屏，仪表永远只有一个槽位。
     *   · 只有滑动才换，主屏点应用绝不自作主张投屏
     *   · 已投着 A 再投 B：A 退回主屏（不 finish、不杀进程，酷狗的歌不停），B 上仪表
     *   · 退到主屏的 A 之后照常能点
     *   · 右滑退出：投着的退回主屏，仪表还原原模式
     */
    void castFromGesture() {
        String[] t = target();
        if (t == null) { log("还没选投屏应用，先打开「仪表投屏」选一个"); return; }
        if (getPackageName().equals(t[0])) { log("前台是我们自己的设置页，不投它"); return; }
        if (t[0].equals(mCastPkg)) { log("「" + label(t[0]) + "」已经在仪表上了"); return; }
        if (t[0].equals(Caster.AMAP)) {
            // 投的就是原车高德：走原车导航通道，不搬窗口，仪表跟着主屏走。
            // 原车高德自己管理仪表，守卫必须先停，别跟它抢总线（v13）。
            stopNavGuard();
            if (mCastViaOverlay) {
                mOverlay.teardown();
                mCastViaOverlay = false;
                mCastPkg = null;
                log("已撤掉仪表悬浮层，交还原车导航通道");
            }
            switchThemeForCast(Vd.THEME_NAVI);
            restoreAmap();
            retireCurrent();
            openNavChannel();
            mNavFollow = true;
            mCastPkg = t[0];
            log("已投屏 " + label(t[0]) + "（原车导航通道，仪表跟随主屏）");
            return;
        }
        // v14 主路径：仪表悬浮层 + 虚拟屏 —— 原车高德不禁、仪表模式不动、总线不碰，
        // app 在虚拟屏（后台）渲染，主屏随便用。返回 false=预检失败才走降级。
        if (tryOverlayCast(t)) return;
        fallbackCast(t);
    }

    /**
     * v14 主路径尝试。返回 false=悬浮路径预检就过不了（没权限/没仪表屏），
     * 调用方直接走降级；返回 true=已受理，成败走异步回调 onOverlayCastResult。
     */
    private boolean tryOverlayCast(final String[] t) {
        String e = ClusterOverlay.precheck(this);
        if (e != null) { log("悬浮路径用不了：" + e); return false; }
        if (!mCastViaOverlay) {
            // 从 v13 状态干净退场：守卫/搬屏/主题/禁用的高德全还原，让原车恢复
            // 原样 —— 悬浮层后加在同层之上，原车内容照跑，投的画面浮在它上面
            stopNavGuard();
            retireCurrent();
            restoreTheme();
            restoreAmap();
        }
        mOverlay.cast(t[0], t[1], new ClusterOverlay.Callback() {
            @Override public void onResult(boolean ok, String err) {
                mWork.post(new Runnable() {
                    @Override public void run() { onOverlayCastResult(t, ok, err); }
                });
            }
        });
        return true;
    }

    /** 悬浮投屏的异步结果：成了记账，败了撤干净自动降级到 v13 搬屏。 */
    private void onOverlayCastResult(String[] t, boolean ok, String err) {
        if (ok) {
            mCastViaOverlay = true;
            mCastPkg = t[0];
            log("已投屏 " + label(t[0]) + "（仪表悬浮：原车高德和仪表模式都没动，主屏随便用）");
            return;
        }
        log("悬浮投屏失败：" + err);
        if (mCastViaOverlay) {
            mOverlay.teardown();
            mCastViaOverlay = false;
            mCastPkg = null;
        }
        log("自动改走搬屏模式（投屏期间临时禁用原车高德，退出自动恢复）");
        fallbackCast(t);
    }

    /** v13 搬屏降级路径：app 搬到仪表 display 2 + 临时禁用高德防压屏，退出时还原。 */
    private void fallbackCast(String[] t) {
        switchThemeForCast(-1);
        retireCurrent();
        startNavGuard();
        applyCastFill();
        disableAmapForCast();
        String err = Caster.startOnDisplay(this, t[0], t[1], Caster.CLUSTER);
        if (err != null) {
            String e2 = Caster.startOnDisplay(this, t[0], t[1], Caster.CLUSTER, true);
            if (e2 == null) log("独立实例起不来，改整实例搬屏（主屏那份会跟过去）");
            err = e2;
        }
        if (err == null) {
            mCastViaOverlay = false;
            mCastPkg = t[0];
            log("已投屏 " + label(t[0]));
        } else {
            log("投屏失败 " + label(t[0]) + "：" + err);
            log("仪表屏状态：" + Caster.describeDisplays(this));
        }
    }

    /** 把上一个投在仪表上的应用退回主屏，不杀进程。 */
    private void retireCurrent() {
        String prev = mCastPkg;
        mCastPkg = null;
        boolean follow = mNavFollow;
        mNavFollow = false;
        if (prev == null) return;
        if (follow) { closeNavChannel(); log("原车导航通道已断开，仪表还原原模式"); return; }
        if (Caster.moveToDisplay(this, prev, Caster.MAIN)) {
            log(label(prev) + " 已退回主屏（没杀进程）");
        } else {
            log(label(prev) + " 退回主屏失败，可能还留在仪表上");
        }
    }

    /**
     * 按界面上选的那一档切仪表模式，并回读校验。force 非空时用它覆盖选择
     * （原车导航通道必须是导航档，极简档下仪表不画地图）。
     * 极简档 = 仪表基本不画东西，投上去的软件就是整块画面。
     */
    private void switchThemeForCast(int force) {
        int want = force > 0 ? force : mCfg.castTheme();
        Vd v = Vd.connect(this);
        if (!v.ok()) { log("总线没连上，仪表模式不变，直接把应用放上去"); return; }
        if (mThemeBefore < 0) mThemeBefore = readTheme(v);
        v.lastPath = 0;
        v.setTheme(want);
        int back = readBack(v);
        mThemeSeen = back;
        String name = Vd.themeName(want);
        if (back == want) {
            log("仪表已切到" + name + "（原=" + Vd.themeName(mThemeBefore)
                    + "，通道=" + pathName(v.lastPath) + "）");
        } else {
            log("切" + name + "没生效，仪表还停在" + Vd.themeName(back)
                    + "（通道=" + pathName(v.lastPath) + "）");
        }
    }

    /**
     * 打开/关闭原车导航投屏通道（CAR_LAN 721699 + 721702）。
     * 这两条就是原车自己在发的：导航投屏不搬窗口，仪表由 psmap 的渲染器订阅总线来画，
     * 所以主屏导航照常，仪表同时跟随。总线拒了就把真实原因写日志，绝不假报成功。
     */
    private void openNavChannel() {
        Vd v = Vd.connect(this);
        if (!v.ok()) { log("总线没连上，导航通道开不了"); return; }
        String e1 = v.publishNaviCluster(true);
        String e2 = v.publishNaviArea(true);
        if (e1 != null || e2 != null)
            log("导航投屏通道下发有问题：721699=" + (e1 == null ? "已发" : e1)
                    + "，721702=" + (e2 == null ? "已发" : e2));
        else log("已向总线发出「导航上仪表」请求（721699+721702）");
    }

    private void closeNavChannel() {
        Vd v = Vd.inst();
        if (v == null || !v.ok()) return;
        v.publishNaviCluster(false);
        v.publishNaviArea(false);
    }

    /**
     * 启动「仪表导航区接管守卫」。周期性地往 CAR_LAN 总线发送：
     *   • 721699 VDNaviDisplayCluster (DisplayCluster="true")
     *   • 721702 VDNaviDisplayArea (NaviDisplayArea=1)
     *   • 721698 VDNaviRoadInfo (segRemainDis=0, roadName="ClusterCast", progress=0)
     *   • 721701 VDNaviDigitalInfo (TBT 清空：cameraType=3, naviStatus=4)
     * 防止高德后台吐「地图信息准备中」盖住投屏。投原车高德时放行。
     * v13：只在投第三方软件期间运行；退出投屏 / 投原车高德时 stopNavGuard() 停掉，
     * 让恢复后的 psmap 独占总线把原车地图画回去。
     */
    private void startNavGuard() {
        if (!mCfg.suppressNaviCluster()) return;
        synchronized (mNavGuardLock) {
            if (mNavGuardTick != null) return;
            mNavGuardTick = new Runnable() {
                @Override public void run() {
                    if (mNavFollow) { mWork.postDelayed(this, 3000); return; }  // 投原车导航时放行
                    Vd v = Vd.inst();
                    if (v != null && v.ok()) {
                        String e0 = v.publishNaviDisplayJson(true);          // DisplayCluster=true
                        String e1 = v.publishNaviAreaNormal();               // Area=1
                        String e2 = v.publishNaviRoad(0, 0, 0, "ClusterCast", "Ready", 0, 0);
                        String e3 = v.publishNaviDigital(3, 4, 0, -1);       // TBT cleared
                        if (e0 != null || e1 != null || e2 != null || e3 != null)
                            log("导航区接管失败：" + (e0 == null?"ok":e0)
                                    + " / " + (e1 == null?"ok":e1)
                                    + " / " + (e2 == null?"ok":e2)
                                    + " / " + (e3 == null?"ok":e3));
                    }
                    mWork.postDelayed(this, 3000);
                }
            };
            mWork.post(mNavGuardTick);
        }
        log("仪表导航区接管已启动（3 秒一次）");
    }

    /**
     * v13：停掉导航区接管守卫。退出投屏 / 投原车高德时调用 ——
     * 高德恢复后要自己往总线吐状态画地图，守卫再发假数据就是抢它的总线，
     * 「退出还原车地图」就不成立了。removeCallbacks 把排队的下一次 tick 一并撤销。
     */
    private void stopNavGuard() {
        synchronized (mNavGuardLock) {
            if (mNavGuardTick == null) return;
            mWork.removeCallbacks(mNavGuardTick);
            mNavGuardTick = null;
        }
        log("仪表导航区接管已停止");
    }

    /**
     * 投屏第三方软件时禁用原车高德（用户要求：投屏禁用、退出解除，全自动对称操作）。
     * v13 核心时序 —— 若高德此刻处于我们禁出来的状态（进程重启后自愈还没跑到等），
     * 必须「先解禁 → 等 1.8 秒 → 再禁」：解禁让 psmap 重新启动并往 QNX 锁存
     * DisplayCluster=true（仪表矩形通道打开），再禁掉它让全屏 overlay 消失 ——
     * 锁存还在，投屏画面才盖得上去；对一个已死的 psmap「禁用」等于什么都没做，
     * 仪表照旧被原车内容压住。进程中途被杀没人恢复的问题用 amapOffByUs 落盘
     * + onCreate 自愈兜底。
     */
    private void disableAmapForCast() {
        if (mAmapDisabledByUs) return;
        if (mCfg.amapOffByUs()) {
            String e = Caster.setAmapEnabled(this, true);
            log(e == null ? "先解禁原车高德，让仪表通道重新锁存后再禁"
                    : "解禁原车高德失败：" + e);
            if (e == null) {
                try { Thread.sleep(1800); } catch (InterruptedException ignored) { }
            }
        }
        String err = Caster.setAmapEnabled(this, false);
        if (err == null) {
            mAmapDisabledByUs = true;
            mCfg.setAmapOffByUs(true);
            log("已禁用原车高德（它的仪表 overlay 会消失，投屏不再被压；退出投屏自动恢复）");
        } else {
            log("禁用原车高德失败：" + err + "（它若还活着，全屏 overlay 会压住投屏画面）");
        }
    }

    /**
     * 恢复原车高德。只恢复「我们自己禁的」状态；没禁过就什么都不动。
     */
    private void restoreAmap() {
        if (!mAmapDisabledByUs && !mCfg.amapOffByUs()) return;
        mAmapDisabledByUs = false;
        mCfg.setAmapOffByUs(false);
        String err = Caster.setAmapEnabled(this, true);
        log(err == null ? "已恢复原车高德" : "恢复高德失败：" + err);
    }

    /** 三指右滑：投着的退回主屏，仪表退回原显示模式，解除禁用的高德。 */
    void exitFromGesture() {
        if (mCastViaOverlay) {
            // v14 悬浮模式：撤悬浮层、销虚拟屏（里面的任务随之销毁）。
            // 原车高德、仪表模式从头到尾没动，什么都不用还原。
            mOverlay.teardown();
            mCastViaOverlay = false;
            String prev = mCastPkg;
            mCastPkg = null;
            log(prev != null
                    ? "已退出投屏（" + label(prev) + " 的悬浮层已撤，仪表还回原车内容）"
                    : "已退出投屏");
            return;
        }
        stopNavGuard();
        retireCurrent();
        restoreTheme();
        restoreAmap();
        log("已退出投屏，仪表退回原显示模式");
    }

    private void restoreTheme() {
        int back = mThemeBefore;
        mThemeBefore = -1;
        if (back < 0) return;
        Vd v = Vd.inst();
        if (v == null) return;
        v.setTheme(back);
        mThemeSeen = readBack(v);
    }

    private int readTheme(Vd v) {
        int t = v.getThemeViaProxy();
        if (t < 0) t = v.getTheme();
        mThemeSeen = t;
        return t;
    }

    /** 界面上显示的当前仪表主题，由工作线程刷新。 */
    volatile int mThemeSeen = -1;

    public int themeSeen() { return mThemeSeen; }

    /**
     * 整屏填充：startOnDisplay 里已把仪表屏整块物理区域写成 launchBounds，
     * 这里把要写的区域照原样记进日志。这台 ROM 忽略 launchBounds 的话，
     * 投完还是原来的小窗，日志一对尺寸就知道 —— 绝不用「已铺满」三个字糊过去。
     */
    void applyCastFill() {
        if (!mCfg.castFill()) return;
        Rect r = Caster.fullBounds(this, Caster.CLUSTER);
        log(r == null ? "没读到仪表屏尺寸，这次投屏沿用系统默认区域"
                : "投屏区域已写成仪表整屏 " + r.width() + "x" + r.height()
                        + "（Z 序由系统决定，量出来没铺满会照实写）");
    }

    /** 优先跟随前台应用（需使用情况访问权限），没授权就用上次选定的目标。 */
    String[] target() {
        if (mCfg.followTop()) {
            String pkg = TopApp.get(this);
            if (pkg != null && !pkg.equals(getPackageName())) {
                String cls = Caster.launchable(this, pkg);
                if (cls != null) return new String[]{pkg, cls};
                log(pkg + " 没有可启动的界面，改用上次的目标");
            } else {
                log(TopApp.granted(this)
                        ? "没抓到前台应用（桌面或自身），改用上次的目标"
                        : "没给使用情况访问权限，取不到前台，改用上次的目标");
            }
        }
        String pkg = mCfg.pkg();
        String cls = mCfg.cls();
        if (pkg == null || cls == null) return null;
        return new String[]{pkg, cls};
    }

    public String label(String pkg) {
        try {
            android.content.pm.ApplicationInfo ai =
                    getPackageManager().getApplicationInfo(pkg, 0);
            return getPackageManager().getApplicationLabel(ai).toString();
        } catch (Throwable t) { return pkg; }
    }

    // ---------- 日志 ----------

    public void setSink(LogSink s) {
        mSink = s;
        if (s != null) s.onLog(mLogBuf.toString());
    }

    public void log(String s) {
        Log.i(TAG, s);
        String line = mTime.format(new Date()) + "  " + s + "\n";
        synchronized (mLogBuf) {
            mLogBuf.append(line);
            if (mLogBuf.length() > 8000) mLogBuf.delete(0, mLogBuf.length() - 6000);
        }
        final LogSink sink = mSink;
        if (sink == null) return;
        mHandler.post(new Runnable() {
            @Override public void run() { sink.onLog(mLogBuf.toString()); }
        });
    }

    public String logText() {
        synchronized (mLogBuf) { return mLogBuf.toString(); }
    }

    private void startForegroundNotice() {
        NotificationManager nm =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        String ch = "cast";
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                    new NotificationChannel(ch, "仪表投屏", NotificationManager.IMPORTANCE_MIN));
        }
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, ch) : new Notification.Builder(this);
        b.setContentTitle("仪表投屏运行中")
                .setContentText("三指左滑投屏 · 三指右滑退出")
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentIntent(pi);
        startForeground(42, b.build());
    }

    static final String TAG = "ClusterCast";
    static final String GESTURE_ACTION = "android.intent.action.car_system_gesture_mode";
    static final int G_3_LEFT = 300;
    static final int G_3_RIGHT = 301;
    private static final long DEBOUNCE_MS = 800L;

    /** 原车下发后也是延迟回读的，总线要一点时间才落到 MCU。 */
    static int readBack(Vd v) {
        try { Thread.sleep(250); } catch (InterruptedException ignored) { }
        int t = v.getThemeViaProxy();
        if (t >= 0) return t;
        t = v.getTheme();
        if (t >= 0) return t;
        try { Thread.sleep(400); } catch (InterruptedException ignored) { }
        t = v.getThemeViaProxy();
        return t >= 0 ? t : v.getTheme();
    }

    static String pathName(int p) {
        switch (p) {
            case 3: return "VDBus+Proxy";
            case 2: return "Proxy";
            case 1: return "VDBus";
            default: return "无";
        }
    }

    private static volatile CastService sInst = null;

    public static CastService inst() { return sInst; }

    public static void start(Context c) {
        Intent i = new Intent(c, CastService.class);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
        else c.startService(i);
    }
}
