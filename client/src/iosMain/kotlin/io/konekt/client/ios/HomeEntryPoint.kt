package io.konekt.client.ios

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectBase.OverrideInit
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toCValues
import platform.Foundation.NSStringFromClass
import platform.UIKit.UIApplicationDelegateProtocol
import platform.UIKit.UIApplicationDelegateProtocolMeta
import platform.UIKit.UIApplicationMain
import platform.UIKit.UIResponder
import platform.UIKit.UIResponderMeta
import platform.UIKit.UIScreen
import platform.UIKit.UIWindow
import platform.darwin.NSObject

// THE TWELVE LINES BETWEEN A `@Composable` AND A PHONE, and they are the entire reason B-43 said iOS
// "is the part with no Kotlin in it".
//
// It turns out to be Kotlin after all. The assumption was a `.framework` plus an Xcode project — and
// what an iOS application actually needs is a `UIApplicationMain`, a delegate that owns a window, and
// a root view controller. Kotlin/Native has all three through `platform.UIKit`, so the application is
// built by the same compiler, from the same source set, as everything it draws.
//
// What is NOT here is the product: no launch screen, no icon, no signing, no App Store metadata.
// Those are real and they are a different job from proving the composition root reaches a device.
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
fun homeMain() {
    memScoped {
        val args = arrayOf("KonektHome")
        UIApplicationMain(
            argc = args.size,
            argv = args.map { it.cstr.ptr }.toCValues().ptr,
            principalClassName = null,
            // The DELEGATE class by name, which is how UIKit finds it without a storyboard. A
            // misspelling here is a black screen and no error, because UIKit simply proceeds without
            // a delegate.
            delegateClassName = NSStringFromClass(KonektAppDelegate),
        )
    }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class KonektAppDelegate :
    UIResponder,
    UIApplicationDelegateProtocol {
    // UIKit CREATES THIS CLASS ITSELF, with `[[Class alloc] init]`, and a Kotlin class does not export
    // one by default. Without this the application launches and dies immediately with
    // "Initializer is not implemented" — before any Kotlin of ours runs, so nothing in the app can
    // report it and the only evidence is in the device log.
    @OverrideInit
    constructor() : super()

    companion object : UIResponderMeta(), UIApplicationDelegateProtocolMeta

    // Backed by a field of another name: `window` is a property of the protocol, so a private one
    // spelled the same way hides it instead of implementing it.
    private var held: UIWindow? = null

    override fun window(): UIWindow? = held

    override fun setWindow(window: UIWindow?) {
        held = window
    }

    override fun application(
        application: platform.UIKit.UIApplication,
        didFinishLaunchingWithOptions: Map<Any?, *>?,
    ): Boolean {
        // The window is made key and visible HERE rather than left to a storyboard, because there is
        // no storyboard: a bundle without one shows a black screen and reports no error at all, which
        // is the single most confusing way an iOS application can fail to start.
        held =
            UIWindow(frame = UIScreen.mainScreen.bounds).apply {
                rootViewController = homeViewController()
                makeKeyAndVisible()
            }
        return true
    }
}
