package com.opendroid.ai

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Avoids [OpenDroidApp], whose onCreate needs the Android Keystore. */
class BackupPolicyTestApplication : Application()

/**
 * Backups are disabled on purpose (#86): the workspace, background photos and model
 * files must not be copied to cloud backup or device transfer. backup_rules.xml and
 * data_extraction_rules.xml are only defense in depth. This reads the merged
 * manifest, so a manifest rewrite can't silently turn backups back on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = BackupPolicyTestApplication::class)
class BackupPolicyTest {

    @Test
    fun `the app opts out of Android backup`() {
        val flags = ApplicationProvider.getApplicationContext<Context>().applicationInfo.flags

        assertEquals(
            "android:allowBackup must be false in the merged manifest",
            0,
            flags and ApplicationInfo.FLAG_ALLOW_BACKUP
        )
    }
}
