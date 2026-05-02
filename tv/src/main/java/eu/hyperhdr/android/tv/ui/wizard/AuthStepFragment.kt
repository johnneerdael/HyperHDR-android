package eu.hyperhdr.android.tv.ui.wizard

import android.os.Bundle
import androidx.leanback.app.GuidedStepSupportFragment
import androidx.leanback.widget.GuidanceStylist
import androidx.leanback.widget.GuidedAction
import eu.hyperhdr.android.json.HyperHdrJsonApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AuthStepFragment : GuidedStepSupportFragment() {
    private val ID_TOKEN = 20L; private val ID_OK = 21L; private val ID_SKIP = 22L
    private var authRequired = false

    override fun onCreateGuidance(savedInstanceState: Bundle?) =
        GuidanceStylist.Guidance(
            "Authentication",
            "Probing the server… open the HyperHDR web UI to create a token if needed.",
            "Step 2 of 4", null,
        )

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        actions += GuidedAction.Builder(requireContext()).id(ID_TOKEN).title("Token").editable(true).build()
        actions += GuidedAction.Builder(requireContext()).id(ID_OK).title("Continue").build()
        actions += GuidedAction.Builder(requireContext()).id(ID_SKIP).title("Skip (open server)").build()
    }

    override fun onResume() {
        super.onResume()
        val activity = requireActivity() as WizardActivity
        CoroutineScope(Dispatchers.Main).launch {
            val client = HyperHdrJsonApiClient(activity.profileDraft.host, activity.profileDraft.jsonPort)
            authRequired = withContext(Dispatchers.IO) {
                runCatching { client.serverInfo().authRequired }.getOrElse { false }
            }
            if (!authRequired) {
                add(parentFragmentManager, InstanceStepFragment())
            }
        }
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        val activity = requireActivity() as WizardActivity
        when (action.id) {
            ID_OK -> {
                val token = findActionById(ID_TOKEN).description?.toString()?.takeIf { it.isNotBlank() }
                activity.profileDraft = activity.profileDraft.copy(token = token)
                add(parentFragmentManager, InstanceStepFragment())
            }
            ID_SKIP -> add(parentFragmentManager, InstanceStepFragment())
        }
    }
}
