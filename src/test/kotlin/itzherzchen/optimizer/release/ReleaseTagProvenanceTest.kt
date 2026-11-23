package itzherzchen.optimizer.release

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReleaseTagProvenanceTest {

    @Test
    fun `lightweight release tag is strictly refused`() {
        val result = ReleaseTagVerifier.verifyTagMetadata(
            tagType = "commit", // lightweight tag points directly to commit
            gitVerifyOutput = "Good \"git\" signature for 166841132+Herzchens@users.noreply.github.com",
            isReachableFromMain = true
        )
        assertEquals(TagVerificationResult.LIGHTWEIGHT_TAG_REJECTED, result)
    }

    @Test
    fun `unsigned release tag is strictly refused`() {
        val result = ReleaseTagVerifier.verifyTagMetadata(
            tagType = "tag",
            gitVerifyOutput = "error: no signature found\nfatal: tag 'v0.1.0' has no signature",
            isReachableFromMain = true
        )
        assertEquals(TagVerificationResult.UNSIGNED_TAG_REJECTED, result)
    }

    @Test
    fun `signed tag with wrong identity or web flow is strictly refused`() {
        val resultWrongEmail = ReleaseTagVerifier.verifyTagMetadata(
            tagType = "tag",
            gitVerifyOutput = "Good \"git\" signature for attacker@evil.com with ED25519 key SHA256:12345",
            isReachableFromMain = true
        )
        assertEquals(TagVerificationResult.WRONG_SIGNER_IDENTITY, resultWrongEmail)

        val resultWebFlow = ReleaseTagVerifier.verifyTagMetadata(
            tagType = "tag",
            gitVerifyOutput = "Good \"git\" signature for noreply@github.com with RSA key ...",
            isReachableFromMain = true
        )
        assertEquals(TagVerificationResult.WRONG_SIGNER_IDENTITY, resultWebFlow)
    }

    @Test
    fun `corrupt or bad signature on release tag is refused`() {
        val result = ReleaseTagVerifier.verifyTagMetadata(
            tagType = "tag",
            gitVerifyOutput = "BAD signature for 166841132+Herzchens@users.noreply.github.com",
            isReachableFromMain = true
        )
        assertEquals(TagVerificationResult.INVALID_SIGNATURE, result)
    }

    @Test
    fun `signed approved release tag not reachable from origin main is strictly refused`() {
        val result = ReleaseTagVerifier.verifyTagMetadata(
            tagType = "tag",
            gitVerifyOutput = "Good \"git\" signature for 166841132+Herzchens@users.noreply.github.com with ED25519 key ...",
            isReachableFromMain = false // not on main
        )
        assertEquals(TagVerificationResult.NOT_ON_MAIN_BRANCH, result)
    }

    @Test
    fun `signed approved release tag on origin main is verified`() {
        val result = ReleaseTagVerifier.verifyTagMetadata(
            tagType = "tag",
            gitVerifyOutput = "Good \"git\" signature for 166841132+Herzchens@users.noreply.github.com with ED25519 key SHA256:20kPITIYb5mzSR9B3stmLoprDRmEGCUFgN0m8BNrJSc",
            isReachableFromMain = true
        )
        assertEquals(TagVerificationResult.VERIFIED, result)
    }

    @Test
    fun `tag format validation enforces semver and rejects snapshot`() {
        assertEquals(TagVerificationResult.VERIFIED, ReleaseTagVerifier.validateTagFormat("v0.1.0"))
        assertEquals(TagVerificationResult.VERIFIED, ReleaseTagVerifier.validateTagFormat("v1.2.3-rc.1"))
        assertEquals(TagVerificationResult.SNAPSHOT_REJECTED, ReleaseTagVerifier.validateTagFormat("v0.1.0-SNAPSHOT"))
        assertEquals(TagVerificationResult.INVALID_VERSION_FORMAT, ReleaseTagVerifier.validateTagFormat("0.1.0"))
        assertEquals(TagVerificationResult.INVALID_VERSION_FORMAT, ReleaseTagVerifier.validateTagFormat("release-0.1.0"))
        assertEquals(TagVerificationResult.INVALID_VERSION_FORMAT, ReleaseTagVerifier.validateTagFormat("v0.1"))
    }
}
