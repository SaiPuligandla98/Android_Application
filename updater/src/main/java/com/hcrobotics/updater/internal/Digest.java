package com.hcrobotics.updater.internal;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * Computes the SHA-256 digest of a downloaded file.
 *
 * <h2>Why this matters more than anything else in the module</h2>
 * This module downloads an APK and asks the system to execute it. If the bytes
 * that arrive are not the bytes that were published, the device runs code
 * nobody intended to ship.
 *
 * <p>Comparing the download against the digest recorded in the manifest closes
 * that gap. It catches all of:</p>
 * <ul>
 *   <li>A truncated download from a dropped mobile connection.</li>
 *   <li>Silent corruption in a proxy or CDN cache.</li>
 *   <li>A tampered file served from a compromised host.</li>
 * </ul>
 *
 * <h2>What it does not protect against</h2>
 * If an attacker controls the manifest itself, they control the digest too. The
 * manifest URL is therefore forced to HTTPS (see {@code OtaConfig}), and the
 * final backstop is Android's own signature check: an APK signed with a
 * different key than the installed app is refused by the platform, whatever the
 * manifest says.
 *
 * <p>Three independent layers - HTTPS transport, digest verification, signature
 * enforcement - and an attacker must defeat all three.</p>
 *
 * @author HC Robotics
 * @since 1.0.0
 */
public final class Digest {

    /** 8 KB is the sweet spot for streamed hashing: few syscalls, small heap. */
    private static final int BUFFER_SIZE = 8 * 1024;

    /** Lookup table for byte-to-hex conversion. */
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    /** Utility class; never instantiated. */
    private Digest() {
        throw new AssertionError("Digest is a utility class.");
    }

    /**
     * Computes the SHA-256 digest of a file.
     *
     * <p>The file is streamed in chunks rather than read into memory. An APK can
     * be tens of megabytes; loading one into a byte array risks
     * {@link OutOfMemoryError} on a low-end device, which is exactly the sort of
     * device this module tends to run on.</p>
     *
     * @param file the file to hash
     * @return the digest as lowercase hexadecimal
     * @throws IOException if the file cannot be read, or SHA-256 is unavailable
     */
    @NonNull
    public static String sha256(@NonNull File file) throws IOException {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // Every Android platform provides SHA-256; this is unreachable.
            throw new IOException("SHA-256 is not available on this device", e);
        }

        try (InputStream in = new FileInputStream(file)) {
            final byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return toHex(digest.digest());
    }

    /**
     * Compares two digests, ignoring case and surrounding whitespace.
     *
     * <p>Lenient about formatting on purpose: digests get pasted from
     * {@code sha256sum}, PowerShell's {@code Get-FileHash} (which returns
     * uppercase), and CI logs. Being strict about case would reject a digest
     * that is in fact correct, and send someone hunting a non-existent bug.</p>
     *
     * @param expected digest recorded in the manifest
     * @param actual   digest computed from the downloaded file
     * @return {@code true} if the two match
     */
    public static boolean matches(@NonNull String expected, @NonNull String actual) {
        return expected.trim().toLowerCase(Locale.US)
                .equals(actual.trim().toLowerCase(Locale.US));
    }

    /**
     * Renders raw digest bytes as lowercase hexadecimal.
     *
     * @param bytes the digest
     * @return a hex string twice the length of {@code bytes}
     */
    @NonNull
    private static String toHex(@NonNull byte[] bytes) {
        final char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            final int value = bytes[i] & 0xFF;
            out[i * 2] = HEX[value >>> 4];
            out[i * 2 + 1] = HEX[value & 0x0F];
        }
        return new String(out);
    }
}
