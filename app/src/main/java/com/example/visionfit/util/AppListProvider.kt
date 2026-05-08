package com.example.visionfit.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.example.visionfit.model.AppInfo

fun loadLaunchableApps(context: Context): List<AppInfo> {
    val pm = context.packageManager
    
    // Approach 1: Get apps with a launcher intent (the most common user apps)
    val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
        addCategory(Intent.CATEGORY_LAUNCHER)
    }
    val launcherApps = pm.queryIntentActivities(mainIntent, 0)
    
    // Approach 2: Get all installed packages and check for launch intents (backup for some OEMs)
    val allPackages = pm.getInstalledPackages(0)

    val appsFromLauncher = launcherApps.mapNotNull { resolveInfo ->
        val packageName = resolveInfo.activityInfo.packageName
        if (packageName == context.packageName) return@mapNotNull null
        
        val label = resolveInfo.loadLabel(pm).toString()
        val icon = resolveInfo.loadIcon(pm)
        
        AppInfo(
            packageName = packageName,
            label = label.ifBlank { packageName },
            icon = icon
        )
    }

    val appsFromPackages = allPackages.mapNotNull { pkg ->
        val packageName = pkg.packageName
        if (packageName == context.packageName) return@mapNotNull null
        
        val launchIntent = pm.getLaunchIntentForPackage(packageName) ?: return@mapNotNull null
        val appInfo = pkg.applicationInfo ?: return@mapNotNull null
        
        val label = pm.getApplicationLabel(appInfo).toString()
        val icon = pm.getApplicationIcon(appInfo)
        
        AppInfo(
            packageName = packageName,
            label = label.ifBlank { packageName },
            icon = icon
        )
    }

    // Merge and sort
    return (appsFromLauncher + appsFromPackages)
        .distinctBy { it.packageName }
        .sortedBy { it.label.lowercase() }
}
