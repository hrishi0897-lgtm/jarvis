package com.jarvis.app

enum class Risk { SAFE, CONFIRM, BLOCK }

data class Verdict(
    val risk: Risk,
    val reason: String = "",
    val destructive: Boolean = false
)

/**
 * Hard-coded safety rules. These run in code, so they apply even if the AI
 * model ignores its instructions or is tricked by text on screen.
 *
 *   BLOCK   : payments, purchases, PIN / password / card entry,
 *             payment / banking / installer apps
 *   CONFIRM : destructive (delete / remove / clear / uninstall / reset)
 *             OR outward (send / post / share / call / install / publish)
 *
 * `destructive` distinguishes "delete this file" (irreversible) from
 * "send this message" (goes to another person). Both need confirmation,
 * but the wording shown to the user differs.
 */
object SafetyGuard {

    // ---- packages --------------------------------------------------------

    private val BLOCKED_PACKAGES = setOf(
        "com.google.android.apps.nbu.paisa.user",   // Google Pay (India)
        "com.google.android.apps.walletnfcrel",     // Google Wallet
        "com.phonepe.app",
        "net.one97.paytm",
        "in.org.npci.upiapp",                       // BHIM
        "com.paypal.android.p2pmobile",
        "com.venmo",
        "com.squareup.cash",
        "com.samsung.android.spay",
        "com.dreamplug.androidapp",                 // CRED
        "com.google.android.packageinstaller",      // install / uninstall dialogs
        "com.android.packageinstaller",
        "com.samsung.android.packageinstaller"
    )

    // Whole-token matches only. "urban" must NOT match "bank".
    private val BLOCKED_PACKAGE_TOKENS = setOf(
        "upi", "bank", "banking", "wallet", "payment", "payments",
        "paytm", "phonepe", "paypal", "paisa", "cred", "venmo", "cashapp"
    )

    fun isBlockedPackage(pkg: String): Boolean {
        val p = pkg.lowercase()
        if (p.isEmpty()) return false
        if (p in BLOCKED_PACKAGES) return true
        val tokens = p.split('.', '_', '-', '/').filter { it.isNotEmpty() }
        return tokens.any { it in BLOCKED_PACKAGE_TOKENS }
    }

    // ---- phrases ---------------------------------------------------------

    private val PAYMENT_PHRASES = listOf(
        "pay", "payment", "payments", "paynow", "checkout", "place order",
        "place your order", "buy", "purchase", "order now", "confirm order",
        "proceed to pay", "send money", "transfer money", "transfer funds",
        "add money", "top up", "recharge", "donate", "upi pin", "cvv",
        "card number", "start free trial", "upgrade", "go premium",
        "get premium", "subscribe now", "confirm payment", "make payment"
    )

    private val DESTRUCTIVE_PHRASES = listOf(
        "delete", "delete all", "delete forever", "delete for everyone",
        "delete for me", "remove", "erase", "clear data", "clear storage",
        "clear all", "clear history", "uninstall", "factory reset", "reset",
        "wipe", "empty trash", "empty bin", "move to trash", "format",
        "unsend", "discard", "forget"
    )

    // Things that leave the phone or go to another person: ask first.
    private val OUTWARD_PHRASES = listOf(
        "send", "post", "share", "call", "install", "publish"
    )

    private val SENSITIVE_FIELD_PHRASES = listOf(
        "password", "pin", "cvv", "card number", "otp", "upi pin",
        "security code", "passcode", "expiry"
    )

    // ---- helpers ---------------------------------------------------------

    private fun norm(s: String): String {
        val cleaned = s.lowercase().replace(Regex("[^\\p{L}\\p{Nd}]+"), " ").trim()
        return " $cleaned "
    }

    private fun containsPhrase(haystack: String, needle: String): Boolean =
        haystack.contains(" $needle ")

    // ---- public API ------------------------------------------------------

    /** Classify a button / element label (also fed with view ids like "btn_pay"). */
    fun evaluateLabel(label: String): Verdict {
        val n = norm(label)
        if (n.isBlank()) return Verdict(Risk.SAFE)

        PAYMENT_PHRASES.firstOrNull { containsPhrase(n, it) }?.let {
            return Verdict(Risk.BLOCK, "it looks like a payment or purchase control (\"$it\")")
        }
        DESTRUCTIVE_PHRASES.firstOrNull { containsPhrase(n, it) }?.let {
            return Verdict(
                Risk.CONFIRM,
                "it looks like a delete or remove action (\"$it\")",
                destructive = true
            )
        }
        OUTWARD_PHRASES.firstOrNull { containsPhrase(n, it) }?.let {
            return Verdict(Risk.CONFIRM, "it sends, posts or commits something (\"$it\")")
        }
        return Verdict(Risk.SAFE)
    }

    /** Typing into password / PIN / OTP / card fields is never allowed. */
    fun evaluateField(label: String, isPassword: Boolean): Verdict {
        if (isPassword) return Verdict(Risk.BLOCK, "this is a password field")
        val n = norm(label)
        SENSITIVE_FIELD_PHRASES.firstOrNull { containsPhrase(n, it) }?.let {
            return Verdict(Risk.BLOCK, "this field asks for sensitive data (\"$it\")")
        }
        return Verdict(Risk.SAFE)
    }

    fun looksLikeCardNumber(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty() || !t.all { it.isDigit() || it == ' ' || it == '-' }) return false
        val digits = t.count { it.isDigit() }
        return digits in 13..19
    }

    fun evaluateUrl(url: String): Verdict {
        val u = url.trim()
        val lower = u.lowercase()
        if (!(lower.startsWith("http://") || lower.startsWith("https://"))) {
            return Verdict(Risk.BLOCK, "only http/https links can be opened")
        }
        val n = norm(u)
        listOf("checkout", "payment", "pay", "paypal", "upi").firstOrNull {
            containsPhrase(n, it)
        }?.let {
            return Verdict(Risk.BLOCK, "the link looks like a payment page (\"$it\")")
        }
        return Verdict(Risk.SAFE)
    }
}
