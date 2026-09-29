package com.absolutex.core.data.backup

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.absolutex.core.data.AbsolutexDatabase
import com.absolutex.core.data.settings.DataStoreSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.IOException

open class BackupFixture {
    @get:Rule val tmp = TemporaryFolder()
    lateinit var db: AbsolutexDatabase
    lateinit var settings: DataStoreSettings
    lateinit var pending: PendingFavourites
    lateinit var repository: BackupRepository
    lateinit var store: DataStore<Preferences>
    private lateinit var scope: CoroutineScope
    var failSettings = false

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AbsolutexDatabase::class.java)
            .allowMainThreadQueries().build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val realStore = PreferenceDataStoreFactory.create(scope = scope) { tmp.newFolder().resolve("settings.preferences_pb") }
        store = object : DataStore<Preferences> by realStore {
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
                if (failSettings) throw IOException("Injected write failure")
                return realStore.updateData(transform)
            }
        }
        settings = DataStoreSettings(store)
        pending = PendingFavourites(db, settings)
        repository = BackupRepository(db, settings, pending)
    }

    @After fun tearDown() {
        scope.cancel()
        db.close()
    }
}
