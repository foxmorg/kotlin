/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.scopes

import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.name.Name

abstract class FirTypeScope : FirScope(), FirContainingNamesAwareScope {
    // Currently, this function and its property brother both have very weak guarantees
    // - It may silently do nothing on symbols originated from different scope instance
    // - It may return the same overridden symbols more then once in case of substitution
    // - It doesn't guarantee any specific order in which overridden tree will be traversed
    // But if the scope instance is the same as the one from which the symbol was originated, this function will enumarate all members
    // of the overridden tree
    abstract fun processDirectOverriddenFunctionsWithBaseScope(
        functionSymbol: FirFunctionSymbol<*>,
        processor: (FirFunctionSymbol<*>, FirTypeScope) -> ProcessorAction
    ): ProcessorAction

    // ------------------------------------------------------------------------------------

    abstract fun processDirectOverriddenPropertiesWithBaseScope(
        propertySymbol: FirPropertySymbol,
        processor: (FirPropertySymbol, FirTypeScope) -> ProcessorAction
    ): ProcessorAction

    // ------------------------------------------------------------------------------------

    object Empty : FirTypeScope() {
        override fun processDirectOverriddenFunctionsWithBaseScope(
            functionSymbol: FirFunctionSymbol<*>,
            processor: (FirFunctionSymbol<*>, FirTypeScope) -> ProcessorAction
        ): ProcessorAction = ProcessorAction.NEXT

        override fun processDirectOverriddenPropertiesWithBaseScope(
            propertySymbol: FirPropertySymbol,
            processor: (FirPropertySymbol, FirTypeScope) -> ProcessorAction
        ): ProcessorAction = ProcessorAction.NEXT

        override fun getCallableNames(): Set<Name> = emptySet()

        override fun getClassifierNames(): Set<Name> = emptySet()
    }

    protected companion object {
        fun <S : FirCallableSymbol<*>> doProcessDirectOverriddenCallables(
            callableSymbol: S,
            processor: (S, FirTypeScope) -> ProcessorAction,
            directOverriddenMap: Map<S, Collection<S>>,
            baseScope: FirTypeScope,
            processDirectOverriddenCallables: FirTypeScope.(S, (S, FirTypeScope) -> ProcessorAction) -> ProcessorAction
        ): ProcessorAction {
            val directOverridden = directOverriddenMap[callableSymbol]?.takeIf { it.isNotEmpty() }
                ?: return baseScope.processDirectOverriddenCallables(callableSymbol, processor)

            for (overridden in directOverridden) {
                if (overridden.isIntersectionOverride) {
                    if (!baseScope.processDirectOverriddenCallables(overridden, processor)) return ProcessorAction.STOP
                }
                if (!processor(overridden, baseScope)) return ProcessorAction.STOP
            }

            return ProcessorAction.NONE
        }
    }
}

fun FirTypeScope.processOverriddenFunctions(
    functionSymbol: FirFunctionSymbol<*>,
    processor: (FirFunctionSymbol<*>) -> ProcessorAction
): ProcessorAction =
    doProcessAllOverriddenCallables(
        functionSymbol,
        processor,
        FirTypeScope::processDirectOverriddenFunctionsWithBaseScope,
        mutableSetOf()
    )

fun FirTypeScope.processOverriddenProperties(
    propertySymbol: FirPropertySymbol,
    processor: (FirPropertySymbol) -> ProcessorAction
): ProcessorAction =
    doProcessAllOverriddenCallables(
        propertySymbol,
        processor,
        FirTypeScope::processDirectOverriddenPropertiesWithBaseScope,
        mutableSetOf()
    )


private fun <S : FirCallableSymbol<*>> FirTypeScope.doProcessAllOverriddenCallables(
    callableSymbol: S,
    processor: (S) -> ProcessorAction,
    processDirectOverriddenCallablesWithBaseScope: FirTypeScope.(S, (S, FirTypeScope) -> ProcessorAction) -> ProcessorAction,
    visited: MutableSet<S>
): ProcessorAction {
    if (!visited.add(callableSymbol)) return ProcessorAction.NONE
    return processDirectOverriddenCallablesWithBaseScope(callableSymbol) { overridden, baseScope ->
        if (!processor(overridden)) return@processDirectOverriddenCallablesWithBaseScope ProcessorAction.STOP

        baseScope.doProcessAllOverriddenCallables(overridden, processor, processDirectOverriddenCallablesWithBaseScope, visited)
    }
}

inline fun FirTypeScope.processDirectlyOverriddenFunctions(
    functionSymbol: FirFunctionSymbol<*>,
    crossinline processor: (FirFunctionSymbol<*>) -> ProcessorAction
): ProcessorAction = processDirectOverriddenFunctionsWithBaseScope(functionSymbol) { overridden, _ ->
    processor(overridden)
}

inline fun FirTypeScope.processDirectlyOverriddenProperties(
    propertySymbol: FirPropertySymbol,
    crossinline processor: (FirPropertySymbol) -> ProcessorAction
): ProcessorAction = processDirectOverriddenPropertiesWithBaseScope(propertySymbol) { overridden, _ ->
    processor(overridden)
}
