package com.wanderwildwood.kondate.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

/** Whether a real browser (not the system WebView shell) is there to open [url]. */
fun isRealBrowserFor(context: Context, url: String): Boolean {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
    val handler = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
    return isRealBrowser(handler?.activityInfo?.packageName)
}
