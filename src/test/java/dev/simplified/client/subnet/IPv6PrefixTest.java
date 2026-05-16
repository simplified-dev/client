package dev.simplified.client.subnet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.net.Inet6Address;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IpPrefixTest {

    @Test
    @DisplayName("Parses CIDRs at the four canonical fan-out lengths")
    void parsesCanonicalLengths() {
        assertThat(IPv6Prefix.parse("2602:fa02:0a00::/40").length(), is(40));
        assertThat(IPv6Prefix.parse("2001:470:1::/48").length(), is(48));
        assertThat(IPv6Prefix.parse("2001:db8:abcd:ef00::/56").length(), is(56));
        assertThat(IPv6Prefix.parse("2001:db8:abcd:ef00:1234::/60").length(), is(60));
        assertThat(IPv6Prefix.parse("2001:db8::/64").length(), is(64));
        assertThat(IPv6Prefix.parse("::/0").length(), is(0));
        assertThat(IPv6Prefix.parse("2001:db8::1/128").length(), is(128));
    }

    @Test
    @DisplayName("Normalizes host bits at byte boundaries")
    void normalizesByteAlignedHostBits() {
        IPv6Prefix p = IPv6Prefix.parse("2602:fa02:0a00:dead:beef::/40");
        IPv6Prefix canonical = IPv6Prefix.parse("2602:fa02:0a00::/40");
        assertThat(p, equalTo(canonical));
    }

    @Test
    @DisplayName("Normalizes host bits at non-byte-aligned boundaries (e.g. /40)")
    void normalizesNonAlignedHostBits() {
        IPv6Prefix p = IPv6Prefix.parse("2602:fa02:0a01::/40");
        IPv6Prefix canonical = IPv6Prefix.parse("2602:fa02:0a00::/40");
        assertThat(p, equalTo(canonical));
    }

    @Test
    @DisplayName("Normalizes host bits at /60 (4-bit partial byte)")
    void normalizesNibbleAlignedHostBits() {
        IPv6Prefix p = IPv6Prefix.parse("2001:db8:abcd:ef0f::/60");
        IPv6Prefix canonical = IPv6Prefix.parse("2001:db8:abcd:ef00::/60");
        assertThat(p, equalTo(canonical));
    }

    @Test
    @DisplayName("Counts contained subnets correctly across prefix bands")
    void containedSubnetCount() {
        IPv6Prefix p40 = IPv6Prefix.parse("2602:fa02:0a00::/40");
        assertThat(p40.containedSubnetCount(56).longValueExact(), is(65536L));
        assertThat(p40.containedSubnetCount(48).longValueExact(), is(256L));
        assertThat(p40.containedSubnetCount(40).longValueExact(), is(1L));

        IPv6Prefix p48 = IPv6Prefix.parse("2001:470:1::/48");
        assertThat(p48.containedSubnetCount(56).longValueExact(), is(256L));
        assertThat(p48.containedSubnetCount(64).longValueExact(), is(65536L));

        IPv6Prefix p56 = IPv6Prefix.parse("2001:db8:abcd:ef00::/56");
        assertThat(p56.containedSubnetCount(56).longValueExact(), is(1L));

        // Coarser subnets yield zero (prefix can't be partitioned into bigger pieces).
        IPv6Prefix p60 = IPv6Prefix.parse("2001:db8:abcd:ef00::/60");
        assertThat(p60.containedSubnetCount(56), is(BigInteger.ZERO));
        assertThat(p60.containedSubnetCount(40), is(BigInteger.ZERO));
    }

    @Test
    @DisplayName("subnetAt boundaries are contained in the parent prefix")
    void subnetAtBoundaries() {
        IPv6Prefix p40 = IPv6Prefix.parse("2602:fa02:0a00::/40");
        IPv6Prefix first = p40.subnetAt(56, BigInteger.ZERO);
        IPv6Prefix last = p40.subnetAt(56, BigInteger.valueOf(65535));

        assertThat(first.length(), is(56));
        assertThat(last.length(), is(56));
        assertThat(p40.contains(first), is(true));
        assertThat(p40.contains(last), is(true));
        assertThat(first, not(equalTo(last)));
    }

    @Test
    @DisplayName("subnetAt rejects out-of-range indices")
    void subnetAtOutOfRange() {
        IPv6Prefix p40 = IPv6Prefix.parse("2602:fa02:0a00::/40");
        assertThrows(IndexOutOfBoundsException.class,
            () -> p40.subnetAt(56, BigInteger.valueOf(-1)));
        assertThrows(IndexOutOfBoundsException.class,
            () -> p40.subnetAt(56, BigInteger.valueOf(65536)));
    }

    @Test
    @DisplayName("subnetAt rejects subLength coarser than this prefix")
    void subnetAtRejectsCoarserSubLength() {
        IPv6Prefix p56 = IPv6Prefix.parse("2001:db8:abcd:ef00::/56");
        assertThrows(IllegalArgumentException.class,
            () -> p56.subnetAt(40, BigInteger.ZERO));
    }

    @Test
    @DisplayName("Random addresses fall within the source prefix")
    void randomAddressInPrefix() {
        IPv6Prefix p56 = IPv6Prefix.parse("2001:db8:abcd:ef00::/56");
        for (int i = 0; i < 200; i++) {
            Inet6Address addr = p56.randomAddress();
            assertThat(p56.contains(addr), is(true));
        }
    }

    @Test
    @DisplayName("Random addresses span the address space (host bits actually vary)")
    void randomAddressVaries() {
        IPv6Prefix p56 = IPv6Prefix.parse("2001:db8:abcd:ef00::/56");
        Inet6Address first = p56.randomAddress();
        boolean sawDifferent = false;
        for (int i = 0; i < 50; i++) {
            if (!p56.randomAddress().equals(first)) {
                sawDifferent = true;
                break;
            }
        }
        assertThat(sawDifferent, is(true));
    }

    @Test
    @DisplayName("Random contained subnets fall within the source prefix")
    void randomContainedSubnetInPrefix() {
        IPv6Prefix p48 = IPv6Prefix.parse("2001:470:1::/48");
        for (int i = 0; i < 100; i++) {
            IPv6Prefix sub = p48.randomContainedSubnet(56);
            assertThat(sub.length(), is(56));
            assertThat(p48.contains(sub), is(true));
        }
    }

    @Test
    @DisplayName("Random contained subnet of /56 over /56 always yields itself")
    void randomContainedSubnetEqualLength() {
        IPv6Prefix p56 = IPv6Prefix.parse("2001:db8:abcd:ef00::/56");
        IPv6Prefix sub = p56.randomContainedSubnet(56);
        assertThat(sub, equalTo(p56));
    }

    @Test
    @DisplayName("Subnet indices map deterministically to addresses")
    void subnetAtIsDeterministic() {
        IPv6Prefix p48 = IPv6Prefix.parse("2001:470:1::/48");
        IPv6Prefix a = p48.subnetAt(56, BigInteger.valueOf(42));
        IPv6Prefix b = p48.subnetAt(56, BigInteger.valueOf(42));
        assertThat(a, equalTo(b));

        IPv6Prefix c = p48.subnetAt(56, BigInteger.valueOf(43));
        assertThat(a, not(equalTo(c)));
    }

    @Test
    @DisplayName("equals and hashCode honor the normalized form")
    void equalsAndHashCode() {
        IPv6Prefix a = IPv6Prefix.parse("2602:fa02:0a01::/40");
        IPv6Prefix b = IPv6Prefix.parse("2602:fa02:0a00::/40");
        IPv6Prefix c = IPv6Prefix.parse("2602:fa02:0a00::/48");

        assertThat(a, equalTo(b));
        assertThat(a.hashCode(), is(b.hashCode()));
        assertThat(a, not(equalTo(c)));
    }

    @Test
    @DisplayName("toString is round-trippable through parse")
    void toStringRoundTrips() {
        for (String input : new String[]{
            "2602:fa02::/40",
            "2001:470:1::/48",
            "2001:db8:abcd:ef00::/56",
            "::/0",
            "2001:db8::1/128"
        }) {
            IPv6Prefix p = IPv6Prefix.parse(input);
            IPv6Prefix reparsed = IPv6Prefix.parse(p.toString());
            assertThat(reparsed, equalTo(p));
            assertThat(reparsed.length(), is(p.length()));
        }
    }

    @Test
    @DisplayName("Rejects malformed CIDR strings")
    void rejectsMalformedCidr() {
        assertThrows(IllegalArgumentException.class, () -> IPv6Prefix.parse("2602:fa02::"));
        assertThrows(IllegalArgumentException.class, () -> IPv6Prefix.parse("2602:fa02::/"));
        assertThrows(IllegalArgumentException.class, () -> IPv6Prefix.parse("2602:fa02::/-1"));
        assertThrows(IllegalArgumentException.class, () -> IPv6Prefix.parse("2602:fa02::/129"));
        assertThrows(IllegalArgumentException.class, () -> IPv6Prefix.parse("2602:fa02::/abc"));
        assertThrows(IllegalArgumentException.class, () -> IPv6Prefix.parse("not-an-address/64"));
    }

    @Test
    @DisplayName("Rejects IPv4 CIDRs")
    void rejectsIpv4() {
        assertThrows(IllegalArgumentException.class, () -> IPv6Prefix.parse("192.168.1.0/24"));
    }

    @Test
    @DisplayName("of(byte[], length) normalizes host bits")
    void ofBytesNormalizes() {
        byte[] raw = new byte[]{
            (byte) 0x26, (byte) 0x02, (byte) 0xfa, (byte) 0x02,
            (byte) 0x0a, (byte) 0xff, (byte) 0xff, (byte) 0xff,
            (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff,
            (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff
        };
        IPv6Prefix p = IPv6Prefix.of(raw, 40);
        assertThat(p, equalTo(IPv6Prefix.parse("2602:fa02:0a00::/40")));
        // Input array must not have been mutated externally
        assertThat(raw[5], is((byte) 0xff));
    }

    @Test
    @DisplayName("of rejects malformed input")
    void ofValidates() {
        byte[] tooShort = new byte[15];
        assertThrows(IllegalArgumentException.class, () -> IPv6Prefix.of(tooShort, 64));
        byte[] valid = new byte[16];
        assertThrows(IllegalArgumentException.class, () -> IPv6Prefix.of(valid, -1));
        assertThrows(IllegalArgumentException.class, () -> IPv6Prefix.of(valid, 129));
    }

    @Test
    @DisplayName("Indices traverse the full subnet space deterministically")
    void subnetAtCoversFullRange() {
        IPv6Prefix p126 = IPv6Prefix.parse("2001:db8::/126");
        // 2 bits of fan-out -> 4 contained /128s
        java.util.Set<IPv6Prefix> subnets = new java.util.HashSet<>();
        for (long i = 0; i < 4; i++) {
            subnets.add(p126.subnetAt(128, BigInteger.valueOf(i)));
        }
        assertThat(subnets.size(), is(4));
        // All four must be contained in the parent
        for (IPv6Prefix s : subnets) {
            assertThat(p126.contains(s), is(true));
            assertThat(s.length(), is(128));
        }
    }

    @Test
    @DisplayName("BigInteger conversion handles 65k+ subnets")
    void largeFanOut() {
        IPv6Prefix p40 = IPv6Prefix.parse("2602:fa02:0a00::/40");
        BigInteger count = p40.containedSubnetCount(56);
        assertThat(count.longValueExact(), is(greaterThanOrEqualTo(65536L)));
        assertThat(count.longValueExact(), is(lessThan(65537L)));
    }

}
