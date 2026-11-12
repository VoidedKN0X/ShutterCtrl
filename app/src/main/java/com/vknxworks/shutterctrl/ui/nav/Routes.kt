package com.vknxworks.shutterctrl.ui.nav

import kotlinx.serialization.Serializable

// Tabs
@Serializable data object CameraRoute
@Serializable data object CardRoute

// Pushed screens
@Serializable data object LiveViewRoute
@Serializable data object FirmwareRoute

// Opened from the profile picture: the You page, and Settings from there
@Serializable data object YouRoute
@Serializable data object SettingsRoute
