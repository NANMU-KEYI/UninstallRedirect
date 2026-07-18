package com.smyprl.uninstallredirect;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import android.view.MenuItem;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.util.List;

import io.github.libxposed.api.XposedModule;

public class MainHook extends XposedModule {

    private static final String TAG = "UninstallRedirect";
    private static final String INSTALLER_PKG = "com.miui.packageinstaller";

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        Log.d(TAG, "模块已加载");
    }

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        String pkg = param.getPackageName();
        if ("com.miui.home".equals(pkg)) {
            Log.d(TAG, ">>> 桌面进程加载");
            ClassLoader cl = param.getDefaultClassLoader();
            hookUninstallApp(cl);
            hookShowDialog(cl);
        } else if ("com.miui.securitycenter".equals(pkg)) {
            Log.d(TAG, ">>> 安全中心进程加载");
            ClassLoader cl = param.getDefaultClassLoader();
            hookSecurityCenterFull(cl);
        } else if ("com.miui.securitymanager".equals(pkg) || "com.miui.cleanmaster".equals(pkg)) {
            Log.d(TAG, ">>> 手机管家/清理进程加载: " + pkg);
            ClassLoader cl = param.getDefaultClassLoader();
            hookSecurityManager(cl);
        }
    }

    // ==================== 桌面：拖拽卸载 ====================
    private void hookUninstallApp(ClassLoader cl) {
        try {
            Class<?> ctrlClass = cl.loadClass("com.miui.home.launcher.uninstall.UninstallController");
            Class<?> shortcutClass = cl.loadClass("com.miui.home.launcher.ShortcutInfo");
            Method method = ctrlClass.getDeclaredMethod("uninstallApp", shortcutClass);

            hook(method).intercept(chain -> {
                Object shortcut = chain.getArgs().get(0);
                String pkgName = extractPackageName(shortcut);
                Log.d(TAG, "uninstallApp 拦截 (拖拽): " + pkgName);
                if (pkgName != null) startInstallerX(pkgName);
                return false;
            });
            Log.d(TAG, "[✓] Hook uninstallApp (拖拽)");
        } catch (Exception e) {
            Log.e(TAG, "Hook uninstallApp 失败: " + e.getMessage());
        }
    }

    // ==================== 桌面：长按快捷栏点击垃圾桶 ====================
    private void hookShowDialog(ClassLoader cl) {
        try {
            Class<?> ctrlClass = cl.loadClass("com.miui.home.launcher.uninstall.UninstallController");
            Class<?> launcherClass = cl.loadClass("com.miui.home.launcher.BaseLauncher");
            Method method = ctrlClass.getDeclaredMethod("showDialog", launcherClass, List.class, String.class);

            hook(method).intercept(chain -> {
                List<?> list = (List<?>) chain.getArgs().get(1);
                String pkgName = null;
                if (list != null && !list.isEmpty()) pkgName = extractPackageName(list.get(0));
                Log.d(TAG, "showDialog 拦截 (长按), pkg=" + pkgName);
                if (pkgName != null) startInstallerX(pkgName);
                return null;
            });
            Log.d(TAG, "[✓] Hook showDialog (长按)");
        } catch (Exception e) {
            Log.e(TAG, "showDialog 钩子失败: " + e.getMessage());
        }
    }

    // ==================== 安全中心：全面拦截 ====================
    private void hookSecurityCenterFull(ClassLoader cl) {
        // 1. 菜单 Hook
        try {
            Class<?> activityClass = cl.loadClass("android.app.Activity");
            Method method = activityClass.getDeclaredMethod("onMenuItemSelected", int.class, MenuItem.class);
            hook(method).intercept(chain -> {
                Activity activity = (Activity) chain.getThisObject();
                if (activity == null) return chain.proceed();
                if (!activity.getClass().getName().contains("ApplicationsDetails")) return chain.proceed();
                MenuItem item = (MenuItem) chain.getArgs().get(1);
                if (item != null && item.getTitle() != null && item.getTitle().toString().contains("卸载")) {
                    Log.d(TAG, "检测到卸载菜单点击，拦截中...");
                    String pkg = extractPkgFromActivity(activity);
                    if (pkg != null) {
                        startInstallerX(pkg);
                        return true;
                    }
                }
                return chain.proceed();
            });
            Log.d(TAG, "[✓] Hook onMenuItemSelected (安全中心菜单)");
        } catch (Exception e) {
            Log.e(TAG, "Hook onMenuItemSelected 失败: " + e.getMessage());
        }

        // 2. PackageInstaller.uninstall 全面拦截
        try {
            Class<?> pkgInstallerClass = cl.loadClass("android.content.pm.PackageInstaller");
            for (Method method : pkgInstallerClass.getDeclaredMethods()) {
                if (!"uninstall".equals(method.getName())) continue;
                Class<?>[] params = method.getParameterTypes();
                if (params.length >= 1 && params[0] == String.class) {
                    hook(method).intercept(chain -> {
                        String targetPkg = (String) chain.getArgs().get(0);
                        Log.d(TAG, "安全中心 PackageInstaller.uninstall 拦截: " + targetPkg);
                        if (targetPkg == null || targetPkg.equals(INSTALLER_PKG)) {
                            return chain.proceed();
                        }
                        startInstallerX(targetPkg);
                        return null;
                    });
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Hook PackageInstaller.uninstall 失败: " + e.getMessage());
        }

        // 3. Binder 层拦截
        hookIPackageManager(cl);

        // 4. ApplicationPackageManager.deletePackage 拦截
        try {
            Class<?> appPmClass = cl.loadClass("android.app.ApplicationPackageManager");
            for (Method method : appPmClass.getDeclaredMethods()) {
                if (!"deletePackage".equals(method.getName())) continue;
                Class<?>[] params = method.getParameterTypes();
                if (params.length >= 1 && params[0] == String.class) {
                    hook(method).intercept(chain -> {
                        String targetPkg = (String) chain.getArgs().get(0);
                        Log.d(TAG, "安全中心 AppPM.deletePackage 拦截: " + targetPkg);
                        if (targetPkg == null || targetPkg.equals(INSTALLER_PKG)) return chain.proceed();
                        startInstallerX(targetPkg);
                        for (Object arg : chain.getArgs()) {
                            if (arg != null && arg.getClass().getName().contains("IPackageDeleteObserver")) {
                                try {
                                    Method callback = arg.getClass().getMethod("packageDeleted", String.class, int.class);
                                    callback.setAccessible(true);
                                    callback.invoke(arg, targetPkg, -1);
                                } catch (Exception ignored) {}
                                break;
                            }
                        }
                        return null;
                    });
                    break;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Hook AppPM.deletePackage 失败: " + e.getMessage());
        }
    }

    // ==================== 通用：Binder 层拦截 ====================
    private void hookIPackageManager(ClassLoader cl) {
        try {
            Class<?> proxyClass = Class.forName("android.content.pm.IPackageManager$Stub$Proxy");
            boolean hooked = false;
            for (Method method : proxyClass.getDeclaredMethods()) {
                if (!"deletePackage".equals(method.getName()) && !"deletePackageAsUser".equals(method.getName())) {
                    continue;
                }
                Class<?>[] params = method.getParameterTypes();
                if (params.length >= 1 && params[0] == String.class) {
                    hook(method).intercept(chain -> {
                        String targetPkg = (String) chain.getArgs().get(0);
                        Log.d(TAG, "Binder 拦截: " + method.getName() + " -> " + targetPkg);
                        if (targetPkg == null || targetPkg.equals(INSTALLER_PKG)) {
                            return chain.proceed();
                        }
                        startInstallerX(targetPkg);
                        return null;
                    });
                    hooked = true;
                }
            }
            if (hooked) {
                Log.d(TAG, "[✓] Hook IPackageManager (Binder)");
            } else {
                Log.w(TAG, "未找到 IPackageManager Binder 方法");
            }
        } catch (Exception e) {
            Log.e(TAG, "Hook IPackageManager 失败: " + e.getMessage());
        }
    }

    // ==================== 手机管家专用：多层拦截 ====================
    private void hookSecurityManager(ClassLoader cl) {
        // 1. Runtime.exec(String) 拦截
        try {
            Class<?> runtimeClass = Class.forName("java.lang.Runtime");
            Method exec = runtimeClass.getDeclaredMethod("exec", String.class);
            hook(exec).intercept(chain -> {
                String command = (String) chain.getArgs().get(0);
                if (command != null && command.contains("pm uninstall")) {
                    String[] parts = command.split(" ");
                    for (int i = 0; i < parts.length; i++) {
                        if ("uninstall".equals(parts[i]) && i + 1 < parts.length) {
                            String targetPkg = parts[i + 1];
                            if (targetPkg != null && !targetPkg.equals(INSTALLER_PKG)) {
                                Log.d(TAG, "手机管家 Runtime.exec 拦截: " + targetPkg);
                                startInstallerX(targetPkg);
                                return createFakeProcess();
                            }
                        }
                    }
                }
                return chain.proceed();
            });
            Log.d(TAG, "[✓] Hook Runtime.exec(String) (手机管家)");
        } catch (Exception e) {
            Log.e(TAG, "Hook Runtime.exec(String) 失败: " + e.getMessage());
        }

        // 2. Runtime.exec(String[]) 拦截
        try {
            Class<?> runtimeClass = Class.forName("java.lang.Runtime");
            Method execArray = runtimeClass.getDeclaredMethod("exec", String[].class);
            hook(execArray).intercept(chain -> {
                String[] cmdArray = (String[]) chain.getArgs().get(0);
                if (cmdArray != null) {
                    String command = String.join(" ", cmdArray);
                    if (command.contains("pm uninstall")) {
                        String targetPkg = null;
                        for (int i = 0; i < cmdArray.length; i++) {
                            if ("uninstall".equals(cmdArray[i]) && i + 1 < cmdArray.length) {
                                targetPkg = cmdArray[i + 1];
                                break;
                            }
                        }
                        if (targetPkg != null && !targetPkg.equals(INSTALLER_PKG)) {
                            Log.d(TAG, "手机管家 Runtime.exec(String[]) 拦截: " + targetPkg);
                            startInstallerX(targetPkg);
                            return createFakeProcess();
                        }
                    }
                }
                return chain.proceed();
            });
            Log.d(TAG, "[✓] Hook Runtime.exec(String[]) (手机管家)");
        } catch (Exception e) {
            Log.e(TAG, "Hook Runtime.exec(String[]) 失败: " + e.getMessage());
        }

        // 3. ProcessBuilder.start() 拦截
        try {
            Class<?> pbClass = Class.forName("java.lang.ProcessBuilder");
            Method startMethod = pbClass.getDeclaredMethod("start");
            hook(startMethod).intercept(chain -> {
                ProcessBuilder pb = (ProcessBuilder) chain.getThisObject();
                List<String> command = pb.command();
                if (command != null) {
                    String cmdStr = String.join(" ", command);
                    if (cmdStr.contains("pm uninstall")) {
                        String targetPkg = null;
                        for (int i = 0; i < command.size(); i++) {
                            if ("uninstall".equals(command.get(i)) && i + 1 < command.size()) {
                                targetPkg = command.get(i + 1);
                                break;
                            }
                        }
                        if (targetPkg != null && !targetPkg.equals(INSTALLER_PKG)) {
                            Log.d(TAG, "手机管家 ProcessBuilder.start 拦截: " + targetPkg);
                            startInstallerX(targetPkg);
                            return createFakeProcess();
                        }
                    }
                }
                return chain.proceed();
            });
            Log.d(TAG, "[✓] Hook ProcessBuilder.start (手机管家)");
        } catch (Exception e) {
            Log.e(TAG, "Hook ProcessBuilder.start 失败: " + e.getMessage());
        }

        // 4. ApplicationPackageManager.deletePackage 拦截
        try {
            Class<?> appPmClass = cl.loadClass("android.app.ApplicationPackageManager");
            for (Method method : appPmClass.getDeclaredMethods()) {
                if (!"deletePackage".equals(method.getName())) continue;
                Class<?>[] params = method.getParameterTypes();
                if (params.length >= 1 && params[0] == String.class) {
                    hook(method).intercept(chain -> {
                        String targetPkg = (String) chain.getArgs().get(0);
                        Log.d(TAG, "手机管家 AppPM.deletePackage 拦截: " + targetPkg);
                        if (targetPkg == null || targetPkg.equals(INSTALLER_PKG)) return chain.proceed();
                        startInstallerX(targetPkg);
                        for (Object arg : chain.getArgs()) {
                            if (arg != null && arg.getClass().getName().contains("IPackageDeleteObserver")) {
                                try {
                                    Method callback = arg.getClass().getMethod("packageDeleted", String.class, int.class);
                                    callback.setAccessible(true);
                                    callback.invoke(arg, targetPkg, -1);
                                } catch (Exception ignored) {}
                                break;
                            }
                        }
                        return null;
                    });
                    break;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Hook AppPM.deletePackage 失败: " + e.getMessage());
        }

        // 5. Binder 层拦截（保留）
        hookIPackageManager(cl);
    }

    // ==================== 创建模拟 Process ====================
    private Process createFakeProcess() {
        return new Process() {
            @Override public java.io.InputStream getInputStream() { return new ByteArrayInputStream("Success".getBytes()); }
            @Override public java.io.OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
            @Override public java.io.InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]); }
            @Override public int waitFor() { return 0; }
            @Override public int exitValue() { return 0; }
            @Override public void destroy() {}
        };
    }

    // ==================== 启动系统默认卸载器 ====================
    private void startInstallerX(String pkgName) {
        Context ctx = getGlobalContext();
        if (ctx == null) return;
        Intent intent = new Intent(Intent.ACTION_UNINSTALL_PACKAGE);
        intent.setData(Uri.parse("package:" + pkgName));
        intent.setPackage(INSTALLER_PKG);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ctx.startActivity(intent);
            Log.d(TAG, "✓ 已启动系统默认卸载器: " + pkgName);
        } catch (Exception e) {
            Log.e(TAG, "启动失败: " + e.getMessage());
        }
    }

    // ==================== 工具方法 ====================
    private String extractPackageName(Object info) {
        if (info == null) return null;
        try {
            Method getPkg = info.getClass().getMethod("getPackageName");
            getPkg.setAccessible(true);
            return (String) getPkg.invoke(info);
        } catch (Exception ignored) {}
        try {
            Method getIntent = info.getClass().getMethod("getIntent");
            getIntent.setAccessible(true);
            Object intentObj = getIntent.invoke(info);
            if (intentObj instanceof Intent) {
                Intent intent = (Intent) intentObj;
                if (intent.getComponent() != null) return intent.getComponent().getPackageName();
                if (intent.getData() != null && "package".equals(intent.getData().getScheme()))
                    return intent.getData().getSchemeSpecificPart();
            }
        } catch (Exception ignored) {}
        try {
            Method getComp = info.getClass().getMethod("getTargetComponent");
            getComp.setAccessible(true);
            Object comp = getComp.invoke(info);
            if (comp instanceof android.content.ComponentName)
                return ((android.content.ComponentName) comp).getPackageName();
        } catch (Exception ignored) {}
        return null;
    }

    private String extractPkgFromActivity(Activity activity) {
        if (activity == null) return null;
        Intent intent = activity.getIntent();
        if (intent == null) return null;
        Uri data = intent.getData();
        if (data != null && "package".equals(data.getScheme())) return data.getSchemeSpecificPart();
        String[] keys = {"package_name", "miui.intent.extra.PACKAGE_NAME", "pkg"};
        for (String key : keys) {
            if (intent.hasExtra(key)) {
                String pkg = intent.getStringExtra(key);
                if (pkg != null && !pkg.isEmpty()) return pkg;
            }
        }
        if (intent.getComponent() != null) return intent.getComponent().getPackageName();
        return null;
    }

    private Context getGlobalContext() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Method currentMethod = activityThread.getDeclaredMethod("currentActivityThread");
            currentMethod.setAccessible(true);
            Object current = currentMethod.invoke(null);
            Method getAppMethod = activityThread.getDeclaredMethod("getApplication");
            getAppMethod.setAccessible(true);
            Object app = getAppMethod.invoke(current);
            if (app instanceof Context) return (Context) app;
        } catch (Exception e) {
            Log.e(TAG, "getGlobalContext: " + e.getMessage());
        }
        return null;
    }
}