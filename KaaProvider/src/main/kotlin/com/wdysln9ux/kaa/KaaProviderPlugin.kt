package com.wdysln9ux.kaa

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class KaaProviderPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(KaaProvider())
    }
}
