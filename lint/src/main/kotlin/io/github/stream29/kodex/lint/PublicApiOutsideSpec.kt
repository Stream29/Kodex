package io.github.stream29.kodex.lint

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import dev.detekt.api.RuleSet
import dev.detekt.api.RuleSetId
import dev.detekt.api.RuleSetProvider
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtEnumEntry
import org.jetbrains.kotlin.psi.KtModifierListOwner
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtPrimaryConstructor
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtSecondaryConstructor
import org.jetbrains.kotlin.psi.KtTypeAlias

class SpecApiRuleSetProvider : RuleSetProvider {
    override val ruleSetId = RuleSetId("spec-api")

    override fun instance() = RuleSet(ruleSetId, listOf(::PublicApiOutsideSpec))
}

/**
 * Checks implementation source files supplied by Gradle, not source-path spelling.
 *
 * Bodies and non-exported containers are not traversed. Actualization validity,
 * inherited API and enum-entry correspondence require separate compiler/semantic checks.
 * Overrides in public actual types are conservatively checked for explicit markers.
 */
class PublicApiOutsideSpec(config: Config) : Rule(
    config,
    "Public APIs must be declared in specMain; implementation exports must be actual.",
) {
    override fun visitClassOrObject(classOrObject: KtClassOrObject) {
        if (classOrObject is KtEnumEntry || classOrObject.isLocal || !classOrObject.isExported()) return
        if (classOrObject.checkExport()) super.visitClassOrObject(classOrObject)
    }

    override fun visitNamedFunction(function: KtNamedFunction) {
        if (!function.isLocal) function.checkExport()
    }

    override fun visitProperty(property: KtProperty) {
        if (!property.isLocal) property.checkExport()
    }

    override fun visitTypeAlias(typeAlias: KtTypeAlias) {
        typeAlias.checkExport()
    }

    override fun visitPrimaryConstructor(constructor: KtPrimaryConstructor) {
        constructor.checkExport()
        super.visitPrimaryConstructor(constructor)
    }

    override fun visitSecondaryConstructor(constructor: KtSecondaryConstructor) {
        constructor.checkExport()
    }

    override fun visitParameter(parameter: KtParameter) {
        if (parameter.hasValOrVar()) parameter.checkExport()
    }

    private fun KtModifierListOwner.isExported(): Boolean =
        !hasModifier(KtTokens.PRIVATE_KEYWORD) && !hasModifier(KtTokens.INTERNAL_KEYWORD)

    private fun KtNamedDeclaration.checkExport(): Boolean {
        if (!isExported()) return false
        if (hasModifier(KtTokens.ACTUAL_KEYWORD)) return true
        report(
            Finding(
                Entity.from(this),
                "Declare '${name ?: "<constructor>"}' in specMain and implement it with actual, " +
                    "or make it internal/private.",
            ),
        )
        return false
    }
}
