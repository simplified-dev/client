package dev.simplified.client.subnet;

import org.jetbrains.annotations.NotNull;

import java.math.BigInteger;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Immutable IPv6 CIDR network prefix with bit-aligned semantics.
 * <p>
 * Stores the network as a 16-byte address paired with a prefix length in
 * {@code [0, 128]}. Host bits beyond the prefix length are normalized to zero
 * at construction time, so two prefixes parsed from differently-formatted but
 * semantically equivalent CIDR strings (e.g., {@code 2602:fa02:0a01::/40} and
 * {@code 2602:fa00::/40}) compare equal.
 * <p>
 * Provides bit-precise operations for partitioning the prefix into smaller
 * contained subnets, enumerating those subnets by index, picking random
 * contained subnets uniformly, and generating random addresses within the
 * prefix range.
 *
 * @see SubnetRotation
 */
public final class IpPrefix {

    /**
     * Total number of bits in an IPv6 address.
     */
    public static final int IPV6_BITS = 128;

    /**
     * Total number of bytes in an IPv6 address.
     */
    public static final int IPV6_BYTES = 16;

    private final byte @NotNull [] networkBytes;
    private final int length;

    private IpPrefix(byte @NotNull [] networkBytes, int length) {
        this.networkBytes = networkBytes;
        this.length = length;
    }

    /**
     * Parses a CIDR notation string into an {@link IpPrefix}.
     * <p>
     * Host bits beyond the prefix length are masked to zero, so the returned
     * instance always represents the canonical network address.
     *
     * @param cidr an IPv6 CIDR string (e.g., {@code "2602:fa02:0a00::/40"})
     * @return the parsed prefix with host bits normalized
     * @throws IllegalArgumentException if the prefix length is missing, out of
     *         range, or the address portion is not a valid IPv6 literal
     */
    public static @NotNull IpPrefix parse(@NotNull String cidr) {
        int slashIdx = cidr.indexOf('/');
        if (slashIdx < 0)
            throw new IllegalArgumentException("Missing prefix length in CIDR: '" + cidr + "'");

        String address = cidr.substring(0, slashIdx);
        int length;
        try {
            length = Integer.parseInt(cidr.substring(slashIdx + 1));
        } catch (NumberFormatException nfex) {
            throw new IllegalArgumentException("Invalid prefix length in CIDR: '" + cidr + "'", nfex);
        }
        if (length < 0 || length > IPV6_BITS)
            throw new IllegalArgumentException("Prefix length out of range [0, " + IPV6_BITS + "]: " + length);

        InetAddress addr;
        try {
            addr = InetAddress.getByName(address);
        } catch (UnknownHostException uhex) {
            throw new IllegalArgumentException("Invalid IPv6 address in CIDR: '" + address + "'", uhex);
        }
        if (!(addr instanceof Inet6Address))
            throw new IllegalArgumentException("CIDR must be IPv6: '" + cidr + "'");

        byte[] bytes = addr.getAddress();
        applyMask(bytes, length);
        return new IpPrefix(bytes, length);
    }

    /**
     * Constructs a prefix directly from a 16-byte network address and a length.
     * <p>
     * Host bits beyond {@code length} are masked to zero. The input array is
     * defensively copied.
     *
     * @param networkBytes the 16-byte network address
     * @param length the prefix length in {@code [0, 128]}
     * @return the normalized prefix
     */
    public static @NotNull IpPrefix of(byte @NotNull [] networkBytes, int length) {
        if (networkBytes.length != IPV6_BYTES)
            throw new IllegalArgumentException("networkBytes must be " + IPV6_BYTES + " bytes, got " + networkBytes.length);
        if (length < 0 || length > IPV6_BITS)
            throw new IllegalArgumentException("Prefix length out of range [0, " + IPV6_BITS + "]: " + length);

        byte[] copy = networkBytes.clone();
        applyMask(copy, length);
        return new IpPrefix(copy, length);
    }

    /**
     * Returns the prefix length in bits.
     *
     * @return the length in {@code [0, 128]}
     */
    public int length() {
        return this.length;
    }

    /**
     * Returns a defensive copy of the 16-byte network address.
     *
     * @return a fresh byte array of length 16
     */
    public byte @NotNull [] networkBytes() {
        return this.networkBytes.clone();
    }

    /**
     * Returns the canonical {@link Inet6Address} for the network portion.
     *
     * @return the network address with host bits zeroed
     */
    public @NotNull Inet6Address networkAddress() {
        try {
            return (Inet6Address) Inet6Address.getByAddress(this.networkBytes.clone());
        } catch (UnknownHostException uhex) {
            throw new IllegalStateException("Unreachable: 16-byte array always produces a valid address", uhex);
        }
    }

    /**
     * Tests whether this prefix fully contains the given subnet.
     *
     * @param sub the candidate subnet
     * @return {@code true} if {@code sub} has length at least this length and its
     *         leading bits match this prefix
     */
    public boolean contains(@NotNull IpPrefix sub) {
        if (sub.length < this.length) return false;
        return matchesPrefix(this.networkBytes, sub.networkBytes, this.length);
    }

    /**
     * Tests whether this prefix contains the given IPv6 address.
     *
     * @param addr the candidate address
     * @return {@code true} if {@code addr} is an IPv6 address whose leading bits
     *         match this prefix
     */
    public boolean contains(@NotNull InetAddress addr) {
        if (!(addr instanceof Inet6Address)) return false;
        return matchesPrefix(this.networkBytes, addr.getAddress(), this.length);
    }

    /**
     * Returns the number of contained subnets at the given finer prefix length.
     * <p>
     * Returns {@link BigInteger#ZERO} when {@code subLength} is coarser than this
     * prefix (i.e. {@code subLength < this.length}) - a prefix can never be
     * partitioned into subnets larger than itself.
     *
     * @param subLength the contained subnet's prefix length in {@code [0, 128]}
     * @return {@code 2^(subLength - this.length)}, or {@code 0} if {@code subLength < this.length}
     */
    public @NotNull BigInteger containedSubnetCount(int subLength) {
        if (subLength < this.length || subLength > IPV6_BITS) return BigInteger.ZERO;
        return BigInteger.ONE.shiftLeft(subLength - this.length);
    }

    /**
     * Returns the contained subnet at the given index, at the given finer prefix
     * length.
     * <p>
     * The index ranges over {@code [0, containedSubnetCount(subLength))}. Index
     * {@code 0} is the first contained subnet (whose network address equals this
     * prefix's network address); the last index is the highest contained subnet.
     *
     * @param subLength the contained subnet's prefix length, must be in
     *                  {@code [this.length, 128]}
     * @param index the subnet index, in {@code [0, containedSubnetCount(subLength))}
     * @return the contained subnet
     * @throws IllegalArgumentException if {@code subLength} is outside the valid range
     * @throws IndexOutOfBoundsException if {@code index} is outside the valid range
     */
    public @NotNull IpPrefix subnetAt(int subLength, @NotNull BigInteger index) {
        if (subLength < this.length || subLength > IPV6_BITS)
            throw new IllegalArgumentException("subLength out of range [" + this.length + ", " + IPV6_BITS + "]: " + subLength);

        BigInteger count = containedSubnetCount(subLength);
        if (index.signum() < 0 || index.compareTo(count) >= 0)
            throw new IndexOutOfBoundsException("index " + index + " out of range [0, " + count + ")");

        BigInteger networkBig = new BigInteger(1, this.networkBytes);
        int shift = IPV6_BITS - subLength;
        BigInteger combined = networkBig.or(index.shiftLeft(shift));
        byte[] subBytes = toFixedBytes(combined, IPV6_BYTES);
        return new IpPrefix(subBytes, subLength);
    }

    /**
     * Returns a uniformly random contained subnet at the given finer prefix length.
     *
     * @param subLength the contained subnet's prefix length
     * @return a random contained subnet
     * @throws IllegalArgumentException if no subnets exist at {@code subLength}
     */
    public @NotNull IpPrefix randomContainedSubnet(int subLength) {
        return this.randomContainedSubnet(subLength, ThreadLocalRandom.current());
    }

    /**
     * Returns a uniformly random contained subnet at the given finer prefix length,
     * using the supplied random source.
     *
     * @param subLength the contained subnet's prefix length
     * @param rng the random source
     * @return a random contained subnet
     * @throws IllegalArgumentException if no subnets exist at {@code subLength}
     */
    public @NotNull IpPrefix randomContainedSubnet(int subLength, @NotNull Random rng) {
        BigInteger count = containedSubnetCount(subLength);
        if (count.signum() <= 0)
            throw new IllegalArgumentException("No contained subnets at length " + subLength + " for prefix " + this);
        if (count.equals(BigInteger.ONE)) return this.subnetAt(subLength, BigInteger.ZERO);
        return this.subnetAt(subLength, randomBigInteger(count, rng));
    }

    /**
     * Returns a uniformly random IPv6 address contained in this prefix.
     *
     * @return a random address with the network bits fixed and host bits random
     */
    public @NotNull Inet6Address randomAddress() {
        return this.randomAddress(ThreadLocalRandom.current());
    }

    /**
     * Returns a uniformly random IPv6 address contained in this prefix, using
     * the supplied random source.
     *
     * @param rng the random source
     * @return a random address with the network bits fixed and host bits random
     */
    public @NotNull Inet6Address randomAddress(@NotNull Random rng) {
        if (this.length == IPV6_BITS) return this.networkAddress();

        byte[] bytes = this.networkBytes.clone();
        randomizeBits(bytes, this.length, IPV6_BITS, rng);
        try {
            return (Inet6Address) Inet6Address.getByAddress(bytes);
        } catch (UnknownHostException uhex) {
            throw new IllegalStateException("Unreachable: 16-byte array always produces a valid address", uhex);
        }
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof IpPrefix other)) return false;
        return this.length == other.length && Arrays.equals(this.networkBytes, other.networkBytes);
    }

    @Override
    public int hashCode() {
        return 31 * Arrays.hashCode(this.networkBytes) + this.length;
    }

    @Override
    public @NotNull String toString() {
        return this.networkAddress().getHostAddress() + "/" + this.length;
    }

    /**
     * Zeros out the host bits of {@code bytes} so it represents the canonical
     * network address for a prefix of the given length.
     */
    private static void applyMask(byte @NotNull [] bytes, int length) {
        int fullBytes = length / 8;
        int partialBits = length % 8;
        if (partialBits != 0 && fullBytes < bytes.length) {
            int mask = 0xFF << (8 - partialBits);
            bytes[fullBytes] = (byte) (bytes[fullBytes] & mask);
            fullBytes++;
        }
        for (int i = fullBytes; i < bytes.length; i++) bytes[i] = 0;
    }

    /**
     * Returns whether {@code a} and {@code b} agree on their first {@code length}
     * bits.
     */
    private static boolean matchesPrefix(byte @NotNull [] a, byte @NotNull [] b, int length) {
        int fullBytes = length / 8;
        int partialBits = length % 8;
        for (int i = 0; i < fullBytes; i++) {
            if (a[i] != b[i]) return false;
        }
        if (partialBits != 0) {
            int mask = 0xFF << (8 - partialBits);
            if ((a[fullBytes] & mask) != (b[fullBytes] & mask)) return false;
        }
        return true;
    }

    /**
     * Fills bits in {@code [fromBit, toBit)} of {@code bytes} with random values,
     * leaving bits outside that range untouched. Bit 0 is the most significant
     * bit of {@code bytes[0]}.
     */
    private static void randomizeBits(byte @NotNull [] bytes, int fromBit, int toBit, @NotNull Random rng) {
        int bitIndex = fromBit;
        while (bitIndex < toBit) {
            int byteIndex = bitIndex / 8;
            int bitInByte = bitIndex % 8;
            int bitsRemainingInByte = 8 - bitInByte;
            int bitsToFill = Math.min(bitsRemainingInByte, toBit - bitIndex);

            int randomBits = rng.nextInt(1 << bitsToFill);
            int shift = bitsRemainingInByte - bitsToFill;
            int randomMask = (randomBits & ((1 << bitsToFill) - 1)) << shift;
            int clearMask = (~(((1 << bitsToFill) - 1) << shift)) & 0xFF;

            bytes[byteIndex] = (byte) ((bytes[byteIndex] & clearMask) | randomMask);
            bitIndex += bitsToFill;
        }
    }

    /**
     * Returns a uniformly random non-negative {@link BigInteger} in
     * {@code [0, bound)}.
     */
    private static @NotNull BigInteger randomBigInteger(@NotNull BigInteger bound, @NotNull Random rng) {
        int bitLength = bound.bitLength();
        BigInteger result;
        do {
            result = new BigInteger(bitLength, rng);
        } while (result.compareTo(bound) >= 0);
        return result;
    }

    /**
     * Converts a non-negative {@link BigInteger} to a fixed-width big-endian
     * byte array, right-aligning the value and zero-padding the high bytes.
     */
    private static byte @NotNull [] toFixedBytes(@NotNull BigInteger value, int size) {
        byte[] raw = value.toByteArray();
        byte[] result = new byte[size];
        if (raw.length > size) {
            System.arraycopy(raw, raw.length - size, result, 0, size);
        } else {
            System.arraycopy(raw, 0, result, size - raw.length, raw.length);
        }
        return result;
    }

}
