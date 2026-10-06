import UIKit
import ReaktorWebProbe

@main
final class ProbeApplication: UIResponder, UIApplicationDelegate {
    var window: UIWindow?
    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
        let window = UIWindow(frame: UIScreen.main.bounds)
        window.rootViewController = DeviceWebViewProbeKt.createDeviceWebViewProbe()
        window.makeKeyAndVisible()
        self.window = window
        return true
    }
}
