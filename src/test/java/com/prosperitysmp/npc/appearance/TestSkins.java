package com.prosperitysmp.npc.appearance;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;

/** Synthetic test-key skins for other packages' tests. Never Mojang assets; never valid in production. */
public final class TestSkins {
    private static KeyPair key;

    private TestSkins() { }

    public static SignedResidentSkin synthetic(char hex) { return synthetic(hex, false); }

    public static synchronized SignedResidentSkin synthetic(char hex, boolean slim) {
        try {
            if (key == null) {
                var generator = KeyPairGenerator.getInstance("RSA");
                generator.initialize(4096);
                key = generator.generateKeyPair();
            }
            String json = "{\"textures\":{\"SKIN\":{\"url\":\"https://textures.minecraft.net/texture/"
                    + String.valueOf(hex).repeat(64) + "\"" + (slim ? ",\"metadata\":{\"model\":\"slim\"}" : "") + "}}}";
            String value = Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
            Signature signer = Signature.getInstance("SHA1withRSA");
            signer.initSign(key.getPrivate());
            signer.update(value.getBytes(StandardCharsets.US_ASCII));
            return SignedResidentSkin.verifyWithKey(value, Base64.getEncoder().encodeToString(signer.sign()), key.getPublic());
        } catch (java.security.GeneralSecurityException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
