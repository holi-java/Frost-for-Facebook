package com.pitchedapps.frost

import android.os.Bundle
import com.pitchedapps.frost.utils.Prefs
import com.pitchedapps.frost.utils.formattedFbUrl


/**
 * Created by Allan Wang on 2017-06-19.
 *
 * Replica of [WebOverlayActivity] with a different base url
 * Didn't use activity-alias because it causes issues when only one activity has the singleInstance mode
 */
class FrostWebActivity : WebOverlayActivity() {

    override val url: String
        get() = intent.dataString!!.formattedFbUrl


    override fun onCreate(savedInstanceState: Bundle?) {
        Prefs.prevId = Prefs.userId
        super.onCreate(savedInstanceState)
    }

}