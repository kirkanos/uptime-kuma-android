package net.kirkanos.uptimekuma.push.fcm.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import net.kirkanos.uptimekuma.push.fcm.FcmFirebaseTransport
import net.kirkanos.uptimekuma.push.firebase.FirebaseTransport

/** Fills :push's optional `FirebaseTransport` binding (`@BindsOptionalOf`) in builds that include this module. */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class FcmModule {
    @Binds
    abstract fun bindFirebaseTransport(impl: FcmFirebaseTransport): FirebaseTransport
}
