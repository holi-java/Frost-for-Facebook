package com.pitchedapps.frost

import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.support.annotation.StringRes
import android.support.design.widget.*
import android.support.v4.app.ActivityOptionsCompat
import android.support.v4.app.Fragment
import android.support.v4.app.FragmentManager
import android.support.v4.app.FragmentPagerAdapter
import android.support.v4.view.ViewPager
import android.support.v7.widget.Toolbar
import android.view.Menu
import android.view.MenuItem
import ca.allanwang.kau.changelog.showChangelog
import ca.allanwang.kau.searchview.SearchItem
import ca.allanwang.kau.searchview.SearchView
import ca.allanwang.kau.searchview.bindSearchView
import ca.allanwang.kau.utils.*
import co.zsmb.materialdrawerkt.builders.Builder
import co.zsmb.materialdrawerkt.builders.accountHeader
import co.zsmb.materialdrawerkt.builders.drawer
import co.zsmb.materialdrawerkt.draweritems.badgeable.primaryItem
import co.zsmb.materialdrawerkt.draweritems.badgeable.secondaryItem
import co.zsmb.materialdrawerkt.draweritems.divider
import co.zsmb.materialdrawerkt.draweritems.profile.profile
import co.zsmb.materialdrawerkt.draweritems.profile.profileSetting
import com.mikepenz.google_material_typeface_library.GoogleMaterial
import com.mikepenz.iconics.IconicsDrawable
import com.mikepenz.materialdrawer.AccountHeader
import com.mikepenz.materialdrawer.Drawer
import com.pitchedapps.frost.dbflow.loadFbCookie
import com.pitchedapps.frost.dbflow.loadFbTabs
import com.pitchedapps.frost.facebook.FbCookie
import com.pitchedapps.frost.facebook.FbCookie.switchUser
import com.pitchedapps.frost.facebook.FbTab
import com.pitchedapps.frost.facebook.PROFILE_PICTURE_URL
import com.pitchedapps.frost.fragments.WebFragment
import com.pitchedapps.frost.utils.*
import com.pitchedapps.frost.views.BadgedIcon
import com.pitchedapps.frost.web.FrostWebViewSearch
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.Disposable
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.PublishSubject
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit

class MainActivity : BaseActivity(), FrostWebViewSearch.SearchContract {

    lateinit var adapter: SectionsPagerAdapter
    val toolbar: Toolbar by bindView(R.id.toolbar)
    val viewPager: ViewPager by bindView(R.id.container)
    val fab: FloatingActionButton by bindView(R.id.fab)
    val tabs: TabLayout by bindView(R.id.tabs)
    val appBar: AppBarLayout by bindView(R.id.appbar)
    val coordinator: CoordinatorLayout by bindView(R.id.main_content)
    lateinit var drawer: Drawer
    lateinit var drawerHeader: AccountHeader
    var webFragmentObservable = PublishSubject.create<Int>()!!
    var lastPosition = -1
    val headerBadgeObservable = PublishSubject.create<String>()
    var hiddenSearchView: FrostWebViewSearch? = null
    var firstLoadFinished = false
        set(value) {
            L.d("First fragment load has finished")
            field = value
            if (value && hiddenSearchView == null) {
                hiddenSearchView = FrostWebViewSearch(this, this)
            }
        }
    var searchView: SearchView? = null

    companion object {
        const val FRAGMENT_REFRESH = 99
        /*
         * Possible responses from the SettingsActivity
         * after the configurations have changed
         */
        const val REQUEST_RESTART = 90909
        const val REQUEST_REFRESH = 80808
        const val REQUEST_NAV = 10101
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (BuildConfig.VERSION_CODE > Prefs.versionCode) {
            Prefs.versionCode = BuildConfig.VERSION_CODE
            showChangelog(R.xml.changelog, Prefs.textColor) { theme() }
        }
        setContentView(R.layout.activity_main)
        setSupportActionBar(toolbar)
        adapter = SectionsPagerAdapter(supportFragmentManager, loadFbTabs())
        viewPager.adapter = adapter
        viewPager.offscreenPageLimit = 5
        viewPager.addOnPageChangeListener(object : ViewPager.SimpleOnPageChangeListener() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                if (lastPosition == position) return
                if (lastPosition != -1) webFragmentObservable.onNext(-(lastPosition + 1))
                webFragmentObservable.onNext(position)
                lastPosition = position
            }

            override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {
                super.onPageScrolled(position, positionOffset, positionOffsetPixels)
                val delta: Float by lazy { positionOffset * (255 - 128).toFloat() }
                tabsForEachView {
                    tabPosition, view ->
                    view.setAllAlpha(when (tabPosition) {
                        position -> 255.0f - delta
                        position + 1 -> 128.0f + delta
                        else -> 128f
                    })
                }
            }
        })
        viewPager.post { webFragmentObservable.onNext(0); lastPosition = 0 } //trigger hook so title is set
        setupDrawer(savedInstanceState)
        setupTabs()
        fab.setOnClickListener { view ->
            Snackbar.make(view, "Replace with your own action", Snackbar.LENGTH_LONG)
                    .setAction("Action", null).show()
        }
        setFrostColors(toolbar, themeWindow = false, headers = arrayOf(tabs, appBar), backgrounds = arrayOf(viewPager))
    }

    fun tabsForEachView(action: (position: Int, view: BadgedIcon) -> Unit) {
        (0 until tabs.tabCount).asSequence().forEach {
            i ->
            action(i, tabs.getTabAt(i)!!.customView as BadgedIcon)
        }
    }

    fun setupTabs() {
        viewPager.addOnPageChangeListener(TabLayout.TabLayoutOnPageChangeListener(tabs))
        tabs.addOnTabSelectedListener(object : TabLayout.ViewPagerOnTabSelectedListener(viewPager) {
            override fun onTabReselected(tab: TabLayout.Tab) {
                super.onTabReselected(tab)
                currentFragment.web.scrollOrRefresh()
            }

            override fun onTabSelected(tab: TabLayout.Tab) {
                super.onTabSelected(tab)
                (tab.customView as BadgedIcon).badgeText = null
            }
        })
        headerBadgeObservable.throttleFirst(15, TimeUnit.SECONDS).subscribeOn(Schedulers.newThread())
                .map { Jsoup.parse(it) }
                .filter { it.select("[data-sigil=count]").size >= 0 } //ensure headers exist
                .map {
                    val feed = it.select("[data-sigil*=feed] [data-sigil=count]")
                    val requests = it.select("[data-sigil*=requests] [data-sigil=count]")
                    val messages = it.select("[data-sigil*=messages] [data-sigil=count]")
                    val notifications = it.select("[data-sigil*=notifications] [data-sigil=count]")
                    return@map arrayOf(feed, requests, messages, notifications).map { it?.getOrNull(0)?.ownText() }
                }
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe {
                    (feed, requests, messages, notifications) ->
                    tabsForEachView {
                        _, view ->
                        when (view.iicon) {
                            FbTab.FEED.icon -> view.badgeText = feed
                            FbTab.FRIENDS.icon -> view.badgeText = requests
                            FbTab.MESSAGES.icon -> view.badgeText = messages
                            FbTab.NOTIFICATIONS.icon -> view.badgeText = notifications
                        }
                    }
                }
        adapter.pages.forEach {
            tabs.addTab(tabs.newTab()
                    .setCustomView(BadgedIcon(this).apply {
                        iicon = it.icon
                    }))
        }
    }

    fun setupDrawer(savedInstanceState: Bundle?) {
        val navBg = Prefs.bgColor.withMinAlpha(200).toLong()
        val navHeader = Prefs.headerColor.withMinAlpha(200)
        drawer = drawer {
            toolbar = this@MainActivity.toolbar
            savedInstance = savedInstanceState
            translucentStatusBar = false
            sliderBackgroundColor = navBg
            drawerHeader = accountHeader {
                textColor = Prefs.iconColor.toLong()
                backgroundDrawable = ColorDrawable(navHeader)
                selectionSecondLineShown = false
                paddingBelow = false
                cookies().forEach { (id, name) ->
                    profile(name = name ?: "") {
                        iconUrl = PROFILE_PICTURE_URL(id)
                        textColor = Prefs.textColor.toLong()
                        selectedTextColor = Prefs.textColor.toLong()
                        selectedColor = 0x00000001.toLong()
                        identifier = id
                    }
                }
                profileSetting(nameRes = R.string.kau_logout) {
                    iicon = GoogleMaterial.Icon.gmd_exit_to_app
                    iconColor = Prefs.textColor.toLong()
                    textColor = Prefs.textColor.toLong()
                    identifier = -2L
                }
                profileSetting(nameRes = R.string.kau_add_account) {
                    iconDrawable = IconicsDrawable(this@MainActivity, GoogleMaterial.Icon.gmd_add).actionBar().paddingDp(5).color(Prefs.textColor)
                    textColor = Prefs.textColor.toLong()
                    identifier = -3L
                }
                profileSetting(nameRes = R.string.kau_manage_account) {
                    iicon = GoogleMaterial.Icon.gmd_settings
                    iconColor = Prefs.textColor.toLong()
                    textColor = Prefs.textColor.toLong()
                    identifier = -4L
                }
                onProfileChanged { _, profile, current ->
                    if (current) launchWebOverlay(FbTab.PROFILE.url)
                    else when (profile.identifier) {
                        -2L -> {
                            val currentCookie = loadFbCookie(Prefs.userId)
                            if (currentCookie == null) {
                                toast(R.string.account_not_found)
                                FbCookie.reset { launchLogin(cookies(), true) }
                            } else {
                                materialDialogThemed {
                                    title(R.string.kau_logout)
                                    content(String.format(string(R.string.kau_logout_confirm_as_x), currentCookie.name ?: Prefs.userId.toString()))
                                    positiveText(R.string.kau_yes)
                                    negativeText(R.string.kau_no)
                                    onPositive { _, _ ->
                                        FbCookie.logout(Prefs.userId) {
                                            val allCookies = cookies()
                                            allCookies.remove(currentCookie)
                                            launchLogin(allCookies, true)
                                        }
                                    }
                                }
                            }
                        }
                        -3L -> launchNewTask(LoginActivity::class.java, clearStack = false)
                        -4L -> launchNewTask(SelectorActivity::class.java, cookies(), false)
                        else -> {
                            switchUser(profile.identifier, { refreshAll() })
                            tabsForEachView { _, view -> view.badgeText = null }
                        }
                    }
                    false
                }
            }
            drawerHeader.setActiveProfile(Prefs.userId)
            primaryFrostItem(FbTab.FEED_MOST_RECENT)
            primaryFrostItem(FbTab.FEED_TOP_STORIES)
            primaryFrostItem(FbTab.ACTIVITY_LOG)
            divider()
            primaryFrostItem(FbTab.PHOTOS)
            primaryFrostItem(FbTab.GROUPS)
            primaryFrostItem(FbTab.PAGES)
            divider()
            primaryFrostItem(FbTab.EVENTS)
            primaryFrostItem(FbTab.BIRTHDAYS)
            primaryFrostItem(FbTab.ON_THIS_DAY)
            divider()
            primaryFrostItem(FbTab.NOTES)
            primaryFrostItem(FbTab.SAVED)
        }
    }

    fun Builder.primaryFrostItem(item: FbTab) = this.primaryItem(item.titleId) {
        iicon = item.icon
        iconColor = Prefs.textColor.toLong()
        textColor = Prefs.textColor.toLong()
        selectedIconColor = Prefs.textColor.toLong()
        selectedTextColor = Prefs.textColor.toLong()
        selectedColor = 0x00000001.toLong()
        identifier = item.titleId.toLong()
        onClick { _ ->
//            frostAnswers {
//                logContentView(ContentViewEvent()
//                        .putContentName(item.name)
//                        .putContentType("drawer_item"))
//            }
            launchWebOverlay(item.url)
            false
        }
    }

    fun Builder.secondaryFrostItem(@StringRes title: Int, onClick: () -> Unit) = this.secondaryItem(title) {
        textColor = Prefs.textColor.toLong()
        selectedIconColor = Prefs.textColor.toLong()
        selectedTextColor = Prefs.textColor.toLong()
        selectedColor = 0x00000001.toLong()
        identifier = title.toLong()
        onClick { _ -> onClick(); false }
    }


    /**
     * Something happened where the normal search function won't work
     * Fallback to overlay style
     */
    override fun searchOverlayError() {
        hiddenSearchView = null
        //todo remove true searchview and add contract
    }

    override fun emitSearchResponse(items: List<SearchItem>) {
        searchView?.results = items
    }

    fun refreshAll() {
        webFragmentObservable.onNext(FRAGMENT_REFRESH)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        toolbar.tint(Prefs.iconColor)
        setMenuIcons(menu, Prefs.iconColor,
                R.id.action_settings to GoogleMaterial.Icon.gmd_settings)
        searchView = bindSearchView(menu, R.id.action_search) {
            textObserver = {
                observable, _ ->
                observable.observeOn(AndroidSchedulers.mainThread()).subscribe {
                    L.d("Input $it")
                    hiddenSearchView?.query(it)
                }
            }
            foregroundColor = Prefs.textColor
            backgroundColor = Prefs.bgColor
            openListener = {
                hiddenSearchView?.pauseLoad = false
            }
            closeListener = {
                hiddenSearchView?.pauseLoad = true
            }
            onItemClick = {
                position, key, content, searchView ->
                launchWebOverlay(key)
            }
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_settings -> {
                val intent = Intent(this, SettingsActivity::class.java)
                val bundle = ActivityOptionsCompat.makeCustomAnimation(this, R.anim.kau_slide_in_right, R.anim.kau_fade_out).toBundle()
                startActivityForResult(intent, 99, bundle)
            }
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_RESTART -> restart()
            REQUEST_REFRESH -> webFragmentObservable.onNext(FRAGMENT_REFRESH)
            REQUEST_NAV -> frostNavigationBar()
        }
    }

    override fun onResume() {
        super.onResume()
        FbCookie.switchBackUser { }
    }

    override fun onBackPressed() {
        if (searchView?.onBackPressed() ?: false) return
        if (currentFragment.onBackPressed()) return
        super.onBackPressed()
    }

    val currentFragment
        get() = supportFragmentManager.findFragmentByTag("android:switcher:${R.id.container}:${viewPager.currentItem}") as WebFragment

    inner class SectionsPagerAdapter(fm: FragmentManager, val pages: List<FbTab>) : FragmentPagerAdapter(fm) {

        override fun getItem(position: Int): Fragment {
            val fragment = WebFragment(pages[position], position)
            //If first load hasn't occurred, add a listener
            if (!firstLoadFinished) {
                var disposable: Disposable? = null
                fragment.post {
                    disposable = it.web.refreshObservable.subscribe {
                        if (!it) {
                            //Ensure first load finisher only happens once
                            if (!firstLoadFinished) firstLoadFinished = true
                            disposable?.dispose()
                            disposable = null
                        }
                    }
                }
            }
            return fragment
        }

        override fun getCount() = pages.size

        override fun getPageTitle(position: Int): CharSequence = getString(pages[position].titleId)
    }

}
