package com.pitchedapps.frost.injectors

import android.webkit.WebView
import com.pitchedapps.frost.utils.L

/**
 * Created by Allan Wang on 2017-05-31.
 * Mapping of the available assets
 * The enum name must match the css file name
 * //TODO add folder mapping using Prefs
 */
enum class CssAssets(val folder: String = "themes") : InjectorContract {
    MATERIAL_LIGHT, MATERIAL_DARK, MATERIAL_AMOLED, MATERIAL_GLASS, CUSTOM, ROUND_ICONS("components")
    ;

    var file = "${name.toLowerCase()}.compact.css"
    var injector: JsInjector? = null

    override fun inject(webView: WebView, callback: ((String) -> Unit)?) {
        if (injector == null) {
            val content = webView.context.assets.open("css/$folder/$file").bufferedReader().use { it.readText() }
            injector = JsBuilder().css(content).build()
        }
        injector!!.inject(webView, callback)
    }

    fun reset() {
        injector = null
    }

}