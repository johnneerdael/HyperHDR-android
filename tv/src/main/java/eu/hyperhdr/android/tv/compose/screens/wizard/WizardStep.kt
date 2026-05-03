package eu.hyperhdr.android.tv.compose.screens.wizard

import eu.hyperhdr.android.settings.ServerProfile

sealed interface WizardStep {
    object Discovery : WizardStep
    object Manual : WizardStep
    data class Auth(val draft: ServerProfile) : WizardStep
    data class Instance(val draft: ServerProfile) : WizardStep
}
