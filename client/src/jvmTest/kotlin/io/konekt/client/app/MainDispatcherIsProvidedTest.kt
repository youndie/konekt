package io.konekt.client.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertTrue

// THE DESKTOP BUILD HAS TO SUPPLY ITS OWN MAIN DISPATCHER, and nothing about the build says so.
//
// `kotlinx-coroutines-core` DECLARES `Dispatchers.Main` and implements it nowhere. The provider
// arrives through a ServiceLoader from a platform module — `-android` on Android, `-swing` here —
// and with none present `Main` is not slow or degraded, it throws `MissingMainCoroutineDispatcher`
// the first time anything dispatches to it.
//
// WHY A TEST AND NOT A COMMENT ON THE DEPENDENCY. The desktop application ran for half an hour
// without it: Compose Desktop opens its window on an EDT dispatcher of its own, so everything
// compiled, everything drew, and the failure sat waiting for the first piece of code that asked for
// `Main` BY NAME — in a library, on a screen nobody had opened yet. A dependency whose absence is
// invisible until a user finds it is exactly the kind that gets removed as unused.
//
// This fails on a classpath without the module and passes with it, which is the whole assertion.
class MainDispatcherIsProvidedTest {
    @Test
    fun `the main dispatcher resolves on the desktop target`() {
        val thread =
            runBlocking {
                withContext(Dispatchers.Main) { Thread.currentThread().name }
            }

        // The name is checked as well as the dispatch succeeding: `Main` here must be the Swing
        // event thread rather than merely something that ran. A provider that dispatched anywhere
        // would satisfy the call and break the one guarantee `Main` carries.
        assertTrue(
            thread.startsWith("AWT-EventQueue"),
            "Dispatchers.Main ran on \"$thread\" rather than on the Swing event thread",
        )
    }
}
