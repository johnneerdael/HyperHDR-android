package eu.hyperhdr.android.tv.ui.wizard

import android.app.Activity
import android.content.Context
import android.net.nsd.NsdManager
import android.net.wifi.WifiManager
import android.os.Bundle
import androidx.leanback.app.GuidedStepSupportFragment
import androidx.fragment.app.FragmentActivity
import eu.hyperhdr.android.discovery.HyperHdrMdnsDiscovery
import eu.hyperhdr.android.settings.EncryptedProfileStorage
import eu.hyperhdr.android.settings.ProfileStore
import eu.hyperhdr.android.settings.ServerProfile

class WizardActivity : FragmentActivity() {
    lateinit var discovery: HyperHdrMdnsDiscovery
    lateinit var profileStore: ProfileStore
    var profileDraft: ServerProfile = ServerProfile(host = "")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        discovery = HyperHdrMdnsDiscovery(
            getSystemService(NSD_SERVICE) as NsdManager,
            getSystemService(Context.WIFI_SERVICE) as? WifiManager,
        ).also { it.start() }
        profileStore = ProfileStore(EncryptedProfileStorage.create(this))
        if (savedInstanceState == null) {
            GuidedStepSupportFragment.addAsRoot(this, DiscoveryStepFragment(), android.R.id.content)
        }
    }

    override fun onDestroy() { discovery.stop(); super.onDestroy() }

    fun finishOk() { setResult(Activity.RESULT_OK); finish() }
}
