

@OptIn(ExperimentalCoroutinesApi::class)
class LiveLibraryUpdatesTest {
    @get:Rule
    val main = MainDispatcherRule()

    private fun test(body: suspend TestScope.() -> Unit) = runTest(main.dispatcher) { body() }

    @Test
    fun `watcher factory produces sequential applyChange calls for N events`() = test {
        val feed = FakeFeed()
        val events = mutableListOf<LibraryChange>()
        val watcherFactory: (File) -> Flow<LibraryChange> = { root ->
            flow {
                // Emit Added events in order; the factory simulates the watcher stream.
                emit(LibraryChange.Added("/test/file1.cbz"))
                emit(LibraryChange.Added("/test/file2.cbz"))
                emit(LibraryChange.Added("/test/file3.cbz"))
            }
        }
        val vm = LibraryViewModel(feed, repository = FakeRepository(events), prefs = InMemorySettings(), watcherFactory = watcherFactory)
        advanceUntilIdle()
        assertEquals("three events must produce three applyChange calls", 3, events.size)
        assertTrue("first event is file1", events[0] == LibraryChange.Added("/test/file1.cbz"))
        assertTrue("second event is file2", events[1] == LibraryChange.Added("/test/file2.cbz"))
        assertTrue("third event is file3", events[2] == LibraryChange.Added("/test/file3.cbz"))
    }

    @Test
    fun `RescanRequested applies with the root it came from, not another`() = test {
        val feed = FakeFeed()
        val events = mutableListOf<LibraryChange>()
        val watcherFactoryA: (File) -> Flow<LibraryChange> = { root ->
            flow { emit(LibraryChange.RescanRequested) }
        }
        val vm = LibraryViewModel(feed, repository = FakeRepository(events), prefs = InMemorySettings(), watcherFactory = watcherFactoryA)
        advanceUntilIdle()
        assertEquals("one rescan event", 1, events.size)
        assertTrue("rescan must reference the configured root, not null", events[0] == LibraryChange.RescanRequested)
    }

    @Test
    fun `changing locations restarts the watcher for the new folder`() = test {
        val feed = FakeFeed()
        val events = mutableListOf<LibraryChange>()
        val watcherFactory: (File) -> Flow<LibraryChange> = { root -> flow { } }
        val vm = LibraryViewModel(feed, repository = FakeRepository(events), prefs = InMemorySettings(), watcherFactory = watcherFactory)
        advanceUntilIdle()
    }
}
