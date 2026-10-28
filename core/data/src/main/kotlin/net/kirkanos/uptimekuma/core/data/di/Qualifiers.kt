package net.kirkanos.uptimekuma.core.data.di

import javax.inject.Qualifier

/** Long-lived scope of the data layer (socket sessions, sync writers). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
internal annotation class DataScope

/** Dispatcher for disk/Keystore work in the data layer. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
internal annotation class DataDispatcher

/** The DataStore holding server config and non-secret settings of `:core:data`. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
internal annotation class KumaSettings
