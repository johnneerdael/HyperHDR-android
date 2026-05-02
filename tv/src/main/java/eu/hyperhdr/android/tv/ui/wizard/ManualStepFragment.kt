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
        actions += GuidedAction.Builder(requireContext())
            .id(ID_HOST).title("Host").description("e.g. 192.168.1.10")
            .editable(true).build()
        actions += GuidedAction.Builder(requireContext())
            .id(ID_FLAT).title("19400").description("Flatbuffer port")
            .editable(true).build()
        actions += GuidedAction.Builder(requireContext())
            .id(ID_JSON).title("19444").description("JSON-API port")
            .editable(true).build()
        actions += GuidedAction.Builder(requireContext()).id(ID_OK).title("Continue").build()
    }

    // Called when the user finishes editing a particular field. Return GuidedAction.ACTION_ID_NEXT
    // to advance focus to the next action; that's the canonical pattern for editable forms.
    override fun onGuidedActionEditedAndProceed(action: GuidedAction): Long {
        return GuidedAction.ACTION_ID_NEXT
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        if (action.id != ID_OK) return
        val host = findActionById(ID_HOST).title?.toString().orEmpty().trim()
        val flat = findActionById(ID_FLAT).title?.toString()?.toIntOrNull() ?: 19400
        val json = findActionById(ID_JSON).title?.toString()?.toIntOrNull() ?: 19444
        if (host.isBlank()) return  // refuse to advance with empty host
        val activity = requireActivity() as WizardActivity
        activity.profileDraft = activity.profileDraft.copy(host = host, flatbufPort = flat, jsonPort = json)
        add(parentFragmentManager, AuthStepFragment())
    }
}
