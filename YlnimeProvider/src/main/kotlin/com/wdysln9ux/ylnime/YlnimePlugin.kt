package com.wdysln9ux.ylnime

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class YlnimePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Ylnime())
    }
}
