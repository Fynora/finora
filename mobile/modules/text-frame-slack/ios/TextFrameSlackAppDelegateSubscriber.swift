import ExpoModulesCore

/// Installs the paragraph text hooks before the first screen is drawn. See `FYNTextFrameSlack.h`.
public class TextFrameSlackAppDelegateSubscriber: ExpoAppDelegateSubscriber {
  public func application(
    _ application: UIApplication,
    didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
  ) -> Bool {
    FYNTextFrameSlack.install()
    return true
  }
}
