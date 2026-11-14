package com.hpg.merchant.infrastructure.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.RSAKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Loads the RSA key used to sign access tokens.
 *
 * <p>Production: the key pair comes from files mounted from a secrets manager, so every replica signs
 * with the same key and tokens survive restarts. Local development: if no files are configured, an
 * ephemeral key is generated and a warning is logged — tokens then become invalid on restart, which is
 * harmless locally (clients just request a new token). The {@code kid} is the key's RFC 7638 thumbprint,
 * so it is stable for a given key and lets resource servers pick the right key during rotation.
 */
final class SigningKeyProvider {

    private static final Logger log = LoggerFactory.getLogger(SigningKeyProvider.class);

    private SigningKeyProvider() {
    }

    static RSAKey load(SecurityProperties.Jwt config) {
        try {
            if (config.privateKeyLocation() != null && config.publicKeyLocation() != null) {
                RSAPrivateKey privateKey = (RSAPrivateKey) KeyFactory.getInstance("RSA")
                        .generatePrivate(new PKCS8EncodedKeySpec(pemBody(config.privateKeyLocation())));
                RSAPublicKey publicKey = (RSAPublicKey) KeyFactory.getInstance("RSA")
                        .generatePublic(new X509EncodedKeySpec(pemBody(config.publicKeyLocation())));
                log.info("Loaded JWT signing key from configured PEM files");
                return build(publicKey, privateKey);
            }
            if (config.requireConfiguredKey()) {
                throw new IllegalStateException("No JWT signing key configured (hpg.security.jwt.private-key-location "
                        + "and public-key-location). Every replica must sign with the same, persistent key.");
            }
            log.warn("No JWT signing key configured (hpg.security.jwt.*). Generating an EPHEMERAL key: "
                    + "tokens will be invalid after restart. Never run production like this.");
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            return build((RSAPublicKey) pair.getPublic(), (RSAPrivateKey) pair.getPrivate());
        } catch (GeneralSecurityException | IOException | JOSEException e) {
            throw new IllegalStateException("Cannot load JWT signing key", e);
        }
    }

    /**
     * Public keys of retired signing keys (key rotation): published next to the active key so tokens they
     * signed stay verifiable until they expire. They are never used to sign.
     */
    static java.util.List<RSAKey> previous(SecurityProperties.Jwt config) {
        java.util.List<RSAKey> keys = new java.util.ArrayList<>();
        for (Resource location : config.previousPublicKeyLocations()) {
            try {
                RSAPublicKey publicKey = (RSAPublicKey) KeyFactory.getInstance("RSA")
                        .generatePublic(new X509EncodedKeySpec(pemBody(location)));
                keys.add(new RSAKey.Builder(publicKey).keyIDFromThumbprint().build());
            } catch (GeneralSecurityException | IOException | JOSEException e) {
                throw new IllegalStateException("Cannot load previous JWT public key " + location, e);
            }
        }
        if (!keys.isEmpty()) {
            log.info("Publishing {} retired JWT public key(s) for tokens issued before the last rotation", keys.size());
        }
        return keys;
    }

    private static RSAKey build(RSAPublicKey publicKey, RSAPrivateKey privateKey) throws JOSEException {
        return new RSAKey.Builder(publicKey).privateKey(privateKey).keyIDFromThumbprint().build();
    }

    private static byte[] pemBody(Resource resource) throws IOException {
        String pem = new String(resource.getContentAsByteArray(), StandardCharsets.US_ASCII);
        String base64 = pem.replaceAll("-----(BEGIN|END) [A-Z ]+-----", "").replaceAll("\\s", "");
        return Base64.getDecoder().decode(base64);
    }
}
