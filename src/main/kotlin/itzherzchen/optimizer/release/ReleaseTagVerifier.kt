package itzherzchen.optimizer.release

/**
 * Result of release tag cryptographic and topology provenance verification.
 */
enum class TagVerificationResult {
    VERIFIED,
    INVALID_VERSION_FORMAT,
    SNAPSHOT_REJECTED,
    LIGHTWEIGHT_TAG_REJECTED,
    UNSIGNED_TAG_REJECTED,
    WRONG_SIGNER_IDENTITY,
    INVALID_SIGNATURE,
    NOT_ON_MAIN_BRANCH
}

/**
 * Validates release tag version formatting, annotated tag type, owner ED25519 cryptographic
 * signature, and origin/main reachability as enforced in .github/workflows/release.yml.
 */
object ReleaseTagVerifier {
    const val APPROVED_SIGNER_EMAIL = "166841132+Herzchens@users.noreply.github.com"
    const val APPROVED_KEY_TYPE = "ssh-ed25519"
    const val APPROVED_KEY_PREFIX = "AAAAC3NzaC1lZDI1NTE5AAAAIMwKdHjkKqlWReNnF5Hz6GmWWCkZqx9sNklZuAeMPiRD"

    private val SEMVER_REGEX = Regex("""^v\d+\.\d+\.\d+(-[a-zA-Z0-9.]+)?$""")

    fun validateTagFormat(tag: String): TagVerificationResult {
        if (!SEMVER_REGEX.matches(tag)) return TagVerificationResult.INVALID_VERSION_FORMAT
        val version = tag.removePrefix("v")
        if (version.contains("SNAPSHOT", ignoreCase = true)) return TagVerificationResult.SNAPSHOT_REJECTED
        return TagVerificationResult.VERIFIED
    }

    fun verifyTagMetadata(
        tagType: String,
        gitVerifyOutput: String,
        isReachableFromMain: Boolean
    ): TagVerificationResult {
        if (tagType.trim() != "tag") {
            return TagVerificationResult.LIGHTWEIGHT_TAG_REJECTED
        }

        if (gitVerifyOutput.contains("no signature found", ignoreCase = true) ||
            gitVerifyOutput.contains("not signed", ignoreCase = true)) {
            return TagVerificationResult.UNSIGNED_TAG_REJECTED
        }

        if (gitVerifyOutput.contains("BAD signature", ignoreCase = true) ||
            (gitVerifyOutput.contains("error:", ignoreCase = true) && !gitVerifyOutput.contains("Good \"git\" signature"))) {
            return TagVerificationResult.INVALID_SIGNATURE
        }

        if (!gitVerifyOutput.contains("Good \"git\" signature for $APPROVED_SIGNER_EMAIL")) {
            return TagVerificationResult.WRONG_SIGNER_IDENTITY
        }

        if (!isReachableFromMain) {
            return TagVerificationResult.NOT_ON_MAIN_BRANCH
        }

        return TagVerificationResult.VERIFIED
    }
}
