package io.graphcrud.model;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

final class CanonicalIdentityEncoding {
    private CanonicalIdentityEncoding() {}

    static String component(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
