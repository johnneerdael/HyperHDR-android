package eu.hyperhdr.android.tv.ui.wizard

import android.os.Bundle
import androidx.leanback.app.GuidedStepSupportFragment
import androidx.leanback.widget.GuidanceStylist
import androidx.leanback.widget.GuidedAction

class ManualStepFragment : GuidedStepSupportFragment() {
    private val ID_HOST = 10L; private val ID_FLAT = 11L; private val ID_JSON = 12L; private val ID_OK = 13L

    override fun onCreateGuidance(savedInstanceState: Bundle?) =
        GuidanceStylist.Guidance("Enter HyperHDR server", "Host/IP and ports.", "Step 1b", null)

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        actions += GuidedAction.Builder(requireContext()).id(ID_HOST).title("Host").editable(true).build()
        actions += GuidedAction.Builder(requireContext()).id(ID_FLAT).title("Flatbuffer port").description("19400").editable(true).build()
        actions += GuidedAction.Builder(requireContext()).id(ID_JSON).title("JSON-API port").description("19444").editable(true).build()
        actions += GuidedAction.Builder(requireContext()).id(ID_OK).title("Continue").build()
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        if (action.id != ID_OK) return
        val host = findActionById(ID_HOST).description?.toString().orEmpty()
        val flat = findActionById(ID_FLAT).description?.toString()?.toIntOrNull() ?: 19400
        val json = findActionById(ID_JSON).description?.toString()?.toIntOrNull() ?: 19444
        val activity = requireActivity() as WizardActivity
        activity.profileDraft = activity.profileDraft.copy(host = host, flatbufPort = flat, jsonPort = json)
        add(parentFragmentManager, AuthStepFragment())
    }
}
