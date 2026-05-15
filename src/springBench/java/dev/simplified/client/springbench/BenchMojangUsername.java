package dev.simplified.client.springbench;

/**
 * Mojang username lookup payload, matching the shape returned by
 * {@code https://api.minecraftservices.com/minecraft/profile/lookup/name/{username}}.
 *
 * @param id the player's unique id as a 32-character hex string with no dashes
 * @param name the case-corrected player username
 */
public record BenchMojangUsername(String id, String name) {
}
