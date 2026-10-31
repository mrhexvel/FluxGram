package org.telegram.messenger.plugins.packages;

import java.io.File;
import java.io.IOException;

public final class PluginInstalledPackageCheck {
    public static void main(String[] args) throws Exception {
        try {
            PluginPackageValidator.Result result =
                    PluginPackageValidator.inspectInstalledArchive(
                            new File(args[0]),
                            new File(args[1]));
            System.out.println("signature:" + result.signatureVerification.status);
            System.out.println("plugin:" + result.signatureVerification.pluginId);
            System.out.println("key:" + result.signatureVerification.keyId);
            System.out.println("payload:" + result.signatureVerification.payloadSha256);
            System.out.println("rotation:" + (result.rotationProof == null
                    ? "none"
                    : result.rotationProof.fromGeneration + "->" + result.rotationProof.toGeneration));
        } catch (IOException expected) {
            System.err.println(expected.getMessage());
            System.exit(2);
        }
    }
}
