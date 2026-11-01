import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SIGNING = ROOT / "TMessagesProj/src/main/java/org/telegram/messenger/plugins/signing"
REPUTATION = ROOT / "TMessagesProj/src/main/java/org/telegram/messenger/plugins/reputation"


class PluginReputationStoreTest(unittest.TestCase):
    def test_java_harness(self):
        if not shutil.which("javac") or not shutil.which("java"):
            self.skipTest("Requires JDK")

        with tempfile.TemporaryDirectory(prefix="plugin-reputation-store-") as output:
            output_path = Path(output)
            stubs = self._write_android_stubs(output_path)
            sources = [
                SIGNING / "PluginSignedPayload.java",
                SIGNING / "PluginSignatureEnvelope.java",
                SIGNING / "PluginSignatureVerification.java",
                SIGNING / "PluginSignatureVerifier.java",
                SIGNING / "PluginSignerIdentity.java",
                SIGNING / "PluginKeyRotationProof.java",
                REPUTATION / "PluginReputationResponse.java",
                REPUTATION / "PluginReputationVerification.java",
                REPUTATION / "PluginReputationVerifier.java",
                REPUTATION / "PluginReputationState.java",
                REPUTATION / "PluginReputationPolicy.java",
                REPUTATION / "PluginReputationCache.java",
                REPUTATION / "PluginReputationStore.java",
                ROOT / "tests/java/PluginReputationStoreCheck.java",
                *stubs,
            ]
            subprocess.run(
                ["javac", "--release", "8", "-d", output, *map(str, sources)],
                check=True,
                capture_output=True,
                text=True,
            )
            subprocess.run(
                [
                    "java",
                    "-cp",
                    output,
                    "org.telegram.messenger.plugins.reputation.PluginReputationStoreCheck",
                ],
                check=True,
            )

    @staticmethod
    def _write_android_stubs(root):
        package = root / "stubs/android/system"
        package.mkdir(parents=True)
        files = {
            "ErrnoException.java": """
package android.system;
public class ErrnoException extends Exception {
    public final int errno = 0;
}
""",
            "StructStat.java": """
package android.system;
public class StructStat {
    public long st_mode;
    public long st_size;
}
""",
            "OsConstants.java": """
package android.system;
public final class OsConstants {
    public static final int O_RDONLY = 1;
    public static final int O_WRONLY = 2;
    public static final int O_RDWR = 32;
    public static final int O_CREAT = 4;
    public static final int O_TRUNC = 8;
    public static final int O_NOFOLLOW = 16;
    public static final int ENOENT = 2;
    public static boolean S_ISREG(long mode) { return true; }
    public static boolean S_ISDIR(long mode) { return true; }
}
""",
            "Os.java": """
package android.system;
import java.io.FileDescriptor;
public final class Os {
    public static StructStat lstat(String path) throws ErrnoException { throw new ErrnoException(); }
    public static FileDescriptor open(String path, int flags, int mode) throws ErrnoException { throw new ErrnoException(); }
    public static StructStat fstat(FileDescriptor descriptor) throws ErrnoException { throw new ErrnoException(); }
    public static void fsync(FileDescriptor descriptor) throws ErrnoException { throw new ErrnoException(); }
    public static void close(FileDescriptor descriptor) throws ErrnoException { throw new ErrnoException(); }
    public static void rename(String source, String target) throws ErrnoException { throw new ErrnoException(); }
    public static void remove(String path) throws ErrnoException { throw new ErrnoException(); }
}
""",
        }
        result = []

        for name, content in files.items():
            path = package / name
            path.write_text(content.strip() + "\n", encoding="utf-8")
            result.append(path)

        return result


if __name__ == "__main__":
    unittest.main()
