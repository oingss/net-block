package com.cablebee.pkgserver;

/**
 * Entry point for app_process.
 *
 * Usage:
 *   app_process /system/bin com.cablebee.pkgserver.Main [packageName]
 *
 * With no args  → dump ALL packages, one JSON per line on stdout
 * With one arg  → dump that single package only
 *
 * Each line:
 *   {"package":"com.foo","label":"Foo","icon":"<base64-png>","apkPath":"...","apkSize":123,
 *    "enabled":true,"flags":0,"versionCode":1,"versionName":"1.0",
 *    "firstInstallTime":0,"lastUpdateTime":0,"dataDir":"/data/data/com.foo",
 *    "minSdkVersion":21,"targetSdkVersion":33}
 *
 * Error line (package not found / exception):
 *   {"package":"com.foo","error":"message"}
 */
public class Main {
    public static void main(String[] args) {
        try {
            PackageServer server = new PackageServer();
            // 解析参数：--sort=label 或 --sort=package，其余视为包名
            String sortBy = "package"; // 默认按包名
            String singlePkg = null;
            for (String arg : args) {
                if (arg.startsWith("--sort=")) {
                    sortBy = arg.substring(7);
                } else {
                    singlePkg = arg;
                }
            }
            if (singlePkg != null) {
                server.dumpPackage(singlePkg);
            } else {
                server.dumpAll(sortBy);
            }
        } catch (Throwable t) {
            System.err.println("fatal: " + t.getMessage());
            t.printStackTrace(System.err);
            System.exit(1);
        }
    }
}
