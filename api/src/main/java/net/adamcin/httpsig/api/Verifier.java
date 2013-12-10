/*
 * This is free and unencumbered software released into the public domain.
 *
 * Anyone is free to copy, modify, publish, use, compile, sell, or
 * distribute this software, either in source code form or as a compiled
 * binary, for any purpose, commercial or non-commercial, and by any
 * means.
 *
 * In jurisdictions that recognize copyright laws, the author or authors
 * of this software dedicate any and all copyright interest in the
 * software to the public domain. We make this dedication for the benefit
 * of the public at large and to the detriment of our heirs and
 * successors. We intend this dedication to be an overt act of
 * relinquishment in perpetuity of all present and future rights to this
 * software under copyright law.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS BE LIABLE FOR ANY CLAIM, DAMAGES OR
 * OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
 * ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 *
 * For more information, please refer to <http://unlicense.org/>
 */

package net.adamcin.httpsig.api;

import java.util.Date;
import java.util.GregorianCalendar;
import java.util.TimeZone;

/**
 * The Server-Side component of the protocol which verifies {@link Authorization} headers using SSH Public Keys
 */
public final class Verifier {
    public static final long DEFAULT_SKEW = 300000L;

    private final Keychain keychain;
    private KeyIdentifier keyIdentifier;
    private long skew = DEFAULT_SKEW;

    public Verifier(Keychain keychain) {
        this(keychain, null);
    }

    public Verifier(Keychain keychain, KeyIdentifier keyIdentifier) {
        this.keychain = keychain != null ? keychain : new DefaultKeychain();
        this.keyIdentifier = keyIdentifier != null ? keyIdentifier : Constants.DEFAULT_KEY_IDENTIFIER;
    }

    public Keychain getKeychain() {
        return keychain;
    }

    /**
     * @return server skew in milliseconds
     */
    public long getSkew() {
        return skew;
    }

    /**
     * @param skew new server skew in milliseconds
     */
    public void setSkew(long skew) {
        this.skew = skew;
    }

    /**
     * Verifies the provided {@link Authorization} header against the original {@link Challenge}
     * @param challenge the WWW-Authenticate challenge sent to the client in the previous response
     * @param authorization the authorization header
     * @return true if valid, false otherwise
     */
    public boolean verify(Challenge challenge, SignatureBuilder signatureBuilder, Authorization authorization) {
        if (challenge == null) {
            throw new IllegalArgumentException("challenge cannot be null");
        }

        if (signatureBuilder == null || authorization == null) {
            return false;
        }

        // if challenge requires :all headers, verify that all headers included in the request are declared by the authorization
        if (challenge.getHeaders().contains(Constants.HEADER_ALL)) {
            for (String header : signatureBuilder.getHeaderNames()) {
                if (!authorization.getHeaders().contains(header)) {
                    return false;
                }
            }
        }

        // verify that all headers required by the challenge are declared by the authorization
        for (String header : challenge.getHeaders()) {
            if (!header.startsWith(":") && !authorization.getHeaders().contains(header)) {
                return false;
            }
        }

        // verify that all headers declared by the authorization are present in the request
        for (String header : authorization.getHeaders()) {
            if (signatureBuilder.getHeaderValues(header).isEmpty()) {
                return false;
            }
        }

        // if date is declared by the authorization, verify that its value is within $skew of the current time
        if (authorization.getHeaders().contains(Constants.HEADER_DATE) && skew >= 0) {
            Date requestTime = signatureBuilder.getDateGMT();
            Date currentTime = new GregorianCalendar(TimeZone.getTimeZone("UTC")).getTime();
            Date past = new Date(currentTime.getTime() - skew);
            Date future = new Date(currentTime.getTime() + skew);
            if (requestTime.before(past) || requestTime.after(future)) {
                return false;
            }
        }

        Key key = keychain.toMap(this.keyIdentifier).get(authorization.getKeyId());
        if (key != null && key.verify(authorization.getAlgorithm(),
                                      signatureBuilder.buildContent(authorization.getHeaders(), Constants.CHARSET),
                                      authorization.getSignatureBytes())) {
            return true;
        } else {
            return false;
        }
    }
}