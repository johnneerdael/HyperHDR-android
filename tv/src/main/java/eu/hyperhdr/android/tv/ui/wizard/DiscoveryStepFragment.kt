package eu.hyperhdr.android.tv.ui.wizard

import android.os.Bundle
import androidx.leanback.app.GuidedStepSupportFragment
import androidx.leanback.widget.GuidanceStylist
import androidx.leanback.widget.GuidedAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class DiscoveryStepFragment : GuidedStepSupportFragment() {

    private var browseJob: Job? = null
    private val ACTION_MANUAL = 1L
    private val ACTION_FOUND_BASE = 1000L

    override fun onCreateGuidance(savedInstanceState: Bundle?) =
        GuidanceStylist.Guidance(
            "Looking for HyperHDR servers…",
            "Pick a server below or enter manually.",
            "Step 1 of 4", null,
        )

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        actions.add(GuidedAction.Builder(requireContext())
            .id(ACTION_MANUAL).title("Enter manually…").build())
    }

    override fun onResume() {
        super.onResume()
        val activity = requireActivity() as WizardActivity
        browseJob = CoroutineScope(Dispatchers.Main).launch {
            activity.discovery.servers.collect { servers ->
                val newActions = mutableListOf<GuidedAction>()
                servers.forEachIndexed { idx, s ->
                    newActions += GuidedAction.Builder(requireContext())
                        .id(ACTION_FOUND_BASE + idx)
                        .title(s.name)
                        .description("${s.host}:${s.flatbufPort}")
                        .build()
                }
                newActions += GuidedAction.Builder(requireContext())
                    .id(ACTION_MANUAL).title("Enter manually…").build()
                actions = newActions
            }
        }
    }

    override fun onPause() { browseJob?.cancel(); super.onPause() }

    override fun onGuidedActionClicked(action: GuidedAction) {
        val activity = requireActivity() as WizardActivity
        when {
            action.id == ACTION_MANUAL -> {
                add(parentFragmentManager, ManualStepFragment())
            }
            action.id >= ACTION_FOUND_BASE -> {
                val idx = (action.id - ACTION_FOUND_BASE).toInt()
                val s = activity.discovery.servers.value[idx]
                activity.profileDraft = activity.profileDraft.copy(
                    host = s.host, flatbufPort = s.flatbufPort, jsonPort = s.jsonPort,
                )
                add(parentFragmentManager, AuthStepFragment())
            }
        }
    }
}
