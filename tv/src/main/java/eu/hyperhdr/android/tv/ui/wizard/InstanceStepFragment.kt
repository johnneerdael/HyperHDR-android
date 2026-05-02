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

class InstanceStepFragment : GuidedStepSupportFragment() {
    private val INSTANCE_BASE = 100L

    override fun onCreateGuidance(savedInstanceState: Bundle?) =
        GuidanceStylist.Guidance("Pick the instance", "Which HyperHDR instance should this device drive?", "Step 3 of 4", null)

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        actions += GuidedAction.Builder(requireContext()).id(0L).title("Loading…").build()
    }

    override fun onResume() {
        super.onResume()
        val activity = requireActivity() as WizardActivity
        CoroutineScope(Dispatchers.Main).launch {
            val client = HyperHdrJsonApiClient(activity.profileDraft.host, activity.profileDraft.jsonPort)
            activity.profileDraft.token?.let { runCatching { client.authorize(it) } }
            val info = withContext(Dispatchers.IO) {
                runCatching { client.serverInfo() }.getOrNull()
            }
            val newActions = mutableListOf<GuidedAction>()
            info?.instances?.forEachIndexed { i, inst ->
                newActions += GuidedAction.Builder(requireContext())
                    .id(INSTANCE_BASE + i)
                    .title(inst.name)
                    .description(if (inst.running) "Running (id=${inst.id})" else "Stopped (id=${inst.id})")
                    .build()
            }
            if (newActions.isEmpty()) {
                newActions += GuidedAction.Builder(requireContext())
                    .id(0L).title("No instances reported").description("Check the server").build()
            }
            actions = newActions
        }
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        if (action.id < INSTANCE_BASE) return
        val activity = requireActivity() as WizardActivity
        val client = HyperHdrJsonApiClient(activity.profileDraft.host, activity.profileDraft.jsonPort)
        val idx = (action.id - INSTANCE_BASE).toInt()
        CoroutineScope(Dispatchers.Main).launch {
            val info = withContext(Dispatchers.IO) { runCatching { client.serverInfo() }.getOrNull() }
            val inst = info?.instances?.getOrNull(idx) ?: return@launch
            activity.profileDraft = activity.profileDraft.copy(instanceId = inst.id)
            activity.profileStore.save(activity.profileDraft)
            activity.finishOk()
        }
    }
}
