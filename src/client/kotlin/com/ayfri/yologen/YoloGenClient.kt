package com.ayfri.yologen

import com.ayfri.yologen.config.ConfigHolder
import net.fabricmc.api.ClientModInitializer

object YoloGenClient : ClientModInitializer {
	override fun onInitializeClient() {
		ConfigHolder.load(net.minecraft.client.Minecraft.getInstance())
		DatasetCapture.register()
		AutoCapture.register()
		registerHud()
		registerCommands()
	}
}
