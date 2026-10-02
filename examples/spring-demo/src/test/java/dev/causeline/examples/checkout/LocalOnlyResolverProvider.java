// SPDX-License-Identifier: Apache-2.0
package dev.causeline.examples.checkout;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.spi.InetAddressResolver;
import java.net.spi.InetAddressResolverProvider;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

/**
 * Restricts name resolution in the demo's test JVM to this machine (SC-10). Any other host name is
 * refused and recorded, so a test can assert that nothing tried to reach the network. Registered
 * through {@code META-INF/services}, so it applies to every test in this module.
 */
public final class LocalOnlyResolverProvider extends InetAddressResolverProvider {

    private static final List<String> REFUSED = new CopyOnWriteArrayList<>();

    /** Host names something tried to resolve that are not this machine. */
    static List<String> refusedLookups() {
        return List.copyOf(REFUSED);
    }

    @Override
    public InetAddressResolver get(Configuration configuration) {
        InetAddressResolver builtin = configuration.builtinResolver();
        String self = configuration.lookupLocalHostName().toLowerCase(Locale.ROOT);
        return new InetAddressResolver() {
            @Override
            public Stream<InetAddress> lookupByName(String host, LookupPolicy policy) throws UnknownHostException {
                String name = host.toLowerCase(Locale.ROOT);
                if (name.equals("localhost") || name.endsWith(".localhost") || name.equals(self)) {
                    return builtin.lookupByName(host, policy);
                }
                REFUSED.add(host);
                throw new UnknownHostException(host + " (network restricted to localhost in tests)");
            }

            @Override
            public String lookupByAddress(byte[] address) throws UnknownHostException {
                return builtin.lookupByAddress(address);
            }
        };
    }

    @Override
    public String name() {
        return "causeline-local-only";
    }
}
