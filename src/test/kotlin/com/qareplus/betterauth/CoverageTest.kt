package com.qareplus.betterauth

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Keeps the SDK honest against the OpenAPI document in `src/test/resources/spec-endpoints.txt`
 * (regenerate with `scripts/extract-endpoints.py`). Every spec path must either appear as a path literal in the SDK
 * sources or be listed in [deliberatelyNotWrapped] with a reason.
 */
class CoverageTest {
    private val deliberatelyNotWrapped = mapOf(
        "/callback/{id}" to "OAuth provider redirect target, handled by the server and the browser, never called by an app",
        "/delete-user/callback" to "email link target that redirects the user's browser",
        "/expo-authorization-proxy" to "browser redirect proxy for the Expo client plugin; app-layer concern",
        "/error" to "server HTML error page",
    )

    private val specPaths: Set<String> by lazy {
        javaClass.getResourceAsStream("/spec-endpoints.txt")!!.bufferedReader().readLines()
            .filter { it.isNotBlank() }
            .map { it.substringAfter(' ') }
            .toSet()
    }

    private val sdkPaths: Set<String> by lazy {
        val literal = Regex("\"(/[A-Za-z0-9_\\-/{}$]*)\"")
        File("src/main/kotlin").walkTopDown()
            .filter { it.extension == "kt" }
            .flatMap { file -> literal.findAll(file.readText()).map { it.groupValues[1] } }
            // "/reset-password/$token" in source is "/reset-password/{token}" in the spec
            .map { it.replace(Regex("\\$[A-Za-z]+"), "{token}") }
            .toSet()
    }

    @Test
    fun `spec has the expected size`() {
        assertTrue(specPaths.size >= 120, "spec-endpoints.txt looks truncated: ${specPaths.size} paths")
    }

    @Test
    fun `every spec path is wrapped or deliberately skipped`() {
        val missing = specPaths.filter { it !in sdkPaths && it !in deliberatelyNotWrapped }.sorted()
        assertTrue(missing.isEmpty(), "Spec paths with no SDK method:\n" + missing.joinToString("\n"))
    }

    @Test
    fun `skip list does not hide wrapped or unknown paths`() {
        val stale = deliberatelyNotWrapped.keys.filter { it !in specPaths }
        assertTrue(stale.isEmpty(), "deliberatelyNotWrapped has paths that are not in the spec: $stale")
    }

    @Test
    fun `sdk does not call paths the spec does not know about`() {
        // helper literals that are not endpoints
        val ignore = setOf("/")
        val unknown = sdkPaths.filter { it !in specPaths && it !in ignore }.sorted()
        assertTrue(unknown.isEmpty(), "SDK paths missing from the spec (typo, or regenerate spec-endpoints.txt):\n" + unknown.joinToString("\n"))
    }
}
