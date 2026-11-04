import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "TMessagesProj/src/main/java/org/telegram"
RES = ROOT / "TMessagesProj/src/main/res"


class StartupAppearanceTest(unittest.TestCase):
    def test_system_splash_uses_animated_fluxgram_icon(self):
        day = (RES / "values-v31/styles.xml").read_text()
        night = (RES / "values-night/styles.xml").read_text()
        duration = (RES / "values/integers.xml").read_text()

        for styles in (day, night):
            self.assertIn(
                '<item name="android:windowSplashScreenAnimatedIcon">@drawable/fluxgram_splash_animation</item>',
                styles,
            )
            self.assertNotIn("@drawable/tg_splash_320", styles)

        self.assertIn('<integer name="splash_screen_duration">970</integer>', duration)
        self.assertIn('<item name="android:windowSplashScreenBackground">#f2f2f4</item>', day)
        self.assertIn('<item name="android:windowSplashScreenBackground">#25272b</item>', night)

    def test_system_splash_animation_is_bounded_transparent_webp(self):
        animation = (
            RES / "drawable-nodpi/fluxgram_splash_animation.webp"
        ).read_bytes()

        self.assertEqual(b"RIFF", animation[:4])
        self.assertEqual(b"WEBP", animation[8:12])
        self.assertIn(b"ANIM", animation)
        self.assertIn(b"VP8L", animation)
        self.assertEqual(0x10, animation[20] & 0x10)
        self.assertGreater(animation.count(b"ANMF"), 1)
        self.assertLessEqual(len(animation), 512 * 1024)

    def test_activity_startup_background_uses_active_telegram_theme(self):
        activity = (JAVA / "ui/LaunchActivity.java").read_text()
        startup = activity.split("protected void onCreate(Bundle savedInstanceState)", 1)[1].split(
            "Theme.createCommonChatResources();", 1
        )[0]

        self.assertIn("ApplicationLoader.postInitApplication();", startup)
        self.assertIn("new ColorDrawable(", startup)
        self.assertIn("Theme.getColor(Theme.key_windowBackgroundWhite)", startup)
        self.assertLess(
            startup.index("ApplicationLoader.postInitApplication();"),
            startup.index("Theme.getColor(Theme.key_windowBackgroundWhite)"),
        )

    def test_activity_keeps_only_system_splash_until_animation_finishes(self):
        activity = (JAVA / "ui/LaunchActivity.java").read_text()

        self.assertIn("setOnExitAnimationListener", activity)
        self.assertIn("Math.max(0, 970 - elapsed)", activity)
        self.assertIn("splashScreenView.postDelayed(splashScreenView::remove, remaining)", activity)
        self.assertNotIn("FluxGramStartupAnimationView", activity)


if __name__ == "__main__":
    unittest.main()
