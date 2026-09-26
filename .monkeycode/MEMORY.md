# User Instruction Memory

This file records user instructions, preferences, and teachings for reference in future interactions.

## Format

### User Instruction Entry
User instruction entries should follow this format:

[User Instruction Summary]
- Date: [YYYY-MM-DD]
- Context: [Mentioned scenario or time]
- Instructions:
  - [Content of user teaching or instruction, described line by line]

### Project Knowledge Entry
Entries discovered by the Agent during task execution should follow this format:

[Project Knowledge Summary]
- Date: [YYYY-MM-DD]
- Context: Discovered by Agent while performing [specific task description]
- Category: [Operations & Deployment|Build Methods|Testing Methods|Troubleshooting & Debugging|Workflow & Collaboration|Environment Configuration]
- Instructions:
  - [Specific knowledge points, described line by line]

## Deduplication Strategy
- Before adding a new entry, check for similar or identical instructions.
- If a duplicate is found, skip the new entry or merge it with the existing one.
- When merging, update the context or date information.
- This helps avoid redundant entries and keeps the memory file tidy.

## Entries

[Project Knowledge Summary]
- Date: 2026-09-26
- Context: Discovered by Agent while fixing aapt2/javac build failures and verifying the full pipeline locally
- Category: Build Methods
- Instructions:
  - 本仓库构建走 build.ps1 六步纯系统流水线（aapt2 compile/link → javac → d8 → aapt add → zipalign → apksigner），已在 Linux 上验证通过
  - 本地无 SDK/JDK 时：apt 装 openjdk-17-jdk-headless；commandline-tools zip 解压后需 chmod +x bin/*，再 sdkmanager 装 build-tools;34.0.0 + platforms;android-34
  - EVCam（src/com/kooo）依赖 AndroidX/Glide/OkHttp，被 javac 阶段显式排除（见 build.ps1 注释），但 res 完整参与 aapt2；缺的 AndroidX attr/style 桩补在 res/values/evcam_compat.xml
  - 校验资源引用要覆盖三个维度：@ref、?attr/、style parent 链，以及 themes/styles 里 `<item name="X">` 隐式 attr 引用（第四项曾漏过导致 link 失败）
  - 推送只用默认分支 main（用户明确要求），仓库 NJS98-UI/mgtp；GitHub 不支持 -o merge_request.* push options

[Project Knowledge Summary]
- Date: 2026-09-26
- Context: 用户反馈导航栏悬在屏幕中间、所有功能页不可见后，按用户指示以 a7f38e5 重建主界面
- Category: Workflow & Collaboration
- Instructions:
  - 主界面布局基准 = a7f38e5（左右分栏：左侧栏 + rightPanel 覆盖切换），该结构经实机验证可用
  - 导航栏固定在屏幕最下方，五个 tab：投屏/空调/车窗/盲区/记录仪，点击切换 rightPanel 内容
  - 嵌套 weight 布局曾导致导航居中、内容全空，改布局时保持外层竖向 [内容 weight1 + 底部导航] 结构
  - 旧 main（含 EVCam 源码集成那条线）备份在 backup/old-main-8010b85 分支
  - versionCode 已到 16，之后每次发新包要递增，否则车机上覆盖安装会失败
