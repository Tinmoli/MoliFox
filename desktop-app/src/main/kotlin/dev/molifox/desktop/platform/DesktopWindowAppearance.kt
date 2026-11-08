// 原生透明能力只在创建窗口时确定，运行中通过内容底色切换透明度，避免修改已显示的 AWT 窗口。
package dev.molifox.desktop.platform

import java.awt.GraphicsDevice
import java.awt.GraphicsEnvironment

fun nativeWindowTransparencySupported(): Boolean {
    if (GraphicsEnvironment.isHeadless()) return false
    return GraphicsEnvironment
        .getLocalGraphicsEnvironment()
        .defaultScreenDevice
        .isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT)
}
