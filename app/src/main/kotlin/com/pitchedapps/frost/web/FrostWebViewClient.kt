package com.pitchedapps.frost.web

import android.content.Context
import android.graphics.Bitmap
import android.view.KeyEvent
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.pitchedapps.frost.LoginActivity
import com.pitchedapps.frost.MainActivity
import com.pitchedapps.frost.SelectorActivity
import com.pitchedapps.frost.facebook.FACEBOOK_COM
import com.pitchedapps.frost.facebook.FbCookie
import com.pitchedapps.frost.injectors.*
import com.pitchedapps.frost.utils.L
import com.pitchedapps.frost.utils.Prefs
import com.pitchedapps.frost.utils.cookies
import com.pitchedapps.frost.utils.launchNewTask
import io.reactivex.subjects.Subject

/**
 * Created by Allan Wang on 2017-05-31.
 */
open class FrostWebViewClient(val webCore: FrostWebViewCore) : WebViewClient() {

    val refreshObservable: Subject<Boolean> = webCore.refreshObservable

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        L.i("FWV Loading $url")
//        L.v("Cookies ${CookieManager.getInstance().getCookie(url)}")
        refreshObservable.onNext(true)
        if (!url.contains(FACEBOOK_COM)) return
        if (url.contains("logout.php")) FbCookie.logout(Prefs.userId, { launchLogin(view.context) })
        else if (url.contains("login.php")) FbCookie.reset({ launchLogin(view.context) })
    }

    fun launchLogin(c: Context) {
        if (c is MainActivity && c.cookies().isNotEmpty())
            c.launchNewTask(SelectorActivity::class.java, c.cookies())
        else
            c.launchNewTask(LoginActivity::class.java)
    }

    override fun onPageFinished(view: WebView, url: String) {
        super.onPageFinished(view, url)
        L.i("Page finished $url")
        if (!url.contains(FACEBOOK_COM)) {
            refreshObservable.onNext(false)
            return
        }
        view.jsInject(JsActions.LOGIN_CHECK,
                CssAssets.ROUND_ICONS.maybe(Prefs.showRoundedIcons),
                CssHider.PEOPLE_YOU_MAY_KNOW.maybe(!Prefs.showSuggestedFriends),
                CssHider.ADS.maybe(!Prefs.showFacebookAds),
                JsAssets.HEADER_BADGES.maybe(webCore.baseEnum != null))
        onPageFinishedActions(url)
    }

    open internal fun onPageFinishedActions(url: String?) {
        injectAndFinish()
    }

    internal fun injectAndFinish() {
        L.d("Page finished reveal")
        webCore.jsInject(CssHider.HEADER,
                Prefs.themeInjector,
                JsAssets.CLICK_A.maybe(webCore.baseEnum != null),
                callback = {
                    refreshObservable.onNext(false)
                })
    }

    open fun handleHtml(html: String) {
        L.d("Handle Html")
    }

    open fun emit(flag: Int) {
        L.d("Emit $flag")
    }

    override fun shouldOverrideKeyEvent(view: WebView, event: KeyEvent): Boolean {
        L.d("Key event ${event.keyCode}")
        return super.shouldOverrideKeyEvent(view, event)
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest?): Boolean {
        L.d("Url Loading ${request?.url?.path}")
        return super.shouldOverrideUrlLoading(view, request)
    }

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest?): WebResourceResponse? {
        if (request == null || !(request.url.host?.contains(FACEBOOK_COM) ?: false)) return super.shouldInterceptRequest(view, request)
        L.v("Url intercept ${request.url.path}")
        return super.shouldInterceptRequest(view, request)
    }

}