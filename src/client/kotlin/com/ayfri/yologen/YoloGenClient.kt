package com.ayfri.yologen

import net.fabricmc.api.ClientModInitializer

object YoloGenClient : ClientModInitializer {
    override fun onInitializeClient() {
        DatasetCapture.register()
        AutoCapture.register()
    }
}
